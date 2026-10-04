package tw.driver.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class AnalysisSettingsTest {
    @Test fun missingSelectedKeyNeverMakesRequestEvenWithAnotherKey() = runBlocking {
        var called = false
        try {
            configuredAi(AiSettings(provider = AiProvider.DEEPSEEK, geminiKey = "fake", fallback = true)) { called = true; "result" }
            fail("Requires the selected provider's key")
        } catch (e: AiInputException) { assertTrue(e.message!!.contains("Tab 1")) }
        assertFalse(called)
    }
    @Test fun selectedProviderAndOptionalFailoverAreRespected() = runBlocking {
        val settings = AiSettings(provider = AiProvider.DEEPSEEK, geminiKey = "fake-g", deepseekKey = "fake-d", fallback = true)
        val calls = mutableListOf<AiProvider>()
        val result = configuredAi(settings) { provider ->
            calls += provider
            if (provider == AiProvider.DEEPSEEK) throw AiHttpException(503, "DeepSeek")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(listOf(AiProvider.DEEPSEEK, AiProvider.GEMINI), calls)
        assertFalse(settings.toString().contains("fake-d"))
    }
    @Test fun customModelAndPromptReachRequestWithoutPuttingKeyInBody() {
        val settings = AiSettings(deepseekModel = "user-model", deepseekKey = "fake-secret", prompt = "保留陪同人數")
        val prompt = analysisPrompt(settings, LocationTerms(listOf(LocationTerm("北院", "北區醫院"))), "北院接客")
        val body = AiBookingParser.deepseekRequest("北院接客", settings, prompt)
        assertEquals("user-model", body.getString("model"))
        assertTrue(body.toString().contains("保留陪同人數"))
        assertTrue(body.toString().contains("北區醫院"))
        assertFalse(body.toString().contains("fake-secret"))
    }
    @Test fun longestAliasWinsWithoutCascadingOrChangingLatinWords() {
        val terms = LocationTerms(listOf(LocationTerm("北院", "甲医院"), LocationTerm("北院二館", "乙医院"), LocationTerm("甲医院", "不應套用"), LocationTerm("NTU", "臺大醫院")))
        assertEquals("乙医院 → 甲医院 / 臺大醫院 / NTUH", terms.expand("北院二館 → 北院 / ntu / NTUH"))
        assertEquals("北院二館 → 乙医院；北院 → 甲医院", terms.comment("北院二館到北院"))
    }
    @Test fun fullNamesContainingAnAliasAreNotExpandedAgain() {
        val terms = LocationTerms(listOf(LocationTerm("台大", "台大醫院")))
        assertEquals("台大醫院 → 台大醫院", terms.expand("台大 → 台大醫院"))
        assertEquals("", terms.comment("台大醫院"))
        assertEquals("", LocationTerms().comment("台大"))
    }
    @Test fun sourceAliasesAreRestoredAndAmbiguousMappingsAreNotGuessed() {
        val terms = LocationTerms(listOf(LocationTerm("北院", "北區醫院")))
        assertEquals("北院門口", terms.preserveSource("北區醫院門口", "去北院門口"))
        assertEquals("北區醫院", terms.preserveSource("北區醫院", "去北區醫院"))
        val ambiguous = LocationTerms(listOf(LocationTerm("北院", "北區醫院"), LocationTerm("北醫", "北區醫院")))
        assertEquals("北區醫院", ambiguous.preserveSource("北區醫院", "北院到北醫"))
    }
    @Test fun conflictingAliasesCannotBeSaved() {
        try { LocationTerms(listOf(LocationTerm("NTU", "甲"), LocationTerm("ntu", "乙"))).validate(); fail("duplicate alias") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun routeLookupUsesExpandedNameAndChangedMappingDoesNotReuseOldPlace() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"places":[{"id":"a","displayName":{"text":"甲院"}}]}"""))
            server.enqueue(MockResponse().setBody("""{"places":[{"id":"b","displayName":{"text":"乙院"}}]}"""))
            var terms = LocationTerms(listOf(LocationTerm("北院", "甲院地址")))
            val client = GoogleRouteClient("fake", placesUrl = server.url("/places").toString(), locationTerms = { terms })
            val raw = "北院"
            assertEquals("a", client.resolve(raw, "") { _, _ -> error("one result") }.id)
            assertEquals("甲院地址", JSONObject(server.takeRequest().body.readUtf8()).getString("textQuery"))
            terms = LocationTerms(listOf(LocationTerm("北院", "乙院地址")))
            assertEquals("b", client.resolve(raw, "") { _, _ -> error("one result") }.id)
            assertEquals("乙院地址", JSONObject(server.takeRequest().body.readUtf8()).getString("textQuery"))
            assertEquals("北院", raw)
        }
    }
    @Test fun senderRulesSupportNamesWithPipeAndRejectIncompleteRows() {
        assertEquals(1, SenderAlertRules.validate(listOf(SenderAlertRule("群組 | A", "司機"), SenderAlertRule(" 群組 | A ", "司機"))).size)
        try { SenderAlertRules.validate(listOf(SenderAlertRule("群組", ""))); fail("incomplete") }
        catch (_: IllegalArgumentException) { }
    }
}
