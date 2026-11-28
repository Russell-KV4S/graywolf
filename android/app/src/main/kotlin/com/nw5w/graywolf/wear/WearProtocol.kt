package com.nw5w.graywolf.wear

import com.nw5w.graywolf.api.ConversationDto
import com.nw5w.graywolf.api.MessageDto
import com.nw5w.graywolf.api.StationHistoryEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * Message paths and compact JSON wire codecs shared between Phone (`:app`) and Watch (`:wear`)
 * over Google Play Services Wearable MessageClient RPCs (`sendRequest` / `onRequest`).
 */
object WearProtocol {
    const val PATH_STATIONS_REQ = "/graywolf/stations_req"
    const val PATH_CONVERSATIONS_REQ = "/graywolf/conversations_req"
    const val PATH_THREAD_REQ = "/graywolf/thread_req"
    const val PATH_SEND_MSG_REQ = "/graywolf/send_msg_req"
    const val PATH_MARK_READ_REQ = "/graywolf/mark_read_req"
    const val PATH_INBOUND_MSG_PUSH = "/graywolf/inbound_msg"

    fun encodeStationsResponse(stations: List<StationHistoryEntry>): ByteArray {
        val arr = JSONArray()
        for (st in stations) {
            val obj = JSONObject()
                .put("callsign", st.callsign)
                .put("lat", st.lat)
                .put("lon", st.lon)
                .put("ageLabel", st.ageLabel)
                .put("comment", st.comment)
                .put("statusText", st.statusText)
                .put("isEmergency", st.isEmergency)
                .put("via", st.via)
                .put("gated", st.gated)
                .put("speedKt", st.speedKt)
                .put("course", st.course)
                .put("lastHeardEpochMs", st.lastHeardEpochMs)
            st.distanceKm?.let { obj.put("distanceKm", it) }
            st.bearingDeg?.let { obj.put("bearingDeg", it) }
            st.tempF?.let { obj.put("tempF", it) }
            arr.put(obj)
        }
        return JSONObject().put("stations", arr).toString().toByteArray(Charsets.UTF_8)
    }

    fun encodeConversationsResponse(
        conversations: List<ConversationDto>,
        favorites: List<String>,
        recentCalls: List<String>,
    ): ByteArray {
        val convArr = JSONArray()
        for (c in conversations) {
            convArr.put(
                JSONObject()
                    .put("threadKind", c.threadKind)
                    .put("threadKey", c.threadKey)
                    .put("alias", c.alias)
                    .put("lastAt", c.lastAt)
                    .put("lastSnippet", c.lastSnippet)
                    .put("lastSenderCall", c.lastSenderCall)
                    .put("unreadCount", c.unreadCount),
            )
        }
        return JSONObject()
            .put("conversations", convArr)
            .put("favorites", JSONArray(favorites))
            .put("recentCalls", JSONArray(recentCalls))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun encodeMessagesResponse(messages: List<MessageDto>): ByteArray {
        val arr = JSONArray()
        for (m in messages) {
            arr.put(encodeMessageObject(m))
        }
        return JSONObject().put("messages", arr).toString().toByteArray(Charsets.UTF_8)
    }

    fun encodeMessageObject(m: MessageDto): JSONObject = JSONObject()
        .put("id", m.id)
        .put("direction", m.direction)
        .put("status", m.status)
        .put("fromCall", m.fromCall)
        .put("toCall", m.toCall)
        .put("text", m.text)
        .put("createdAt", m.createdAt)
        .put("unread", m.unread)
        .put("threadKind", m.threadKind)
        .put("threadKey", m.threadKey)
        .put("source", m.source)

    fun encodeOkResponse(ok: Boolean, message: MessageDto? = null, error: String? = null): ByteArray {
        val obj = JSONObject().put("ok", ok)
        message?.let { obj.put("message", encodeMessageObject(it)) }
        error?.let { obj.put("error", it) }
        return obj.toString().toByteArray(Charsets.UTF_8)
    }
}
