package tw.driver.schedule

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun MessageScreen(rides: List<RideOrder>) {
    val context = LocalContext.current
    val store = remember { MessageStore.get(context) }
    val messages by store.messages.collectAsState()
    val unread by store.unread.collectAsState()
    val status by store.status.collectAsState()
    val active by store.active.collectAsState()
    val keywords by store.keywords.collectAsState()
    val outgoing by store.outgoing.collectAsState()
    var showKeywords by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var arrivalMessage by remember { mutableStateOf<LineMessage?>(null) }
    val source = store.currentSource()
    val analyses by InsertionAnalysis.entries.collectAsState()
    val evaluations = analyses.filterKeys { it.first == source }.mapNotNull { (key, value) -> value.result?.let { key.second to it } }.toMap()
    var replyMessage by remember { mutableStateOf<LineMessage?>(null) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { clock = System.currentTimeMillis(); kotlinx.coroutines.delay(15000) } }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var onlyMatches by rememberSaveable { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Message", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showSettings = true }) { Text("連線設定") }
            if (active) TextButton(onClick = { MessageService.stop(context) }) { Text("停止") }
        }
        if (!notifications) {
            Text("通知尚未開啟：仍會高亮訊息，但無法跳出搶單通知。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("開啟通知") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilterChip(selected = onlyMatches, onClick = { onlyMatches = !onlyMatches }, label = { Text("只看關鍵字") })
            TextButton(enabled = unread > 0, onClick = {
                val through = messages.maxOfOrNull { it.seq } ?: 0L
                scope.launch { withContext(Dispatchers.IO) { store.markAllRead(through) } }
            }) { Text("全部已讀 ($unread)") }
        }
        Row {
        TextButton(onClick = { showKeywords = true }) { Text("關鍵字設定") }
        TextButton(onClick = { showHistory = true }) { Text("傳送紀錄") }
        TextButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, MessageService.ALERT_CHANNEL))
        }) { Text("通知設定") }
        }
        Text(if (keywords.isEmpty()) "關鍵字提醒已停用" else keywords.joinToString(" · "), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
        outgoing.firstOrNull()?.let { Text("${it.target}：${it.label}", style = MaterialTheme.typography.labelSmall) }
        val visible = if (onlyMatches) messages.filter { it.keywords.isNotEmpty() } else messages
        if (visible.isEmpty()) {
            Text(if (onlyMatches) "目前沒有符合關鍵字的訊息" else "尚未收到訊息。請設定 Server、Token 與 instance_id，開始接收。", modifier = Modifier.padding(vertical = 24.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(visible, key = { "${it.instance}:${it.seq}" }) { message ->
                val matched = message.keywords.isNotEmpty()
                Card(modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = if (matched) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer),
                    border = if (message.unread) BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary) else null) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("${if (message.unread) "● " else ""}${message.chat.ifBlank { "未知聊天室" }}", fontWeight = FontWeight.Bold)
                        val date = remember(message.timestamp) { runCatching { Instant.ofEpochSecond(message.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM/dd HH:mm:ss")) }.getOrDefault("") }
                        Text("${message.sender} · $date ${message.time}", style = MaterialTheme.typography.labelSmall)
                        if (matched) Text("關鍵字：${message.keywords.joinToString(" · ")}", color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.Bold)
                        SelectionContainer { Text(message.content) }
                        Row {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(message.content)) }) { Text("複製") }
                            if (message.unread) TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { store.markRead(listOf(message.seq)) } } }) { Text("標為已讀") }
                        }
                        if (matched) {
                            val analysis = analyses[source to message.seq]
                            if (analysis != null && analysis.result == null) Text(analysis.status, style = MaterialTheme.typography.bodySmall)
                            val evaluation = evaluations[message.seq]
                            val valid = evaluation?.takeIf { it.isFresh(rides, Instant.ofEpochMilli(clock)) }
                            evaluation?.let {
                                Text(if (valid == null) "評估已過期或排程已變更，請重新評估" else "${it.case.pickup} → ${it.case.destination}\n${it.summary}", style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                Button(colors = ButtonDefaults.buttonColors(containerColor = valid?.let { if (it.feasible) FeasibleGreen else InfeasibleRed } ?: MaterialTheme.colorScheme.secondary,
                                    contentColor = if (valid != null) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.onSecondary), onClick = {
                                    arrivalMessage = message
                                }) { Text("評估可行性") }
                                TextButton(onClick = { replyMessage = message }) { Text("發送訊息") }
                            }
                            outgoing.firstOrNull { it.actionKey.startsWith("eta:${message.seq}:") }?.let { Text(it.label, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
            if (messages.size == 500) item { Text("顯示最新 500 則訊息", style = MaterialTheme.typography.labelSmall) }
        }
    }
    if (showSettings) MessageConnectionDialog(onDismiss = { showSettings = false }, onStarted = {
        showSettings = false
        if (Build.VERSION.SDK_INT >= 33 && !notifications) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    })
    if (showKeywords) KeywordSettingsDialog { showKeywords = false }
    if (showHistory) OutgoingHistoryDialog(onDismiss = { showHistory = false })
    arrivalMessage?.let { message -> InsertionFeasibilityDialog(message, rides, onDismiss = { arrivalMessage = null }, onEvaluated = { InsertionAnalysis.save(source, message.seq, InsertionAnalysisEntry(it.case, it, "評估完成")) }) }
    replyMessage?.let { message ->
        InsertionReplyDialog(message, evaluations[message.seq], analyses[source to message.seq]?.case, source, rides,
            onDismiss = { replyMessage = null })
    }

}

@Composable private fun MessageConnectionDialog(onDismiss: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var endpoint by remember { mutableStateOf(LineFlowSettings().endpoint) }
    var instance by remember { mutableStateOf("instance1") }
    // Never put credentials in saved-instance state or a ViewModel's string representation.
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        runCatching { withContext(Dispatchers.IO) { MessageSettingsStore(context).load() } }
            .onSuccess { endpoint = it.endpoint; instance = it.instance; token = it.token }
            .onFailure { error = "讀取設定失敗，請重新輸入" }
        busy = false
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Message 連線設定") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server WebSocket 位址") }, singleLine = true, enabled = !busy)
            OutlinedTextField(token, { token = it }, label = { Text("Access Token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
            OutlinedTextField(instance, { instance = it }, label = { Text("instance_id") }, singleLine = true, enabled = !busy)
            Text("開始後會在背景接收，並顯示常駐通知。首次歷史同步不跳搶單通知；重新連線僅提醒最近 2 分鐘的補收訊息。", style = MaterialTheme.typography.bodySmall)
            Text("使用 WSS 加密連線，Token 於連線後驗證，不放入網址。", style = MaterialTheme.typography.bodySmall)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(enabled = !busy, onClick = {
            val settings = LineFlowSettings(endpoint.trim().trimEnd('/'), token.trim(), instance.trim(), true)
            try { settings.url() } catch (e: IllegalArgumentException) { error = e.message ?: "設定無效"; return@TextButton }
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { MessageSettingsStore(context).save(settings); MessageStore.get(context).select(settings.source) }
                    MessageService.start(context)
                    onStarted()
                } catch (_: Exception) { error = "無法儲存或啟動接收，請重試" }
                busy = false
            }
        }) { Text(if (busy) "處理中…" else "儲存並開始") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}
