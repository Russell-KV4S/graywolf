package com.nw5w.graywolf.car

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.nw5w.graywolf.GraywolfApp
import com.nw5w.graywolf.MainActivity
import com.nw5w.graywolf.R
import com.nw5w.graywolf.api.GraywolfLocalApi
import com.nw5w.graywolf.api.OwnPositionDto
import com.nw5w.graywolf.api.StationHistoryEntry
import com.nw5w.graywolf.api.StationHistoryFilter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Main Android Auto screen showing the live APRS tactical map together with a collapsible
 * newest-first Station History panel (`MapWithContentTemplate` on API >= 7) and a one-tap
 * full-screen map toggle (`NavigationTemplate`), plus Beacon TX and station inspection.
 */
class GraywolfCarMapScreen(carContext: CarContext) : Screen(carContext) {

    private val app = carContext.applicationContext as GraywolfApp
    private val api = GraywolfLocalApi(tokenProvider = { app.bearerToken })
    private val ioExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var pollFuture: ScheduledFuture<*>? = null

    private var historyVisible: Boolean = true
    private var stations: List<StationHistoryEntry> = emptyList()
    private var ownPos: OwnPositionDto? = null
    private var loading: Boolean = true

    private val renderer = AprsMapSurfaceRenderer(carContext) {
        invalidate()
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(renderer)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                pollFuture?.cancel(false)
                pollFuture = ioExecutor.scheduleWithFixedDelay(
                    { refreshData() },
                    0L,
                    5L,
                    TimeUnit.SECONDS,
                )
            }

            override fun onStop(owner: LifecycleOwner) {
                pollFuture?.cancel(false)
                pollFuture = null
            }

            override fun onDestroy(owner: LifecycleOwner) {
                pollFuture?.cancel(false)
                renderer.release()
                ioExecutor.shutdownNow()
            }
        })
    }

    override fun onGetTemplate(): Template {
        val useSplitMap = historyVisible && carContext.carAppApiLevel >= 7
        return if (useSplitMap) {
            MapWithContentTemplate.Builder()
                .setMapController(buildMapController())
                .setActionStrip(buildMainActionStrip())
                .setContentTemplate(buildHistoryListTemplate())
                .build()
        } else if (historyVisible) {
            // Pre-API 7 fallback when history list is visible.
            buildHistoryListTemplate()
        } else {
            NavigationTemplate.Builder()
                .setActionStrip(buildMainActionStrip())
                .setMapActionStrip(buildMapActionStrip())
                .setPanModeListener { /* pan handled cleanly by AprsMapSurfaceRenderer.onScroll */ }
                .build()
        }
    }

    private fun buildMainActionStrip(): ActionStrip {
        val toggleIcon = if (historyVisible) R.drawable.ic_car_fullscreen else R.drawable.ic_car_history
        val toggleTitle = if (historyVisible) "Full Map" else "History"
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(toggleTitle)
                    .setIcon(carIcon(toggleIcon))
                    .setOnClickListener {
                        historyVisible = !historyVisible
                        invalidate()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_beacon))
                    .setOnClickListener { sendBeaconNow() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_recenter))
                    .setOnClickListener {
                        renderer.recenter()
                        invalidate()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_refresh))
                    .setOnClickListener { ioExecutor.execute { refreshData() } }
                    .build(),
            )
            .build()
    }

    private fun buildMapActionStrip(): ActionStrip {
        return ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_zoom_in))
                    .setOnClickListener { renderer.zoomIn() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_zoom_out))
                    .setOnClickListener { renderer.zoomOut() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(carIcon(R.drawable.ic_car_recenter))
                    .setOnClickListener { renderer.recenter() }
                    .build(),
            )
            .build()
    }

    private fun buildMapController(): MapController {
        return MapController.Builder()
            .setMapActionStrip(buildMapActionStrip())
            .setPanModeListener { /* surface handles scroll/scale directly */ }
            .build()
    }

    private fun buildHistoryListTemplate(): ListTemplate {
        val listLimit = try {
            carContext.getCarService(ConstraintManager::class.java)
                .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        } catch (_: Throwable) {
            6
        }.coerceIn(4, 25)

        val itemBuilder = ItemList.Builder()
            .setNoItemsMessage(if (loading) "Loading stations..." else "No stations heard yet")

        for (st in stations.take(listLimit)) {
            val distStr = st.distanceKm?.let { km ->
                val mi = km * 0.621371
                val dir = st.bearingDeg?.let { StationHistoryFilter.cardinalDirection(it) } ?: ""
                String.format(Locale.US, "%.1f mi %s", mi, dir).trim()
            } ?: "No GPS"

            val title = buildString {
                append(st.callsign)
                if (st.isEmergency) append(" [EMG]")
                append(" • ").append(st.ageLabel)
                append(" • ").append(distStr)
            }

            val line2 = buildString {
                if (st.comment.isNotBlank()) {
                    append(st.comment.take(48))
                } else if (st.statusText.isNotBlank()) {
                    append(st.statusText.take(48))
                } else {
                    append(if (st.gated) "APRS-IS" else if (st.via.isBlank()) "Direct RF" else "Via ${st.via}")
                }
                st.tempF?.let { append(" • ${it.toInt()}°F") }
            }

            itemBuilder.addItem(
                Row.Builder()
                    .setTitle(title)
                    .addText(line2)
                    .setBrowsable(true)
                    .setOnClickListener {
                        renderer.focusStation(st)
                        screenManager.push(StationDetailCarScreen(carContext, st, renderer))
                    }
                    .build(),
            )
        }

        val header = Header.Builder()
            .setTitle("Station History (${stations.size})")
            .build()

        return ListTemplate.Builder()
            .setHeader(header)
            .setSingleList(itemBuilder.build())
            .build()
    }

    private fun refreshData() {
        try {
            val (rfOnly, directRxOnly) = MainActivity.loadMapFilterPrefs(carContext)
            val rawStations = api.fetchStations(timerange = "24h")
            val exclusions = api.fetchExclusions()
            val pos = api.fetchPosition()

            val filtered = StationHistoryFilter.buildHistory(
                stations = rawStations,
                exclusions = exclusions,
                rfOnly = rfOnly,
                directRxOnly = directRxOnly,
                ownLat = pos?.takeIf { it.valid }?.lat,
                ownLon = pos?.takeIf { it.valid }?.lon,
            )

            ownPos = pos
            stations = filtered
            loading = false
            renderer.updateData(pos, filtered, selectedCall = null)
            invalidate()
        } catch (t: Throwable) {
            Log.w(TAG, "refreshData failed: $t")
            loading = false
            invalidate()
        }
    }

    private fun sendBeaconNow() {
        ioExecutor.execute {
            val sent = api.triggerFirstEnabledBeacon()
            val msg = if (sent != null) {
                "Beacon transmitted (${sent.callsign.ifBlank { sent.type }})"
            } else {
                "No beacon configured or TX unavailable"
            }
            CarToast.makeText(carContext, msg, CarToast.LENGTH_SHORT).show()
        }
    }

    private fun carIcon(resId: Int): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()

    companion object {
        private const val TAG = "GraywolfCarMapScreen"
    }
}

/**
 * Displays detailed telemetry for a selected station while keeping the station focused on the
 * car surface map, and lets the operator hand off turn-by-turn navigation to Google Maps.
 */
class StationDetailCarScreen(
    carContext: CarContext,
    private val station: StationHistoryEntry,
    private val renderer: AprsMapSurfaceRenderer,
) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val distLine = station.distanceKm?.let { km ->
            val mi = km * 0.621371
            val brg = station.bearingDeg?.let { "${it.toInt()}° ${StationHistoryFilter.cardinalDirection(it)}" } ?: ""
            String.format(Locale.US, "%.1f mi (%.1f km) %s • Heard %s ago", mi, km, brg, station.ageLabel)
        } ?: "Heard ${station.ageLabel} ago"

        val pathLine = buildString {
            append(if (station.gated) "APRS-IS gated" else if (station.via.isBlank()) "Direct RF" else "RF via ${station.via}")
            if (station.speedKt > 0.5) {
                append(String.format(Locale.US, " • %.0f kt @ %.0f°", station.speedKt, station.course))
            }
            station.tempF?.let { append(String.format(Locale.US, " • %.1f°F", it)) }
        }

        val coordsLine = String.format(Locale.US, "%.5f, %.5f", station.lat, station.lon)

        val navIntent = Intent(
            CarContext.ACTION_NAVIGATE,
            Uri.parse(String.format(Locale.US, "google.navigation:q=%.6f,%.6f", station.lat, station.lon)),
        )

        val paneBuilder = Pane.Builder()
            .addRow(Row.Builder().setTitle(distLine).build())
            .addRow(Row.Builder().setTitle("Path & Motion").addText(pathLine).build())
            .addRow(Row.Builder().setTitle("Position").addText(coordsLine).build())
            .addAction(
                Action.Builder()
                    .setTitle("Navigate")
                    .setBackgroundColor(CarColor.PRIMARY)
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_navigate)).build())
                    .setOnClickListener {
                        try {
                            carContext.startCarApp(navIntent)
                        } catch (t: Throwable) {
                            CarToast.makeText(carContext, "No navigation app available", CarToast.LENGTH_SHORT).show()
                        }
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setTitle("Recenter GPS")
                    .setOnClickListener {
                        renderer.recenter()
                        screenManager.pop()
                    }
                    .build(),
            )

        val notes = station.statusText.ifBlank { station.comment }
        if (notes.isNotBlank()) {
            paneBuilder.addRow(Row.Builder().setTitle("Comment").addText(notes.take(80)).build())
        }

        val header = Header.Builder()
            .setStartHeaderAction(Action.BACK)
            .setTitle(station.callsign)
            .build()

        val paneTemplate = PaneTemplate.Builder(paneBuilder.build())
            .setHeader(header)
            .build()

        return if (carContext.carAppApiLevel >= 7) {
            MapWithContentTemplate.Builder()
                .setMapController(
                    MapController.Builder()
                        .setMapActionStrip(
                            ActionStrip.Builder()
                                .addAction(Action.PAN)
                                .addAction(
                                    Action.Builder()
                                        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_zoom_in)).build())
                                        .setOnClickListener { renderer.zoomIn() }
                                        .build(),
                                )
                                .addAction(
                                    Action.Builder()
                                        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_zoom_out)).build())
                                        .setOnClickListener { renderer.zoomOut() }
                                        .build(),
                                )
                                .build(),
                        )
                        .build(),
                )
                .setContentTemplate(paneTemplate)
                .build()
        } else {
            paneTemplate
        }
    }
}
