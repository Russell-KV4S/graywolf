package com.nw5w.graywolf.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AprsMapProjectionTest {

    @Test
    fun `center projects onto viewport visibleArea anchor`() {
        val fullViewport = MapViewport(widthPx = 1280, heightPx = 720)
        val (fx, fy) = AprsMapProjection.project(
            lat = 34.73,
            lon = -86.58,
            centerLat = 34.73,
            centerLon = -86.58,
            zoom = 11.0,
            viewport = fullViewport,
        )
        assertEquals(640f, fx, 0.01f)
        assertEquals(360f, fy, 0.01f)

        // When MapWithContentTemplate overlays a 480px card on the left, anchor shifts right.
        val splitViewport = MapViewport(
            widthPx = 1280,
            heightPx = 720,
            visibleLeft = 480,
            visibleTop = 40,
            visibleRight = 1280,
            visibleBottom = 720,
        )
        val (sx, sy) = AprsMapProjection.project(
            lat = 34.73,
            lon = -86.58,
            centerLat = 34.73,
            centerLon = -86.58,
            zoom = 11.0,
            viewport = splitViewport,
        )
        assertEquals(880f, sx, 0.01f)
        assertEquals(380f, sy, 0.01f)
    }

    @Test
    fun `panByPixels shifts map inversely and roundtrips`() {
        val vp = MapViewport(widthPx = 1000, heightPx = 800)
        val (newLat, newLon) = AprsMapProjection.panByPixels(
            centerLat = 34.73,
            centerLon = -86.58,
            zoom = 11.0,
            deltaSurfaceXPx = 100f,
            deltaSurfaceYPx = 50f,
        )
        // Panning finger right (+dx) pulls earlier west longitudes into the center (smaller lon).
        assertTrue(newLon < -86.58)
        // Panning finger down (+dy) pulls north latitudes into the center (larger lat).
        assertTrue(newLat > 34.73)

        val (backX, backY) = AprsMapProjection.project(
            lat = 34.73,
            lon = -86.58,
            centerLat = newLat,
            centerLon = newLon,
            zoom = 11.0,
            viewport = vp,
        )
        assertEquals(600f, backX, 0.5f)
        assertEquals(450f, backY, 0.5f)
    }

    @Test
    fun `niceRangeRingKm and tileXY produce reasonable values`() {
        val ringKm = AprsMapProjection.niceRangeRingKm(lat = 34.73, zoom = 11.0)
        assertTrue(ringKm in 2.0..20.0)
        val (tx, ty) = AprsMapProjection.tileXY(34.7304, -86.5861, 11)
        assertTrue(tx in 0 until 2048)
        assertTrue(ty in 0 until 2048)
    }
}
