package tw.driver.schedule

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Temporary reproductions of the current receive failure modes. */
class LineFlowReceiveDiagnosticsTest {
    private class Store : LineFlowPersistence {
        var seq = 100L
        val messages = linkedMapOf<Long, LineMessage>()
        override fun cursor() = seq
        override fun initialized() = true
        override fun save(messages: List<LineMessage>, cursor: Long?, initialized: Boolean): List<LineMessage> {
            val fresh = messages.filter { !this.messages.containsKey(it.seq) }
            fresh.forEach { this.messages[it.seq] = it }
            if (cursor != null) seq = maxOf(seq, cursor)
            return fresh
        }
    }
    private fun message(seq: Long) = LineMessage(seq, "instance1", "chat", "sender", "text", "", 1000)

    @Test fun missedPushThenHigherSequenceSkipsMissingMessageOnReconnect() {
        val store = Store()
        val session = LineFlowSession(store)
        session.accept(LineFlowEvent.Batch(100, emptyList()), 1000)
        // Server has 101, but only pushes 102 to the app.
        session.accept(LineFlowEvent.New(message(102)), 1000)
        assertFalse(store.messages.containsKey(101))
        assertEquals(102L, JSONObject(LineFlowSession(store).initialRequest()).getLong("since_seq_id"))
    }

    @Test fun lateLowerSequenceIsDiscardedEvenWhenNeverStored() {
        val store = Store()
        val session = LineFlowSession(store)
        session.accept(LineFlowEvent.Batch(100, emptyList()), 1000)
        session.accept(LineFlowEvent.New(message(102)), 1000)
        session.accept(LineFlowEvent.New(message(101)), 1000)
        assertFalse(store.messages.containsKey(101))
        assertEquals(setOf(102L), store.messages.keys)
    }

    @Test fun pongNeverRequestsMessageSync() {
        val session = LineFlowSession(Store())
        session.accept(LineFlowEvent.Batch(100, emptyList()), 1000)
        repeat(10) { assertNull(session.accept(LineFlowEvent.Pong, 1000).nextRequest) }
    }
}
