package com.nw5w.graywolf.car

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

data class MapViewport(
    val widthPx: Int,
    val heightPx: Int,
    val visibleLeft: Int = 0,
    val visibleTop: Int = 0,
    val visibleRight: Int = widthPx,
    val visibleBottom: Int = heightPx,
) {
    val anchorX: Float
        get() = ((visibleLeft + visibleRight).coerceAtLeast(0)) / 2f

    val anchorY: Float
        get() = ((visibleTop + visibleBottom).coerceAtLeast(0)) / 2f

    val visibleWidth: Int
        get() = (visibleRight - visibleLeft).coerceAtLeast(1)

    val visibleHeight: Int
        get() = (visibleBottom - visibleTop).coerceAtLeast(1)
}

/**
 * Pure Web Mercator projection helpers shared between [AprsMapSurfaceRenderer]
 * and unit tests without Android framework dependencies.
 */
object AprsMapProjection {
    const val TILE_SIZE_PX = 256.0
    const val MIN_ZOOM = 4.0
    const val MAX_ZOOM = 16.0
    private const val MAX_LAT = 85.05112878
    private const val EARTH_CIRCUMFERENCE_KM = 40075.016686

    fun worldSizePx(zoom: Double): Double = TILE_SIZE_PX * 2.0.pow(zoom.coerceIn(MIN_ZOOM, MAX_ZOOM))

    fun clampLat(lat: Double): Double = lat.coerceIn(-MAX_LAT, MAX_LAT)

    fun normalizeLon(lon: Double): Double {
        var v = lon
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return v
    }

    fun lonToWorldX(lon: Double, zoom: Double): Double {
        val worldSize = worldSizePx(zoom)
        return ((normalizeLon(lon) + 180.0) / 360.0) * worldSize
    }

    fun latToWorldY(lat: Double, zoom: Double): Double {
        val rad = Math.toRadians(clampLat(lat))
        val worldSize = worldSizePx(zoom)
        val mercN = ln(tan(PI / 4.0 + rad / 2.0))
        return (worldSize / 2.0) - (worldSize * mercN / (2.0 * PI))
    }

    fun worldXToLon(wx: Double, zoom: Double): Double {
        val worldSize = worldSizePx(zoom)
        return normalizeLon((wx / worldSize) * 360.0 - 180.0)
    }

    fun worldYToLat(wy: Double, zoom: Double): Double {
        val worldSize = worldSizePx(zoom)
        val n = PI - (2.0 * PI * wy) / worldSize
        return clampLat(Math.toDegrees(atan(sinh(n))))
    }

    fun project(
        lat: Double,
        lon: Double,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        viewport: MapViewport,
    ): Pair<Float, Float> {
        val cx = lonToWorldX(centerLon, zoom)
        val cy = latToWorldY(centerLat, zoom)
        val px = lonToWorldX(lon, zoom)
        val py = latToWorldY(lat, zoom)
        val sx = viewport.anchorX + (px - cx).toFloat()
        val sy = viewport.anchorY + (py - cy).toFloat()
        return sx to sy
    }

    fun panByPixels(
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
        deltaSurfaceXPx: Float,
        deltaSurfaceYPx: Float,
    ): Pair<Double, Double> {
        val cx = lonToWorldX(centerLon, zoom) - deltaSurfaceXPx
        val cy = latToWorldY(centerLat, zoom) - deltaSurfaceYPx
        return worldYToLat(cy, zoom) to worldXToLon(cx, zoom)
    }

    fun kmPerPixel(lat: Double, zoom: Double): Double {
        val latCos = cos(Math.toRadians(clampLat(lat))).coerceAtLeast(0.1)
        return (EARTH_CIRCUMFERENCE_KM * latCos) / worldSizePx(zoom)
    }

    fun niceRangeRingKm(lat: Double, zoom: Double, targetRadiusPx: Float = 130f): Double {
        val targetKm = kmPerPixel(lat, zoom) * targetRadiusPx
        val steps = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0)
        return steps.minByOrNull { kotlin.math.abs(it - targetKm) } ?: 5.0
    }

    fun tileXY(lat: Double, lon: Double, zoomInt: Int): Pair<Int, Int> {
        val n = 1 shl zoomInt
        val x = floor(((normalizeLon(lon) + 180.0) / 360.0) * n).toInt().coerceIn(0, n - 1)
        val latRad = Math.toRadians(clampLat(lat))
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n)
            .toInt()
            .coerceIn(0, n - 1)
        return x to y
    }
}
