package tw.driver.schedule

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class MessageService : Service() {
    private var connection: LineFlowConnection? = null
    @Volatile private var connectionVersion = 0
    private val store by lazy { MessageStore.get(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CONNECTION_CHANNEL, "Message 背景接收", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        // Channel badge settings cannot be changed after creation. Migrate the old foreground channel.
        manager.deleteNotificationChannel("lineflow_connection")
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "搶單關鍵字提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "依 Message 頁面設定的關鍵字提醒"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    private fun pending() = PendingIntent.getActivity(this, 5,
        Intent(this, MainActivity::class.java).putExtra(OPEN_MESSAGE, true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun ongoing(text: String): Notification = NotificationCompat.Builder(this, CONNECTION_CHANNEL)
        .setSmallIcon(R.drawable.ic_message_notification).setContentTitle("Message 搶單訊息接收")
        .setContentText(text).setContentIntent(pending()).setOngoing(true).setOnlyAlertOnce(true)
        .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
        .addAction(0, "停止接收", PendingIntent.getService(this, 6, Intent(this, MessageService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            runCatching { MessageSettingsStore(this).let { prefs -> val s = prefs.load(); prefs.save(LineFlowSettings(s.endpoint, s.token, s.instance, false)) } }
            store.status.value = "已停止接收"
            stopSelf(); return START_NOT_STICKY
        }
        startForeground(5, ongoing("正在啟動"))
        val settings = runCatching { MessageSettingsStore(this).load() }.getOrElse {
            store.status.value = "無法讀取連線設定，請重新儲存"; stopSelf(); return START_NOT_STICKY
        }
        if (!settings.enabled) { stopSelf(); return START_NOT_STICKY }
        val version = ++connectionVersion
        store.canSend.value = false
        MessageGateway.detach(connection)
        connection?.close()
        store.active.value = true
        connection = LineFlowConnection(settings, store.persistence(settings.source), status = { text ->
            store.status.value = text
            runCatching { getSystemService(NotificationManager::class.java).notify(5, ongoing(text)) }
        }, alert = { InsertionAnalysis.enqueue(applicationContext, settings.source, it); notifyMessage(it, settings.source) }, fatal = {
            runCatching {
                val prefs = MessageSettingsStore(this)
                val current = prefs.load()
                if (current.source == settings.source && current.token == settings.token) prefs.save(LineFlowSettings(current.endpoint, current.token, current.instance, false))
            }
            stopSelf()
        }, beforeStart = { store.select(settings.source) }, keywords = { store.keywords.value },
            readyChanged = { if (version == connectionVersion) store.canSend.value = it }, sendChanged = store::updateOutgoing)
        MessageGateway.attach(settings.source, connection!!)
        connection?.start()
        return START_STICKY
    }
    private fun notifyMessage(message: LineMessage, source: String) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("搶單 · ${message.keywords.joinToString(" / ")}")
            .setContentText("${message.chat} · ${message.sender}：${message.content}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${message.chat} · ${message.sender}\n${message.content}"))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending()).setAutoCancel(true)
            .build()
        store.postIfUnread(source, message) {
            runCatching { getSystemService(NotificationManager::class.java).notify("${message.instance}:${message.seq}", 10, notification) }
        }
    }
    override fun onDestroy() {
        connectionVersion++
        MessageGateway.detach(connection)
        connection?.close(); connection = null
        store.canSend.value = false
        store.active.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val OPEN_MESSAGE = "open_message"
        const val ALERT_CHANNEL = "lineflow_orders_v1"
        private const val CONNECTION_CHANNEL = "lineflow_connection_no_badge_v2"
        private const val STOP = "tw.driver.schedule.STOP_MESSAGE"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, MessageService::class.java)) }
        fun stop(context: Context) { context.startService(Intent(context, MessageService::class.java).setAction(STOP)) }
    }
}
