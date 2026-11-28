package com.nw5w.graywolf.wear

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WearModelsTest {

    @Test
    fun `normalizeCallsign uppercases and strips invalid characters`() {
        assertEquals("NW5W-7", WearModels.normalizeCallsign(" nw5w-7 "))
        assertEquals("SAR_1", WearModels.normalizeCallsign("sar_1!"))
    }

    @Test
    fun `formatDistanceAndBearing formats miles and cardinal direction`() {
        assertEquals("1.0 mi N", WearModels.formatDistanceAndBearing(1.60934, 2.0))
        assertEquals("5.0 mi SE", WearModels.formatDistanceAndBearing(8.04672, 135.0))
        assertEquals("", WearModels.formatDistanceAndBearing(null, 90.0))
    }

    @Test
    fun `parseStationsPayload and parseConversationsPayload decode bridge JSON`() {
        val stationsJson = """
            {"stations":[{"callsign":"K4XYZ-9","lat":34.73,"lon":-86.58,"ageLabel":"2m","comment":"Hi","statusText":"","isEmergency":true,"via":"WIDE1-1","gated":false,"speedKt":12.0,"course":90.0,"lastHeardEpochMs":1700000,"distanceKm":3.2,"bearingDeg":45.0,"tempF":71.0}]}
        """.trimIndent()
        val stations = WearModels.parseStationsPayload(stationsJson)
        assertEquals(1, stations.size)
        assertEquals("K4XYZ-9", stations[0].callsign)
        assertTrue(stations[0].isEmergency)
        assertEquals(3.2, stations[0].distanceKm ?: 0.0, 0.01)

        val convsJson = """
            {"conversations":[{"threadKind":"tactical","threadKey":"SAR1","alias":"SAR Net","lastAt":"2026-11-28T03:00:00Z","lastSnippet":"En route","lastSenderCall":"W1AW-1","unreadCount":2}],"favorites":["K4ABC-9"],"recentCalls":["K4XYZ-9"]}
        """.trimIndent()
        val payload = WearModels.parseConversationsPayload(convsJson)
        assertEquals(1, payload.conversations.size)
        assertEquals("SAR1", payload.conversations[0].threadKey)
        assertEquals(2, payload.conversations[0].unreadCount)
        assertEquals(listOf("K4ABC-9"), payload.favorites)
        assertEquals(listOf("K4XYZ-9"), payload.recentCalls)

        val inbound = WearModels.parseMessageObject(
            JSONObject("""{"id":9,"direction":"in","status":"received","fromCall":"W1AW","toCall":"NW5W","text":"Copy","createdAt":"now","unread":true,"threadKind":"dm","threadKey":"W1AW","source":"rf"}"""),
        )
        assertEquals("W1AW", inbound.fromCall)
        assertTrue(inbound.unread)
        assertTrue(QuickRepliesStore.PRESETS.contains("73"))
    }
}
