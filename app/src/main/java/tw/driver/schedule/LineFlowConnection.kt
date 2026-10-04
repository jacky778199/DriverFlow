package tw.driver.schedule

import okhttp3.*
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** All callbacks, database writes and reconnect decisions run on one worker. */
class LineFlowConnection(
    private val settings: LineFlowSettings,
    private val persistence: LineFlowPersistence,
    private val status: (String) -> Unit,
    private val alert: (LineMessage) -> Unit,
    private val fatal: () -> Unit,
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build(),
    private val allowCleartext: Boolean = false,
    private val beforeStart: () -> Unit = {},
    private val keywords: () -> List<String> = { MessageKeywords.defaults },
    private val readyChanged: (Boolean) -> Unit = {},
    private val sendChanged: (String, String, String) -> Unit = { _, _, _ -> },
    private val acknowledgementTimeoutMillis: Long = 60_000,
    private val authTimeoutMillis: Long = 15_000,
    private val received: (LineMessage) -> Unit = {},
    private val screenshotTimeoutMillis: Long = 15_000
) {
    private val worker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var stopped = false
    private var socket: WebSocket? = null
    private var generation = 0
    private var retries = 0
    private var lastReceived = System.nanoTime()
    private var syncSent = 0L
    private var retry: ScheduledFuture<*>? = null
    private var authenticated = false
    private var authTimer: ScheduledFuture<*>? = null
    private var ready = false
    private val pending = mutableMapOf<String, ScheduledFuture<*>?>()
    private class PendingScreenshot(val result: CompletableDeferred<ServerScreenshot>, val timer: ScheduledFuture<*>)
    private val screenshots = mutableMapOf<String, PendingScreenshot>()
    internal suspend fun screenshot(expectedSource: String): ServerScreenshot {
        val id = "screen-${UUID.randomUUID()}"
        val result = CompletableDeferred<ServerScreenshot>()
        try {
            if (stopped) throw IOException("訊息接收服務已停止，請先重新連線")
            worker.execute {
                if (!result.isActive) return@execute
                if (stopped || !ready || expectedSource != settings.source) {
                    result.completeExceptionally(IOException("尚未連線或來源已變更，請先完成 Message 連線"))
                    return@execute
                }
                if (socket?.send(ScreenshotProtocol.request(id)) != true) {
                    result.completeExceptionally(IOException("連線未接受截圖請求，請稍後重試"))
                    return@execute
                }
                val timer = worker.schedule({
                    screenshots.remove(id)?.result?.completeExceptionally(IOException("等待截圖逾時，請重新取得"))
                }, screenshotTimeoutMillis, TimeUnit.MILLISECONDS)
                screenshots[id] = PendingScreenshot(result, timer)
            }
            return result.await()
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            throw IOException("訊息接收服務已停止，請先重新連線")
        } finally {
            result.cancel()
            runCatching { worker.execute { screenshots.remove(id)?.timer?.cancel(false) } }
        }
    }
    private fun disconnectSending() {
        authenticated = false; authTimer?.cancel(false); authTimer = null
        ready = false; readyChanged(false)
        pending.forEach { (id, timer) -> timer?.cancel(false); sendChanged(id, "unknown", "連線中斷，未取得送出確認") }
        pending.clear()
        screenshots.values.forEach { it.timer.cancel(false); it.result.completeExceptionally(IOException("連線中斷，請重新連線後取得截圖")) }
        screenshots.clear()
    }
    fun send(request: OutgoingMessage) {
        if (stopped) { sendChanged(request.id, "not_sent", "接收服務已停止"); return }
        try { worker.execute {
            if (stopped || !ready || request.source != settings.source) { sendChanged(request.id, "not_sent", "尚未連線或來源已變更"); return@execute }
            try {
                val payload = LineFlowProtocol.send(request)
                sendChanged(request.id, "sending", "")
                if (socket?.send(payload) != true) { sendChanged(request.id, "not_sent", "連線未接受訊息"); return@execute }
                pending[request.id] = worker.schedule({
                    if (pending.containsKey(request.id)) { pending[request.id] = null; sendChanged(request.id, "unknown", "等待回執逾時，請確認 LINE") }
                }, acknowledgementTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (_: Exception) { sendChanged(request.id, "unknown", "傳送處理中斷，請確認 LINE") }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { sendChanged(request.id, "not_sent", "接收服務已停止") }
    }
    private fun dispatch(block: () -> Unit) {
        if (!stopped) runCatching { worker.execute { if (!stopped) block() } }
    }
    fun start() = dispatch {
        try { beforeStart() } catch (_: Exception) { fail("本機訊息儲存無法開啟，請檢查儲存空間"); return@dispatch }
        connect()
        worker.scheduleWithFixedDelay({
            if (!stopped && authenticated && socket != null) {
                val now = System.nanoTime()
                if (now - lastReceived > TimeUnit.SECONDS.toNanos(60) || (syncSent != 0L && now - syncSent > TimeUnit.SECONDS.toNanos(60))) reconnect()
                else if (socket?.send("{\"action\":\"ping\"}") == false) reconnect()
            }
        }, 25, 25, TimeUnit.SECONDS)
    }
    private fun fail(message: String) {
        disconnectSending()
        status(message)
        generation++
        socket?.cancel(); socket = null
        retry?.cancel(false)
        fatal()
    }
    private fun connect() {
        val url = try { settings.url(allowCleartext) } catch (_: Exception) { fail("設定錯誤：請檢查位址、Token 與實例"); return }
        val current = ++generation
        val session = try { LineFlowSession(persistence) } catch (_: Exception) { fail("無法讀取同步進度，請檢查儲存空間"); return }
        lastReceived = System.nanoTime(); syncSent = 0
        status(if (retries == 0) "連線中" else "重新連線中")
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = dispatch {
                if (current != generation) return@dispatch
                status("正在驗證")
                authTimer = worker.schedule({ if (current == generation && !authenticated) reconnect() }, authTimeoutMillis, TimeUnit.MILLISECONDS)
                if (!webSocket.send(LineFlowProtocol.auth(settings))) reconnect()
            }
            override fun onMessage(webSocket: WebSocket, text: String) = dispatch {
                if (current != generation) return@dispatch
                try {
                    val event = LineFlowProtocol.parse(text, settings.instance, keywords())
                    lastReceived = System.nanoTime()
                    if (event == LineFlowEvent.AuthFail) { fail("驗證失敗：請檢查 Token 與 instance_id"); return@dispatch }
                    if (!authenticated) {
                        if (event != LineFlowEvent.AuthOk) { fail("驗證流程異常：Server 未回覆 auth_ok"); return@dispatch }
                        authenticated = true; authTimer?.cancel(false); authTimer = null
                        status("正在同步")
                        syncSent = System.nanoTime()
                        if (!webSocket.send(session.initialRequest())) reconnect()
                        return@dispatch
                    }
                    if (event == LineFlowEvent.AuthOk) return@dispatch
                    if (event is LineFlowEvent.ScreenshotResult) {
                        screenshots.remove(event.requestId)?.let { pendingScreenshot ->
                            pendingScreenshot.timer.cancel(false)
                            if (event.image != null) pendingScreenshot.result.complete(event.image)
                            else pendingScreenshot.result.completeExceptionally(IOException(event.error))
                        }
                        return@dispatch
                    }
                    if (event is LineFlowEvent.SendResult && pending.containsKey(event.requestId)) {
                        pending.remove(event.requestId)?.cancel(false)
                        sendChanged(event.requestId, if (event.success) "success" else "error", event.error)
                    }
                    val result = session.accept(event, System.currentTimeMillis() / 1000)
                    result.received.forEach { received(it) }
                    result.alerts.forEach { alert(it) }
                    result.nextRequest?.let {
                        syncSent = System.nanoTime()
                        if (!webSocket.send(it)) reconnect()
                    }
                    if (result.connected) { retries = 0; syncSent = 0; ready = true; readyChanged(true); status("已連線") }
                } catch (_: Exception) {
                    fail("處理失敗：訊息格式或本機儲存異常，請檢查後重新連線")
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = dispatch {
                if (current != generation) return@dispatch
                when (code) {
                    1008 -> fail("驗證失敗：請更新 Token")
                    1003 -> fail("設定錯誤：instance_id 不存在或未啟用")
                    else -> reconnect()
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch {
                if (current == generation) reconnect()
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch {
                if (current != generation) return@dispatch
                when (response?.code) {
                    401, 403 -> fail("驗證失敗：請檢查 Token 與 Server 權限")
                    404 -> fail("設定錯誤：找不到 WebSocket 路徑")
                    else -> reconnect()
                }
            }
        })
    }
    private fun reconnect() {
        disconnectSending()
        generation++
        socket?.cancel(); socket = null; syncSent = 0
        retry?.cancel(false)
        val seconds = minOf(30, 1 shl minOf(retries++, 5))
        status("連線中斷，${seconds} 秒後重試")
        retry = worker.schedule({ if (!stopped) connect() }, seconds * 1000L + Random.nextLong(500), TimeUnit.MILLISECONDS)
    }
    fun close() {
        if (stopped) return
        stopped = true
        worker.execute { disconnectSending(); generation++; retry?.cancel(false); socket?.cancel(); socket = null }
        worker.shutdown()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
