package com.nw5w.graywolf.wear

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class WearStation(
    val callsign: String,
    val lat: Double,
    val lon: Double,
    val ageLabel: String,
    val comment: String,
    val statusText: String,
    val isEmergency: Boolean,
    val via: String,
    val gated: Boolean,
    val speedKt: Double,
    val course: Double,
    val lastHeardEpochMs: Long,
    val distanceKm: Double?,
    val bearingDeg: Double?,
    val tempF: Double?,
)

data class WearConversation(
    val threadKind: String,
    val threadKey: String,
    val alias: String,
    val lastAt: String,
    val lastSnippet: String,
    val lastSenderCall: String,
    val unreadCount: Int,
)

data class WearConversationsPayload(
    val conversations: List<WearConversation>,
    val favorites: List<String>,
    val recentCalls: List<String>,
)

data class WearMessage(
    val id: Long,
    val direction: String,
    val status: String,
    val fromCall: String,
    val toCall: String,
    val text: String,
    val createdAt: String,
    val unread: Boolean,
    val threadKind: String,
    val threadKey: String,
    val source: String,
)

object WearModels {
    fun cardinalDirection(bearingDeg: Double): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((bearingDeg + 22.5) % 360.0) / 45.0).toInt()
        return dirs[idx.coerceIn(0, 7)]
    }

    fun formatDistanceAndBearing(distanceKm: Double?, bearingDeg: Double?): String {
        val km = distanceKm ?: return ""
        val mi = km * 0.621371
        val dir = bearingDeg?.let { cardinalDirection(it) } ?: ""
        return String.format(Locale.US, "%.1f mi %s", mi, dir).trim()
    }

    fun normalizeCallsign(raw: String): String =
        raw.trim().uppercase(Locale.US).replace(Regex("[^A-Z0-9_-]"), "")

    fun parseStationsPayload(json: String): List<WearStation> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = JSONObject(trimmed)
        val arr = root.optJSONArray("stations") ?: return emptyList()
        val out = ArrayList<WearStation>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val dist = if (o.has("distanceKm") && !o.isNull("distanceKm")) o.optDouble("distanceKm") else null
            val brg = if (o.has("bearingDeg") && !o.isNull("bearingDeg")) o.optDouble("bearingDeg") else null
            val temp = if (o.has("tempF") && !o.isNull("tempF")) o.optDouble("tempF") else null
            out.add(
                WearStation(
                    callsign = o.optString("callsign", ""),
                    lat = o.optDouble("lat", 0.0),
                    lon = o.optDouble("lon", 0.0),
                    ageLabel = o.optString("ageLabel", "?"),
                    comment = o.optString("comment", ""),
                    statusText = o.optString("statusText", ""),
                    isEmergency = o.optBoolean("isEmergency", false),
                    via = o.optString("via", ""),
                    gated = o.optBoolean("gated", false),
                    speedKt = o.optDouble("speedKt", 0.0),
                    course = o.optDouble("course", 0.0),
                    lastHeardEpochMs = o.optLong("lastHeardEpochMs", 0L),
                    distanceKm = dist,
                    bearingDeg = brg,
                    tempF = temp,
                ),
            )
        }
        return out
    }

    fun parseConversationsPayload(json: String): WearConversationsPayload {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) {
            return WearConversationsPayload(emptyList(), emptyList(), emptyList())
        }
        val root = JSONObject(trimmed)
        val convArr = root.optJSONArray("conversations") ?: JSONArray()
        val convs = ArrayList<WearConversation>(convArr.length())
        for (i in 0 until convArr.length()) {
            val o = convArr.optJSONObject(i) ?: continue
            convs.add(
                WearConversation(
                    threadKind = o.optString("threadKind", "dm"),
                    threadKey = o.optString("threadKey", ""),
                    alias = o.optString("alias", ""),
                    lastAt = o.optString("lastAt", ""),
                    lastSnippet = o.optString("lastSnippet", ""),
                    lastSenderCall = o.optString("lastSenderCall", ""),
                    unreadCount = o.optInt("unreadCount", 0),
                ),
            )
        }
        fun stringArray(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i, "").trim()
                if (s.isNotEmpty()) list.add(s)
            }
            return list
        }
        return WearConversationsPayload(
            conversations = convs,
            favorites = stringArray(root.optJSONArray("favorites")),
            recentCalls = stringArray(root.optJSONArray("recentCalls")),
        )
    }

    fun parseMessagesPayload(json: String): List<WearMessage> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("{")) return emptyList()
        val root = JSONObject(trimmed)
        val arr = root.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<WearMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseMessageObject(o))
        }
        return out
    }

    fun parseMessageObject(o: JSONObject): WearMessage = WearMessage(
        id = o.optLong("id", 0L),
        direction = o.optString("direction", "in"),
        status = o.optString("status", ""),
        fromCall = o.optString("fromCall", o.optString("from_call", "")),
        toCall = o.optString("toCall", o.optString("to_call", "")),
        text = o.optString("text", ""),
        createdAt = o.optString("createdAt", o.optString("created_at", "")),
        unread = o.optBoolean("unread", false),
        threadKind = o.optString("threadKind", o.optString("thread_kind", "dm")),
        threadKey = o.optString("threadKey", o.optString("thread_key", "")),
        source = o.optString("source", "rf"),
    )
}
