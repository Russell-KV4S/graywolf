package com.nw5w.graywolf.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.nw5w.graywolf.GraywolfApp
import com.nw5w.graywolf.MainActivity
import com.nw5w.graywolf.api.GraywolfLocalApi
import com.nw5w.graywolf.api.MessageDto
import com.nw5w.graywolf.api.StationHistoryFilter
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Wear OS Data Layer bridge running on the phone. Serves watch RPC requests cleanly off the
 * local Go backend (stations, exclusions, favorites, conversations, threads, send, mark-read)
 * so all watch features function 100% off-grid over RF with zero cloud dependency.
 */
class WearBridgeService : WearableListenerService() {

    private val rpcExecutor = Executors.newCachedThreadPool()

    override fun onDestroy() {
        rpcExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequest(peerNodeId: String, path: String, request: ByteArray): Task<ByteArray> {
        val source = TaskCompletionSource<ByteArray>()
        rpcExecutor.execute {
            try {
                val app = applicationContext as GraywolfApp
                val api = GraywolfLocalApi(tokenProvider = { app.bearerToken })
                source.setResult(handleRequest(api, path, request))
            } catch (e: Exception) {
                source.setException(e)
            }
        }
        return source.task
    }

    private fun handleRequest(api: GraywolfLocalApi, path: String, request: ByteArray): ByteArray {
        return when (path) {
            WearProtocol.PATH_STATIONS_REQ -> {
                val (rfOnly, directRxOnly) = MainActivity.loadMapFilterPrefs(applicationContext)
                val rawStations = api.fetchStations(timerange = "24h")
                val exclusions = api.fetchExclusions()
                val ownPos = api.fetchPosition()
                val history = StationHistoryFilter.buildHistory(
                    stations = rawStations,
                    exclusions = exclusions,
                    rfOnly = rfOnly,
                    directRxOnly = directRxOnly,
                    ownLat = ownPos?.takeIf { it.valid }?.lat,
                    ownLon = ownPos?.takeIf { it.valid }?.lon,
                    maxItems = 40,
                )
                WearProtocol.encodeStationsResponse(history)
            }

            WearProtocol.PATH_CONVERSATIONS_REQ -> {
                val conversations = api.fetchConversations(limit = 50)
                val favorites = api.fetchFavorites()
                val recentCalls = StationHistoryFilter.buildHistory(
                    stations = api.fetchStations(timerange = "24h"),
                    exclusions = api.fetchExclusions(),
                    maxItems = 15,
                ).map { it.callsign }
                WearProtocol.encodeConversationsResponse(conversations, favorites, recentCalls)
            }

            WearProtocol.PATH_THREAD_REQ -> {
                val req = JSONObject(String(request, Charsets.UTF_8).ifBlank { "{}" })
                val kind = req.optString("threadKind", "dm")
                val key = req.optString("threadKey", "")
                val msgs = if (key.isBlank()) emptyList() else api.fetchThreadMessages(kind, key, limit = 30)
                WearProtocol.encodeMessagesResponse(msgs)
            }

            WearProtocol.PATH_SEND_MSG_REQ -> {
                val req = JSONObject(String(request, Charsets.UTF_8).ifBlank { "{}" })
                val to = req.optString("to", "").trim()
                val text = req.optString("text", "").trim()
                if (to.isEmpty() || text.isEmpty()) {
                    WearProtocol.encodeOkResponse(ok = false, error = "Recipient and text required")
                } else {
                    val sent = api.sendMessage(to, text)
                    if (sent != null) {
                        WearProtocol.encodeOkResponse(ok = true, message = sent)
                    } else {
                        WearProtocol.encodeOkResponse(ok = false, error = "Send failed")
                    }
                }
            }

            WearProtocol.PATH_MARK_READ_REQ -> {
                val req = JSONObject(String(request, Charsets.UTF_8).ifBlank { "{}" })
                val id = req.optLong("messageId", 0L)
                val ok = if (id > 0L) api.markMessageRead(id) else false
                WearProtocol.encodeOkResponse(ok = ok)
            }

            else -> {
                Log.w(TAG, "Unknown Wear RPC path: $path")
                "{}".toByteArray(Charsets.UTF_8)
            }
        }
    }

    companion object {
        private const val TAG = "WearBridgeService"

        fun pushInboundMessageToWatches(context: Context, msg: MessageDto) {
            thread(name = "graywolf-wear-push") {
                try {
                    val payload = WearProtocol.encodeMessageObject(msg).toString().toByteArray(Charsets.UTF_8)
                    val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                    val msgClient = Wearable.getMessageClient(context)
                    for (node in nodes) {
                        msgClient.sendMessage(node.id, WearProtocol.PATH_INBOUND_MSG_PUSH, payload)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "pushInboundMessageToWatches failed: $t")
                }
            }
        }
    }
}
