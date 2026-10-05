package com.example.ui.screens

import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.TrainSignalRecord
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.max

private data class MapPoint(
    val signal: TrainSignalRecord,
    val geoPoint: GeoPoint
)

@Composable
fun HistoryTrackMap(
    signals: List<TrainSignalRecord>,
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

    val mapView = remember(context) {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
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
            renderHistoryTrack(view, mapPoints)
        }
    )
}

private fun renderHistoryTrack(
    mapView: MapView,
    points: List<MapPoint>
) {
    mapView.overlays.clear()

    if (points.isEmpty()) {
        mapView.invalidate()
        return
    }

    val geoPoints = points.map { it.geoPoint }
    val uniqueGeoPoints = geoPoints.distinctBy { point ->
        point.latitude.toString() + "," + point.longitude.toString()
    }

    if (uniqueGeoPoints.size <= 1) {
        mapView.controller.setCenter(geoPoints.first())
        mapView.controller.setZoom(17.0)
    } else {
        val bounds = BoundingBox.fromGeoPoints(uniqueGeoPoints)
        mapView.controller.setCenter(bounds.center)
        mapView.zoomToBoundingBox(bounds.increaseByScale(1.25f), false)
        val zoom = mapView.zoomLevelDouble.coerceIn(2.0, 18.0)
        mapView.controller.setZoom(zoom)
    }

    if (geoPoints.size >= 2) {
        val polyline = Polyline(mapView).apply {
            setPoints(geoPoints)
            outlinePaint.color = AndroidColor.BLACK
            outlinePaint.strokeWidth = 4f
        }
        mapView.overlays.add(polyline)
    }

    val lastPoint = points.last()
    val marker = Marker(mapView).apply {
        position = lastPoint.geoPoint
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = createTrainMarkerDrawable(mapView)
        title = lastPoint.signal.trainNo
        snippet = buildMarkerSnippet(lastPoint.signal)
    }
    mapView.overlays.add(marker)

    mapView.invalidate()
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