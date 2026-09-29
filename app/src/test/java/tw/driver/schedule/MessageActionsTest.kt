package tw.driver.schedule

import kotlinx.coroutines.runBlocking
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MessageActionsTest {
    @Test fun configuredKeywordsAreLiteralNormalizedAndCanBeDisabled() {
        val keywords = MessageKeywords.parseSettings(" 自費 \n ＋３００\n+300\nC++\n[A]\n\n")
        assertEquals(listOf("自費", "＋３００", "C++", "[A]"), keywords)
        assertEquals(listOf("＋３００", "C++", "[A]"), MessageKeywords.match("+ 300 C++ [A]", keywords))
        assertTrue(MessageKeywords.match("自費 +300", emptyList()).isEmpty())
        assertTrue(MessageKeywords.match("+3000 CAA A", listOf("+300", "C++", "[A]")).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { MessageKeywords.parseSettings("x".repeat(41)) }
    }
    @Test fun customKeywordsApplyToLiveProtocolEvents() {
        val event = """{"type":"new_message","data":{"seq_id":1,"instance_id":"instance1","content":"急件 +300"}}"""
        val parsed = LineFlowProtocol.parse(event, "instance1", listOf("急件")) as LineFlowEvent.New
        assertEquals(listOf("急件"), parsed.message.keywords)
    }
    @Test fun firstAddressCannotSkipAnEarlierHospitalAndAiCannotInventAddress() {
        val first = "台北市中正區中山南路8號"
        assertEquals(first, FirstMessageAddress.local("自費 +300\n$first → 新北市板橋區文化路1號"))
        assertEquals("", FirstMessageAddress.local("台大醫院 → $first"))
        assertEquals("台大醫院", FirstMessageAddress.validateAi("""{"address":"台大醫院"}""", "台大醫院 → $first"))
        assertThrows(IllegalArgumentException::class.java) { FirstMessageAddress.validateAi("""{"address":"假地址"}""", first) }
    }
    @Test fun arrivalReplyUsesCeilingMinutesAndExpires() {
        val estimate = MessageArrival("台北市中正區中山南路8號", "現在位置", 601, 3000, 1000)
        assertEquals("11 分 可到 台北市中正區中山南路8號", estimate.reply)
        assertTrue(estimate.isFresh(301000))
        assertFalse(estimate.isFresh(301001))
        assertFalse(estimate.isFresh(999))
        assertEquals(1L, estimate.copy(seconds = 0).minutes)
    }
    @Test fun coordinateOriginUsesTrafficRoutingAndResolvedDestination() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"routes":[{"duration":"601s","distanceMeters":3000}]}"""))
            val client = GoogleRouteClient("test", routesUrl = server.url("/routes").toString())
            assertEquals(601L, client.computeFromPosition(25.03, 121.56, "first-address-id", Instant.now()).seconds)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(25.03, body.getJSONObject("origin").getJSONObject("location").getJSONObject("latLng").getDouble("latitude"), 0.00001)
            assertEquals("first-address-id", body.getJSONObject("destination").getString("placeId"))
            assertEquals("TRAFFIC_AWARE", body.getString("routingPreference"))
        }
    }
    @Test fun sendPayloadAndReplyAreCorrelatedByRequestId() {
        val request = OutgoingMessage("unique-id", "source", "action", "小明", "王先生去程客上\n已接到")
        val payload = JSONObject(LineFlowProtocol.send(request))
        assertEquals("send_message", payload.getString("action"))
        assertEquals(request.id, payload.getString("request_id"))
        assertEquals(request.target, payload.getString("target"))
        assertEquals(request.text, payload.getString("message"))
        val result = LineFlowProtocol.parse("""{"type":"send_result","request_id":"unique-id","status":"error","error_message":"找不到聊天室"}""", "instance1") as LineFlowEvent.SendResult
        assertFalse(result.success)
        assertEquals("找不到聊天室", result.error)
    }
    private fun persistence() = object : LineFlowPersistence {
        override fun cursor() = 0L
        override fun initialized() = true
        override fun save(messages: List<LineMessage>, cursor: Long?, initialized: Boolean) = messages
    }
    private data class Update(val id: String, val state: String)
    @Test fun sendWaitsForMatchingServerAcknowledgement() {
        val serverSockets = LinkedBlockingQueue<WebSocket>()
        val sent = LinkedBlockingQueue<JSONObject>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { serverSockets.add(webSocket) }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = JSONObject(text)
                    if (json.getString("action") == "auth") { webSocket.send("""{"type":"auth_ok"}"""); return }
                    if (json.getString("action") == "sync") webSocket.send("""{"type":"sync_batch","since_seq_id":0,"count":0,"messages":[]}""")
                    else sent.add(json)
                }
            }))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val settings = LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "test-only")
            val ready = LinkedBlockingQueue<Boolean>()
            val updates = LinkedBlockingQueue<Update>()
            val connection = LineFlowConnection(settings, persistence(), {}, {}, {}, allowCleartext = true,
                readyChanged = { if (it) ready.add(it) }, sendChanged = { id, state, _ -> updates.add(Update(id, state)) })
            try {
                connection.start(); assertEquals(true, ready.poll(5, TimeUnit.SECONDS))
                val request = OutgoingMessage("request-1", settings.source, "eta:1", "原聊天室", "12分可到第一個地址")
                connection.send(request)
                assertEquals("request-1", sent.poll(5, TimeUnit.SECONDS)!!.getString("request_id"))
                assertEquals(Update("request-1", "sending"), updates.poll(5, TimeUnit.SECONDS))
                val socket = serverSockets.poll(5, TimeUnit.SECONDS)!!
                socket.send("""{"type":"send_result","request_id":"unrelated","status":"success"}""")
                assertNull(updates.poll(100, TimeUnit.MILLISECONDS))
                socket.send("""{"type":"send_result","request_id":"request-1","status":"success"}""")
                assertEquals(Update("request-1", "success"), updates.poll(5, TimeUnit.SECONDS))
            } finally { connection.close() }
        }
    }
    @Test fun timedOutSendBecomesUnknownAndIsNeverAutomaticallyResent() {
        val sent = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (JSONObject(text).getString("action") == "auth") { webSocket.send("""{"type":"auth_ok"}"""); return }
                    if (JSONObject(text).getString("action") == "sync") webSocket.send("""{"type":"sync_batch","since_seq_id":0,"count":0,"messages":[]}""")
                    else sent.add(text)
                }
            }))
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val settings = LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "test-only")
            val ready = LinkedBlockingQueue<Boolean>()
            val updates = LinkedBlockingQueue<Update>()
            val connection = LineFlowConnection(settings, persistence(), {}, {}, {}, allowCleartext = true,
                readyChanged = { if (it) ready.add(it) }, sendChanged = { id, state, _ -> updates.add(Update(id, state)) }, acknowledgementTimeoutMillis = 100)
            try {
                connection.start(); assertEquals(true, ready.poll(5, TimeUnit.SECONDS))
                connection.send(OutgoingMessage("request-2", settings.source, "passenger:1", "小明", "王先生客上"))
                assertEquals("sending", updates.poll(5, TimeUnit.SECONDS)!!.state)
                assertEquals("unknown", updates.poll(5, TimeUnit.SECONDS)!!.state)
                assertNotNull(sent.poll(5, TimeUnit.SECONDS))
                assertNull(sent.poll(200, TimeUnit.MILLISECONDS))
            } finally { connection.close() }
        }
    }
    @Test fun disconnectAfterSendMarksUnknownAndReconnectOnlySyncs() {
        val sent = LinkedBlockingQueue<String>()
        MockWebServer().use { server ->
            repeat(2) { attempt ->
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (JSONObject(text).getString("action") == "auth") { webSocket.send("""{"type":"auth_ok"}"""); return }
                    if (JSONObject(text).getString("action") == "sync") webSocket.send("""{"type":"sync_batch","since_seq_id":0,"count":0,"messages":[]}""")
                        else { sent.add(text); if (attempt == 0) webSocket.close(1011, "test disconnect") }
                    }
                }))
            }
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val settings = LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "test-only")
            val ready = LinkedBlockingQueue<Boolean>()
            val updates = LinkedBlockingQueue<Update>()
            val connection = LineFlowConnection(settings, persistence(), {}, {}, {}, allowCleartext = true,
                readyChanged = { if (it) ready.add(it) }, sendChanged = { id, state, _ -> updates.add(Update(id, state)) })
            try {
                connection.start(); assertEquals(true, ready.poll(5, TimeUnit.SECONDS))
                connection.send(OutgoingMessage("request-3", settings.source, "passenger:3", "小明", "王先生客下"))
                assertEquals("sending", updates.poll(5, TimeUnit.SECONDS)!!.state)
                assertEquals("unknown", updates.poll(5, TimeUnit.SECONDS)!!.state)
                assertEquals(true, ready.poll(5, TimeUnit.SECONDS))
                assertEquals(2, server.requestCount)
                assertNotNull(sent.poll(5, TimeUnit.SECONDS))
                assertNull(sent.poll(200, TimeUnit.MILLISECONDS))
            } finally { connection.close() }
        }
    }
    @Test fun replyKeepsOriginalAddressAfterRouteNormalization() {
        val parsed = InsertionCase("台大醫院", "台北車站", "2026-09-28")
        val normalized = parsed.copy(pickup = "台北市中正區中山南路7號")
        assertEquals("台大醫院", normalized.originalPickup)
        assertEquals("12 分 可到 台大醫院", insertionReplyText("12", normalized.originalPickup, "即時可等 台大醫院 → 台北車站"))
        assertThrows(IllegalArgumentException::class.java) { insertionReplyText("12", normalized.pickup, "台大醫院 → 台北車站") }
        assertThrows(IllegalArgumentException::class.java) { insertionReplyText("", "台大醫院", "台大醫院 → 台北車站") }
    }
}
