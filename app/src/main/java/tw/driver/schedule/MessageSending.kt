package tw.driver.schedule

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

data class OutgoingMessage(val id: String, val source: String, val actionKey: String, val target: String,
    val text: String, val state: String = "queued", val detail: String = "", val created: Long = System.currentTimeMillis()) {
    val label: String get() = when (state) {
        "queued" -> "準備傳送"
        "sending" -> "傳送中，等待 Server 確認"
        "success" -> "Server 確認已送出"
        "error" -> "Server 回報失敗：$detail"
        "not_sent" -> "未送出：$detail"
        else -> "結果不確定，請先確認 LINE 是否收到"
    }
    val retryable get() = state in listOf("error", "not_sent", "unknown")
}

/** One service-owned sender; no screen creates a second socket. Requests are persisted before dispatch. */
object MessageGateway {
    private var source = ""
    private var connection: LineFlowConnection? = null
    @Synchronized fun attach(key: String, value: LineFlowConnection) { source = key; connection = value }
    @Synchronized fun detach(value: LineFlowConnection?) { if (connection === value) { connection = null; source = "" } }
    internal suspend fun screenshot(expectedSource: String): ServerScreenshot {
        val receiver = synchronized(this) { connection.takeIf { source == expectedSource } }
            ?: throw java.io.IOException("尚未連線，請先到設定 → Tab 6 開始接收")
        return receiver.screenshot(expectedSource)
    }
    suspend fun submit(context: Context, target: String, text: String, actionKey: String, expectedSource: String,
        retryId: String? = null): OutgoingMessage = withContext(Dispatchers.IO) {
        require(target.isNotBlank() && text.isNotBlank()) { "回報對象與內容不可空白" }
        val store = MessageStore.get(context)
        val request = OutgoingMessage(UUID.randomUUID().toString(), expectedSource, actionKey, target.trim(), text.trim())
        val reserved = store.reserve(request, retryId)
        if (reserved.id == request.id) {
            val sender = synchronized(this@MessageGateway) { connection.takeIf { source == expectedSource } }
            if (sender == null) store.updateOutgoing(request.id, "not_sent", "請先在 Message 連線")
            else sender.send(request)
        }
        reserved
    }
}
