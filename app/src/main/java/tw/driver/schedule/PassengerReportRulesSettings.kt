package tw.driver.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun PassengerReportRulesSettings() {
    val context = LocalContext.current
    val store = remember { PassengerReportRulesStore(context) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf(emptyList<PassengerReportRule>()) }
    var busy by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try { rows = withContext(Dispatchers.IO) { store.load().items } }
        catch (_: Exception) { status = "讀取乘客對照失敗，請重新設定" }
        busy = false
    }
    SettingsGroup("固定乘客的回報對象", "建立行程時，依乘客姓名自動填入客上／客下回報對象。") {
        Text("姓名需與行程中的乘客姓名相同（忽略首尾空白與英文大小寫）。可填 LINE 聯絡人或聊天室的完整名稱。個別行程仍可修改；未設定對照時使用上方的一般回報對象。", style = MaterialTheme.typography.bodySmall)
        rows.forEachIndexed { index, row ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(row.customer, { value -> rows = rows.toMutableList().also { it[index] = row.copy(customer = value) }; status = "" },
                    label = { Text("乘客姓名") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(row.target, { value -> rows = rows.toMutableList().also { it[index] = row.copy(target = value) }; status = "" },
                    label = { Text("預設回報對象") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { rows = rows.filterIndexed { i, _ -> i != index }; status = "" }, enabled = !busy) { Text("刪除第 ${index + 1} 組") }
            }
            HorizontalDivider()
        }
        if (rows.isEmpty()) Text("尚未設定固定乘客。", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { rows = rows + PassengerReportRule("", ""); status = "" }, enabled = !busy && rows.size < 200) { Text("新增乘客對照") }
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val rules = PassengerReportRules(rows.map { PassengerReportRule(it.customer.trim(), it.target.trim()) })
                    withContext(Dispatchers.IO) { store.save(rules) }
                    rows = rules.items
                    status = "已儲存，後續建立行程會自動填入；已儲存行程維持原回報對象。"
                } catch (e: Exception) { status = e.message ?: "儲存失敗，請重試" }
                finally { busy = false }
            }
        }) { Text(if (busy) "儲存中…" else "儲存乘客對照") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}
