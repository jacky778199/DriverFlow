package tw.driver.schedule

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MessageScreenshotTest {
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1xkAAAAASUVORK5CYII="
    private fun result(id: String) = JSONObject().put("type", "screenshot_result").put("request_id", id)
        .put("status", "success").put("format", "png").put("width", 1).put("height", 1).put("image_base64", png)
    @Test fun requestMatchesServerContractAndSuccessfulImageIsDecoded() {
        val payload = JSONObject(ScreenshotProtocol.request("req-screen-001"))
        assertEquals("screenshot", payload.getString("action"))
        assertEquals("req-screen-001", payload.getString("request_id"))
        assertEquals("jpeg", payload.getString("format"))
        assertEquals(80, payload.getInt("quality"))
        val event = LineFlowProtocol.parse(result("req-screen-001").toString(), "instance1") as LineFlowEvent.ScreenshotResult
        assertEquals("req-screen-001", event.requestId)
        assertNotNull(event.image)
        assertEquals(1, event.image!!.width)
        assertEquals("png", event.image!!.format)
        assertEquals("", event.error)
    }
    @Test fun dataUriOnlyResponseAndServerErrorsAreSupported() {
        val json = result("id").also { it.remove("image_base64") }.put("data_uri", "data:image/png;base64,$png")
        assertNotNull(ScreenshotProtocol.result(json).image)
        val error = ScreenshotProtocol.result(JSONObject().put("request_id", "id").put("status", "error").put("error_message", "裝置離線"))
        assertNull(error.image)
        assertEquals("裝置離線", error.error)
    }
    @Test fun invalidImageAndOversizedDimensionsBecomeCorrelatedErrors() {
        val malformed = ScreenshotProtocol.result(result("bad").put("image_base64", "not an image!"))
        assertEquals("bad", malformed.requestId)
        assertNull(malformed.image)
        assertTrue(malformed.error.isNotBlank())
        assertNull(ScreenshotProtocol.result(result("huge").put("width", 9000)).image)
        assertNull(ScreenshotProtocol.result(result("wrong-format").put("format", "jpeg")).image)
    }
    private fun persistence() = object : LineFlowPersistence {
        override fun cursor() = 0L
        override fun initialized() = true
        override fun save(messages: List<LineMessage>, cursor: Long?, initialized: Boolean) = messages
    }
    private class Session(val server: MockWebServer, val connection: LineFlowConnection, val source: String,
        val socket: WebSocket, val requests: LinkedBlockingQueue<JSONObject>, val fatal: LinkedBlockingQueue<Boolean>) : AutoCloseable {
        override fun close() { connection.close(); server.close() }
    }
    private fun connected(timeout: Long = 15000): Session {
        val server = MockWebServer()
        val sockets = LinkedBlockingQueue<WebSocket>()
        val requests = LinkedBlockingQueue<JSONObject>()
        val ready = LinkedBlockingQueue<Boolean>()
        val fatal = LinkedBlockingQueue<Boolean>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val payload = JSONObject(text)
                when (payload.getString("action")) {
                    "auth" -> webSocket.send("""{"type":"auth_ok"}""")
                    "sync" -> webSocket.send("""{"type":"sync_batch","since_seq_id":0,"count":0,"messages":[]}""")
                    "screenshot" -> requests.add(payload)
                }
            }
        }))
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
        val settings = LineFlowSettings(server.url("/ws/lineflow").newBuilder().host("127.0.0.1").build().toString().replace("http://", "ws://"), "fake-token")
        val connection = LineFlowConnection(settings, persistence(), {}, {}, { fatal.add(true) }, allowCleartext = true,
            readyChanged = { if (it) ready.add(true) }, screenshotTimeoutMillis = timeout)
        connection.start()
        check(ready.poll(5, TimeUnit.SECONDS) == true) { "Mock server did not authenticate" }
        return Session(server, connection, settings.source, sockets.poll(5, TimeUnit.SECONDS)!!, requests, fatal)
    }
    @Test fun screenshotUsesAuthenticatedSocketAndIgnoresUnrelatedReplies() = runBlocking {
        connected().use { session ->
            val pending = async { session.connection.screenshot(session.source) }
            // Start coroutine before waiting on the blocking server queue.
            kotlinx.coroutines.yield()
            val request = session.requests.poll(5, TimeUnit.SECONDS)!!
            val id = request.getString("request_id")
            session.socket.send(result("unrelated").toString())
            session.socket.send(result(id).toString())
            val image = withTimeout(5000) { pending.await() }
            assertEquals(1, image.height)
            assertTrue(session.fatal.isEmpty())
        }
    }
    @Test fun timeoutFailsOnlyTheScreenshotAndNextRequestStillWorks() = runBlocking {
        connected(150).use { session ->
            try { session.connection.screenshot(session.source); fail("Should time out") }
            catch (e: IOException) { assertTrue(e.message!!.contains("逾時")) }
            val old = session.requests.poll(5, TimeUnit.SECONDS)!!
            val next = async { session.connection.screenshot(session.source) }
            kotlinx.coroutines.yield()
            val request = session.requests.poll(5, TimeUnit.SECONDS)!!
            assertNotEquals(old.getString("request_id"), request.getString("request_id"))
            session.socket.send(result(old.getString("request_id")).toString())
            session.socket.send(result(request.getString("request_id")).toString())
            assertEquals(1, withTimeout(5000) { next.await() }.width)
            assertTrue(session.fatal.isEmpty())
        }
    }
    @Test fun malformedReplyDoesNotDisconnectMessageReception() = runBlocking {
        connected().use { session ->
            val pending = async { runCatching { session.connection.screenshot(session.source) } }
            kotlinx.coroutines.yield()
            val request = session.requests.poll(5, TimeUnit.SECONDS)!!
            session.socket.send(result(request.getString("request_id")).put("image_base64", "bad").toString())
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is IOException)
            assertTrue(session.fatal.isEmpty())
        }
    }
    @Test fun disconnectFailsPendingScreenshotAndWrongSourceNeverSends() = runBlocking {
        connected().use { session ->
            try { session.connection.screenshot("another-source"); fail("wrong source") }
            catch (e: IOException) { assertTrue(e.message!!.contains("來源")) }
            assertTrue(session.requests.isEmpty())
            val pending = async { runCatching { session.connection.screenshot(session.source) } }
            kotlinx.coroutines.yield()
            assertNotNull(session.requests.poll(5, TimeUnit.SECONDS))
            session.socket.close(1000, "test disconnect")
            val error = withTimeout(5000) { pending.await() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue(error!!.message!!.contains("連線中斷"))
        }
    }
}
