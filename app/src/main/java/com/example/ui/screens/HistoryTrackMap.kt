package com.example.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.SurfaceCard
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
            setBuiltInZoomControls(false)
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

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
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

        Card(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = 8.dp),
            shape = RoundedCornerShape(7.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCard.copy(alpha = 0.92f))
        ) {
            Column {
                IconButton(
                    onClick = { mapView.controller.zoomIn() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "放大地图",
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { mapView.controller.zoomOut() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Remove,
                        contentDescription = "缩小地图",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}


private class RailwayLabelOverlay(
    private val mapView: MapView,
    private val data: RailwayMapData
) : org.osmdroid.views.overlay.Overlay() {

    private val projectionPointA = Point()
    private val projectionPointB = Point()
    private val projectionPoint = Point()

    private val stationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }

    private val stationStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        style = Paint.Style.STROKE
    }

    private val textStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val zoom = mapView.zoomLevelDouble
        drawRailwayNames(canvas, zoom)
        drawStations(canvas, zoom)
    }

    private fun drawRailwayNames(canvas: Canvas, zoom: Double) {
        if (zoom < 8.0) return

        val bounds = mapView.boundingBox
        val density = mapView.resources.displayMetrics.density
        val textSizeDp = when {
            zoom >= 15.0 -> 14f
            zoom >= 13.0 -> 13f
            zoom >= 11.0 -> 12f
            else -> 11f
        }
        textPaint.textSize = textSizeDp * density
        textStrokePaint.textSize = textPaint.textSize
        textStrokePaint.strokeWidth = 3.0f * density

        val placedBoxes = ArrayList<RectF>()

        data.lines.forEach lineLoop@ { line ->
            val name = line.name.trim()

            // 与 LBJ_Map/map.html 一致：默认的“线路数字编号”不是可视线路名称。
            if (name.isEmpty() || Regex("^线路\\d+$").matches(name)) return@lineLoop
            if (line.points.size < 2) return@lineLoop

            val visibleSegments = ArrayList<List<GeoPoint>>()
            var currentSegment = ArrayList<GeoPoint>()

            line.points.forEach { geoPoint ->
                if (bounds.contains(geoPoint)) {
                    currentSegment.add(geoPoint)
                } else {
                    if (currentSegment.size >= 2) {
                        visibleSegments.add(currentSegment)
                    }
                    currentSegment = ArrayList()
                }
            }
            if (currentSegment.size >= 2) {
                visibleSegments.add(currentSegment)
            }

            visibleSegments.forEach segmentLoop@ { segment ->
                val pixelPoints = segment.map { mapView.projection.toPixels(it, Point()) }
                var pixelLength = 0f
                for (i in 1 until pixelPoints.size) {
                    val dx = (pixelPoints[i].x - pixelPoints[i - 1].x).toFloat()
                    val dy = (pixelPoints[i].y - pixelPoints[i - 1].y).toFloat()
                    pixelLength += kotlin.math.sqrt(dx * dx + dy * dy)
                }

                if (pixelLength < 80f * density) return@segmentLoop

                val targetDistance = pixelLength / 2f
                var accumulated = 0f
                var midPoint = pixelPoints.first()
                var pointA = pixelPoints.first()
                var pointB = pixelPoints[1]

                for (i in 1 until pixelPoints.size) {
                    val dx = (pixelPoints[i].x - pixelPoints[i - 1].x).toFloat()
                    val dy = (pixelPoints[i].y - pixelPoints[i - 1].y).toFloat()
                    val segmentDistance = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (accumulated + segmentDistance >= targetDistance) {
                        pointA = pixelPoints[i - 1]
                        pointB = pixelPoints[i]
                        val ratio = if (segmentDistance > 0f) {
                            (targetDistance - accumulated) / segmentDistance
                        } else {
                            0f
                        }
                        midPoint = Point(
                            (pointA.x + (pointB.x - pointA.x) * ratio).roundToInt(),
                            (pointA.y + (pointB.y - pointA.y) * ratio).roundToInt()
                        )
                        break
                    }
                    accumulated += segmentDistance
                }

                var angle = Math.toDegrees(
                    atan2(
                        (pointB.y - pointA.y).toDouble(),
                        (pointB.x - pointA.x).toDouble()
                    )
                ).toFloat()
                if (angle > 90f) angle -= 180f
                else if (angle < -90f) angle += 180f

                val textWidth = textPaint.measureText(name) + 8f * density
                val x = midPoint.x.toFloat()
                val y = midPoint.y.toFloat()
                if (x !in -200f..(mapView.width + 200f) ||
                    y !in -100f..(mapView.height + 100f)
                ) {
                    return@segmentLoop
                }

                val box = RectF(
                    x - textWidth / 2f,
                    y - 8f * density,
                    x + textWidth / 2f,
                    y + 8f * density
                )
                val expandedBox = RectF(
                    box.left - 10f * density,
                    box.top - 5f * density,
                    box.right + 10f * density,
                    box.bottom + 5f * density
                )
                if (placedBoxes.any { existing ->
                        expandedBox.left <= existing.right &&
                            expandedBox.right >= existing.left &&
                            expandedBox.top <= existing.bottom &&
                            expandedBox.bottom >= existing.top
                    }) {
                    return@segmentLoop
                }

                placedBoxes.add(expandedBox)
                canvas.save()
                canvas.rotate(angle, x, y)
                val baseline = y - 5f * density
                canvas.drawText(name, x, baseline, textStrokePaint)
                canvas.drawText(name, x, baseline, textPaint)
                canvas.restore()
            }
        }
    }

    private fun drawStations(canvas: Canvas, zoom: Double) {
        val density = mapView.resources.displayMetrics.density

        data.stations.forEach stationLoop@ { station ->
            val importance = station.importance
            val shouldShow = when {
                zoom >= 14.0 -> true
                zoom >= 11.0 -> importance <= 5
                zoom >= 9.0 -> importance <= 3
                zoom >= 7.0 -> importance <= 2
                zoom >= 5.0 -> importance <= 1
                else -> importance <= 0
            }
            if (!shouldShow) return@stationLoop

            mapView.projection.toPixels(station.point, projectionPoint)
            if (projectionPoint.x !in -100..(mapView.width + 100) ||
                projectionPoint.y !in -100..(mapView.height + 100)
            ) {
                return@stationLoop
            }

            val radius = when {
                zoom >= 15.0 -> 4.0f * density
                zoom >= 12.0 -> 3.5f * density
                else -> 3.0f * density
            }
            stationStrokePaint.strokeWidth = max(1f, 1.0f * density)
            canvas.drawCircle(
                projectionPoint.x.toFloat(),
                projectionPoint.y.toFloat(),
                radius + 1f * density,
                stationStrokePaint
            )
            canvas.drawCircle(
                projectionPoint.x.toFloat(),
                projectionPoint.y.toFloat(),
                radius,
                stationPaint
            )

            if (station.name.isNotBlank() && zoom >= 10.0) {
                textPaint.textSize = when {
                    zoom >= 15.0 -> 14f * density
                    zoom >= 13.0 -> 12f * density
                    else -> 10f * density
                }
                textStrokePaint.textSize = textPaint.textSize
                textStrokePaint.strokeWidth = 3.0f * density
                val textX = projectionPoint.x.toFloat()
                val textY = projectionPoint.y.toFloat() - radius - 5f * density
                canvas.drawText(station.name, textX, textY, textStrokePaint)
                canvas.drawText(station.name, textX, textY, textPaint)
            }
        }
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

    val markerIndex = selectedPoint?.let { selected ->
        points.indexOfFirst { it.signal.id == selected.signal.id }
    }?.takeIf { it >= 0 } ?: points.lastIndex

    val markerPoint = points.getOrNull(markerIndex)
    if (markerPoint != null) {
        mapView.overlays.add(
            TrainPositionOverlay(
                mapView,
                markerPoint.geoPoint
            )
        )
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
private class TrainPositionOverlay(
    private val mapView: MapView,
    private val geoPoint: GeoPoint
) : org.osmdroid.views.overlay.Overlay() {

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(85, 33, 150, 243)
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(33, 150, 243)
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return

        val density = mapView.resources.displayMetrics.density
        val point = mapView.projection.toPixels(geoPoint, Point())
        val centerX = point.x.toFloat()
        val centerY = point.y.toFloat()

        canvas.drawCircle(centerX, centerY, 11f * density, haloPaint)
        canvas.drawCircle(centerX, centerY, 7f * density, ringPaint)
        canvas.drawCircle(centerX, centerY, 5f * density, centerPaint)
    }

    override fun onSingleTapConfirmed(
        e: MotionEvent?,
        mapView: MapView?
    ): Boolean {
        return true
    }
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