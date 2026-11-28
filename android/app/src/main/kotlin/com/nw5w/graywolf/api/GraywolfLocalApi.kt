package com.nw5w.graywolf.api

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class StationPosition(
    val lat: Double,
    val lon: Double,
    val speedKt: Double,
    val course: Double,
    val via: String,
    val gated: Boolean,
    val direction: String,
    val timestamp: String,
    val comment: String,
)

data class StationDto(
    val callsign: String,
    val isObject: Boolean,
    val symbolTable: String,
    val symbolCode: String,
    val positions: List<StationPosition>,
    val lastHeard: String,
    val lastDirectHeard: String,
    val direction: String,
    val via: String,
    val gated: Boolean,
    val comment: String,
    val statusCode: Int,
    val statusText: String,
    val tempF: Double?,
)

data class OwnPositionDto(
    val valid: Boolean,
    val source: String,
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val hasAlt: Boolean,
    val speedKt: Double,
    val headingDeg: Double,
    val hasCourse: Boolean,
    val timestamp: String,
)

data class BeaconDto(
    val id: Long,
    val type: String,
    val channel: String,
    val callsign: String,
    val enabled: Boolean,
)

data class MessageDto(
    val id: Long,
    val direction: String,
    val status: String,
    val ourCall: String,
    val peerCall: String,
    val fromCall: String,
    val toCall: String,
    val text: String,
    val msgId: String,
    val createdAt: String,
    val receivedAt: String,
    val source: String,
    val via: String,
    val unread: Boolean,
    val threadKind: String,
    val threadKey: String,
    val kind: String,
    val isAck: Boolean = false,
    val isBulletin: Boolean = false,
)

data class ConversationDto(
    val threadKind: String,
    val threadKey: String,
    val alias: String,
    val lastAt: String,
    val lastSnippet: String,
    val lastSenderCall: String,
    val unreadCount: Int,
    val totalCount: Int,
    val participantCount: Int,
    val archived: Boolean,
)

data class MessageChangeEvent(
    val id: Long,
    val kind: String,
    val message: MessageDto?,
)

/**
 * Synchronous HTTP + SSE client for Graywolf's loopback Go REST API (`http://127.0.0.1:8080`).
 * Every method is safe to call from background threads/coroutines.
 */
class GraywolfLocalApi(
    private val tokenProvider: () -> String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    fun fetchStations(timerange: String = "24h"): List<StationDto> {
        val enc = urlEncode(timerange)
        val body = get("/api/stations?timerange=$enc&include=weather") ?: return emptyList()
        return parseStationsJson(body)
    }

    fun fetchExclusions(): List<String> {
        val body = get("/api/stations/exclusions") ?: return emptyList()
        return parseCallsignListJson(body)
    }

    fun fetchFavorites(): List<String> {
        val body = get("/api/stations/favorites") ?: return emptyList()
        return parseCallsignListJson(body)
    }

    fun fetchPosition(): OwnPositionDto? {
        val body = get("/api/position") ?: return null
        return parsePositionJson(body)
    }

    fun fetchBeacons(): List<BeaconDto> {
        val body = get("/api/beacons") ?: return emptyList()
        return parseBeaconsJson(body)
    }

    fun triggerBeaconSend(beaconId: Long): Boolean {
        val code = post("/api/beacons/$beaconId/send", null)?.first ?: return false
        return code in 200..299
    }

    fun triggerFirstEnabledBeacon(): BeaconDto? {
        val beacons = fetchBeacons()
        val chosen = beacons.firstOrNull { it.enabled } ?: beacons.firstOrNull() ?: return null
        return if (triggerBeaconSend(chosen.id)) chosen else null
    }

    fun fetchConversations(limit: Int = 50): List<ConversationDto> {
        val body = get("/api/messages/conversations?limit=$limit") ?: return emptyList()
        return parseConversationsJson(body)
    }

    fun fetchThreadMessages(threadKind: String, threadKey: String, limit: Int = 30): List<MessageDto> {
        val path = "/api/messages?thread_kind=${urlEncode(threadKind)}&thread_key=${urlEncode(threadKey)}&limit=$limit"
        val body = get(path) ?: return emptyList()
        return parseMessagesPageJson(body)
    }

    fun sendMessage(to: String, text: String): MessageDto? {
        val payload = JSONObject()
            .put("to", to)
            .put("text", text)
            .toString()
        val (code, resp) = post("/api/messages", payload) ?: return null
        if (code !in 200..299 || resp.isBlank()) return null
        return parseMessageJson(JSONObject(resp))
    }

    fun markMessageRead(messageId: Long): Boolean {
        val code = post("/api/messages/$messageId/read", null)?.first ?: return false
        return code in 200..299
    }

    /**
     * Streams Server-Sent Events from `/api/messages/events` until [isActive] returns false
     * or the stream closes/errors. Caller is responsible for reconnect backoff.
     */
    fun streamMessageEvents(isActive: () -> Boolean, onEvent: (MessageChangeEvent) -> Unit) {
        val conn = openConnection("/api/messages/events", "GET", readTimeoutMs = 30_000)
        conn.setRequestProperty("Accept", "text/event-stream")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("SSE /api/messages/events HTTP $code")
            }
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                val dataBuf = StringBuilder()
                while (isActive()) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) {
                        if (dataBuf.isNotEmpty()) {
                            val raw = dataBuf.toString().trim()
                            dataBuf.setLength(0)
                            parseMessageChangeJson(raw)?.let(onEvent)
                        }
                        continue
                    }
                    if (line.startsWith(":")) continue
                    if (line.startsWith("data:")) {
                        if (dataBuf.isNotEmpty()) dataBuf.append('\n')
                        dataBuf.append(line.substring(5).trimStart())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): String? {
        val conn = openConnection(path, "GET")
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $path -> HTTP $code")
                null
            } else {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GET $path failed: $t")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun post(path: String, jsonBody: String?): Pair<Int, String>? {
        val conn = openConnection(path, "POST")
        conn.doOutput = true
        return try {
            if (jsonBody != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(jsonBody.toByteArray(StandardCharsets.UTF_8)) }
            } else {
                conn.setRequestProperty("Content-Length", "0")
                conn.outputStream.close()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "POST $path -> HTTP $code ($text)")
            }
            code to text
        } catch (t: Throwable) {
            Log.w(TAG, "POST $path failed: $t")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(path: String, method: String, readTimeoutMs: Int = 5_000): HttpURLConnection {
        val url = URL("$baseUrl$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3_000
        conn.readTimeout = readTimeoutMs
        val token = tokenProvider()
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return conn
    }

    companion object {
        private const val TAG = "GraywolfLocalApi"
        const val DEFAULT_BASE_URL = "http://127.0.0.1:8080"

        private fun urlEncode(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name())

        internal fun parseStationsJson(json: String): List<StationDto> {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val arr = JSONArray(trimmed)
            val out = ArrayList<StationDto>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val posArr = obj.optJSONArray("positions")
                val positions = ArrayList<StationPosition>(posArr?.length() ?: 0)
                if (posArr != null) {
                    for (p in 0 until posArr.length()) {
                        val po = posArr.optJSONObject(p) ?: continue
                        val lat = po.optDouble("lat", Double.NaN)
                        val lon = po.optDouble("lon", Double.NaN)
                        if (lat.isNaN() || lon.isNaN()) continue
                        positions.add(
                            StationPosition(
                                lat = lat,
                                lon = lon,
                                speedKt = po.optDouble("speed_kt", 0.0).let { if (it.isNaN()) 0.0 else it },
                                course = po.optDouble("course", 0.0).let { if (it.isNaN()) 0.0 else it },
                                via = po.optString("via", ""),
                                gated = po.optBoolean("gated", false),
                                direction = po.optString("direction", ""),
                                timestamp = po.optString("timestamp", ""),
                                comment = po.optString("comment", ""),
                            ),
                        )
                    }
                }
                val weatherObj = obj.optJSONObject("weather")
                val tempF = if (weatherObj != null && weatherObj.has("temp_f") && !weatherObj.isNull("temp_f")) {
                    weatherObj.optDouble("temp_f", Double.NaN).takeUnless { it.isNaN() }
                } else {
                    null
                }
                out.add(
                    StationDto(
                        callsign = obj.optString("callsign", ""),
                        isObject = obj.optBoolean("is_object", false),
                        symbolTable = obj.optString("symbol_table", "/"),
                        symbolCode = obj.optString("symbol_code", ">"),
                        positions = positions,
                        lastHeard = obj.optString("last_heard", ""),
                        lastDirectHeard = obj.optString("last_direct_heard", ""),
                        direction = obj.optString("direction", ""),
                        via = obj.optString("via", ""),
                        gated = obj.optBoolean("gated", false),
                        comment = obj.optString("comment", ""),
                        statusCode = obj.optInt("status_code", -1),
                        statusText = obj.optString("status_text", ""),
                        tempF = tempF,
                    ),
                )
            }
            return out
        }

        internal fun parseCallsignListJson(json: String): List<String> {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val arr = JSONArray(trimmed)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val cs = obj.optString("callsign", "").trim()
                if (cs.isNotEmpty()) out.add(cs)
            }
            return out
        }

        internal fun parsePositionJson(json: String): OwnPositionDto? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            val obj = JSONObject(trimmed)
            return OwnPositionDto(
                valid = obj.optBoolean("valid", false),
                source = obj.optString("source", ""),
                lat = obj.optDouble("lat", 0.0),
                lon = obj.optDouble("lon", 0.0),
                altM = obj.optDouble("alt_m", 0.0),
                hasAlt = obj.optBoolean("has_alt", false),
                speedKt = obj.optDouble("speed_kt", 0.0),
                headingDeg = obj.optDouble("heading_deg", 0.0),
                hasCourse = obj.optBoolean("has_course", false),
                timestamp = obj.optString("timestamp", ""),
            )
        }

        internal fun parseBeaconsJson(json: String): List<BeaconDto> {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val arr = JSONArray(trimmed)
            val out = ArrayList<BeaconDto>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(
                    BeaconDto(
                        id = obj.optLong("id", 0L),
                        type = obj.optString("type", ""),
                        channel = obj.optString("channel", ""),
                        callsign = obj.optString("callsign", ""),
                        enabled = obj.optBoolean("enabled", true),
                    ),
                )
            }
            return out
        }

        internal fun parseConversationsJson(json: String): List<ConversationDto> {
            val trimmed = json.trim()
            if (!trimmed.startsWith("[")) return emptyList()
            val arr = JSONArray(trimmed)
            val out = ArrayList<ConversationDto>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                out.add(
                    ConversationDto(
                        threadKind = obj.optString("thread_kind", "dm"),
                        threadKey = obj.optString("thread_key", ""),
                        alias = obj.optString("alias", ""),
                        lastAt = obj.optString("last_at", ""),
                        lastSnippet = obj.optString("last_snippet", ""),
                        lastSenderCall = obj.optString("last_sender_call", ""),
                        unreadCount = obj.optInt("unread_count", 0),
                        totalCount = obj.optInt("total_count", 0),
                        participantCount = obj.optInt("participant_count", 0),
                        archived = obj.optBoolean("archived", false),
                    ),
                )
            }
            return out
        }

        internal fun parseMessagesPageJson(json: String): List<MessageDto> {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return emptyList()
            val obj = JSONObject(trimmed)
            val arr = obj.optJSONArray("changes") ?: return emptyList()
            val out = ArrayList<MessageDto>(arr.length())
            for (i in 0 until arr.length()) {
                val ch = arr.optJSONObject(i) ?: continue
                val msgObj = ch.optJSONObject("message") ?: continue
                out.add(parseMessageJson(msgObj))
            }
            return out
        }

        internal fun parseMessageChangeJson(json: String): MessageChangeEvent? {
            val trimmed = json.trim()
            if (!trimmed.startsWith("{")) return null
            val obj = JSONObject(trimmed)
            val msgObj = obj.optJSONObject("message")
            return MessageChangeEvent(
                id = obj.optLong("id", 0L),
                kind = obj.optString("kind", ""),
                message = msgObj?.let { parseMessageJson(it) },
            )
        }

        internal fun parseMessageJson(obj: JSONObject): MessageDto = MessageDto(
            id = obj.optLong("id", 0L),
            direction = obj.optString("direction", ""),
            status = obj.optString("status", ""),
            ourCall = obj.optString("our_call", ""),
            peerCall = obj.optString("peer_call", ""),
            fromCall = obj.optString("from_call", ""),
            toCall = obj.optString("to_call", ""),
            text = obj.optString("text", ""),
            msgId = obj.optString("msg_id", ""),
            createdAt = obj.optString("created_at", ""),
            receivedAt = obj.optString("received_at", ""),
            source = obj.optString("source", ""),
            via = obj.optString("via", ""),
            unread = obj.optBoolean("unread", false),
            threadKind = obj.optString("thread_kind", "dm"),
            threadKey = obj.optString("thread_key", ""),
            kind = obj.optString("kind", "text"),
            isAck = obj.optBoolean("is_ack", false),
            isBulletin = obj.optBoolean("is_bulletin", false),
        )
    }
}
