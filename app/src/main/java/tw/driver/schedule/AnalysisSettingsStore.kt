package tw.driver.schedule

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class AiSettingsStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ai-settings"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("driver-ai-settings", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("driver-ai-settings", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): AiSettings {
        if (!file.baseFile.exists()) return AiSettings()
        val bytes = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        return decodeAiSettings(json).also { it.validate() }
    }
    @Synchronized fun save(settings: AiSettings) {
        settings.validate()
        val json = JSONObject().put("version", 2).put("provider", settings.provider.name).put("geminiModel", settings.geminiModel).put("geminiKey", settings.geminiKey)
            .put("deepseekModel", settings.deepseekModel).put("deepseekKey", settings.deepseekKey).put("fallback", settings.fallback).put("prompt", settings.prompt)
            .put("messagePrompt", settings.messagePrompt).put("customUrl", settings.customUrl).put("customModel", settings.customModel).put("customKey", settings.customKey)
            .put("fallbackProvider", settings.backup.name)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
        AppSettingsSync.stageAi(context, settings)
    }
}

internal class LocationTermsStore(context: Context) {
    private val prefs = context.getSharedPreferences("location_terms", Context.MODE_PRIVATE)
    fun load(): LocationTerms {
        val array = JSONArray(prefs.getString("items", "[]"))
        return LocationTerms(List(array.length()) { i -> array.getJSONObject(i).let { LocationTerm(it.getString("short"), it.getString("full")) } }).also { it.validate() }
    }
    fun save(terms: LocationTerms) {
        terms.validate()
        val array = JSONArray(terms.items.map { JSONObject().put("short", it.short).put("full", it.full) })
        check(prefs.edit().putString("items", array.toString()).commit()) { "儲存失敗，請重試" }
    }
}
