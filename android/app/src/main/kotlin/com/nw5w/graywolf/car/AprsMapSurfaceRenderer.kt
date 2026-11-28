package com.nw5w.graywolf.car

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.LruCache
import android.view.Surface
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.nw5w.graywolf.api.OwnPositionDto
import com.nw5w.graywolf.api.StationHistoryEntry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders an interactive dark-theme APRS tactical map onto the Android Auto projection [Surface].
 * Falls back cleanly to a vector grid + range rings when off-grid over RF and asynchronously layers
 * cached dark basemap tiles when cellular/Wi-Fi data is available.
 */
class AprsMapSurfaceRenderer(
    private val context: Context,
    private val onFollowStateChanged: (autoFollow: Boolean) -> Unit = {},
) : SurfaceCallback {

    private val renderThread = HandlerThread("graywolf-car-render").apply { start() }
    private val renderHandler = Handler(renderThread.looper)
    private val tileExecutor = Executors.newFixedThreadPool(2)
    private val tileCache = object : LruCache<String, Bitmap>(64) {}
    private val inFlightTiles = Collections.synchronizedSet(HashSet<String>())
    private val tileDiskDir = File(context.cacheDir, "car_tiles").apply { mkdirs() }

    @Volatile private var surface: Surface? = null
    @Volatile private var viewport = MapViewport(1280, 720)
    @Volatile private var centerLat: Double = 34.7304
    @Volatile private var centerLon: Double = -86.5861
    @Volatile private var hasInitialFix = false
    @Volatile private var zoom: Double = 11.0
    @Volatile private var autoFollow: Boolean = true
    @Volatile private var ownPos: OwnPositionDto? = null
    @Volatile private var stations: List<StationHistoryEntry> = emptyList()
    @Volatile private var selectedCallsign: String? = null

    private val bgPaint = Paint().apply { color = Color.parseColor("#0F141A") }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1D2835")
        strokeWidth = 1.5f
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334155")
        style = Paint.Style.STROKE
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val ringLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#94A3B8")
        textSize = 24f
        typeface = Typeface.MONOSPACE
    }
    private val ownFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.FILL
    }
    private val ownStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 15, 23, 42)
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F8FAFC")
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
    }
    private val statusTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        textSize = 22f
        typeface = Typeface.MONOSPACE
    }

    fun isAutoFollow(): Boolean = autoFollow

    fun updateData(
        newOwnPos: OwnPositionDto?,
        newStations: List<StationHistoryEntry>,
        selectedCall: String?,
    ) {
        ownPos = newOwnPos
        stations = newStations
        selectedCallsign = selectedCall

        if (autoFollow && newOwnPos != null && newOwnPos.valid) {
            centerLat = newOwnPos.lat
            centerLon = newOwnPos.lon
            hasInitialFix = true
        } else if (!hasInitialFix) {
            val anchor = newStations.firstOrNull()
            if (anchor != null) {
                centerLat = anchor.lat
                centerLon = anchor.lon
                hasInitialFix = true
            }
        }
        requestRedraw()
    }

    fun focusStation(station: StationHistoryEntry) {
        setAutoFollowInternal(false)
        centerLat = station.lat
        centerLon = station.lon
        selectedCallsign = station.callsign
        hasInitialFix = true
        if (zoom < 12.0) zoom = 12.0
        requestRedraw()
    }

    fun recenter() {
        setAutoFollowInternal(true)
        selectedCallsign = null
        val own = ownPos
        if (own != null && own.valid) {
            centerLat = own.lat
            centerLon = own.lon
        } else {
            stations.firstOrNull()?.let {
                centerLat = it.lat
                centerLon = it.lon
            }
        }
        requestRedraw()
    }

    fun zoomIn() {
        zoom = (zoom + 1.0).coerceIn(AprsMapProjection.MIN_ZOOM, AprsMapProjection.MAX_ZOOM)
        requestRedraw()
    }

    fun zoomOut() {
        zoom = (zoom - 1.0).coerceIn(AprsMapProjection.MIN_ZOOM, AprsMapProjection.MAX_ZOOM)
        requestRedraw()
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        surface = surfaceContainer.surface
        val cur = viewport
        viewport = MapViewport(
            widthPx = surfaceContainer.width.coerceAtLeast(1),
            heightPx = surfaceContainer.height.coerceAtLeast(1),
            visibleLeft = cur.visibleLeft.coerceIn(0, surfaceContainer.width),
            visibleTop = cur.visibleTop.coerceIn(0, surfaceContainer.height),
            visibleRight = if (cur.visibleRight > 0 && cur.widthPx == surfaceContainer.width) {
                cur.visibleRight
            } else {
                surfaceContainer.width.coerceAtLeast(1)
            },
            visibleBottom = if (cur.visibleBottom > 0 && cur.heightPx == surfaceContainer.height) {
                cur.visibleBottom
            } else {
                surfaceContainer.height.coerceAtLeast(1)
            },
        )
        requestRedraw()
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        val cur = viewport
        if (visibleArea.width() > 0 && visibleArea.height() > 0) {
            viewport = cur.copy(
                visibleLeft = visibleArea.left,
                visibleTop = visibleArea.top,
                visibleRight = visibleArea.right,
                visibleBottom = visibleArea.bottom,
            )
            requestRedraw()
        }
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        if (viewport.visibleWidth == viewport.widthPx && stableArea.width() > 0 && stableArea.height() > 0) {
            viewport = viewport.copy(
                visibleLeft = stableArea.left,
                visibleTop = stableArea.top,
                visibleRight = stableArea.right,
                visibleBottom = stableArea.bottom,
            )
            requestRedraw()
        }
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        surface = null
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        setAutoFollowInternal(false)
        val (newLat, newLon) = AprsMapProjection.panByPixels(
            centerLat = centerLat,
            centerLon = centerLon,
            zoom = zoom,
            deltaSurfaceXPx = -distanceX,
            deltaSurfaceYPx = -distanceY,
        )
        centerLat = newLat
        centerLon = newLon
        hasInitialFix = true
        requestRedraw()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor <= 0f) return
        val deltaZoom = ln(scaleFactor.toDouble()) / ln(2.0)
        zoom = (zoom + deltaZoom).coerceIn(AprsMapProjection.MIN_ZOOM, AprsMapProjection.MAX_ZOOM)
        requestRedraw()
    }

    fun release() {
        surface = null
        renderThread.quitSafely()
        tileExecutor.shutdownNow()
    }

    private fun setAutoFollowInternal(follow: Boolean) {
        if (autoFollow != follow) {
            autoFollow = follow
            onFollowStateChanged(follow)
        }
    }

    fun requestRedraw() {
        renderHandler.removeCallbacks(drawRunnable)
        renderHandler.post(drawRunnable)
    }

    private val drawRunnable = Runnable { drawFrame() }

    private fun drawFrame() {
        val target = surface ?: return
        if (!target.isValid) return
        val vp = viewport
        var canvas: Canvas? = null
        try {
            canvas = target.lockCanvas(null) ?: return
            canvas.drawRect(0f, 0f, vp.widthPx.toFloat(), vp.heightPx.toFloat(), bgPaint)

            drawTilesAndGrid(canvas, vp)
            drawRangeRings(canvas, vp)
            drawStations(canvas, vp)
            drawOwnship(canvas, vp)
            drawHudOverlay(canvas, vp)
        } catch (t: Throwable) {
            Log.w(TAG, "drawFrame error: $t")
        } finally {
            if (canvas != null) {
                try {
                    target.unlockCanvasAndPost(canvas)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun drawTilesAndGrid(canvas: Canvas, vp: MapViewport) {
        val zInt = zoom.roundToInt().coerceIn(4, 16)
        val scale = Math.pow(2.0, zoom - zInt)
        val tileScreenSize = (AprsMapProjection.TILE_SIZE_PX * scale).toFloat()

        val cx = AprsMapProjection.lonToWorldX(centerLon, zoom)
        val cy = AprsMapProjection.latToWorldY(centerLat, zoom)
        val originLeft = cx - vp.anchorX
        val originTop = cy - vp.anchorY

        val maxTile = (1 shl zInt) - 1
        val startTx = kotlin.math.floor(originLeft / tileScreenSize).toInt().coerceIn(0, maxTile)
        val endTx = kotlin.math.floor((originLeft + vp.widthPx) / tileScreenSize).toInt().coerceIn(0, maxTile)
        val startTy = kotlin.math.floor(originTop / tileScreenSize).toInt().coerceIn(0, maxTile)
        val endTy = kotlin.math.floor((originTop + vp.heightPx) / tileScreenSize).toInt().coerceIn(0, maxTile)

        var anyTileDrawn = false
        val dst = Rect()
        if ((endTx - startTx + 1) * (endTy - startTy + 1) <= 24) {
            for (tx in startTx..endTx) {
                for (ty in startTy..endTy) {
                    val bmp = getOrFetchTile(zInt, tx, ty)
                    if (bmp != null) {
                        val left = (tx * tileScreenSize - originLeft).roundToInt()
                        val top = (ty * tileScreenSize - originTop).roundToInt()
                        dst.set(left, top, (left + tileScreenSize).roundToInt() + 1, (top + tileScreenSize).roundToInt() + 1)
                        canvas.drawBitmap(bmp, null, dst, null)
                        anyTileDrawn = true
                    }
                }
            }
        }

        // Subtle tactical grid overlay (acts as full offline vector basemap over RF too).
        if (!anyTileDrawn) {
            val stepPx = 120f
            var x = (vp.anchorX % stepPx)
            while (x < vp.widthPx) {
                canvas.drawLine(x, 0f, x, vp.heightPx.toFloat(), gridPaint)
                x += stepPx
            }
            var y = (vp.anchorY % stepPx)
            while (y < vp.heightPx) {
                canvas.drawLine(0f, y, vp.widthPx.toFloat(), y, gridPaint)
                y += stepPx
            }
        }
    }

    private fun drawRangeRings(canvas: Canvas, vp: MapViewport) {
        val anchorLat = ownPos?.takeIf { it.valid }?.lat ?: centerLat
        val anchorLon = ownPos?.takeIf { it.valid }?.lon ?: centerLon
        val (ax, ay) = AprsMapProjection.project(anchorLat, anchorLon, centerLat, centerLon, zoom, vp)
        val ringKm = AprsMapProjection.niceRangeRingKm(anchorLat, zoom)
        val kmPerPx = AprsMapProjection.kmPerPixel(anchorLat, zoom).coerceAtLeast(1e-6)
        val radiusPx = (ringKm / kmPerPx).toFloat()
        if (radiusPx in 24f..2000f) {
            canvas.drawCircle(ax, ay, radiusPx, ringPaint)
            canvas.drawCircle(ax, ay, radiusPx * 2f, ringPaint)
            val label = if (ringKm < 1.0) "${(ringKm * 1000).roundToInt()}m" else "${ringKm.roundToInt()}km"
            canvas.drawText(label, ax + 6f, ay - radiusPx + 26f, ringLabelPaint)
        }
    }

    private fun drawStations(canvas: Canvas, vp: MapViewport) {
        val snapshot = stations
        val selected = selectedCallsign
        // Draw oldest first so newest and selected pins sit cleanly on top.
        for (st in snapshot.asReversed()) {
            val (sx, sy) = AprsMapProjection.project(st.lat, st.lon, centerLat, centerLon, zoom, vp)
            if (sx < -80f || sx > vp.widthPx + 80f || sy < -80f || sy > vp.heightPx + 80f) continue

            val isSelected = selected != null && st.callsign.equals(selected, ignoreCase = true)
            val pinColor = when {
                st.isEmergency -> Color.parseColor("#EF4444")
                !st.gated -> Color.parseColor("#22C55E")
                else -> Color.parseColor("#F59E0B")
            }

            if (isSelected) {
                markerRingPaint.color = Color.parseColor("#FACC15")
                canvas.drawCircle(sx, sy, 22f, markerRingPaint)
            }
            markerFillPaint.color = pinColor
            canvas.drawCircle(sx, sy, if (isSelected) 13f else 10f, markerFillPaint)
            canvas.drawCircle(sx, sy, if (isSelected) 13f else 10f, ownStrokePaint)

            val label = if (isSelected) "${st.callsign} (${st.ageLabel})" else st.callsign
            val tw = labelTextPaint.measureText(label)
            val lx = sx + 14f
            val ly = sy - 10f
            canvas.drawRoundRect(
                lx - 4f,
                ly - 22f,
                lx + tw + 8f,
                ly + 8f,
                6f,
                6f,
                labelBgPaint,
            )
            canvas.drawText(label, lx + 2f, ly, labelTextPaint)
        }
    }

    private fun drawOwnship(canvas: Canvas, vp: MapViewport) {
        val own = ownPos ?: return
        if (!own.valid) return
        val (ox, oy) = AprsMapProjection.project(own.lat, own.lon, centerLat, centerLon, zoom, vp)
        if (own.hasCourse) {
            val rad = Math.toRadians(own.headingDeg)
            val tipX = ox + (sin(rad) * 20f).toFloat()
            val tipY = oy - (cos(rad) * 20f).toFloat()
            val leftX = ox + (sin(rad + 2.45) * 15f).toFloat()
            val leftY = oy - (cos(rad + 2.45) * 15f).toFloat()
            val rightX = ox + (sin(rad - 2.45) * 15f).toFloat()
            val rightY = oy - (cos(rad - 2.45) * 15f).toFloat()
            val path = Path().apply {
                moveTo(tipX, tipY)
                lineTo(leftX, leftY)
                lineTo(rightX, rightY)
                close()
            }
            canvas.drawPath(path, ownFillPaint)
            canvas.drawPath(path, ownStrokePaint)
        } else {
            canvas.drawCircle(ox, oy, 12f, ownFillPaint)
            canvas.drawCircle(ox, oy, 12f, ownStrokePaint)
        }
    }

    private fun drawHudOverlay(canvas: Canvas, vp: MapViewport) {
        val countText = "${stations.size} stations • z${zoom.roundToInt()}" + if (autoFollow) " • GPS" else ""
        val tx = vp.visibleLeft + 18f
        val ty = (vp.visibleBottom - 18f).coerceAtLeast(36f)
        val tw = statusTextPaint.measureText(countText)
        canvas.drawRoundRect(tx - 8f, ty - 26f, tx + tw + 10f, ty + 8f, 8f, 8f, labelBgPaint)
        canvas.drawText(countText, tx, ty, statusTextPaint)
    }

    private fun getOrFetchTile(z: Int, x: Int, y: Int): Bitmap? {
        val key = "$z/$x/$y"
        tileCache.get(key)?.let { return it }

        val diskFile = File(tileDiskDir, "${z}_${x}_${y}.png")
        if (diskFile.exists() && diskFile.length() > 0L) {
            BitmapFactory.decodeFile(diskFile.absolutePath)?.let {
                tileCache.put(key, it)
                return it
            }
        }

        if (inFlightTiles.add(key)) {
            tileExecutor.execute {
                try {
                    val url = URL("https://basemaps.cartocdn.com/dark_all/$z/$x/$y.png")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 2_500
                    conn.readTimeout = 3_500
                    conn.setRequestProperty("User-Agent", "Graywolf-AndroidAuto/1.0")
                    if (conn.responseCode in 200..299) {
                        val bytes = conn.inputStream.use { it.readBytes() }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            tileCache.put(key, bmp)
                            try {
                                diskFile.writeBytes(bytes)
                            } catch (_: Throwable) {
                            }
                            requestRedraw()
                        }
                    }
                    conn.disconnect()
                } catch (_: Throwable) {
                    // Expected off-grid over RF; keep vector grid clean.
                } finally {
                    inFlightTiles.remove(key)
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AprsMapSurfaceRenderer"
    }
}
