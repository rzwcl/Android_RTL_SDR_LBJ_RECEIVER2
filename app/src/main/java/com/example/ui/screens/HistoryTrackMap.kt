package com.example.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.TrainSignalRecord
import com.example.util.RailwayMapData
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

enum class HistoryMapMode {
    OSM,
    SATELLITE
}

private val EsriWorldImageryTileSource = object : OnlineTileSourceBase(
    "ESRI World Imagery",
    0,
    19,
    256,
    ".jpg",
    arrayOf(
        "https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
    ),
    "Esri, Maxar, Earthstar Geographics, and the GIS User Community"
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        return getBaseUrl() +
            MapTileIndex.getZoom(pMapTileIndex) + "/" +
            MapTileIndex.getY(pMapTileIndex) + "/" +
            MapTileIndex.getX(pMapTileIndex) +
            mImageFilenameEnding
    }
}

private data class MapPoint(
    val signal: TrainSignalRecord,
    val geoPoint: GeoPoint
)

private data class MapViewportKey(
    val points: List<String>,
    val selectedSignalId: Long?,
    val mapSignature: String
)

private class HistoryMapView(context: Context) : MapView(context) {
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }
}

@Composable
fun HistoryTrackMap(
    signals: List<TrainSignalRecord>,
    railwayMapData: RailwayMapData? = null,
    mapMode: HistoryMapMode = HistoryMapMode.OSM,
    selectedSignalId: Long? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val mapPoints = remember(signals) {
        signals.mapNotNull { signal ->
            parseSignalCoordinate(signal)?.let { geoPoint ->
                MapPoint(signal, geoPoint)
            }
        }
    }
    val railwayMapSignature = railwayMapData?.let { it.fileName + ":" + it.lines.size + ":" + it.stations.size } ?: ""
    val viewportKey = remember(mapPoints, selectedSignalId, railwayMapSignature) {
        MapViewportKey(
            points = mapPoints.map {
                "${it.signal.id}:${it.geoPoint.latitude}:${it.geoPoint.longitude}"
            },
            selectedSignalId = selectedSignalId,
            mapSignature = railwayMapSignature
        )
    }
    var lastViewportKey by remember { mutableStateOf<MapViewportKey?>(null) }

    val mapView = remember(context) {
        Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
        )
        Configuration.getInstance().userAgentValue =
            "SDR-LBJ/1.1.2 (" + context.packageName + ")"
        HistoryMapView(context).apply {
            setTileSource(if (mapMode == HistoryMapMode.OSM) TileSourceFactory.MAPNIK else EsriWorldImageryTileSource)
            setMultiTouchControls(true)
            setUseDataConnection(true)
            minZoomLevel = 2.0
            maxZoomLevel = 18.0
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        mapView.onResume()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { view ->
            val shouldFitViewport = viewportKey != lastViewportKey
            renderHistoryTrack(
                mapView = view,
                points = mapPoints,
                railwayMapData = railwayMapData,
                mapMode = mapMode,
                selectedSignalId = selectedSignalId,
                fitViewport = shouldFitViewport
            )
            if (shouldFitViewport) {
                lastViewportKey = viewportKey
            }
        }
    )
}


private class RailwayLabelOverlay(
    private val mapView: MapView,
    private val data: RailwayMapData
) : org.osmdroid.views.overlay.Overlay() {

    private val projectionPointA = Point()
    private val projectionPointB = Point()

    private val stationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }

    private val stationStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, AndroidColor.BLACK)
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val zoom = mapView.zoomLevelDouble
        drawRailwayNames(canvas, zoom)
        drawStations(canvas, zoom)
    }

    private fun drawRailwayNames(canvas: Canvas, zoom: Double) {
        if (zoom < 9.0) return

        val textSizeDp = when {
            zoom >= 15.0 -> 13f
            zoom >= 13.0 -> 12f
            zoom >= 11.0 -> 11f
            else -> 10f
        }
        textPaint.textSize = textSizeDp * mapView.resources.displayMetrics.density

        val minLineLengthKm = when {
            zoom >= 15.0 -> 0.15
            zoom >= 13.0 -> 0.30
            zoom >= 11.0 -> 0.60
            else -> 1.20
        }

        val maxLabelsPerName = when {
            zoom >= 15.0 -> 8
            zoom >= 13.0 -> 5
            zoom >= 11.0 -> 3
            else -> 1
        }

        val usedNames = HashSet<String>()
        data.lines.forEach { line ->
            if (line.points.size < 2) return@forEach

            val name = line.name.trim()
            if (name.isEmpty()) return@forEach
            if (zoom < 12.0 && name in usedNames) return@forEach

            val totalKm = polylineLengthKm(line.points)
            if (totalKm < minLineLengthKm) return@forEach

            val labelCount = if (zoom >= 15.0) {
                min(maxLabelsPerName, max(1, (totalKm / 1.0).roundToInt()))
            } else if (zoom >= 13.0) {
                min(maxLabelsPerName, max(1, (totalKm / 2.0).roundToInt()))
            } else if (zoom >= 11.0) {
                min(maxLabelsPerName, max(1, (totalKm / 4.0).roundToInt()))
            } else {
                1
            }

            val fractions = if (labelCount <= 1) {
                listOf(0.5)
            } else {
                (1..labelCount).map { it.toDouble() / (labelCount + 1) }
            }

            fractions.forEach { fraction ->
                val sample = samplePolyline(line.points, fraction) ?: return@forEach
                val p1 = mapView.projection.toPixels(sample.before, projectionPointA)
                val p2 = mapView.projection.toPixels(sample.after, projectionPointB)

                val dx = (p2.x - p1.x).toFloat()
                val dy = (p2.y - p1.y).toFloat()
                if (dx == 0f && dy == 0f) return@forEach

                var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angle > 90f) angle -= 180f
                if (angle < -90f) angle += 180f

                val x = (p1.x + p2.x) / 2f
                val y = (p1.y + p2.y) / 2f
                if (x !in -200f..(mapView.width + 200f) || y !in -100f..(mapView.height + 100f)) {
                    return@forEach
                }

                canvas.save()
                canvas.rotate(angle, x, y)
                canvas.drawText(name, x, y - 4f * mapView.resources.displayMetrics.density, textPaint)
                canvas.restore()
            }

            usedNames += name
        }
    }

    private fun drawStations(canvas: Canvas, zoom: Double) {
        val shouldDrawNames = zoom >= 10.0
        val shouldDrawMinorStations = zoom >= 12.0
        val density = mapView.resources.displayMetrics.density

        val radius = when {
            zoom >= 15.0 -> 4.0f * density
            zoom >= 12.0 -> 3.5f * density
            else -> 3.0f * density
        }

        textPaint.textSize = when {
            zoom >= 15.0 -> 13f * density
            zoom >= 13.0 -> 12f * density
            else -> 11f * density
        }

        data.stations.forEachIndexed { index, station ->
            // At smaller zooms, keep the station layer sparse instead of drawing every point.
            if (!shouldDrawMinorStations && index % 2 != 0) return@forEachIndexed

            val point = mapView.projection.toPixels(station.point, Point())
            if (point.x !in -100..(mapView.width + 100) || point.y !in -100..(mapView.height + 100)) {
                return@forEachIndexed
            }

            stationStrokePaint.strokeWidth = max(1f, 1.0f * density)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius + 1f * density, stationStrokePaint)
            canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), radius, stationPaint)

            if (shouldDrawNames && station.name.isNotBlank()) {
                canvas.drawText(
                    station.name,
                    point.x.toFloat(),
                    point.y.toFloat() - radius - 4f * density,
                    textPaint
                )
            }
        }
    }

    private data class SampledSegment(
        val before: GeoPoint,
        val after: GeoPoint
    )

    private fun samplePolyline(points: List<GeoPoint>, fraction: Double): SampledSegment? {
        if (points.size < 2) return null

        val lengths = DoubleArray(points.size)
        var total = 0.0
        for (i in 1 until points.size) {
            total += points[i - 1].distanceToAsDouble(points[i]) / 1000.0
            lengths[i] = total
        }
        if (total <= 0.0) return null

        val target = total * fraction.coerceIn(0.0, 1.0)
        for (i in 1 until points.size) {
            if (target <= lengths[i]) {
                return SampledSegment(points[i - 1], points[i])
            }
        }
        return SampledSegment(points[points.size - 2], points.last())
    }

    private fun polylineLengthKm(points: List<GeoPoint>): Double {
        if (points.size < 2) return 0.0
        var totalMeters = 0.0
        for (i in 1 until points.size) {
            totalMeters += points[i - 1].distanceToAsDouble(points[i])
        }
        return totalMeters / 1000.0
    }
}

private fun renderHistoryTrack(
    mapView: MapView,
    points: List<MapPoint>,
    railwayMapData: RailwayMapData?,
    mapMode: HistoryMapMode,
    selectedSignalId: Long?,
    fitViewport: Boolean
) {
    mapView.closeInfoWindow()
    mapView.setTileSource(
        if (mapMode == HistoryMapMode.OSM) TileSourceFactory.MAPNIK else EsriWorldImageryTileSource
    )
    mapView.overlays.clear()

    val geoPoints = points.map { it.geoPoint }
    val uniqueGeoPoints = geoPoints.distinctBy { point ->
        point.latitude.toString() + "," + point.longitude.toString()
    }
    val selectedPoint = selectedSignalId?.let { signalId ->
        points.firstOrNull { it.signal.id == signalId }
    }

    val mapLinePoints = if (mapMode == HistoryMapMode.SATELLITE) {
        railwayMapData?.lines?.flatMap { it.points }.orEmpty()
    } else {
        emptyList()
    }
    val fitPoints = when {
        uniqueGeoPoints.isNotEmpty() -> uniqueGeoPoints
        mapLinePoints.isNotEmpty() -> mapLinePoints
        else -> emptyList()
    }

    if (fitViewport) {
        when {
            selectedPoint != null -> {
                mapView.controller.setCenter(selectedPoint.geoPoint)
                mapView.controller.setZoom(16.0)
            }
            fitPoints.size == 1 -> {
                mapView.controller.setCenter(fitPoints.first())
                mapView.controller.setZoom(17.0)
            }
            fitPoints.size > 1 -> {
                val bounds = BoundingBox.fromGeoPoints(fitPoints)
                mapView.controller.setCenter(bounds.center)
                mapView.zoomToBoundingBox(bounds.increaseByScale(1.25f), false)
                val zoom = mapView.zoomLevelDouble.coerceIn(2.0, 18.0)
                mapView.controller.setZoom(zoom)
            }
        }
    }

    val density = mapView.resources.displayMetrics.density
    if (mapMode == HistoryMapMode.SATELLITE) railwayMapData?.lines?.forEach { line ->
        if (line.points.size < 2) return@forEach

        if (line.outlineWidth > 0f) {
            val outline = Polyline(mapView).apply {
                setPoints(line.points)
                outlinePaint.color = withOpacity(line.outlineColor, line.opacity)
                outlinePaint.strokeWidth = (line.width + line.outlineWidth * 2f) * density
            }
            mapView.overlays.add(outline)
        }

        val polyline = Polyline(mapView).apply {
            setPoints(line.points)
            outlinePaint.color = withOpacity(line.color, line.opacity)
            outlinePaint.strokeWidth = line.width * density
        }
        mapView.overlays.add(polyline)
    }

    if (mapMode == HistoryMapMode.SATELLITE && railwayMapData?.hasFeatures == true) {
        mapView.overlays.add(RailwayLabelOverlay(mapView, railwayMapData))
    }

    val markerPoint = selectedPoint ?: points.lastOrNull()
    if (markerPoint != null) {
        val marker = Marker(mapView).apply {
            position = markerPoint.geoPoint
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createTrainMarkerDrawable(mapView)
            title = markerPoint.signal.trainNo + " · " + markerPoint.signal.positionKm
            snippet = buildMarkerSnippet(markerPoint.signal)
        }
        mapView.overlays.add(marker)
    }

    mapView.invalidate()
}

private fun withOpacity(color: Int, opacity: Float): Int {
    val alpha = (AndroidColor.alpha(color) * opacity.coerceIn(0.0f, 1.0f)).toInt().coerceIn(0, 255)
    return AndroidColor.argb(
        alpha,
        AndroidColor.red(color),
        AndroidColor.green(color),
        AndroidColor.blue(color)
    )
}
private fun createTrainMarkerDrawable(mapView: MapView): GradientDrawable {
    val density = mapView.resources.displayMetrics.density
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(AndroidColor.BLACK)
        setStroke(max(1, (1.5f * density).toInt()), AndroidColor.WHITE)
        setSize(
            (24f * density).toInt(),
            (24f * density).toInt()
        )
    }
}

private fun buildMarkerSnippet(signal: TrainSignalRecord): String {
    val speed = signal.speed.ifBlank { "未知" }
    val position = signal.positionKm.ifBlank { "未解析" }
    val coordinates = listOf(signal.longitude.trim(), signal.latitude.trim())
        .filter { it.isNotEmpty() }
        .joinToString(" ")
    return "速度: " + speed + " km/h\n公里标: " + position +
        if (coordinates.isNotBlank()) "\n" + coordinates else ""
}

private fun parseSignalCoordinate(signal: TrainSignalRecord): GeoPoint? {
    val longitude = parseDms(signal.longitude)
    val latitude = parseDms(signal.latitude)
    if (longitude == null || latitude == null) {
        val combined = listOf(signal.longitude.trim(), signal.latitude.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        return parseCombinedCoordinate(combined)
    }

    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
        return null
    }
    if (kotlin.math.abs(latitude) <= 0.001 && kotlin.math.abs(longitude) <= 0.001) {
        return null
    }
    return GeoPoint(latitude, longitude)
}

private fun parseCombinedCoordinate(value: String): GeoPoint? {
    if (value.isBlank()) return null
    val parts = value.trim().split(Regex("\\s+"))
    if (parts.size < 2) return null

    val first = parseDms(parts[0]) ?: return null
    val second = parseDms(parts[1]) ?: return null

    // LBJ stores longitude first and latitude second.
    val longitude = first
    val latitude = second

    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
        return null
    }
    if (kotlin.math.abs(latitude) <= 0.001 && kotlin.math.abs(longitude) <= 0.001) {
        return null
    }
    return GeoPoint(latitude, longitude)
}

private fun parseDms(value: String): Double? {
    val text = value.trim()
    if (text.isEmpty()) return null

    val degreeIndex = text.indexOf('°')
    if (degreeIndex < 1) return null

    val suffix = text.lastOrNull()
    val minuteEnd = text.indexOfFirst { it == '′' || it == '\'' }
    val minuteText = if (minuteEnd > degreeIndex) {
        text.substring(degreeIndex + 1, minuteEnd)
    } else {
        text.substring(degreeIndex + 1).removeSuffix("E")
            .removeSuffix("W").removeSuffix("N").removeSuffix("S")
    }

    val degrees = text.substring(0, degreeIndex).toDoubleOrNull() ?: return null
    val minutes = minuteText.toDoubleOrNull() ?: return null
    if (minutes !in 0.0..<60.0) return null

    val directionMultiplier = when (suffix) {
        'W', 'S' -> -1.0
        else -> 1.0
    }
    return directionMultiplier * (degrees + minutes / 60.0)
}