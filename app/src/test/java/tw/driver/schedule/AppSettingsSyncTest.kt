package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AppSettingsSyncTest {
    @Test fun publicSettingsRoundTripPreservesLocalKeysAndExactPrompts() {
        val remote = AiSettings(provider = AiProvider.DEEPSEEK, geminiKey = "remote-gemini",
            deepseekKey = "remote-deepseek", customKey = "remote-custom", prompt = "排程提示詞",
            messagePrompt = "訊息提示詞", fallback = true)
        val payload = publicAiSettings(remote)
        assertFalse(payload.contains("remote-gemini"))
        assertFalse(payload.contains("remote-deepseek"))
        assertFalse(payload.contains("remote-custom"))
        val local = AiSettings(geminiKey = "local-gemini", deepseekKey = "local-deepseek", customKey = "local-custom")
        val injected = JSONObject(payload).put("geminiKey", "injected").toString()
        val merged = mergePublicAiSettings(injected, local)
        assertEquals(AiProvider.DEEPSEEK, merged.provider)
        assertEquals("local-gemini", merged.geminiKey)
        assertEquals("local-deepseek", merged.deepseekKey)
        assertEquals("local-custom", merged.customKey)
        assertEquals(remote.prompt, merged.prompt)
        assertEquals(remote.messagePrompt, merged.messagePrompt)
        assertEquals(publicAiSettings(remote), publicAiSettings(merged))
    }
    @Test fun onlyUserPreferencesAreSyncedNotConnectionSecretsOrDailyRecords() {
        assertEquals(syncedPreferences.size, syncedPreferences.map { it.id }.distinct().size)
        assertTrue(syncedPreferences.contains(SyncedPreference("passenger_report_rules", "items")))
        assertTrue(syncedPreferences.contains(SyncedPreference("message_rules", "sender_alert_rules")))
        assertFalse(syncedPreferences.any { it.key.contains("token") || it.key.contains("departure") || it.key.contains("password") })
    }
    @Test fun shortcutPrefersFixedTargetsAndDeduplicatesHistoricalGroups() {
        val rules = PassengerReportRules(listOf(PassengerReportRule("王先生", "照護群組"),
            PassengerReportRule("林小姐", "Family"), PassengerReportRule("張先生", "照護群組")))
        val choices = reportTargetSuggestions(rules, listOf(" 照護群組 ", "family", "歷史群組", "", "歷史群組"))
        assertEquals(listOf("照護群組", "Family", "歷史群組"), choices.map { it.target })
        assertEquals(listOf(true, true, false), choices.map { it.fixed })
        assertTrue(choices.first().label.contains("王先生"))
        assertTrue(reportTargetSuggestions(PassengerReportRules(), listOf("", " ")).isEmpty())
    }
}
