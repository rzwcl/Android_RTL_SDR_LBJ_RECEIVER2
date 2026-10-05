package com.example.ui.screens

import android.content.Context
import android.graphics.Color as AndroidColor
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
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max

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

    if (mapMode == HistoryMapMode.SATELLITE) railwayMapData?.stations?.forEach { station ->
        val marker = Marker(mapView).apply {
            position = station.point
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createStationMarkerDrawable(mapView)
            title = station.name
            snippet = station.lineName
        }
        mapView.overlays.add(marker)
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

private fun createStationMarkerDrawable(mapView: MapView): GradientDrawable {
    val density = mapView.resources.displayMetrics.density
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(AndroidColor.WHITE)
        setStroke(max(1, (1f * density).toInt()), AndroidColor.BLACK)
        setSize(
            (10f * density).toInt(),
            (10f * density).toInt()
        )
    }
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