package tw.driver.schedule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class AiFailoverTest {
    @Test fun serviceUnavailableSwitchesExactlyOnce() = runBlocking {
        var switched = 0
        var backupCalls = 0
        val result = AiFailover.run(primary = { throw AiHttpException(503, "Gemini") },
            backup = { backupCalls++; "backup" }, onFallback = { switched++ })
        assertEquals("backup", result); assertEquals(1, switched); assertEquals(1, backupCalls)
    }
    @Test fun authMalformedAndCancellationDoNotSwitch() {
        assertFalse(AiFailover.eligible(AiHttpException(403, "Gemini")))
        assertFalse(AiFailover.eligible(IllegalArgumentException("malformed")))
        assertFalse(AiFailover.eligible(CancellationException()))
        assertTrue(AiFailover.eligible(java.net.SocketTimeoutException()))
        assertTrue(AiFailover.eligible(AiHttpException(429, "Gemini")))
    }
    @Test fun primarySuccessNeverCallsBackup() = runBlocking {
        assertEquals("primary", AiFailover.run(primary = { "primary" }, backup = { error("Unexpected backup") }, onFallback = { error("Unexpected switch") }))
    }
    @Test fun backupOnlyWorks() = runBlocking {
        assertEquals("backup", AiFailover.run(primary = null, backup = { "backup" }, onFallback = { error("Unexpected switch") }))
    }
    @Test fun backupFailureDoesNotLoop() = runBlocking {
        var count = 0
        try {
            AiFailover.run<String>({ throw AiHttpException(503, "Gemini") }, { count++; throw AiHttpException(503, "DeepSeek") }, {})
            fail("Expected failure")
        } catch (e: AiHttpException) { assertTrue(e.message!!.contains("DeepSeek")) }
        assertEquals(1, count)
    }
    @Test fun transportTimeoutTriggersBackupWithoutWaitingForFullResponse() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("late").setBodyDelay(2, TimeUnit.SECONDS))
            val transport = AiTransport(OkHttpClient.Builder().callTimeout(150, TimeUnit.MILLISECONDS).retryOnConnectionFailure(false).build())
            val result = AiFailover.run({ transport.post(server.url("/").toString(), "x-test", "fake", "{}", "Test") }, { "fallback" }, {})
            assertEquals("fallback", result)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test(expected = AiInputException::class) fun truncatedDeepseekResponseNeverCreatesDrafts() {
        AiBookingParser.decodeDeepseek(JSONObject("""{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}"""), "原文", "")
    }
}
