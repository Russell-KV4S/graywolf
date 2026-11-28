package com.nw5w.graywolf.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.nw5w.graywolf.GraywolfApp
import com.nw5w.graywolf.api.GraywolfLocalApi
import kotlin.concurrent.thread

/**
 * Handles inline voice/keyboard replies and "Mark as read" actions triggered from
 * Android Auto, Wear OS, or phone notification shades.
 */
class MessageReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val messageId = intent.getLongExtra(MessageNotificationManager.EXTRA_MESSAGE_ID, 0L)
        val notifId = intent.getIntExtra(MessageNotificationManager.EXTRA_NOTIFICATION_ID, 0)
        val app = context.applicationContext as? GraywolfApp ?: return
        val api = GraywolfLocalApi(tokenProvider = { app.bearerToken })

        when (action) {
            MessageNotificationManager.ACTION_REPLY -> {
                val replyText = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(MessageNotificationManager.KEY_TEXT_REPLY)
                    ?.toString()
                    ?.trim()
                    .orEmpty()
                val target = intent.getStringExtra(MessageNotificationManager.EXTRA_REPLY_TARGET).orEmpty()
                val threadKind = intent.getStringExtra(MessageNotificationManager.EXTRA_THREAD_KIND) ?: "dm"
                val threadKey = intent.getStringExtra(MessageNotificationManager.EXTRA_THREAD_KEY) ?: target
                if (replyText.isEmpty() || target.isEmpty()) return

                val pending = goAsync()
                thread(name = "graywolf-notif-reply") {
                    try {
                        val sent = api.sendMessage(target, replyText)
                        if (messageId > 0L) {
                            api.markMessageRead(messageId)
                        }
                        if (sent != null) {
                            MessageNotificationManager.activeInstance?.onReplied(
                                threadKind = threadKind,
                                threadKey = threadKey,
                                notifId = notifId,
                                replyText = replyText,
                            )
                        } else {
                            Log.w(TAG, "sendMessage failed for target=$target")
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }

            MessageNotificationManager.ACTION_MARK_READ -> {
                val pending = goAsync()
                thread(name = "graywolf-notif-read") {
                    try {
                        if (messageId > 0L) {
                            api.markMessageRead(messageId)
                        }
                        if (notifId != 0) {
                            MessageNotificationManager.activeInstance?.cancelNotification(notifId)
                        }
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "MessageReplyReceiver"
    }
}
