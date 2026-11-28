package com.nw5w.graywolf.notify

import com.nw5w.graywolf.MainActivity
import com.nw5w.graywolf.api.MessageDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageNotificationTest {

    private fun sampleMsg(
        direction: String = "in",
        unread: Boolean = true,
        isAck: Boolean = false,
        text: String = "Copy 59",
        threadKind: String = "dm",
        threadKey: String = "K4ABC-9",
        fromCall: String = "K4ABC-9",
        toCall: String = "NW5W-7",
    ) = MessageDto(
        id = 100L,
        direction = direction,
        status = "received",
        ourCall = "NW5W-7",
        peerCall = "K4ABC-9",
        fromCall = fromCall,
        toCall = toCall,
        text = text,
        msgId = "12",
        createdAt = "2026-11-28T03:00:00Z",
        receivedAt = "2026-11-28T03:00:01Z",
        source = "rf",
        via = "",
        unread = unread,
        threadKind = threadKind,
        threadKey = threadKey,
        kind = "text",
        isAck = isAck,
    )

    @Test
    fun `shouldNotify filters outbound, read, ack, and blank messages`() {
        assertTrue(MessageNotificationManager.shouldNotify(sampleMsg()))
        assertFalse(MessageNotificationManager.shouldNotify(sampleMsg(direction = "out")))
        assertFalse(MessageNotificationManager.shouldNotify(sampleMsg(unread = false)))
        assertFalse(MessageNotificationManager.shouldNotify(sampleMsg(isAck = true)))
        assertFalse(MessageNotificationManager.shouldNotify(sampleMsg(text = "   ")))
    }

    @Test
    fun `resolveReplyDestination chooses tactical key or DM sender`() {
        val dm = sampleMsg(threadKind = "dm", threadKey = "K4ABC-9", fromCall = "K4ABC-9")
        assertEquals("K4ABC-9", MessageNotificationManager.resolveReplyDestination(dm))

        val tac = sampleMsg(threadKind = "tactical", threadKey = "SAR1", fromCall = "W1AW-2", toCall = "SAR1")
        assertEquals("SAR1", MessageNotificationManager.resolveReplyDestination(tac))
    }

    @Test
    fun `notificationIdForThread is stable per thread and differs across threads`() {
        val a1 = MessageNotificationManager.notificationIdForThread("dm", "K4ABC-9")
        val a2 = MessageNotificationManager.notificationIdForThread("dm", "K4ABC-9")
        val b = MessageNotificationManager.notificationIdForThread("tactical", "SAR1")
        assertEquals(a1, a2)
        assertNotEquals(a1, b)
        assertTrue(a1 >= 20_000)
    }

    @Test
    fun `buildThreadRoute matches MainActivity sanitizeHashRoute whitelist`() {
        val dmRoute = MessageNotificationManager.buildThreadRoute("dm", "K4ABC-9")
        val tacRoute = MessageNotificationManager.buildThreadRoute("tactical", "ARES_1")
        assertEquals("#/messages?thread=dm:K4ABC-9", dmRoute)
        assertEquals("#/messages?thread=tactical:ARES_1", tacRoute)
        assertEquals(dmRoute, MainActivity.sanitizeHashRoute(dmRoute))
        assertEquals(tacRoute, MainActivity.sanitizeHashRoute(tacRoute))
        assertNull(MainActivity.sanitizeHashRoute("#/messages?thread=dm:bad<script>"))
        assertNull(MainActivity.sanitizeHashRoute("https://evil.example"))
    }
}
