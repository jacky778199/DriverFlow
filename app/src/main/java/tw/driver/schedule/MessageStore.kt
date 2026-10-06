package tw.driver.schedule

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class MessageSettingsStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "lineflow-settings"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("lineflow-settings", null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("lineflow-settings", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun load(): LineFlowSettings {
        if (!file.baseFile.exists()) return LineFlowSettings()
        val bytes = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val settings = LineFlowSettings(json.getString("endpoint"), json.getString("token"), json.getString("instance"), json.getBoolean("enabled"))
        return settings
    }
    fun save(settings: LineFlowSettings) {
        val json = JSONObject().put("endpoint", settings.endpoint).put("token", settings.token)
            .put("instance", settings.instance).put("enabled", settings.enabled)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

class MessageStore private constructor(context: Context) : SQLiteOpenHelper(context,
    File(context.noBackupFilesDir, "lineflow.db").absolutePath, null, 3) {
    private val prefs = context.getSharedPreferences("message_rules", Context.MODE_PRIVATE)
    private val notificationManager = context.getSystemService(android.app.NotificationManager::class.java)
    private val _keywords = MutableStateFlow(runCatching {
        prefs.getString("keywords", null)?.let { raw ->
            JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } }
                .let { saved -> if (saved == listOf("即時可等", "報分", "跳表", "自費", "+300", "+400")) MessageKeywords.defaults else saved }
        } ?: MessageKeywords.defaults
    }.getOrDefault(MessageKeywords.defaults))
    val keywords = _keywords.asStateFlow()
    private val _senderAlertRules = MutableStateFlow(runCatching {
        prefs.getString("sender_alert_rules", null)?.let { raw ->
            JSONArray(raw).let { array -> List(array.length()) { index ->
                array.getJSONObject(index).let { SenderAlertRule(it.getString("chat"), it.getString("sender")) }
            } }
        } ?: emptyList()
    }.getOrDefault(emptyList()))
    val senderAlertRules = _senderAlertRules.asStateFlow()
    val canSend = MutableStateFlow(false)
    private val _outgoing = MutableStateFlow<List<OutgoingMessage>>(emptyList())
    val outgoing = _outgoing.asStateFlow()
    private var recovered = false
    private val _messages = MutableStateFlow<List<LineMessage>>(emptyList())
    val messages = _messages.asStateFlow()
    private val _unread = MutableStateFlow(0)
    val unread = _unread.asStateFlow()
    private val _feasibleUnread = MutableStateFlow(0)
    val feasibleUnread = _feasibleUnread.asStateFlow()
    private val _evaluationStates = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val evaluationStates = _evaluationStates.asStateFlow()
    val status = MutableStateFlow("未連線")
    val active = MutableStateFlow(false)
    private var source = ""
    @Synchronized fun currentSource() = source
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (source TEXT NOT NULL, seq INTEGER NOT NULL, instance TEXT NOT NULL, chat TEXT NOT NULL, sender TEXT NOT NULL, content TEXT NOT NULL, time TEXT NOT NULL, timestamp INTEGER NOT NULL, unread INTEGER NOT NULL, feasible INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(source, seq))")
        db.execSQL("CREATE TABLE cursors (source TEXT PRIMARY KEY, seq INTEGER NOT NULL, initialized INTEGER NOT NULL)")
        createOutbox(db)
    }
    private fun createOutbox(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE outgoing (id TEXT PRIMARY KEY, source TEXT NOT NULL, action_key TEXT NOT NULL, target TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX outgoing_action ON outgoing(source,action_key,created)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createOutbox(db)
        if (oldVersion < 3) db.execSQL("ALTER TABLE messages ADD COLUMN feasible INTEGER NOT NULL DEFAULT 0")
    }
    @Synchronized fun migrateSource(old: String, new: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("UPDATE OR IGNORE messages SET source=? WHERE source=?", arrayOf(new, old))
            db.execSQL("INSERT OR IGNORE INTO cursors(source,seq,initialized) SELECT ?,seq,initialized FROM cursors WHERE source=?", arrayOf(new, old))
            db.execSQL("UPDATE cursors SET seq=MAX(seq,COALESCE((SELECT seq FROM cursors WHERE source=?),0)),initialized=MAX(initialized,COALESCE((SELECT initialized FROM cursors WHERE source=?),0)) WHERE source=?", arrayOf(old, old, new))
            db.execSQL("UPDATE outgoing SET source=? WHERE source=?", arrayOf(new, old))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    @Synchronized fun select(key: String) {
        source = key
        if (!recovered) {
            writableDatabase.execSQL("UPDATE outgoing SET state='unknown',detail='App 曾中斷，請確認 LINE' WHERE state IN ('queued','sending')")
            recovered = true
        }
        refresh(); refreshOutgoing()
    }
    @Synchronized fun saveKeywords(text: String) {
        val list = MessageKeywords.parseSettings(text)
        check(prefs.edit().putString("keywords", JSONArray(list).toString()).commit()) { "關鍵字儲存失敗" }
        _keywords.value = list
        refresh()
    }
    @Synchronized internal fun reloadSyncedRules() {
        _keywords.value = prefs.getString("keywords", null)?.let { raw ->
            val array = JSONArray(raw)
            List(array.length()) { array.getString(it) }
        } ?: MessageKeywords.defaults
        _senderAlertRules.value = prefs.getString("sender_alert_rules", null)?.let { raw ->
            val array = JSONArray(raw)
            SenderAlertRules.validate(List(array.length()) { i -> array.getJSONObject(i).let {
                SenderAlertRule(it.getString("chat"), it.getString("sender"))
            } })
        } ?: emptyList()
        refresh()
    }
    @Synchronized fun reserve(request: OutgoingMessage, retryId: String?): OutgoingMessage {
        require(source.isNotBlank() && request.source == source) { "連線來源已變更，請重新開啟操作" }
        val old = readableDatabase.rawQuery("SELECT * FROM outgoing WHERE source=? AND action_key=? ORDER BY created DESC,rowid DESC LIMIT 1", arrayOf(source, request.actionKey)).use { if (it.moveToFirst()) it.outgoingMessage() else null }
        if (old != null && !(retryId == old.id && old.retryable)) return old
        writableDatabase.execSQL("INSERT INTO outgoing VALUES(?,?,?,?,?,?,?,?)", arrayOf(request.id, source, request.actionKey, request.target, request.text, request.state, request.detail, request.created))
        refreshOutgoing()
        return request
    }
    @Synchronized fun updateOutgoing(id: String, state: String, detail: String = "") {
        writableDatabase.execSQL("UPDATE outgoing SET state=?,detail=? WHERE id=?", arrayOf(state, detail, id))
        refreshOutgoing()
    }
    private fun android.database.Cursor.outgoingMessage() = OutgoingMessage(getString(0), getString(1), getString(2), getString(3), getString(4), getString(5), getString(6), getLong(7))
    private fun refreshOutgoing() {
        _outgoing.value = readableDatabase.rawQuery("SELECT * FROM outgoing WHERE source=? ORDER BY created DESC,rowid DESC LIMIT 50", arrayOf(source)).use { c -> buildList { while (c.moveToNext()) add(c.outgoingMessage()) } }
    }
    fun persistence(key: String) = object : LineFlowPersistence {
        override fun cursor(): Long = checkpoint(key).first
        override fun initialized(): Boolean = checkpoint(key).second
        override fun save(messages: List<LineMessage>, cursor: Long?, initialized: Boolean) = insert(key, messages, cursor, initialized)
    }
    @Synchronized private fun checkpoint(key: String): Pair<Long, Boolean> = readableDatabase.rawQuery("SELECT seq, initialized FROM cursors WHERE source=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getLong(0) to (it.getInt(1) == 1) else 0L to false
    }
    @Synchronized private fun insert(key: String, messages: List<LineMessage>, cursor: Long?, initialized: Boolean): List<LineMessage> {
        val db = writableDatabase
        val fresh = mutableListOf<LineMessage>()
        db.beginTransaction()
        try {
            messages.forEach { m ->
                val values = ContentValues().apply {
                    put("source", key); put("seq", m.seq); put("instance", m.instance); put("chat", m.chat)
                    put("sender", m.sender); put("content", m.content); put("time", m.time); put("timestamp", m.timestamp); put("unread", if (m.unread) 1 else 0)
                }
                if (db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) fresh.add(m)
            }
            if (cursor != null) {
                val old = checkpoint(key)
                db.execSQL("INSERT OR REPLACE INTO cursors(source,seq,initialized) VALUES(?,?,?)", arrayOf(key, maxOf(cursor, old.first), if (initialized || old.second) 1 else 0))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (source == key) refresh()
        return fresh
    }
    @Synchronized fun markRead(seqs: List<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            seqs.forEach { db.execSQL("UPDATE messages SET unread=0 WHERE source=? AND seq=?", arrayOf(source, it)) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        refresh()
    }
    @Synchronized fun markAllRead(throughSeq: Long) {
        writableDatabase.execSQL("UPDATE messages SET unread=0 WHERE source=? AND seq<=?", arrayOf(source, throughSeq))
        refresh()
    }
    @Synchronized fun saveSenderAlertRules(text: String) = saveSenderAlertRules(SenderAlertRules.parse(text))
    @Synchronized fun saveSenderAlertRules(input: List<SenderAlertRule>) {
        val rules = SenderAlertRules.validate(input)
        val json = JSONArray().apply { rules.forEach { put(JSONObject().put("chat", it.chat).put("sender", it.sender)) } }
        check(prefs.edit().putString("sender_alert_rules", json.toString()).commit()) { "提醒規則儲存失敗" }
        _senderAlertRules.value = rules
    }
    @Synchronized fun setFeasible(key: String, seq: Long, feasible: Boolean) {
        writableDatabase.execSQL("UPDATE messages SET feasible=? WHERE source=? AND seq=?", arrayOf(if (feasible) 1 else -1, key, seq))
        if (source == key) refresh()
    }
    @Synchronized fun postIfUnread(key: String, message: LineMessage, post: () -> Unit) {
        if (source != key || MessageKeywords.match(message.content, keywords.value).isEmpty()) return
        val unread = readableDatabase.rawQuery("SELECT unread FROM messages WHERE source=? AND seq=?", arrayOf(key, message.seq.toString())).use { it.moveToFirst() && it.getInt(0) == 1 }
        if (unread) post()
    }
    @Synchronized private fun refresh() {
        _messages.value = readableDatabase.rawQuery("SELECT seq,instance,chat,sender,content,time,timestamp,unread FROM messages WHERE source=? ORDER BY seq DESC LIMIT 500", arrayOf(source)).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val matched = MessageKeywords.match(cursor.getString(4), keywords.value)
                add(LineMessage(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getLong(6), matched, unread = cursor.getInt(7) == 1 && matched.isNotEmpty()))
            } }
        }
        _unread.value = readableDatabase.rawQuery("SELECT content FROM messages WHERE source=? AND unread=1", arrayOf(source)).use { c -> var count = 0; while (c.moveToNext()) if (MessageKeywords.match(c.getString(0), keywords.value).isNotEmpty()) count++; count }
        _feasibleUnread.value = readableDatabase.rawQuery("SELECT COUNT(*) FROM messages WHERE source=? AND unread=1 AND feasible=1", arrayOf(source)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        _evaluationStates.value = readableDatabase.rawQuery("SELECT seq,feasible FROM messages WHERE source=? AND feasible!=0 ORDER BY seq DESC LIMIT 500", arrayOf(source)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getLong(0), c.getInt(1) == 1) }
        }
        val unreadTags = readableDatabase.rawQuery("SELECT instance,seq,content FROM messages WHERE source=? AND unread=1", arrayOf(source)).use { c -> buildSet {
            while (c.moveToNext()) if (MessageKeywords.match(c.getString(2), keywords.value).isNotEmpty()) add("${c.getString(0)}:${c.getLong(1)}")
        } }
        notificationManager.activeNotifications.filter { it.notification.channelId in setOf(MessageService.ALERT_CHANNEL, MessageService.EVALUATION_CHANNEL) && it.tag?.removePrefix("evaluation:") !in unreadTags }
            .forEach { notificationManager.cancel(it.tag, it.id) }
    }
    companion object {
        @Volatile private var instance: MessageStore? = null
        fun get(context: Context): MessageStore = instance ?: synchronized(this) { instance ?: MessageStore(context.applicationContext).also { instance = it } }
    }
}
