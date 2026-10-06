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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
@Composable fun MessageScreen(rides: List<RideOrder>, isDark: Boolean, onSettings: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MessageStore.get(context) }
    val messages by store.messages.collectAsState()
    val unread by store.unread.collectAsState()
    val evaluationStates by store.evaluationStates.collectAsState()
    val status by store.status.collectAsState()
    val active by store.active.collectAsState()
    val keywords by store.keywords.collectAsState()
    val outgoing by store.outgoing.collectAsState()
    val connected by store.canSend.collectAsState()
    var showScreenshot by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var arrivalMessage by remember { mutableStateOf<LineMessage?>(null) }
    val source = store.currentSource()
    val analyses by InsertionAnalysis.entries.collectAsState()
    val evaluations = analyses.filterKeys { it.first == source }.mapNotNull { (key, value) -> value.result?.let { key.second to it } }.toMap()
    var replyMessage by remember { mutableStateOf<LineMessage?>(null) }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { clock = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
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
        // ── 標題列 ───────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Message", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onSettings) { Text("設定") }
            if (active) TextButton(onClick = { MessageService.stop(context) }) { Text("停止") }
        }

        // ── 通知未開啟警告 ─────────────────────────────────
        if (!notifications) {
            Text("通知尚未開啟：仍會高亮訊息，但無法跳出搶單通知。", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("開啟通知") }
        }

        // ── 過濾列 ───────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = onlyMatches, onClick = { onlyMatches = !onlyMatches }, label = { Text("只看關鍵字") })
            TextButton(enabled = unread > 0, onClick = {
                val through = messages.maxOfOrNull { it.seq } ?: 0L
                scope.launch { withContext(Dispatchers.IO) { store.markAllRead(through) } }
            }) { Text("全部已讀 ($unread)") }
        }

        // ── 功能按鈕列 ─────────────────────────────────────────
        Row {
            OutlinedButton(onClick = { showScreenshot = true }, enabled = connected) { Text("查看截圖") }
            TextButton(onClick = { showHistory = true }) { Text("傳送紀錄") }
        }

        // ── 關鍵字摘要 ─────────────────────────────────────────
        Text(
            if (keywords.isEmpty()) "關鍵字提醒已停用" else keywords.joinToString(" · "),
            maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        outgoing.firstOrNull()?.let { Text("${it.target}：${it.label}", style = MaterialTheme.typography.labelSmall) }

        // ── 訊息列表 ───────────────────────────────────────────
        val visible = if (onlyMatches) messages.filter { it.keywords.isNotEmpty() } else messages
        if (visible.isEmpty()) {
            Text(
                if (onlyMatches) "目前沒有符合關鍵字的訊息" else "尚未收到訊息。請設定 Server、Token 與 instance_id，開始接收。",
                modifier = Modifier.padding(vertical = 24.dp)
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(visible, key = { "${it.instance}:${it.seq}" }) { message ->
                MessageCard(
                    message = message,
                    isDark = isDark,
                    source = source,
                    analyses = analyses,
                    evaluations = evaluations,
                    evaluationStates = evaluationStates,
                    rides = rides,
                    clock = clock,
                    outgoing = outgoing,
                    onCopy = { clipboard.setText(AnnotatedString(message.content)) },
                    onMarkRead = { scope.launch { withContext(Dispatchers.IO) { store.markRead(listOf(message.seq)) } } },
                    onAssess = { arrivalMessage = message },
                    onReply = { replyMessage = message }
                )
            }
            if (messages.size == 500) item { Text("顯示最新 500 則訊息", style = MaterialTheme.typography.labelSmall) }
        }
    }

    // ── 對話框 ─────────────────────────────────────────────────
    if (showScreenshot) MessageScreenshotDialog(source, onDismiss = { showScreenshot = false })
    if (showHistory) OutgoingHistoryDialog(onDismiss = { showHistory = false })
    arrivalMessage?.let { message ->
        InsertionFeasibilityDialog(message, rides, onDismiss = { arrivalMessage = null },
            onEvaluated = {
                InsertionAnalysis.save(source, message.seq, InsertionAnalysisEntry(it.case, it, "評估完成"))
                scope.launch(Dispatchers.IO) { store.setFeasible(source, message.seq, it.feasible) }
            })
    }
    replyMessage?.let { message ->
        InsertionReplyDialog(message, evaluations[message.seq], analyses[source to message.seq]?.case, source, rides,
            onDismiss = { replyMessage = null })
    }
}

// ── 訊息卡片元件 ───────────────────────────────────────────────────
@Composable private fun MessageCard(
    message: LineMessage,
    isDark: Boolean,
    source: String,
    analyses: Map<Pair<String, Long>, InsertionAnalysisEntry>,
    evaluations: Map<Long, InsertionResult>,
    evaluationStates: Map<Long, Boolean>,
    rides: List<RideOrder>,
    clock: Long,
    outgoing: List<OutgoingMessage>,
    onCopy: () -> Unit,
    onMarkRead: () -> Unit,
    onAssess: () -> Unit,
    onReply: () -> Unit,
) {
    val context = LocalContext.current
    val terms = remember { LocationTermsStore(context).load() }
    val matched = message.keywords.isNotEmpty()
    val recent = messageIsRecent(message.timestamp, message.time, clock)
    val feasible = evaluations[message.seq]?.feasible ?: evaluationStates[message.seq]
    val containerColor = when {
        !recent || !matched -> MaterialTheme.colorScheme.surfaceContainer
        feasible == true && isDark -> Color(0xFF173A2A)
        feasible == true -> Color(0xFFE2F4E8)
        feasible == false && isDark -> Color(0xFF4A252A)
        feasible == false -> Color(0xFFFCE9E8)
        isDark -> Color(0xFF30363B)
        else -> Color(0xFFF0F2F4)
    }
    val contentColor = if (isDark) Color(0xFFF4F7F8) else Color(0xFF20272D)
    val borderStroke: BorderStroke? = when {
        recent && matched -> BorderStroke(1.5.dp, when (feasible) {
            true -> if (isDark) Color(0xFF5BCB85) else Color(0xFF348C53)
            false -> if (isDark) Color(0xFFE7858A) else Color(0xFFC0585C)
            null -> if (isDark) Color(0xFF687983) else Color(0xFFAFBBC3)
        })
        recent && message.unread -> BorderStroke(2.dp, MaterialTheme.colorScheme.tertiary)
        else               -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        border = borderStroke
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // ── 聊天室名稱 ────────────────────────────────────
            Text(
                "${if (message.unread) "● " else ""}${message.chat.ifBlank { "未知聊天室" }}",
                fontWeight = FontWeight.Bold
            )

            // ── 發送者 · 台灣時間：優先使用訊息的 Unix timestamp ────────
            Text(
                "${message.sender} · ${messageTimeTaiwan(message.timestamp, message.time)} · ${messageMinutesAgo(message.timestamp, message.time, clock)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // ── 關鍵字標籤 ────────────────────────────────────
            if (matched) {
                Text(
                    "關鍵字：${message.keywords.joinToString(" · ")}",
                    color = if (isDark) Color(0xFFD8E4E9) else Color(0xFF40515C),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            // ── 正文 + 按鈕：左右兩欄 ─────────────────────────────
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // 左側：訊息內容
                SelectionContainer(Modifier.weight(1f).padding(end = 8.dp)) {
                    Column {
                        Text(message.content, style = MaterialTheme.typography.bodyMedium)
                        LocationTermComment(message.content)
                    }
                }
                // 右側：動作按鈕群（垂直排列，緊湊）
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    TextButton(
                        onClick = onCopy,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) { Text("複製", style = MaterialTheme.typography.labelSmall) }
                    if (message.unread) {
                        TextButton(
                            onClick = onMarkRead,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("標為已讀", style = MaterialTheme.typography.labelSmall) }
                    }
                    if (matched) {
                        val evaluation = evaluations[message.seq]
                        val valid = evaluation?.takeIf { it.isFresh(rides, Instant.ofEpochMilli(clock)) }
                        val btnColor = valid?.let { insertionColor(it.level) }
                            ?: MaterialTheme.colorScheme.secondary
                        Button(
                            onClick = onAssess,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = btnColor,
                                contentColor = if (valid != null) { if (btnColor.luminance() > 0.45f) Color.Black else Color.White }
                                    else MaterialTheme.colorScheme.onSecondary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text("評估可行性", style = MaterialTheme.typography.labelSmall) }
                        TextButton(
                            onClick = onReply,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("發送訊息", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }

            // 評估狀態固定放在卡片最下方，失敗原因不會被其他操作資訊蓋過。
            if (matched) {
                outgoing.firstOrNull { it.actionKey.startsWith("eta:${message.seq}:") }?.let {
                    Text(it.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val analysis   = analyses[source to message.seq]
                val evaluation = evaluations[message.seq]
                val valid      = evaluation?.takeIf { it.isFresh(rides, Instant.ofEpochMilli(clock)) }

                // 自動評估進行中/失敗 狀態文字
                if (analysis != null && analysis.result == null) {
                    Text(
                        analysis.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 評估結果摘要
                evaluation?.let {
                    if (valid == null) Text("評估已過期或排程已變更，請重新評估",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else InsertionRouteTimeline(valid, terms, isDark)
                }
            }
        }
    }
}

// ── 連線設定對話框 ──────────────────────────────────────────────
@Composable internal fun MessageConnectionDialog(onDismiss: () -> Unit, onStarted: () -> Unit) {
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
            Text("請向訊息服務管理員取得服務位址、連線金鑰及實例名稱。", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("服務位址（WebSocket）") }, singleLine = true, enabled = !busy)
            OutlinedTextField(token, { token = it }, label = { Text("連線金鑰（Token）") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy)
            OutlinedTextField(instance, { instance = it }, label = { Text("接收實例名稱") }, singleLine = true, enabled = !busy)
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
