package tw.driver.schedule

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.text.Normalizer

data class LineMessage(
    val seq: Long, val instance: String, val chat: String, val sender: String,
    val content: String, val time: String, val timestamp: Long,
    val keywords: List<String> = MessageKeywords.match(content), val unread: Boolean = false
)

object MessageKeywords {
    val defaults = listOf("即時可等", "即時", "報分", "跳表", "自費", "+300", "+400")
    private fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .replace(Regex("[\\s\\p{Z}\\u200B\\uFEFF]+"), "")
    fun parseSettings(text: String): List<String> {
        val result = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(::normalize)
        require(result.size <= 50 && result.all { it.length <= 40 }) { "最多 50 個關鍵字，每個最多 40 字" }
        require(result.all { normalize(it).isNotEmpty() }) { "關鍵字不可只包含空白" }
        return result
    }
    fun match(text: String, keywords: List<String> = defaults): List<String> {
        val normalized = normalize(text)
        val matched = keywords.filter { raw ->
            val keyword = normalize(raw)
            if (keyword.matches(Regex("\\+[0-9]+"))) Regex(Regex.escape(keyword) + "(?![0-9])").containsMatchIn(normalized)
            else keyword.isNotEmpty() && normalized.contains(keyword)
        }
        return if (matched.any { normalize(it) == "即時可等" }) matched.filterNot { normalize(it) == "即時" } else matched
    }
}

// Deliberately not a data class: toString must never expose the token.
class LineFlowSettings(val endpoint: String = DEFAULT_ENDPOINT,
    val token: String = "", val instance: String = "instance1", val enabled: Boolean = false) {
    companion object {
        const val DEFAULT_ENDPOINT = "wss://your-server.example.com/ws/lineflow"
    }
    val source: String get() = "$endpoint|$instance"
    fun url(allowCleartext: Boolean = false): String {
        require(endpoint.startsWith("wss://") || (allowCleartext && endpoint.startsWith("ws://"))) { "請使用 wss:// 加密位址" }
        val http = endpoint.replaceFirst("wss://", "https://").replaceFirst("ws://", "http://").toHttpUrlOrNull()
        require(http != null && http.username.isEmpty() && http.password.isEmpty() && http.query == null && http.fragment == null) { "位址不可包含 Token、帳密或查詢參數" }
        require(http.encodedPath == "/ws/lineflow") { "位址路徑必須是 /ws/lineflow" }
        require(token.isNotBlank() && instance.isNotBlank()) { "請輸入 Token 與 instance_id" }
        require(http.scheme == "https" || (allowCleartext && http.host in listOf("localhost", "127.0.0.1", "::1"))) { "請使用 wss:// 加密位址" }
        return http.toString()
    }
}

sealed interface LineFlowEvent {
    data object AuthOk : LineFlowEvent
    data object AuthFail : LineFlowEvent
    class ScreenshotResult(val requestId: String, val image: ServerScreenshot?, val error: String) : LineFlowEvent
    data class SendResult(val requestId: String, val success: Boolean, val error: String) : LineFlowEvent
    data class Batch(val since: Long, val messages: List<LineMessage>) : LineFlowEvent
    data class New(val message: LineMessage) : LineFlowEvent
    data object Pong : LineFlowEvent
    data object Ignored : LineFlowEvent
}

object LineFlowProtocol {
    fun auth(settings: LineFlowSettings): String = JSONObject().put("action", "auth").put("token", settings.token).put("instance_id", settings.instance).toString()
    fun send(request: OutgoingMessage): String {
        require(request.target.isNotBlank() && request.text.isNotBlank()) { "回報對象與內容不可空白" }
        return JSONObject().put("action", "send_message").put("request_id", request.id)
            .put("target", request.target).put("message", request.text).toString()
    }
    fun sync(seq: Long) = JSONObject().put("action", "sync").put("since_seq_id", seq).toString()
    fun parse(text: String, instance: String, keywords: List<String> = MessageKeywords.defaults): LineFlowEvent {
        val json = JSONObject(text)
        fun message(obj: JSONObject): LineMessage {
            require(obj.getString("instance_id") == instance) { "來源實例不符" }
            val seq = obj.getLong("seq_id")
            require(seq > 0)
            return LineMessage(seq, instance, obj.optString("chat_name"), obj.optString("sender_name"),
                obj.getString("content"), obj.optString("msg_time"), obj.optLong("timestamp"), MessageKeywords.match(obj.getString("content"), keywords))
        }
        return when (json.getString("type")) {
            "auth_ok" -> {
                require(!json.has("instance_id") || json.getString("instance_id") == instance) { "驗證實例不符" }
                LineFlowEvent.AuthOk
            }
            "auth_fail" -> LineFlowEvent.AuthFail
            "send_result" -> {
                require(json.getString("status") in listOf("success", "error"))
                LineFlowEvent.SendResult(json.getString("request_id"), json.getString("status") == "success",
                    if (json.isNull("error_message")) "" else json.optString("error_message").take(300))
            }
            "screenshot_result" -> ScreenshotProtocol.result(json)
            "pong" -> LineFlowEvent.Pong
            "new_message" -> LineFlowEvent.New(message(json.getJSONObject("data")))
            "sync_batch" -> {
                val array = json.getJSONArray("messages")
                require(json.getInt("count") == array.length() && array.length() <= 500)
                LineFlowEvent.Batch(json.getLong("since_seq_id"), List(array.length()) { message(array.getJSONObject(it)) }.sortedBy { it.seq })
            }
            else -> LineFlowEvent.Ignored
        }
    }
}

interface LineFlowPersistence {
    fun cursor(): Long
    fun initialized(): Boolean
    fun save(messages: List<LineMessage>, cursor: Long?, initialized: Boolean = false): List<LineMessage>
}

/** Live events may arrive before a sync page. Only the sync frontier advances until all pages finish. */
class LineFlowSession(private val store: LineFlowPersistence) {
    private var syncing = true
    private var requested = store.cursor()
    private var liveMax = requested
    private val returning = store.initialized()
    fun initialRequest() = LineFlowProtocol.sync(requested)
    data class Result(val alerts: List<LineMessage> = emptyList(), val nextRequest: String? = null,
        val connected: Boolean = false, val received: List<LineMessage> = emptyList())
    fun accept(event: LineFlowEvent, now: Long): Result = when (event) {
        is LineFlowEvent.New -> {
            val message = event.message
            val fresh = if (message.seq <= store.cursor()) emptyList() else store.save(listOf(message.copy(unread = message.keywords.isNotEmpty())), if (syncing) null else message.seq)
            liveMax = maxOf(liveMax, message.seq)
            Result(alerts = fresh.filter { it.keywords.isNotEmpty() }, received = fresh)
        }
        is LineFlowEvent.Batch -> {
            require(syncing && event.since == requested) { "同步回應游標不符" }
            require(event.messages.all { it.seq > requested }) { "同步序號沒有前進" }
            val end = event.messages.maxOfOrNull { it.seq } ?: requested
            val more = event.messages.size == 500
            val fresh = store.save(event.messages.map { it.copy(unread = returning && it.keywords.isNotEmpty() && now - it.timestamp in 0..120) },
                if (more) end else maxOf(end, liveMax), !more)
            requested = end
            syncing = more
            Result(fresh.filter { it.unread }, if (more) LineFlowProtocol.sync(end) else null, !more,
                fresh.filter { returning && now - it.timestamp in 0..120 })
        }
        else -> Result()
    }
}
