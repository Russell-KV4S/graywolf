package com.nw5w.graywolf.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.nw5w.graywolf.MainActivity
import com.nw5w.graywolf.R
import com.nw5w.graywolf.api.GraywolfLocalApi
import com.nw5w.graywolf.api.MessageDto
import com.nw5w.graywolf.api.StationHistoryFilter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams `/api/messages/events` from the local Go backend and surfaces native
 * `MessagingStyle` notifications compatible with Phone, Wear OS, and Android Auto
 * (inline reply, preset quick replies, voice reply, and mark-as-read).
 */
class MessageNotificationManager(
    private val context: Context,
    private val api: GraywolfLocalApi,
    private val ownCallProvider: () -> String = { "Graywolf" },
    var onInboundMessage: ((MessageDto) -> Unit)? = null,
) {
    private data class ThreadKey(val kind: String, val key: String)

    private data class ThreadEntry(
        val sender: String,
        val text: String,
        val timestampMs: Long,
    )

    private val running = AtomicBoolean(false)
    private val notifiedIds = Collections.synchronizedSet(LinkedHashSet<Long>())
    private val threadHistory = ConcurrentHashMap<ThreadKey, MutableList<ThreadEntry>>()
    private var sseThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        activeInstance = this
        ensureChannel()
        sseThread = Thread({
            var backoffMs = 1_000L
            while (running.get()) {
                try {
                    api.streamMessageEvents(isActive = { running.get() }) { event ->
                        backoffMs = 1_000L
                        val msg = event.message ?: return@streamMessageEvents
                        if (shouldNotify(msg) && rememberMessageId(msg.id)) {
                            onInboundMessage?.invoke(msg)
                            postMessageNotification(msg)
                        }
                    }
                } catch (t: Throwable) {
                    if (running.get()) {
                        Log.w(TAG, "SSE stream error: $t")
                    }
                }
                if (!running.get()) break
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    break
                }
                backoffMs = (backoffMs * 2L).coerceAtMost(15_000L)
            }
        }, "graywolf-msg-sse").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        sseThread?.interrupt()
        sseThread = null
        if (activeInstance === this) {
            activeInstance = null
        }
    }

    fun onReplied(threadKind: String, threadKey: String, notifId: Int, replyText: String) {
        val tKey = ThreadKey(threadKind, threadKey)
        val own = ownCallProvider().ifBlank { "Me" }
        val entries = threadHistory.computeIfAbsent(tKey) { Collections.synchronizedList(mutableListOf()) }
        synchronized(entries) {
            entries.add(ThreadEntry(sender = own, text = replyText, timestampMs = System.currentTimeMillis()))
            while (entries.size > MAX_THREAD_MESSAGES) {
                entries.removeAt(0)
            }
        }
        val syntheticReply = MessageDto(
            id = 0L,
            direction = "out",
            status = "queued",
            ourCall = own,
            peerCall = threadKey,
            fromCall = own,
            toCall = threadKey,
            text = replyText,
            msgId = "",
            createdAt = "",
            receivedAt = "",
            source = "rf",
            via = "",
            unread = false,
            threadKind = threadKind,
            threadKey = threadKey,
            kind = "text",
        )
        postNotificationInternal(
            tKey = tKey,
            anchorMsg = syntheticReply,
            notifId = notifId,
            replyTarget = threadKey,
            alertOnce = true,
        )
    }

    fun cancelNotification(notifId: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.cancel(notifId)
    }

    private fun rememberMessageId(id: Long): Boolean {
        if (id <= 0L) return true
        synchronized(notifiedIds) {
            if (!notifiedIds.add(id)) return false
            while (notifiedIds.size > MAX_NOTIFIED_IDS) {
                val first = notifiedIds.firstOrNull() ?: break
                notifiedIds.remove(first)
            }
            return true
        }
    }

    private fun postMessageNotification(msg: MessageDto) {
        val tKind = msg.threadKind.ifBlank { "dm" }
        val tKeyStr = msg.threadKey.ifBlank { msg.fromCall }
        val tKey = ThreadKey(tKind, tKeyStr)
        val tsMs = StationHistoryFilter.parseTimestampMs(msg.receivedAt.ifBlank { msg.createdAt })
            .let { if (it > 0L) it else System.currentTimeMillis() }
        val sender = msg.fromCall.ifBlank { tKeyStr }

        val entries = threadHistory.computeIfAbsent(tKey) { Collections.synchronizedList(mutableListOf()) }
        synchronized(entries) {
            entries.add(ThreadEntry(sender = sender, text = msg.text, timestampMs = tsMs))
            while (entries.size > MAX_THREAD_MESSAGES) {
                entries.removeAt(0)
            }
        }

        val notifId = notificationIdForThread(tKind, tKeyStr)
        val replyTarget = resolveReplyDestination(msg)
        postNotificationInternal(
            tKey = tKey,
            anchorMsg = msg,
            notifId = notifId,
            replyTarget = replyTarget,
            alertOnce = false,
        )
    }

    private fun postNotificationInternal(
        tKey: ThreadKey,
        anchorMsg: MessageDto,
        notifId: Int,
        replyTarget: String,
        alertOnce: Boolean,
    ) {
        ensureChannel()
        val own = ownCallProvider().ifBlank { "Graywolf" }
        val mePerson = Person.Builder().setName(own).setKey(own).build()
        val style = NotificationCompat.MessagingStyle(mePerson)
            .setGroupConversation(tKey.kind == "tactical")
        if (tKey.kind == "tactical") {
            style.setConversationTitle(tKey.key)
        }

        val entriesSnapshot = synchronized(
            threadHistory[tKey] ?: emptyList<ThreadEntry>(),
        ) {
            (threadHistory[tKey] ?: emptyList()).toList()
        }
        for (entry in entriesSnapshot) {
            val person = if (entry.sender.equals(own, ignoreCase = true)) {
                mePerson
            } else {
                Person.Builder().setName(entry.sender).setKey(entry.sender).build()
            }
            style.addMessage(entry.text, entry.timestampMs, person)
        }

        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("Reply to $replyTarget")
            .setChoices(QUICK_REPLIES)
            .build()

        val replyIntent = Intent(context, MessageReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_MESSAGE_ID, anchorMsg.id)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
            putExtra(EXTRA_REPLY_TARGET, replyTarget)
            putExtra(EXTRA_THREAD_KIND, tKey.kind)
            putExtra(EXTRA_THREAD_KEY, tKey.key)
        }
        val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPending = PendingIntent.getBroadcast(context, notifId * 2, replyIntent, replyFlags)

        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Reply",
            replyPending,
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .extend(
                NotificationCompat.Action.WearableExtender()
                    .setHintLaunchesActivity(false)
                    .setHintDisplayActionInline(true),
            )
            .build()

        val readIntent = Intent(context, MessageReplyReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_MESSAGE_ID, anchorMsg.id)
            putExtra(EXTRA_NOTIFICATION_ID, notifId)
        }
        val immutableFlag = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val readPending = PendingIntent.getBroadcast(context, notifId * 2 + 1, readIntent, immutableFlag)
        val readAction = NotificationCompat.Action.Builder(
            0,
            "Mark as read",
            readPending,
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HASH_ROUTE, buildThreadRoute(tKey.kind, tKey.key))
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPending = PendingIntent.getActivity(context, notifId * 2 + 2, tapIntent, immutableFlag)

        val builder = NotificationCompat.Builder(context, CHANNEL_INBOUND_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(alertOnce)
            .setContentIntent(tapPending)
            .addAction(replyAction)
            .addAction(readAction)
            .extend(
                NotificationCompat.WearableExtender()
                    .setContentIntentAvailableOffline(true),
            )

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(notifId, builder.build())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_INBOUND_MESSAGES) != null) return
        val channel = NotificationChannel(
            CHANNEL_INBOUND_MESSAGES,
            "APRS Messages",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming APRS direct and tactical messages with Car & Watch quick reply"
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "GraywolfMsgNotify"
        private const val MAX_THREAD_MESSAGES = 8
        private const val MAX_NOTIFIED_IDS = 512

        const val CHANNEL_INBOUND_MESSAGES = "graywolf_aprs_messages"
        const val KEY_TEXT_REPLY = "key_text_reply"
        const val ACTION_REPLY = "com.nw5w.graywolf.ACTION_MESSAGE_REPLY"
        const val ACTION_MARK_READ = "com.nw5w.graywolf.ACTION_MESSAGE_MARK_READ"
        const val EXTRA_MESSAGE_ID = "extra_message_id"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_REPLY_TARGET = "extra_reply_target"
        const val EXTRA_THREAD_KIND = "extra_thread_kind"
        const val EXTRA_THREAD_KEY = "extra_thread_key"

        @Volatile
        var activeInstance: MessageNotificationManager? = null
            private set

        val QUICK_REPLIES: Array<CharSequence> = arrayOf(
            "73",
            "Copy",
            "QSL",
            "On my way",
            "Standing by",
            "73 de mobile",
        )

        fun shouldNotify(msg: MessageDto): Boolean {
            if (!msg.direction.equals("in", ignoreCase = true)) return false
            if (!msg.unread) return false
            if (msg.isAck) return false
            if (msg.text.isBlank()) return false
            return true
        }

        fun resolveReplyDestination(msg: MessageDto): String {
            return if (msg.threadKind.equals("tactical", ignoreCase = true)) {
                msg.threadKey.ifBlank { msg.toCall }
            } else {
                msg.fromCall.ifBlank { msg.threadKey }
            }
        }

        fun notificationIdForThread(threadKind: String, threadKey: String): Int {
            val hash = ("$threadKind:$threadKey").hashCode() and 0x0FFFFFFF
            return 20_000 + (hash % 100_000)
        }

        fun buildThreadRoute(threadKind: String, threadKey: String): String {
            val safeKind = if (threadKind.equals("tactical", ignoreCase = true)) "tactical" else "dm"
            val encKey = URLEncoder.encode(threadKey, StandardCharsets.UTF_8.name())
            return "#/messages?thread=$safeKind:$encKey"
        }
    }
}
