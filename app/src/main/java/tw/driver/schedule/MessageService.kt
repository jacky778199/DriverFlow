package tw.driver.schedule

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MessageService : Service() {
    private var connection: LineFlowConnection? = null
    @Volatile private var connectionVersion = 0
    private val store by lazy { MessageStore.get(this) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
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
        }, alert = { msg ->
            CoroutineScope(Dispatchers.IO).launch {
                val entry = InsertionAnalysis.analyze(applicationContext, settings.source, msg)
                val result = entry.result
                if (result != null) {
                    store.setFeasible(settings.source, msg.seq, result.feasible)
                    notifyEvaluation(msg, settings.source, result)
                }
            }
        }, fatal = {
            runCatching {
                val prefs = MessageSettingsStore(this)
                val current = prefs.load()
                if (current.source == settings.source && current.token == settings.token) prefs.save(LineFlowSettings(current.endpoint, current.token, current.instance, false))
            }
            stopSelf()
        }, beforeStart = { store.select(settings.source) }, keywords = { store.keywords.value },
            readyChanged = { if (version == connectionVersion) store.canSend.value = it }, sendChanged = store::updateOutgoing,
            received = { msg ->
                if (version == connectionVersion && SenderAlertRules.matches(msg, store.senderAlertRules.value)) notifySenderAlert(msg)
            })
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
    private fun notifySenderAlert(message: LineMessage) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(this, SENDER_ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("${message.chat} · ${message.sender}")
            .setContentText(message.content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.content))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending()).setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java)
            .notify("sender:${message.instance}:${message.seq}", 12, notification) }
    }
    private fun notifyEvaluation(message: LineMessage, source: String, result: InsertionResult) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val detail = "${result.case.pickup} → ${result.case.destination}\n${result.summary}"
        val notification = NotificationCompat.Builder(this, EVALUATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("${if (result.feasible) "✅ 可插單" else "❌ 無法插單"} · ${message.keywords.joinToString(" / ")}")
            .setContentText("${message.chat} · ${message.sender}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${message.chat} · ${message.sender}\n$detail"))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setContentIntent(pending()).setAutoCancel(true)
            .build()
        store.postIfUnread(source, message) {
            runCatching { getSystemService(NotificationManager::class.java).notify("evaluation:${message.instance}:${message.seq}", 11, notification) }
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
        private const val NOTIFICATION_GROUP = "message_notifications"
        fun ensureNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannelGroup(NotificationChannelGroup(NOTIFICATION_GROUP, "Message 通知與提醒"))
            manager.createNotificationChannel(NotificationChannel(CONNECTION_CHANNEL, "Message 背景接收", NotificationManager.IMPORTANCE_LOW).apply { group = NOTIFICATION_GROUP; setShowBadge(false) })
            // Channel badge settings cannot be changed after creation. Migrate the old foreground channel.
            manager.deleteNotificationChannel("lineflow_connection")
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "搶單關鍵字提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                group = NOTIFICATION_GROUP
                description = "依設定 Tab 6 的關鍵字提醒"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            manager.createNotificationChannel(NotificationChannel(SENDER_ALERT_CHANNEL, "指定群組與發送者提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                group = NOTIFICATION_GROUP
                description = "符合設定 Tab 6 的群組名稱及發送者時提醒"
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            })
            val kookaburraSoundUri = Uri.parse("android.resource://${context.packageName}/${R.raw.kookaburra}")
            val audioAttr = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            manager.createNotificationChannel(NotificationChannel(EVALUATION_CHANNEL, "插單評估結果", NotificationManager.IMPORTANCE_HIGH).apply {
                group = NOTIFICATION_GROUP
                description = "自動評估完成時播放 kookaburra 聲音"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 200, 100, 300)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setSound(kookaburraSoundUri, audioAttr)
            })
        }

        const val OPEN_MESSAGE = "open_message"
        const val ALERT_CHANNEL = "lineflow_orders_v1"
        const val SENDER_ALERT_CHANNEL = "lineflow_sender_alert_v1"
        const val EVALUATION_CHANNEL = "lineflow_evaluation_kookaburra_v1"
        private const val CONNECTION_CHANNEL = "lineflow_connection_no_badge_v2"
        private const val STOP = "tw.driver.schedule.STOP_MESSAGE"
        fun start(context: Context) { ContextCompat.startForegroundService(context, Intent(context, MessageService::class.java)) }
        fun stop(context: Context) { context.startService(Intent(context, MessageService::class.java).setAction(STOP)) }
    }
}
