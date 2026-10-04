package tw.driver.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import androidx.compose.material.icons.filled.Check

@Composable
internal fun FinanceSections(date: LocalDate, rides: List<RideOrder>, expenses: List<ExpenseItem>,
    rental: MonthlyRentalPlan, onUpdate: (List<RideOrder>) -> Unit, onOpenRide: (Long) -> Unit = {}) {
    var detail by remember { mutableStateOf<String?>(null) }
    var collecting by remember { mutableStateOf(false) }
    var undoBatch by remember { mutableStateOf<String?>(null) }
    val today = rides.filter { it.completed && it.serviceDate == date.toString() }
    val revenue = today.sumOf(::operatingRevenueCents)
    val costs = expenses.filter { it.costCentsOn(date) > 0 }
    val cost = costs.sumOf { it.costCentsOn(date) } + rental.totalCostCentsOn(date)
    val received = collectedOn(rides, date)
    val payments = expenses.filter { it.paidDate == date.toString() }
    val paid = payments.sumOf { it.amountCents } + rental.paymentCentsOn(date)
    val pending = outstanding(rides, date)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FinanceCard("今天工作成果", "依 $date 的已完成行程與成本", listOf(
            "營收" to revenue, "成本" to cost, "營運淨額" to revenue - cost), { detail = "work" })
        val unconfirmed = today.count { (it.category in cashCategories && it.amountDue.isBlank() && it.received.isBlank()) ||
            (it.category in subsidyCategories && it.subsidyDue.isBlank()) || (it.category == "日照" && it.daycareMonthly.isBlank()) }
        if (unconfirmed > 0) Text("有 $unconfirmed 趟金額待確認，目前合計尚未完整", color = MaterialTheme.colorScheme.error)
        FinanceCard("今天收付款", "依 $date 的實際收付款日期", listOf(
            "實收" to received, "實付" to paid, "現金淨流入" to received - paid), { detail = "cash" })
        FinanceCard("尚未收款", "截至 $date，包含以前的已完成行程", listOf(

            "補助" to pending.filter { it.kind == SUBSIDY_RECEIPT }.sumOf { it.remaining },
            "日照" to pending.filter { it.kind == DAYCARE_RECEIPT }.sumOf { it.remaining }), { detail = "pending" })
        Button(onClick = { collecting = true }) { Text("登記月結收款") }
    }
    if (collecting) CollectionDialog(rides, date,
        onDismiss = { collecting = false }, onSave = { updated -> onUpdate(updated); collecting = false })
    detail?.let { selected ->
        FinanceChartDialog(date, selected == "cash", rides, expenses, rental,
            onDismiss = { detail = null }, onUndo = { undoBatch = it },
            pending = selected == "pending", onOpenRide = onOpenRide)
    }
    undoBatch?.let { batch ->
        AlertDialog(onDismissRequest = { undoBatch = null }, title = { Text("撤銷整筆收款？") },
            text = { Text("這筆收款分配到的所有行程會恢復待收；原行程營收保持不變。") },
            confirmButton = { TextButton(onClick = {
                onUpdate(rides.map { ride ->
                    val receipts = ride.monthlyReceipts.filterNot { receipt -> receipt.batchId == batch }
                    if (receipts == ride.monthlyReceipts) ride else ride.copy(monthlyReceipts = receipts,
                        received = ride.received)
                })
                undoBatch = null
            }) { Text("確認撤銷") } }, dismissButton = { TextButton(onClick = { undoBatch = null }) { Text("取消") } })
    }
}

@Composable
private fun FinanceCard(title: String, subtitle: String, values: List<Pair<String, Long>>, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (title == "今天收付款") Icon(Icons.Default.Payments, contentDescription = "現金流", modifier = Modifier.size(20.dp))
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            values.filter { title != "今天工作成果" || it.second != 0L }.forEach { (label, amount) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label); Text("${yuan(amount)} 元", style = MaterialTheme.typography.titleSmall)
                }
            }
            Text("點擊查看明細", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CollectionKindChip(selected: Boolean, onClick: () -> Unit, label: String) {
    FilterChip(selected, onClick, label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(Icons.Default.Check, contentDescription = "已選取", modifier = Modifier.size(18.dp)) }) else null,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary))
}

@Composable
private fun CollectionDialog(rides: List<RideOrder>, selectedDate: LocalDate,
    onDismiss: () -> Unit, onSave: (List<RideOrder>) -> Unit) {
    var startText by remember { mutableStateOf(selectedDate.withDayOfMonth(1).toString()) }
    var dateText by remember { mutableStateOf(selectedDate.toString()) }
    var subsidy by remember { mutableStateOf(true) }
    var daycare by remember { mutableStateOf(true) }
    var method by remember { mutableStateOf("轉帳") }
    var expanded by remember { mutableStateOf(false) }
    var actual by remember { mutableStateOf("") }
    var allocations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var error by remember { mutableStateOf("") }
    val startDate = runCatching { LocalDate.parse(startText.trim()) }.getOrNull()
    val date = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val kinds = buildSet { if (subsidy) add(SUBSIDY_RECEIPT); if (daycare) add(DAYCARE_RECEIPT) }
    val entries = if (startDate != null && date != null) collectionOutstanding(rides, startDate, date, kinds) else emptyList()
    // Include later receipts in the ceiling so backdated collections cannot overpay an invoice.
    val available = entries.mapNotNull { e -> remainingCents(e.ride, e.kind).takeIf { it > 0 }?.let { e.copy(remaining = minOf(e.remaining, it)) } }
    val fingerprint = available.joinToString { "${it.key}:${it.remaining}" }
    LaunchedEffect(fingerprint, startText, subsidy, daycare) {
        allocations = available.associate { it.key to yuan(it.remaining) }
        actual = yuan(available.sumOf { it.remaining }); error = ""
    }
    val allocated = allocations.values.sumOf(::cents)
    val total = available.sumOf { it.remaining }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("登記月結收款") },
        text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(startText, { startText = it }, label = { Text("行程起始日期（YYYY-MM-DD）") }, singleLine = true) }
            item { Text("包含起始日期，至實際收款日的已完成待收行程。", style = MaterialTheme.typography.bodySmall) }
            item {
                Row {
                    CollectionKindChip(subsidy, { subsidy = !subsidy }, "補助")
                    Spacer(Modifier.width(8.dp))
                    CollectionKindChip(daycare, { daycare = !daycare }, "日照")
                }
            }
            item { OutlinedTextField(dateText, { dateText = it }, label = { Text("實際收款日期（YYYY-MM-DD）") }, singleLine = true) }
            item { Row {
                listOf("現金", "轉帳").forEach { value -> FilterChip(method == value, { method = value }, label = { Text(value) }); Spacer(Modifier.width(8.dp)) }
            } }
            item {
                kinds.forEach { kind -> Text("${receiptKindLabel(kind)}：${available.count { it.kind == kind }} 筆 · ${yuan(available.filter { it.kind == kind }.sumOf { it.remaining })} 元") }
                Text("待收合計：${yuan(total)} 元")
                OutlinedTextField(actual, { actual = it; expanded = true }, label = { Text("實際收到金額（元）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起分配明細" else "選擇行程／部分付款") }
                if (cents(actual) != allocated) Text("請調整各筆分配：目前分配 ${yuan(allocated)} 元，差額 ${yuan(cents(actual) - allocated)} 元", color = MaterialTheme.colorScheme.error)
            }
            if (expanded) items(available, key = { it.key }) { item ->
                Column {
                    Text("${item.ride.serviceDate} ${item.ride.customer} · ${receiptKindLabel(item.kind)} · 待收 ${yuan(item.remaining)} 元")
                    Row {
                        Checkbox(cents(allocations[item.key].orEmpty()) > 0, { checked -> allocations = allocations + (item.key to if (checked) yuan(item.remaining) else "0") })
                        OutlinedTextField(allocations[item.key].orEmpty(), { allocations = allocations + (item.key to it) },
                            label = { Text("本次分配（元；未付填 0）") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    }
                }
            }
            if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error) }
            item { Text("金額不同請確認分配；結算金額有調整時，先到行程修改應收並在備註記錄原因。", style = MaterialTheme.typography.bodySmall) }
        } }, confirmButton = { TextButton(onClick = {
            error = runCatching {
                require(startDate != null && date != null) { "請填寫有效起始日期與收款日期" }
                require(startDate <= date) { "行程起始日期不可晚於收款日期" }
                require(date <= LocalDate.now()) { "實際收款日期不可在未來" }
                require(Money.parse(actual)?.cents?.let { it > 0 } == true) { "收款金額需大於 0，最多兩位小數" }
                require(allocations.values.all { Money.parse(it) != null }) { "分配金額請填非負數字，最多兩位小數" }
                require(cents(actual) == allocated) { "實收與分配合計不符，請確認各筆金額" }
                val amounts = allocations.mapValues { cents(it.value) }.filterValues { it > 0 }
                require(amounts.keys.map { it.substringBefore(':') }.distinct().size <= 450) { "一次最多 450 趟，請分批收款" }
                require(amounts.all { (key, amount) -> available.find { it.key == key }?.remaining?.let { amount <= it } == true }) { "分配不可超過待收" }
                onSave(allocateCollection(rides, amounts, date, method))
            }.exceptionOrNull()?.message.orEmpty()
        }) { Text("確認收款") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
