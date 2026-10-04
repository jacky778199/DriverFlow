package tw.driver.schedule

import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LineFlowTest {
    private class MemoryStore(var seq: Long = 0, var ready: Boolean = false) : LineFlowPersistence {
        val messages = linkedMapOf<Long, LineMessage>()
        override fun cursor() = seq
        override fun initialized() = ready
        override fun save(messages: List<LineMessage>, cursor: Long?, initialized: Boolean): List<LineMessage> {
            val fresh = messages.filter { !this.messages.containsKey(it.seq) }.distinctBy { it.seq }
            fresh.forEach { this.messages[it.seq] = it }
            if (cursor != null) seq = maxOf(seq, cursor)
            ready = ready || initialized
            return fresh
        }
    }
    private fun message(seq: Long, content: String = "即時可等 自費 +300", timestamp: Long = 1000) =
        LineMessage(seq, "instance1", "搶單群", "司機", content, "10:00", timestamp)
    private fun json(seq: Long) = JSONObject().put("seq_id", seq).put("instance_id", "instance1")
        .put("content", "自費 +400").put("timestamp", 1000)

    @Test fun normalizesFullWidthAndWhitespaceWithoutMatchingLargerAmounts() {
        assertEquals(listOf("即時可等", "報分", "跳表", "自費", "+300", "+400"), MessageKeywords.match("即時 可等 報分 跳表 自費 ＋３００ / + 400"))
        assertEquals(listOf("即時"), MessageKeywords.match("即時 台大醫院 → 台北車站"))
        assertTrue(MessageKeywords.match("300元 +3000 +4000 一般預約").isEmpty())
    }
    @Test fun senderRulesRequireBothNamesAndAllowRegularMessages() {
        val rules = SenderAlertRules.parse("搶單群 | 王司機\n 搶單群|王司機 ")
        assertEquals(1, rules.size)
        assertTrue(SenderAlertRules.matches(LineMessage(1, "instance1", "搶單群", "王司機", "一般訊息", "", 1000), rules))
        assertFalse(SenderAlertRules.matches(LineMessage(2, "instance1", "其他群", "王司機", "一般訊息", "", 1000), rules))
        assertFalse(SenderAlertRules.matches(LineMessage(3, "instance1", "搶單群", "其他人", "一般訊息", "", 1000), rules))
        assertThrows(IllegalArgumentException::class.java) { SenderAlertRules.parse("搶單群|") }
    }
    @Test fun senderNotificationsUseFreshLiveAndRecentBackfillWithoutOldHistory() {
        val initial = MemoryStore()
        val first = LineFlowSession(initial)
        assertTrue(first.accept(LineFlowEvent.Batch(0, listOf(message(1, "一般訊息"))), 1000).received.isEmpty())
        assertEquals(listOf(2L), first.accept(LineFlowEvent.New(message(2, "一般訊息")), 1000).received.map { it.seq })
        assertTrue(first.accept(LineFlowEvent.New(message(2, "一般訊息")), 1000).received.isEmpty())

        val returning = LineFlowSession(MemoryStore(10, true))
        val result = returning.accept(LineFlowEvent.Batch(10,
            listOf(message(11, "一般訊息", 700), message(12, "一般訊息", 950))), 1000)
        assertEquals(listOf(12L), result.received.map { it.seq })
        assertTrue(result.alerts.isEmpty())
    }
    @Test fun validatesAndEncodesCredentialsWithoutEmbeddingThemInSettingsString() {
        val settings = LineFlowSettings("wss://example.com/ws/lineflow", "a&b ?", "instance 1")
        val url = settings.url(false)
        assertEquals("https://example.com/ws/lineflow", url)
        assertFalse(url.contains("?"))
        assertEquals("a&b ?", JSONObject(LineFlowProtocol.auth(settings)).getString("token"))
        assertEquals("instance 1", JSONObject(LineFlowProtocol.auth(settings)).getString("instance_id"))
        assertFalse(settings.toString().contains("a&b"))
        assertThrows(IllegalArgumentException::class.java) { LineFlowSettings("ws://example.com/ws/lineflow", "x").url(false) }
        assertThrows(IllegalArgumentException::class.java) { LineFlowSettings("wss://example.com/ws/lineflow?token=x", "x").url(false) }
        assertThrows(IllegalArgumentException::class.java) { LineFlowSettings("wss://example.com/wrong", "x").url(false) }
    }
    @Test fun parserRejectsWrongInstanceAndMalformedBatch() {
        val event = JSONObject().put("type", "new_message").put("data", json(1))
        assertEquals(1L, (LineFlowProtocol.parse(event.toString(), "instance1") as LineFlowEvent.New).message.seq)
        assertThrows(IllegalArgumentException::class.java) { LineFlowProtocol.parse(event.toString(), "other") }
        val batch = JSONObject().put("type", "sync_batch").put("since_seq_id", 0).put("count", 2).put("messages", JSONArray().put(json(1)))
        assertThrows(IllegalArgumentException::class.java) { LineFlowProtocol.parse(batch.toString(), "instance1") }
    }
    @Test fun syncPagesNeverSkipOlderMessagesWhenLiveMessageArrivesFirst() {
        val store = MemoryStore()
        val session = LineFlowSession(store)
        assertEquals(0L, JSONObject(session.initialRequest()).getLong("since_seq_id"))
        assertEquals(1, session.accept(LineFlowEvent.New(message(700)), 1000).alerts.size)
        assertEquals(0L, store.seq)
        val page = session.accept(LineFlowEvent.Batch(0, (1L..500L).map { message(it) }), 1000)
        assertEquals(500L, JSONObject(page.nextRequest!!).getLong("since_seq_id"))
        assertTrue(page.alerts.isEmpty())
        assertFalse(page.connected)
        val end = session.accept(LineFlowEvent.Batch(500, (501L..700L).map { message(it) }), 1000)
        assertTrue(end.connected)
        assertEquals(700, store.messages.size)
        assertEquals(700L, store.seq)
        assertTrue(session.accept(LineFlowEvent.New(message(700)), 1000).alerts.isEmpty())
        assertEquals(1, store.messages.values.count { it.unread })
    }
    @Test fun reconnectOnlyAlertsRecentNewMatchesAndDeduplicatesLiveReplay() {
        val store = MemoryStore(40, true)
        val session = LineFlowSession(store)
        val result = session.accept(LineFlowEvent.Batch(40, listOf(message(41, timestamp = 700), message(42), message(43, "普通訊息"))), 1000)
        assertEquals(listOf(42L), result.alerts.map { it.seq })
        assertTrue(session.accept(LineFlowEvent.New(message(42)), 1000).alerts.isEmpty())
        assertEquals(43L, store.seq)
        assertEquals(43L, JSONObject(LineFlowSession(store).initialRequest()).getLong("since_seq_id"))
    }
    @Test fun emptyFirstSyncMarksSourceInitialized() {
        val store = MemoryStore()
        assertTrue(LineFlowSession(store).accept(LineFlowEvent.Batch(0, emptyList()), 1000).connected)
        assertTrue(store.ready)
    }
    @Test fun failedPersistenceNeverAdvancesSync() {
        val store = object : LineFlowPersistence {
            override fun cursor() = 5L
            override fun initialized() = true
            override fun save(messages: List<LineMessage>, cursor: Long?, initialized: Boolean): List<LineMessage> = throw java.io.IOException("disk full")
        }
        val session = LineFlowSession(store)
        assertThrows(java.io.IOException::class.java) { session.accept(LineFlowEvent.Batch(5, listOf(message(6))), 1000) }
        assertEquals(5L, JSONObject(session.initialRequest()).getLong("since_seq_id"))
    }
    @Test fun actualWebSocketSyncThenLiveAlertAndDuplicate() {
        val requests = LinkedBlockingQueue<String>()
        val serverSocket = LinkedBlockingQueue<WebSocket>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { serverSocket.add(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    requests.add(text)
                    if (JSONObject(text).getString("action") == "auth") {
                        webSocket.send("""{"type":"auth_ok","instance_id":"instance1"}""")
                        return
                    }
                    webSocket.send("{\"type\":\"sync_batch\",\"since_seq_id\":0,\"count\":0,\"messages\":[]}")
                }
            }))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val alerts = LinkedBlockingQueue<LineMessage>()
            val states = LinkedBlockingQueue<String>()
            val connection = LineFlowConnection(LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "test-only"), MemoryStore(), { states.add(it) }, { alerts.add(it) }, { fail("unexpected fatal") }, allowCleartext = true)
            try {
                connection.start()
                val socket = serverSocket.poll(5, TimeUnit.SECONDS)!!
                assertEquals("auth", JSONObject(requests.poll(5, TimeUnit.SECONDS)!!).getString("action"))
                assertEquals("/ws/lineflow", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
                assertEquals("sync", JSONObject(requests.poll(5, TimeUnit.SECONDS)!!).getString("action"))
                val event = JSONObject().put("type", "new_message").put("data", json(1)).toString()
                socket.send(event)
                assertEquals(1L, alerts.poll(5, TimeUnit.SECONDS)!!.seq)
                socket.send(event)
                assertNull(alerts.poll(300, TimeUnit.MILLISECONDS))
                assertTrue(states.contains("已連線"))
            } finally { connection.close() }
        }
    }
    @Test fun invalidTokenCloseStopsInsteadOfRetrying() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.close(1008, "invalid token") }
            }))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val fatal = LinkedBlockingQueue<Boolean>()
            val states = LinkedBlockingQueue<String>()
            val connection = LineFlowConnection(LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "invalid"), MemoryStore(), { states.add(it) }, {}, { fatal.add(true) }, allowCleartext = true)
            try {
                connection.start()
                assertEquals(true, fatal.poll(5, TimeUnit.SECONDS))
                assertTrue(states.any { it.contains("驗證失敗") })
                assertEquals(1, server.requestCount)
            } finally { connection.close() }
        }
    }
    @Test fun handshakeGatesSyncAndSendUntilAuthOk() {
        MockWebServer().use { server ->
            val frames = LinkedBlockingQueue<String>()
            val sockets = LinkedBlockingQueue<WebSocket>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
            }))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val settings = LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "secret-test")
            val updates = LinkedBlockingQueue<String>()
            val connection = LineFlowConnection(settings, MemoryStore(), {}, {}, {}, allowCleartext = true,
                sendChanged = { _, state, _ -> updates.add(state) })
            try {
                connection.start()
                assertEquals("auth", JSONObject(frames.poll(5, TimeUnit.SECONDS)!!).getString("action"))
                connection.send(OutgoingMessage("1", settings.source, "test", "chat", "body"))
                assertEquals("not_sent", updates.poll(5, TimeUnit.SECONDS))
                assertNull(frames.poll(200, TimeUnit.MILLISECONDS))
                sockets.poll(5, TimeUnit.SECONDS)!!.send("""{"type":"auth_ok"}""")
                assertEquals("sync", JSONObject(frames.poll(5, TimeUnit.SECONDS)!!).getString("action"))
            } finally { connection.close() }
        }
    }
    @Test fun authFailureStopsWithoutLeakingServerReason() {
        MockWebServer().use { server ->
            val frames = LinkedBlockingQueue<String>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.add(text)
                    webSocket.send("""{"type":"auth_fail","reason":"secret-token"}""")
                }
            }))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val fatal = LinkedBlockingQueue<Boolean>()
            val states = LinkedBlockingQueue<String>()
            val connection = LineFlowConnection(LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "secret-token"), MemoryStore(), { states.add(it) }, {}, { fatal.add(true) }, allowCleartext = true)
            try {
                connection.start()
                assertEquals(true, fatal.poll(5, TimeUnit.SECONDS))
                assertEquals("auth", JSONObject(frames.poll(5, TimeUnit.SECONDS)!!).getString("action"))
                assertNull(frames.poll(200, TimeUnit.MILLISECONDS))
                assertTrue(states.none { it.contains("secret-token") })
                assertEquals(1, server.requestCount)
            } finally { connection.close() }
        }
    }
    @Test fun authTimeoutReconnectsAndAuthenticatesAgain() {
        MockWebServer().use { server ->
            val frames = LinkedBlockingQueue<String>()
            repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) { frames.add(JSONObject(text).getString("action")) }
            })) }
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val connection = LineFlowConnection(LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "test"), MemoryStore(), {}, {}, {}, allowCleartext = true, authTimeoutMillis = 150)
            try {
                connection.start()
                assertEquals("auth", frames.poll(5, TimeUnit.SECONDS))
                assertEquals("auth", frames.poll(5, TimeUnit.SECONDS))
            } finally { connection.close() }
        }
    }
    @Test fun authRejectsWrongInstanceAndNonLocalCleartext() {
        assertThrows(IllegalArgumentException::class.java) { LineFlowProtocol.parse("""{"type":"auth_ok","instance_id":"other"}""", "instance1") }
        assertThrows(IllegalArgumentException::class.java) { LineFlowSettings("ws://example.com/ws/lineflow", "x").url(true) }
        assertEquals("wss://your-server.example.com/ws/lineflow", LineFlowSettings().endpoint)
    }
}
