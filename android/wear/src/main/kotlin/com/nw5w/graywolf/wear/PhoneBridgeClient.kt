package com.nw5w.graywolf.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Local Wear Data Layer RPC client communicating exclusively with the companion phone app.
 * No direct internet or cloud dependency is used, preserving full off-grid RF operation.
 */
class PhoneBridgeClient(private val context: Context) {

    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)

    suspend fun hasConnectedPhone(): Boolean = resolvePhoneNodeId() != null

    suspend fun fetchStations(): List<WearStation> {
        val bytes = sendRpc("/graywolf/stations_req", ByteArray(0)) ?: return emptyList()
        return WearModels.parseStationsPayload(String(bytes, Charsets.UTF_8))
    }

    suspend fun fetchConversations(): WearConversationsPayload? {
        val bytes = sendRpc("/graywolf/conversations_req", ByteArray(0)) ?: return null
        return WearModels.parseConversationsPayload(String(bytes, Charsets.UTF_8))
    }

    suspend fun fetchThreadMessages(threadKind: String, threadKey: String): List<WearMessage> {
        val req = JSONObject()
            .put("threadKind", threadKind)
            .put("threadKey", threadKey)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val bytes = sendRpc("/graywolf/thread_req", req) ?: return emptyList()
        return WearModels.parseMessagesPayload(String(bytes, Charsets.UTF_8))
    }

    suspend fun sendMessage(to: String, text: String): WearMessage? {
        val req = JSONObject()
            .put("to", to)
            .put("text", text)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val bytes = sendRpc("/graywolf/send_msg_req", req) ?: return null
        val resp = JSONObject(String(bytes, Charsets.UTF_8))
        if (!resp.optBoolean("ok", false)) return null
        val msgObj = resp.optJSONObject("message") ?: return null
        return WearModels.parseMessageObject(msgObj)
    }

    suspend fun markMessageRead(messageId: Long): Boolean {
        val req = JSONObject()
            .put("messageId", messageId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val bytes = sendRpc("/graywolf/mark_read_req", req) ?: return false
        val resp = JSONObject(String(bytes, Charsets.UTF_8))
        return resp.optBoolean("ok", false)
    }

    fun registerInboundListener(onMessage: (WearMessage) -> Unit): MessageClient.OnMessageReceivedListener {
        val listener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
            if (event.path == "/graywolf/inbound_msg") {
                try {
                    val raw = String(event.data, Charsets.UTF_8)
                    val msg = WearModels.parseMessageObject(JSONObject(raw))
                    onMessage(msg)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to decode inbound push: $t")
                }
            }
        }
        messageClient.addListener(listener)
        return listener
    }

    fun unregisterInboundListener(listener: MessageClient.OnMessageReceivedListener) {
        messageClient.removeListener(listener)
    }

    private suspend fun resolvePhoneNodeId(): String? {
        return try {
            val nodes = nodeClient.connectedNodes.await()
            nodes.firstOrNull { it.isNearby }?.id ?: nodes.firstOrNull()?.id
        } catch (t: Throwable) {
            Log.w(TAG, "connectedNodes lookup failed: $t")
            null
        }
    }

    private suspend fun sendRpc(path: String, payload: ByteArray): ByteArray? {
        val nodeId = resolvePhoneNodeId() ?: return null
        return try {
            messageClient.sendRequest(nodeId, path, payload).await()
        } catch (t: Throwable) {
            Log.w(TAG, "RPC $path failed: $t")
            null
        }
    }

    companion object {
        private const val TAG = "PhoneBridgeClient"
    }
}
