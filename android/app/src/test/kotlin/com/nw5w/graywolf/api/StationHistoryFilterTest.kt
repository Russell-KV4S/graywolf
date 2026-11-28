package com.nw5w.graywolf.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationHistoryFilterTest {

    @Test
    fun `callsignMatches handles wildcards and base callsigns`() {
        assertTrue(StationHistoryFilter.callsignMatches("N0CALL*", "N0CALL-9"))
        assertTrue(StationHistoryFilter.callsignMatches("N0CALL*", "n0call"))
        assertTrue(StationHistoryFilter.callsignMatches("N0CALL", "N0CALL-15"))
        assertTrue(StationHistoryFilter.callsignMatches("N0CALL-9", "N0CALL-9"))
        assertFalse(StationHistoryFilter.callsignMatches("N0CALL-9", "N0CALL-10"))
        assertFalse(StationHistoryFilter.callsignMatches("W1AW*", "K1ABC-1"))
    }

    @Test
    fun `buildHistory sorts newest first deduplicates and excludes`() {
        val stations = listOf(
            StationDto(
                callsign = "W1AW-1",
                isObject = false,
                symbolTable = "/",
                symbolCode = ">",
                positions = listOf(
                    StationPosition(34.70, -86.60, 10.0, 90.0, "WIDE1-1", false, "RX", "2026-11-28T03:00:00Z", "Older"),
                ),
                lastHeard = "2026-11-28T03:00:00Z",
                lastDirectHeard = "",
                direction = "RX",
                via = "WIDE1-1",
                gated = false,
                comment = "Older",
                statusCode = -1,
                statusText = "",
                tempF = null,
            ),
            StationDto(
                callsign = "K4XYZ-9",
                isObject = false,
                symbolTable = "/",
                symbolCode = "-",
                positions = listOf(
                    StationPosition(34.75, -86.55, 0.0, 0.0, "", false, "RX", "2026-11-28T03:05:00Z", "Newest direct"),
                ),
                lastHeard = "2026-11-28T03:05:00Z",
                lastDirectHeard = "2026-11-28T03:05:00Z",
                direction = "RX",
                via = "",
                gated = false,
                comment = "Newest direct",
                statusCode = 0,
                statusText = "EMERGENCY",
                tempF = 72.5,
            ),
            StationDto(
                callsign = "N0CALL-7",
                isObject = false,
                symbolTable = "/",
                symbolCode = "[",
                positions = listOf(
                    StationPosition(34.80, -86.50, 0.0, 0.0, "", false, "RX", "2026-11-28T03:09:00Z", "Excluded"),
                ),
                lastHeard = "2026-11-28T03:09:00Z",
                lastDirectHeard = "2026-11-28T03:09:00Z",
                direction = "RX",
                via = "",
                gated = false,
                comment = "Excluded",
                statusCode = -1,
                statusText = "",
                tempF = null,
            ),
        )

        val nowMs = StationHistoryFilter.parseTimestampMs("2026-11-28T03:10:00Z")
        val all = StationHistoryFilter.buildHistory(
            stations = stations,
            exclusions = listOf("N0CALL*"),
            ownLat = 34.73,
            ownLon = -86.58,
            nowMs = nowMs,
        )

        assertEquals(2, all.size)
        assertEquals("K4XYZ-9", all[0].callsign)
        assertEquals("5m", all[0].ageLabel)
        assertTrue(all[0].isEmergency)
        assertNotNull(all[0].distanceKm)
        assertNotNull(all[0].bearingDeg)
        assertEquals("W1AW-1", all[1].callsign)
        assertEquals("10m", all[1].ageLabel)
    }

    @Test
    fun `buildHistory honors rfOnly and directRxOnly filters`() {
        val gatedStation = StationDto(
            callsign = "ISGATE-1",
            isObject = false,
            symbolTable = "/",
            symbolCode = "&",
            positions = listOf(
                StationPosition(34.71, -86.61, 0.0, 0.0, "TCPIP*", true, "IS", "2026-11-28T03:08:00Z", "Gated"),
            ),
            lastHeard = "2026-11-28T03:08:00Z",
            lastDirectHeard = "",
            direction = "IS",
            via = "TCPIP*",
            gated = true,
            comment = "Gated",
            statusCode = -1,
            statusText = "",
            tempF = null,
        )
        val digiRfStation = StationDto(
            callsign = "DIGI-2",
            isObject = false,
            symbolTable = "/",
            symbolCode = ">",
            positions = listOf(
                StationPosition(34.72, -86.62, 0.0, 0.0, "WIDE1-1", false, "RX", "2026-11-28T03:07:00Z", "Via digi"),
            ),
            lastHeard = "2026-11-28T03:07:00Z",
            lastDirectHeard = "",
            direction = "RX",
            via = "WIDE1-1",
            gated = false,
            comment = "Via digi",
            statusCode = -1,
            statusText = "",
            tempF = null,
        )
        val directStation = StationDto(
            callsign = "DIRECT-3",
            isObject = false,
            symbolTable = "/",
            symbolCode = ">",
            positions = listOf(
                StationPosition(34.73, -86.63, 0.0, 0.0, "", false, "RX", "2026-11-28T03:06:00Z", "Direct"),
            ),
            lastHeard = "2026-11-28T03:06:00Z",
            lastDirectHeard = "2026-11-28T03:06:00Z",
            direction = "RX",
            via = "",
            gated = false,
            comment = "Direct",
            statusCode = -1,
            statusText = "",
            tempF = null,
        )
        val list = listOf(gatedStation, digiRfStation, directStation)

        val rf = StationHistoryFilter.buildHistory(list, rfOnly = true)
        assertEquals(listOf("DIGI-2", "DIRECT-3"), rf.map { it.callsign })

        val direct = StationHistoryFilter.buildHistory(list, directRxOnly = true)
        assertEquals(listOf("DIRECT-3"), direct.map { it.callsign })
    }

    @Test
    fun `json parsers decode Go API payloads accurately`() {
        val stationsJson = """
            [{"callsign":"K4ABC-9","is_object":false,"symbol_table":"/","symbol_code":">",
              "positions":[{"lat":34.73,"lon":-86.58,"speed_kt":15.5,"course":180.0,"via":"WIDE1-1","gated":false,"direction":"RX","timestamp":"2026-11-28T03:00:00Z","comment":"Mobile"}],
              "last_heard":"2026-11-28T03:00:00Z","last_direct_heard":"","direction":"RX","via":"WIDE1-1","gated":false,"comment":"Mobile","status_code":-1,"status_text":"","weather":{"temp_f":68.4}}]
        """.trimIndent()
        val parsed = GraywolfLocalApi.parseStationsJson(stationsJson)
        assertEquals(1, parsed.size)
        assertEquals("K4ABC-9", parsed[0].callsign)
        assertEquals(68.4, parsed[0].tempF ?: 0.0, 0.01)

        val sseJson = """
            {"id":42,"kind":"created","message":{"id":42,"direction":"in","status":"received","our_call":"NW5W-7","peer_call":"K4ABC-9","from_call":"K4ABC-9","to_call":"NW5W-7","text":"Test","created_at":"2026-11-28T03:00:00Z","unread":true,"thread_kind":"dm","thread_key":"K4ABC-9","kind":"text","is_ack":false}}
        """.trimIndent()
        val ev = GraywolfLocalApi.parseMessageChangeJson(sseJson)
        assertNotNull(ev)
        assertEquals("created", ev!!.kind)
        assertEquals("K4ABC-9", ev.message!!.fromCall)
        assertTrue(ev.message!!.unread)
    }

    @Test
    fun `distance and bearing calculations match expected values`() {
        // 1 degree latitude northward at equator ~ 111.19 km, bearing ~ 0 deg (N)
        val dist = StationHistoryFilter.haversineKm(0.0, 0.0, 1.0, 0.0)
        assertTrue(dist in 110.0..112.0)
        val bearing = StationHistoryFilter.bearingDegrees(0.0, 0.0, 1.0, 0.0)
        assertEquals("N", StationHistoryFilter.cardinalDirection(bearing))
    }
}
