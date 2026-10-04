package tw.driver.schedule

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class AiCustomizationTest {
    @Test fun userPromptsReplaceDefaultsRatherThanAppendToThem() {
        val settings = AiSettings(prompt = "我的完整輸入 Prompt", messagePrompt = "我的插單 Prompt：{today}")
        val input = analysisPrompt(settings, LocationTerms(), "原文")
        assertEquals("我的完整輸入 Prompt", input)
        assertFalse(input.contains("單一司機"))
        val message = messageAnalysisPrompt(settings, LocationTerms(), "北院", LocalDate.of(2026, 10, 1))
        assertTrue(message.startsWith("我的插單 Prompt：2026-10-01"))
        assertFalse(message.contains("報分、自費本身"))
        assertTrue(message.contains("pickup,destination,date,time,asap,note"))
        assertTrue(AiSettings().prompt.contains("單一司機"))
    }
    @Test fun oldSupplementSettingsMigrateWithoutLosingKeysOrInstructions() {
        val json = JSONObject().put("provider", "DEEPSEEK").put("geminiModel", "gemini-2.5-flash").put("geminiKey", "fake-g")
            .put("deepseekModel", "deepseek-flash").put("deepseekKey", "fake-d").put("fallback", true).put("prompt", "保留陪同人数")
        val migrated = decodeAiSettings(json)
        assertEquals(AiProvider.DEEPSEEK, migrated.provider)
        assertEquals("fake-d", migrated.deepseekKey)
        assertTrue(migrated.prompt.startsWith(AiPrompts.booking))
        assertTrue(migrated.prompt.endsWith("保留陪同人数"))
        assertTrue(migrated.messagePrompt.endsWith("保留陪同人数"))
        json.put("version", 2).put("prompt", "完全取代").put("messagePrompt", "自行撰寫")
        val updated = decodeAiSettings(json)
        assertEquals("完全取代", updated.prompt)
        assertEquals("自行撰寫", updated.messagePrompt)
    }
    @Test fun customUrlAcceptsFullHttpsEndpointAndCustomModelIdentifiers() {
        val settings = AiSettings(provider = AiProvider.CUSTOM, customUrl = "https://example.test/v1/chat/completions?version=2",
            customModel = "org/model:32b", customKey = "fake-secret")
        settings.validate()
        assertEquals("org/model:32b", settings.model(AiProvider.CUSTOM))
        assertTrue(settings.ready)
        for (url in listOf("file:///tmp/key", "https://user:password@example.test/v1", "not-a-url", "http://example.test/v1")) {
            try { customEndpoint(url); fail("invalid endpoint should be rejected") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun customProviderSendsActualConfiguredModelPromptAndBearerKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"{\"orders\":[]}"}}]}"""))
            // Direct transport test uses localhost HTTP; saved production settings require HTTPS.
            val settings = AiSettings(provider = AiProvider.CUSTOM, customUrl = server.url("/user/path").toString(),
                customModel = "my/model:latest", customKey = "fake-secret", prompt = "使用者完整 Prompt")
            val result = CustomAiClient().extract(settings, "原始訊息", settings.prompt)
            assertEquals("{\"orders\":[]}", result)
            val request = server.takeRequest()
            assertEquals("/user/path", request.path)
            assertEquals("Bearer fake-secret", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals("my/model:latest", body.getString("model"))
            assertEquals("使用者完整 Prompt", body.getJSONArray("messages").getJSONObject(0).getString("content"))
            assertEquals("原始訊息", body.getJSONArray("messages").getJSONObject(1).getString("content"))
            assertFalse(body.has("thinking"))
            assertFalse(body.toString().contains("fake-secret"))
        }
    }
    @Test fun truncatedCustomResponseIsRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}"""))
            try { CustomAiClient().extract(AiSettings(customUrl = server.url("/").toString()), "原文", "Prompt"); fail("truncated") }
            catch (_: AiInputException) { }
        }
    }
    @Test fun customProviderCanBeSelectedAsFailover() = runBlocking {
        val settings = AiSettings(geminiKey = "fake-g", customKey = "fake-c", customUrl = "https://example.test/chat/completions",
            customModel = "custom/model", fallback = true, fallbackProvider = AiProvider.CUSTOM)
        val calls = mutableListOf<AiProvider>()
        val result = configuredAi(settings) { provider ->
            calls += provider
            if (provider == AiProvider.GEMINI) throw AiHttpException(503, "Gemini")
            "custom-result"
        }
        assertEquals("custom-result", result)
        assertEquals(listOf(AiProvider.GEMINI, AiProvider.CUSTOM), calls)
    }
    @Test fun evaluationDisplayTranslatesEndpointsWithoutChangingOriginalReplyTarget() {
        val raw = InsertionCase("北院", "南院", "2026-10-01")
        val translated = raw.translated(LocationTerms(listOf(LocationTerm("北院", "北區醫院"), LocationTerm("南院", "南區醫院"))))
        assertEquals("北區醫院", translated.pickup)
        assertEquals("南區醫院", translated.destination)
        assertEquals("北院", translated.originalPickup)
        assertEquals("北院", raw.pickup)
    }
    @Test fun ageCounterAndHighlightExpireAtFiveMinuteBoundary() {
        val sent = Instant.parse("2026-10-01T04:00:00Z").epochSecond
        val now = sent * 1000
        assertEquals("0 mins ago", messageMinutesAgo(sent, "", now + 59000))
        assertEquals("1 mins ago", messageMinutesAgo(sent, "", now + 60000))
        assertTrue(messageIsRecent(sent, "", now + 299999))
        assertFalse(messageIsRecent(sent, "", now + 300000))
        assertEquals("5 mins ago", messageMinutesAgo(sent, "", now + 300000))
        assertFalse(messageIsRecent(sent, "", now - 1000))
        assertFalse(messageIsRecent(0, "", now))
        assertEquals("時間未知", messageMinutesAgo(0, "", now))
        assertEquals("時間待確認", messageMinutesAgo(sent, "", now - 1000))
        assertTrue(messageIsRecent(0, "2026-10-01T04:00:00Z", now + 60000))
    }
}
