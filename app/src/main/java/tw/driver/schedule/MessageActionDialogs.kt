package tw.driver.schedule

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.time.Instant

@Composable internal fun KeywordSettingsDialog(onDismiss: () -> Unit) {
    val store = MessageStore.get(LocalContext.current)
    val keywords by store.keywords.collectAsState()
    var text by remember { mutableStateOf(keywords.joinToString("\n")) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("搶單關鍵字") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("每行一個，任一命中就高亮並提醒。清空可停用關鍵字提醒。")
            OutlinedTextField(text, { text = it }, label = { Text("提醒關鍵字（每行一個）") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 260.dp), enabled = !saving)
            TextButton(onClick = { text = MessageKeywords.defaults.joinToString("\n") }, enabled = !saving) { Text("恢復預設") }
            Text("儲存後立即套用到背景收訊與現有清單，不會為舊訊息補發通知。", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            try { withContext(Dispatchers.IO) { store.saveKeywords(text) }; onDismiss() }
            catch (e: Exception) { error = e.message ?: "儲存失敗" }
            finally { saving = false }
        }
    }) { Text("儲存") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

@Composable internal fun SenderAlertSettingsDialog(onDismiss: () -> Unit) {
    val store = MessageStore.get(LocalContext.current)
    val rules by store.senderAlertRules.collectAsState()
    var rows by remember { mutableStateOf(rules) }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("群組與發送者提醒") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("群組與發送者同時符合時就提醒，不需包含關鍵字。請使用 Message 顯示的完整名稱。")
            rows.forEachIndexed { index, rule ->
                OutlinedTextField(rule.chat, { value -> rows = rows.toMutableList().also { it[index] = rule.copy(chat = value) } },
                    label = { Text("第 ${index + 1} 組 · 群組名稱") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                OutlinedTextField(rule.sender, { value -> rows = rows.toMutableList().also { it[index] = rule.copy(sender = value) } },
                    label = { Text("發送者名稱") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index } }, enabled = !saving) { Text("移除此組") }
                HorizontalDivider()
            }
            if (rows.isEmpty()) Text("尚無指定提醒。移除全部並儲存可停用。")
            OutlinedButton(onClick = { rows = rows + SenderAlertRule("", "") }, enabled = !saving && rows.size < 50) { Text("新增一組提醒") }
            Text("忽略前後空白與全半形差異。首次歷史同步不補發通知；重新連線只提醒最近 2 分鐘的新訊息。", style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            try { withContext(Dispatchers.IO) { store.saveSenderAlertRules(rows) }; onDismiss() }
            catch (e: Exception) { error = e.message ?: "儲存失敗" }
            finally { saving = false }
        }
    }) { Text("儲存") } }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}

internal data class SendDraft(val target: String, val text: String, val key: String, val source: String, val retryId: String? = null)

@Composable internal fun ConfirmMessageDialog(draft: SendDraft, onDismiss: () -> Unit, validate: () -> String? = { null }, onSubmitted: (String) -> Unit = {}) {
    val context = LocalContext.current
    val store = MessageStore.get(context)
    val ready by store.canSend.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(if (draft.retryId != null) "確認重新傳送" else "確認回覆訊息") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("回報對象：${draft.target}")
            Text(draft.text, style = MaterialTheme.typography.titleMedium)
            if (draft.retryId != null) Text("若上次結果不確定，請先查看 LINE；重新傳送可能產生重複訊息。")
            if (!ready) Text("尚未連線，請先到 Message 連線設定。", color = MaterialTheme.colorScheme.error)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(enabled = ready && !busy, onClick = {
        val validation = validate()
        if (validation != null) { error = validation; return@TextButton }
        busy = true
        scope.launch {
            try {
                val result = MessageGateway.submit(context, draft.target, draft.text, draft.key, draft.source, draft.retryId)
                onSubmitted(result.id); onDismiss()
            } catch (e: Exception) { error = e.message ?: "未能傳送" }
            finally { busy = false }
        }
    }) { Text(if (busy) "處理中…" else "確認送出") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

@Composable internal fun OutgoingHistoryDialog(onDismiss: () -> Unit, passengerOnly: Boolean = false) {
    val store = MessageStore.get(LocalContext.current)
    val requests by store.outgoing.collectAsState()
    var retry by remember { mutableStateOf<OutgoingMessage?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("傳送紀錄") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val visible = requests.filter { !passengerOnly || it.actionKey.startsWith("passenger:") }
            if (visible.isEmpty()) Text("尚無回報紀錄")
            visible.forEach { item ->
                Text("${item.target}：${item.text}")
                Text(item.label, style = MaterialTheme.typography.bodySmall)
                // ETA is time-sensitive: failed arrival replies must be recalculated, never replayed here.
                if (item.retryable && item.actionKey.startsWith("passenger:")) TextButton(onClick = { retry = item }) { Text("確認後重送") }
                if (item.retryable && item.actionKey.startsWith("eta:")) Text("請回原訊息重新估算後回覆。", style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } })
    retry?.let { request -> ConfirmMessageDialog(SendDraft(request.target, request.text, request.actionKey, request.source, request.id), onDismiss = { retry = null }) }
}



@Composable internal fun InsertionReplyDialog(message: LineMessage, result: InsertionResult?, parsed: InsertionCase?,
    source: String, rides: List<RideOrder>, onDismiss: () -> Unit) {
    var minutes by remember { mutableStateOf(result?.arrival?.minutes?.toString().orEmpty()) }
    var address by remember { mutableStateOf(
        (result?.case?.originalPickup ?: parsed?.originalPickup).orEmpty()
            .takeIf { it.isNotBlank() && message.content.contains(it) } ?: FirstMessageAddress.local(message.content)) }
    var error by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<SendDraft?>(null) }
    if (draft != null) {
        ConfirmMessageDialog(draft!!, onDismiss = { draft = null }, onSubmitted = { onDismiss() })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("發送訊息") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("回覆：${message.chat.ifBlank { "缺少聊天室" }}", style = MaterialTheme.typography.bodySmall)
            if (result == null) Text("尚無計算時間，可自行填寫分鐘數。", style = MaterialTheme.typography.bodySmall)
            else {
                Text("已帶入計算時間：${result.arrival.minutes} 分鐘", style = MaterialTheme.typography.bodySmall)
                if (!result.isFresh(rides)) Text("使用上次計算結果，可修改後送出。", style = MaterialTheme.typography.bodySmall)
                if (!result.feasible) Text(result.summary, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(minutes, { minutes = it; error = "" }, label = { Text("幾分鐘可到") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it; error = "" }, label = { Text("LINE 原文上車地址") }, modifier = Modifier.fillMaxWidth())
            androidx.compose.foundation.text.selection.SelectionContainer {
                Text(message.content, style = MaterialTheme.typography.bodySmall)
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        TextButton(onClick = {
            try {
                require(message.chat.isNotBlank()) { "訊息缺少聊天室，無法決定回覆對象" }
                val body = insertionReplyText(minutes, address, message.content)
                val key = "eta:${message.seq}:${result?.calculatedAt?.toEpochMilli() ?: "manual"}:$body"
                draft = SendDraft(message.chat, body, key, source)
            } catch (e: IllegalArgumentException) { error = e.message.orEmpty() }
        }) { Text("預覽並確認") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

internal fun insertionReplyText(minutes: String, address: String, original: String): String {
    val count = minutes.trim().toLongOrNull()
    require(count != null && count > 0) { "請填寫有效的分鐘數" }
    val rawAddress = address.trim()
    require(rawAddress.isNotBlank() && original.contains(rawAddress)) { "請從 LINE 原文複製上車地址" }
    return "$count 分 可到 $rawAddress"
}
