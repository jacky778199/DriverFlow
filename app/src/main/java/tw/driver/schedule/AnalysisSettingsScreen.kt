package tw.driver.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun SettingsGroup(title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable private fun ApiKeyField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        enabled = enabled, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隱藏" else "顯示") } })
}

@Composable internal fun AiPageSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AiSettingsStore(context) }
    var provider by remember { mutableStateOf(AiProvider.GEMINI) }
    var geminiModel by remember { mutableStateOf("gemini-2.5-flash") }
    var geminiKey by remember { mutableStateOf("") }
    var deepseekModel by remember { mutableStateOf("deepseek-flash") }
    var deepseekKey by remember { mutableStateOf("") }
    var fallback by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf(AiPrompts.booking) }
    var messagePrompt by remember { mutableStateOf(AiPrompts.message) }
    var customUrl by remember { mutableStateOf("") }
    var customModel by remember { mutableStateOf("") }
    var customKey by remember { mutableStateOf("") }
    var backupProvider by remember { mutableStateOf(AiProvider.DEEPSEEK) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val settings = withContext(Dispatchers.IO) { store.load() }
            provider = settings.provider; geminiModel = settings.geminiModel; geminiKey = settings.geminiKey
            deepseekModel = settings.deepseekModel; deepseekKey = settings.deepseekKey; fallback = settings.fallback; prompt = settings.prompt
            messagePrompt = settings.messagePrompt; customUrl = settings.customUrl; customModel = settings.customModel; customKey = settings.customKey
            backupProvider = settings.backup
        } catch (_: Exception) { status = "無法讀取 AI 設定，請重新填寫並儲存" }
        busy = false
    }
    @Composable fun ProviderFields(target: AiProvider) {
        key(target) {
            when (target) {
                AiProvider.GEMINI -> {
                    OutlinedTextField(geminiModel, { geminiModel = it; status = "" }, label = { Text("Gemini 模型名稱") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    ApiKeyField(geminiKey, { geminiKey = it; status = "" }, "Gemini API key", !busy)
                }
                AiProvider.DEEPSEEK -> {
                    OutlinedTextField(deepseekModel, { deepseekModel = it; status = "" }, label = { Text("DeepSeek 模型名稱") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    ApiKeyField(deepseekKey, { deepseekKey = it; status = "" }, "DeepSeek API key", !busy)
                }
                AiProvider.CUSTOM -> {
                    Text("使用 Chat Completions 相容服務；請填入完整 API URL（包含 /chat/completions 等路徑）。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(customUrl, { customUrl = it; status = "" }, label = { Text("完整 API URL（HTTPS）") },
                        placeholder = { Text("https://your-server.example/v1/chat/completions") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(customModel, { customModel = it; status = "" }, label = { Text("模型名稱") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                    ApiKeyField(customKey, { customKey = it; status = "" }, "API key", !busy)
                }
            }
        }
    }
    SettingsGroup("AI 供應商與模型", "輸入與 Message 分析共用此設定。請使用自己的 API key，儲存後即可使用。") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProvider.entries.forEach { option ->
                FilterChip(selected = provider == option, onClick = {
                    provider = option
                    if (backupProvider == option) backupProvider = AiProvider.entries.first { it != option }
                    status = ""
                }, enabled = !busy, label = { Text(option.label) })
            }
        }
        ProviderFields(provider)
        Text("圖片辨識需要所選模型支援圖片。API key 加密儲存在此裝置，不包含在雲端同步或資料匯出。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("暫時連線失敗時使用備援", modifier = Modifier.weight(1f))
            Switch(fallback, { fallback = it; status = "" }, enabled = !busy)
        }
        if (fallback) {
            Text("備援供應商", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AiProvider.entries.filter { it != provider }.forEach { option ->
                    FilterChip(selected = backupProvider == option, onClick = { backupProvider = option; status = "" }, enabled = !busy, label = { Text(option.label) })
                }
            }
            ProviderFields(backupProvider)
            Text("僅在逾時、服務暫時無法使用或流量限制時切換；備援也需要 API key。", style = MaterialTheme.typography.bodySmall)
        }
    }
    SettingsGroup("輸入辨識 Prompt", "直接編輯完整判讀提示詞，儲存後取代預設 Prompt，套用到 Page 1 輸入。") {
        OutlinedTextField(prompt, { prompt = it; status = "" }, label = { Text("完整 Prompt") },
            minLines = 8, maxLines = 14, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("App 會附上必要的 JSON 欄位格式及地點對照資料，以便解析結果。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { prompt = AiPrompts.booking; status = "已恢復預設 Prompt，請儲存套用" }, enabled = !busy) { Text("恢復預設值") }
    }
    SettingsGroup("Message 分析 Prompt", "直接編輯完整提示詞，套用到 Page 6 的插單分析。{today} 會自動代入今天日期。") {
        OutlinedTextField(messagePrompt, { messagePrompt = it; status = "" }, label = { Text("完整 Prompt") },
            minLines = 6, maxLines = 12, enabled = !busy, modifier = Modifier.fillMaxWidth())
        Text("App 會附上此任務必要的 JSON 欄位格式，並檢查地點是否來自原文。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { messagePrompt = AiPrompts.message; status = "已恢復預設 Prompt，請儲存套用" }, enabled = !busy) { Text("恢復預設值") }
    }
    Button(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            try {
                val settings = AiSettings(provider, geminiModel.trim(), geminiKey.trim(), deepseekModel.trim(), deepseekKey.trim(), fallback, prompt.trim(),
                    messagePrompt.trim(), customUrl.trim(), customModel.trim(), customKey.trim(), backupProvider)
                require(!fallback || settings.key(settings.backup).isNotBlank()) { "啟用備援需填寫另一家供應商的 API key" }
                withContext(Dispatchers.IO) { store.save(settings) }
                InsertionAnalysis.invalidate()
                status = if (settings.ready) "已儲存，AI 設定已套用" else "已儲存；請填寫所選供應商的 API key 才能使用 AI"
            } catch (e: Exception) { status = e.message ?: "儲存失敗，請重試" }
            finally { busy = false }
        }
    }) { Text(if (busy) "處理中…" else "儲存 AI 設定") }
    if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
}

@Composable internal fun LocationTermsSettings() {
    val context = LocalContext.current
    val store = remember { LocationTermsStore(context) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<LocationTerm>()) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { rows = withContext(Dispatchers.IO) { store.load().items } }
        catch (_: Exception) { status = "讀取對照表失敗，請重新設定" }
        busy = false
    }
    SettingsGroup("地點簡稱對照表", "把常見簡稱對應到完整地點或地址，供輸入與 Message 分析、路線查詢使用。") {
        Text("例如：簡稱「台大」對應到你實際使用的院區完整名稱或地址。請自行新增對照。原文保留，畫面只補上簡稱轉換註記。", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            Text("原文簡稱", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Text("完整名稱／地址", modifier = Modifier.weight(1.6f), style = MaterialTheme.typography.labelLarge)
        }
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(row.short, { value -> rows = rows.toMutableList().also { it[index] = row.copy(short = value) }; status = "" },
                    modifier = Modifier.weight(1f), singleLine = true, enabled = !busy)
                OutlinedTextField(row.full, { value -> rows = rows.toMutableList().also { it[index] = row.copy(full = value) }; status = "" },
                    modifier = Modifier.weight(1.6f), minLines = 1, maxLines = 3, enabled = !busy)
            }
            TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index }; status = "" }, enabled = !busy) { Text("刪除第 ${index + 1} 組") }
        }
        if (rows.isEmpty()) Text("尚無對照，分析會使用原文地點。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { rows = rows + LocationTerm("", ""); status = "" }, enabled = !busy && rows.size < 100) { Text("新增地點對照") }
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val terms = LocationTerms(rows.map { LocationTerm(it.short.trim(), it.full.trim()) })
                    withContext(Dispatchers.IO) { store.save(terms) }
                    rows = terms.items
                    InsertionAnalysis.invalidate()
                    status = "已儲存，後續分析與查詢會使用此對照表"
                } catch (e: Exception) { status = e.message ?: "儲存失敗，請重試" }
                finally { busy = false }
            }
        }) { Text(if (busy) "處理中…" else "儲存地點對照") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable internal fun LocationTermComment(text: String) {
    val context = LocalContext.current
    val comment = remember(text) { runCatching { LocationTermsStore(context).load().comment(text) }.getOrDefault("") }
    if (comment.isNotBlank()) Text("地點對照：$comment", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
