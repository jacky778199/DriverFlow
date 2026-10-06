package tw.driver.schedule

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class SyncedPreference(val file: String, val key: String) {
    val id get() = "app_${file}_$key"
}
internal val syncedPreferences = listOf(
    SyncedPreference("appearance", "dark_mode"),
    SyncedPreference("appearance", "default_start_point"),
    SyncedPreference("appearance", "passenger_report_target"),
    SyncedPreference("passenger_report_rules", "items"),
    SyncedPreference("location_terms", "items"),
    SyncedPreference("message_rules", "keywords"),
    SyncedPreference("message_rules", "sender_alert_rules"),
    SyncedPreference("app_ai_settings", "public"))

internal fun publicAiSettings(settings: AiSettings): String = JSONObject()
    .put("provider", settings.provider.name).put("geminiModel", settings.geminiModel)
    .put("deepseekModel", settings.deepseekModel).put("fallback", settings.fallback)
    .put("prompt", settings.prompt).put("messagePrompt", settings.messagePrompt)
    .put("customUrl", settings.customUrl).put("customModel", settings.customModel)
    .put("fallbackProvider", settings.backup.name).toString()

internal fun mergePublicAiSettings(text: String, local: AiSettings): AiSettings {
    val input = JSONObject(text)
    // Only public fields are accepted from the cloud; credentials always come from this device.
    val clean = JSONObject().put("version", 2)
    listOf("provider", "geminiModel", "deepseekModel", "fallback", "prompt", "messagePrompt",
        "customUrl", "customModel", "fallbackProvider").forEach { key ->
        if (input.has(key)) clean.put(key, input.get(key))
    }
    clean.put("geminiKey", local.geminiKey).put("deepseekKey", local.deepseekKey).put("customKey", local.customKey)
    return decodeAiSettings(clean).also { it.validate() }
}

internal object AppSettingsSync {
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()
    val status = MutableStateFlow("登入同一帳號後自動同步設定")
    fun stageAi(context: Context, settings: AiSettings) {
        context.getSharedPreferences("app_ai_settings", Context.MODE_PRIVATE).edit()
            .putString("public", publicAiSettings(settings)).commit()
    }
    fun changed() { _revision.value += 1 }
}

/** One cloud document per preference: edits to unrelated settings do not overwrite each other. */
internal class AccountSettingsSync(private val context: Context, private val uid: String,
    private val firestore: FirebaseFirestore) {
    private val collection = firestore.collection("users").document(uid).collection("settings")
    private val journal = context.getSharedPreferences("settings_pending_$uid", Context.MODE_PRIVATE)
    private val preferences = syncedPreferences.associateWith { context.getSharedPreferences(it.file, Context.MODE_PRIVATE) }
    private val suppressed = mutableMapOf<String, String>()
    private var subscription: ListenerRegistration? = null
    private var active = true
    private var ready = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (active) preferences.entries.firstOrNull { it.value === prefs && it.key.key == key }?.let { (setting, _) ->
            val value = prefs.all[setting.key]
            val encoded = encode(value)
            if (suppressed.remove(setting.id) != encoded) {
                journal.edit().putString(setting.id, encoded).commit()
                if (ready) publish(setting, value, encoded)
            }
        }
    }
    fun start() {
        if (java.io.File(context.noBackupFilesDir, "ai-settings").exists()) {
            runCatching { AppSettingsSync.stageAi(context, AiSettingsStore(context).load()) }
        }
        preferences.values.distinct().forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        subscription = collection.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
            if (!active) return@addSnapshotListener
            if (error != null) {
                AppSettingsSync.status.value = "設定同步失敗，請檢查網路或帳號權限"
                Log.w("AppSettingsSync", "Settings listener failed", error); return@addSnapshotListener
            }
            if (snapshot == null) return@addSnapshotListener
            val documents = snapshot.documents.associateBy { it.id }
            syncedPreferences.forEach { setting ->
                val doc = documents[setting.id]
                if (doc != null && !doc.metadata.hasPendingWrites() && !journal.contains(setting.id)) {
                    runCatching { apply(setting, doc.get("value")) }
                        .onFailure { Log.w("AppSettingsSync", "Invalid remote setting ${setting.id}", it) }
                }
            }
            if (!ready && !snapshot.metadata.isFromCache) {
                ready = true
                AppSettingsSync.status.value = "設定已連線，儲存後自動同步"
                syncedPreferences.forEach { setting ->
                    val pending = journal.getString(setting.id, null)
                    if (pending != null) publish(setting, decode(pending), pending)
                    else if (setting.id !in documents && preferences.getValue(setting).contains(setting.key)) {
                        val value = preferences.getValue(setting).all[setting.key]
                        // A transaction prevents a new device seeding its defaults over an existing account value.
                        val ref = collection.document(setting.id)
                        firestore.runTransaction { transaction ->
                            if (!transaction.get(ref).exists()) transaction.set(ref, mapOf("value" to value))
                            Unit
                        }.addOnFailureListener {
                            AppSettingsSync.status.value = "設定同步失敗，請檢查網路或帳號權限"
                            Log.w("AppSettingsSync", "Initial settings upload failed", it)
                        }
                    }
                }
            }
        }
    }
    private fun publish(setting: SyncedPreference, value: Any?, encoded: String) {
        collection.document(setting.id).set(mapOf("value" to value))
            .addOnSuccessListener {
                if (active && journal.getString(setting.id, null) == encoded) journal.edit().remove(setting.id).commit()
                if (active) AppSettingsSync.status.value = "設定已同步"
            }.addOnFailureListener {
                if (active) AppSettingsSync.status.value = "設定尚未同步，連線恢復後重試"
                Log.w("AppSettingsSync", "Settings upload failed", it)
            }
    }
    private fun apply(setting: SyncedPreference, value: Any?) {
        val prefs = preferences.getValue(setting)
        if (prefs.all[setting.key] == value) return
        require(value == null || value is String || value is Boolean)
        if (setting.key == "dark_mode") require(value == null || value is Boolean)
        else require(value == null || value is String)
        if (value is String) {
            require(value.length <= 60000)
            when (setting.file) {
                "passenger_report_rules" -> passengerReportRulesFromJson(value)
                "location_terms" -> {
                    val array = JSONArray(value)
                    LocationTerms(List(array.length()) { i -> array.getJSONObject(i).let { LocationTerm(it.getString("short"), it.getString("full")) } }).validate()
                }
                "message_rules" -> {
                    val array = JSONArray(value)
                    if (setting.key == "keywords") MessageKeywords.parseSettings(
                        List(array.length()) { array.getString(it) }.joinToString("\n"))
                    else SenderAlertRules.validate(List(array.length()) { i -> array.getJSONObject(i).let {
                        SenderAlertRule(it.getString("chat"), it.getString("sender"))
                    } })
                }
                "app_ai_settings" -> {
                    val merged = mergePublicAiSettings(value, AiSettingsStore(context).load())
                    suppressed[setting.id] = encode(publicAiSettings(merged))
                    AiSettingsStore(context).save(merged)
                    AppSettingsSync.changed()
                    return
                }
            }
        }
        suppressed[setting.id] = encode(value)
        val edit = prefs.edit()
        when (value) { null -> edit.remove(setting.key); is Boolean -> edit.putBoolean(setting.key, value); is String -> edit.putString(setting.key, value) }
        check(edit.commit())
        if (setting.file == "message_rules") MessageStore.get(context).reloadSyncedRules()
        AppSettingsSync.changed()
    }
    fun stop() {
        active = false
        AppSettingsSync.status.value = "登入同一帳號後自動同步設定"
        subscription?.remove()
        preferences.values.distinct().forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    private fun encode(value: Any?) = JSONObject().put("value", value ?: JSONObject.NULL).toString()
    private fun decode(value: String): Any? = JSONObject(value).opt("value").takeUnless { it == JSONObject.NULL }
}
