package com.nw5w.graywolf.api

import java.time.Instant
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class StationHistoryEntry(
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val speedKt: Double,
    val course: Double,
    val via: String,
    val gated: Boolean,
    val direction: String,
    val comment: String,
    val statusText: String,
    val isEmergency: Boolean,
    val symbolTable: String,
    val symbolCode: String,
    val tempF: Double?,
    val lastHeardEpochMs: Long,
    val ageLabel: String,
    val distanceKm: Double?,
    val bearingDeg: Double?,
)

/**
 * Pure filtering, sorting (newest first), exclusion matching, and geometry calculations
 * shared across Android Auto and Wear OS station history views.
 */
object StationHistoryFilter {
    private const val EARTH_RADIUS_KM = 6371.0

    fun callsignMatches(pattern: String, callsign: String): Boolean {
        val pat = pattern.trim().uppercase(Locale.US)
        val call = callsign.trim().uppercase(Locale.US)
        if (pat.isEmpty() || call.isEmpty()) return false
        if ('*' in pat) {
            val regex = Regex(
                buildString {
                    append('^')
                    for (ch in pat) {
                        when (ch) {
                            '*' -> append(".*")
                            else -> append(Regex.escape(ch.toString()))
                        }
                    }
                    append('$')
                },
            )
            return regex.matches(call)
        }
        // Without explicit SSID or wildcard, match base callsign and all SSIDs (e.g. N0CALL -> N0CALL-9).
        if ('-' !in pat) {
            return call == pat || call.startsWith("$pat-")
        }
        return call == pat
    }

    fun isExcluded(callsign: String, exclusions: Collection<String>): Boolean {
        if (exclusions.isEmpty()) return false
        return exclusions.any { callsignMatches(it, callsign) }
    }

    fun parseTimestampMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            Instant.parse(raw.trim()).toEpochMilli()
        } catch (_: Throwable) {
            0L
        }
    }

    fun formatAge(ageMs: Long): String {
        val sec = (ageMs / 1000L).coerceAtLeast(0L)
        return when {
            sec < 60L -> "${sec}s"
            sec < 3600L -> "${sec / 60L}m"
            sec < 86_400L -> "${sec / 3600L}h"
            else -> "${sec / 86_400L}d"
        }
    }

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_KM * c
    }

    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun cardinalDirection(bearingDeg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((bearingDeg + 22.5) % 360.0) / 45.0).toInt()
        return dirs[idx.coerceIn(0, 7)]
    }

    fun buildHistory(
        stations: List<StationDto>,
        exclusions: Collection<String> = emptyList(),
        rfOnly: Boolean = false,
        directRxOnly: Boolean = false,
        ownLat: Double? = null,
        ownLon: Double? = null,
        nowMs: Long = System.currentTimeMillis(),
        maxItems: Int = 200,
    ): List<StationHistoryEntry> {
        val deduped = LinkedHashMap<String, StationHistoryEntry>()
        for (st in stations) {
            val call = st.callsign.trim()
            if (call.isEmpty()) continue
            if (isExcluded(call, exclusions)) continue

            val latestPos = st.positions.firstOrNull()
            val dir = latestPos?.direction?.ifBlank { st.direction } ?: st.direction
            val gated = latestPos?.gated ?: st.gated

            if (directRxOnly) {
                val directMs = parseTimestampMs(st.lastDirectHeard)
                if (directMs <= 0L) continue
            } else if (rfOnly) {
                if (!dir.equals("RX", ignoreCase = true) || gated) continue
            }

            if (latestPos == null) continue
            val heardMs = maxOf(
                parseTimestampMs(st.lastHeard),
                parseTimestampMs(latestPos.timestamp),
            )
            val key = call.uppercase(Locale.US)
            val existing = deduped[key]
            if (existing != null && existing.lastHeardEpochMs >= heardMs) continue

            val distKm = if (ownLat != null && ownLon != null) {
                haversineKm(ownLat, ownLon, latestPos.lat, latestPos.lon)
            } else {
                null
            }
            val bearing = if (ownLat != null && ownLon != null) {
                bearingDegrees(ownLat, ownLon, latestPos.lat, latestPos.lon)
            } else {
                null
            }
            val comment = latestPos.comment.ifBlank { st.comment }.trim()
            val ageMs = if (heardMs > 0L) (nowMs - heardMs).coerceAtLeast(0L) else 0L

            deduped[key] = StationHistoryEntry(
                callsign = call,
                lat = latestPos.lat,
                lon = latestPos.lon,
                speedKt = latestPos.speedKt,
                course = latestPos.course,
                via = latestPos.via.ifBlank { st.via },
                gated = gated,
                direction = dir,
                comment = comment,
                statusText = st.statusText.trim(),
                isEmergency = st.statusCode == 0,
                symbolTable = st.symbolTable.ifBlank { "/" },
                symbolCode = st.symbolCode.ifBlank { ">" },
                tempF = st.tempF,
                lastHeardEpochMs = heardMs,
                ageLabel = if (heardMs > 0L) formatAge(ageMs) else "?",
                distanceKm = distKm,
                bearingDeg = bearing,
            )
        }

        return deduped.values
            .sortedByDescending { it.lastHeardEpochMs }
            .take(maxItems.coerceAtLeast(1))
    }
}
