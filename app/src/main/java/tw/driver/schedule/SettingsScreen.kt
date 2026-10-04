package tw.driver.schedule

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val appPageLabels = listOf("輸入", "當日排程", "花費", "地圖入口", "設定", "Message")
private val pagesWithSettings = setOf(0, 1, 4, 5)

@Composable internal fun SettingsPageTabs(selected: Int, onSelect: (Int) -> Unit) {
    // Empty pages remain selectable so their settings content is truly blank.
    TabRow(selectedTabIndex = selected) {
        appPageLabels.forEachIndexed { index, label ->
            val hasSettings = index in pagesWithSettings
            val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.30f)
            Tab(selected = selected == index, onClick = { onSelect(index) },
                selectedContentColor = if (hasSettings) MaterialTheme.colorScheme.primary else muted,
                unselectedContentColor = if (hasSettings) MaterialTheme.colorScheme.onSurfaceVariant else muted,
                modifier = Modifier.semantics { contentDescription = "Tab ${index + 1} $label${if (hasSettings) "" else "，目前無設定"}" },
                text = { Text("Tab ${index + 1}", maxLines = 1, softWrap = false, style = MaterialTheme.typography.labelSmall) })
        }
    }
}

@Composable internal fun SettingsScreen(page: Int, darkMode: Boolean, onDarkMode: (Boolean) -> Unit, onSync: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (page in pagesWithSettings) {
            Text("Page ${page + 1} · ${appPageLabels[page]}", style = MaterialTheme.typography.titleLarge)
            when (page) {
                0 -> AiPageSettings()
                1 -> { DailyStartLocationSettings(); PassengerReportSettings() }
                4 -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("深色模式")
                        Switch(checked = darkMode, onCheckedChange = onDarkMode)
                    }
                    SettingsGroup("雲端同步", "登入帳號，將排程與花費同步到其他裝置。") {
                        val user by FirebaseSyncManager.currentUser.collectAsState()
                        Text(user?.email ?: "尚未登入", style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = onSync) { Text(if (user == null) "登入與同步設定" else "管理同步帳號") }
                    }
                    LocationTermsSettings()
                }
                5 -> MessagePageSettings()
            }
        }
    }
}

@Composable private fun DailyStartLocationSettings() {
    val prefs = LocalContext.current.getSharedPreferences("appearance", 0)
    var point by remember { mutableStateOf(prefs.getString("default_start_point", "").orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    Text("預設起始位置", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(point, { point = it.take(200); saved = false }, modifier = Modifier.fillMaxWidth(),
        label = { Text("起始地址或地點") }, singleLine = true)
    Text("每日起點未另行設定時使用此位置；留白則不顯示首趟交通車程。", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { saved = prefs.edit().putString("default_start_point", point.trim()).commit() }) { Text("儲存起始位置") }
    if (saved) Text("已儲存")
}

@Composable private fun PassengerReportSettings() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", 0) }
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(prefs.getString("passenger_report_target", "小明").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    Text("客上／客下回報對象", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(target, { target = it; status = "" }, modifier = Modifier.fillMaxWidth(),
        label = { Text("LINE 聊天室或聯絡人名稱") }, singleLine = true, enabled = !busy)
    Text("點客上／客下會複製字串，並透過 Message 連線回報到此對象。請填寫完整名稱。", style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy && target.isNotBlank(), onClick = {
        busy = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) { prefs.edit().putString("passenger_report_target", target.trim()).commit() }
            if (saved) target = target.trim()
            status = if (saved) "已儲存" else "儲存失敗，請重試"
            busy = false
        }
    }) { Text(if (busy) "儲存中…" else "儲存") }
    if (status.isNotBlank()) Text(status)
    PassengerReportRulesSettings()
}

@Composable private fun MessagePageSettings() {
    val context = LocalContext.current
    val store = remember { MessageStore.get(context) }
    LaunchedEffect(Unit) { MessageService.ensureNotificationChannels(context) }
    val status by store.status.collectAsState()
    val active by store.active.collectAsState()
    var showConnection by remember { mutableStateOf(false) }
    var showKeywords by remember { mutableStateOf(false) }
    var showSenderAlerts by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    SettingsGroup("訊息連線", "連接訊息接收服務，讓 Message 自動接收新訊息。") {
        Text(status, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showConnection = true }) { Text("設定連線與開始接收") }
        if (active) OutlinedButton(onClick = { MessageService.stop(context) }) { Text("停止接收") }
    }
    SettingsGroup("通知與提醒", "選擇哪些訊息需要提醒，以及通知聲音與顯示方式。") {
        val keywords by store.keywords.collectAsState()
        val rules by store.senderAlertRules.collectAsState()
        Text(if (keywords.isEmpty()) "關鍵字提醒：已停用" else "關鍵字：${keywords.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showKeywords = true }) { Text("編輯提醒關鍵字") }
        Text("指定群組與發送者：${rules.size} 組", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showSenderAlerts = true }) { Text("選擇群組與發送者") }
        HorizontalDivider()
        Text("通知聲音與顯示", style = MaterialTheme.typography.titleSmall)
        Text("下列選項會開啟手機的通知設定，可調整聲音、震動及彈出顯示。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }
        }) { Text("允許 App 通知") }
        fun channel(id: String) {
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, id))
        }
        OutlinedButton(onClick = { channel(MessageService.ALERT_CHANNEL) }) { Text("關鍵字提醒的聲音與顯示") }
        OutlinedButton(onClick = { channel(MessageService.SENDER_ALERT_CHANNEL) }) { Text("指定訊息提醒的聲音與顯示") }
        OutlinedButton(onClick = { channel(MessageService.EVALUATION_CHANNEL) }) { Text("插單評估結果的聲音與顯示") }
    }
    if (showConnection) MessageConnectionDialog(onDismiss = { showConnection = false }, onStarted = {
        showConnection = false
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    })
    if (showKeywords) KeywordSettingsDialog { showKeywords = false }
    if (showSenderAlerts) SenderAlertSettingsDialog { showSenderAlerts = false }
}
