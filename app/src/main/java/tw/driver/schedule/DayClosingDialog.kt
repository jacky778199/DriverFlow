package tw.driver.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

private fun BigDecimal.yuan() = "${stripTrailingZeros().toPlainString()} 元"

@Composable
internal fun MonthlyReceiptsEditor(ride: RideOrder, onChange: (List<MonthlyReceipt>) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var selectedKind by remember { mutableStateOf(SUBSIDY_RECEIPT) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("月結收款紀錄", style = MaterialTheme.typography.titleSmall)
        Text("整批日照／補助入帳可到費用頁一次登記；此處只記錄這趟的月結收款。", style = MaterialTheme.typography.bodySmall)
        listOfNotNull(monthlyKindForCategory(ride.category)).filter { dueCents(ride, it) > 0 }.forEach { kind ->
            Text("${receiptKindLabel(kind)}：已收 ${yuan(paidCents(ride, kind))} 元 · 待收 ${yuan(remainingCents(ride, kind))} 元")
            if (remainingCents(ride, kind) > 0) OutlinedButton(onClick = { selectedKind = kind; adding = true }) { Text("登記${receiptKindLabel(kind)}收款") }
        }
        ride.monthlyReceipts.filter { it.kind in listOf(SUBSIDY_RECEIPT, DAYCARE_RECEIPT) }.forEach { receipt ->
            Row(Modifier.fillMaxWidth()) {
                Text("${receipt.date} · ${receiptKindLabel(receipt.kind)} · ${receipt.method}\n${receipt.amount} 元", Modifier.weight(1f))
                if (!receipt.id.startsWith(receipt.batchId + "-")) TextButton(onClick = { onChange(ride.monthlyReceipts.filterNot { it.id == receipt.id }) }) { Text("移除") }
                else Text("批次收款", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("批次收款請到費用頁依收款日期撤銷；變更後按儲存才會保存。", style = MaterialTheme.typography.labelSmall)
    }
    if (adding) {
        var date by remember { mutableStateOf(LocalDate.now().toString()) }
        var amount by remember { mutableStateOf(yuan(remainingCents(ride, selectedKind))) }
        var method by remember { mutableStateOf(if (selectedKind in listOf(CUSTOMER_RECEIPT, TIP_RECEIPT)) "現金" else "轉帳") }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = false }, title = { Text("登記${receiptKindLabel(selectedKind)}收款") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("待收：${yuan(remainingCents(ride, selectedKind))} 元")
                OutlinedTextField(date, { date = it }, label = { Text("收款日期（YYYY-MM-DD）") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("本次收到金額（元）") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Row { listOf("現金", "轉帳").forEach { v -> FilterChip(method == v, { method = v }, label = { Text(v) }); Spacer(Modifier.width(8.dp)) } }
                if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            } }, confirmButton = { TextButton(onClick = {
                val receipt = MonthlyReceipt(UUID.randomUUID().toString(), date.trim(), selectedKind, amount.trim(), method)
                val next = ride.monthlyReceipts + receipt
                val parsedDate = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                val issues = rideMoneyValidation(ride.copy(monthlyReceipts = next)).toMutableList()
                if (parsedDate == null || parsedDate > LocalDate.now() || parsedDate.toString() < ride.serviceDate) issues += "收款日期需在行程日之後且不可在未來"
                if (issues.isNotEmpty()) error = issues.joinToString("\n") else { onChange(next); adding = false }
            }) { Text("加入") } }, dismissButton = { TextButton(onClick = { adding = false }) { Text("取消") } })
    }
}

@Composable
private fun ClosingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}

@Composable
internal fun DayClosingDialog(date: LocalDate, dayRides: List<RideOrder>, departureTime: String, returnHomeTime: String,
    onDismiss: () -> Unit, onEdit: (RideOrder) -> Unit) {
    val summary = remember(dayRides, date) { daySummary(dayRides, date) }
    val completed = dayRides.filter { it.completed }.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    val contributions = remember(dayRides) { caseIncomeContributions(dayRides) }
    var cashExpanded by remember { mutableStateOf(false) }
    var monthlyExpanded by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$date 日結", style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("行程完成", style = MaterialTheme.typography.titleMedium)
                    Text("已完成 ${summary.completed} / 排程 ${summary.scheduled} 趟")
                    Text("自費 ${summary.selfPay} · 補助 ${summary.subsidized} · 日照 ${summary.daycare} 趟")
                    Text("載客計費時間：${summary.seconds / 3600} 小時 ${(summary.seconds % 3600) / 60} 分 ${summary.seconds % 60} 秒")
                    Text("已完成行程加總；手填優先，否則採有效路線估算。不含空車與緩衝。${if (summary.missingTime > 0) " ${summary.missingTime} 趟時間待確認。" else ""}", style = MaterialTheme.typography.bodySmall)
                    Text("出門 ${departureTime.ifBlank { "未填" }} → 回家 ${returnHomeTime.ifBlank { "未填" }}", style = MaterialTheme.typography.bodyMedium)
                    Text("出門到回家：${homeToHomeDurationLabel(departureTime, returnHomeTime)}", style = MaterialTheme.typography.titleSmall)
                    Text("包含空車、等待與休息；可在當日排程的出門／回家記錄補填。", style = MaterialTheme.typography.bodySmall)
                    ClosingSection("各類行程收入貢獻") {
                        CaseIncomeChart(contributions)
                    }
                    ClosingSection("本日行程現收") {
                        Text("自費乘客付款（含小費）：${summary.selfPayCash.yuan()}")
                        Text("補助單乘客付款（含小費）：${summary.subsidyCash.yuan()}")
                        if (summary.otherCash.signum() != 0) Text("其他已收：${summary.otherCash.yuan()}")
                        Text("小費紀錄：${summary.tips.yuan()}（已含於乘客付款，不另計收入）")
                        TextButton(onClick = { cashExpanded = !cashExpanded }) {
                            Text("現收合計（含小費）：${summary.cashTotal.yuan()} · ${if (cashExpanded) "收合" else "明細"}")
                        }
                        Text("乘客付款已含小費。小費僅供紀錄，不另列入收支計算；收入貢獻另包含月結應收。未填金額尚未計入，請查看待確認。", style = MaterialTheme.typography.bodySmall)
                        if (cashExpanded) completed.forEach { ride ->
                            Text(rideReviewLabel(ride), style = MaterialTheme.typography.labelLarge)
                            Text("${ride.category} · 乘客付款 ${serviceCustomerMoney(ride).yuan()} · 小費紀錄 ${serviceTipMoney(ride).yuan()}（不另計）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    ClosingSection("本日行程月結待收") {
                        Text("補助：${summary.subsidyPending.yuan()}")
                        Text("日照：${summary.daycarePending.yuan()}")
                        TextButton(onClick = { monthlyExpanded = !monthlyExpanded }) {
                            Text("待收合計：${(summary.subsidyPending + summary.daycarePending).yuan()} · ${if (monthlyExpanded) "收合" else "明細"}")
                        }
                        Text("本日已完成行程的月結金額，扣除各次已記錄收款；未填月結金額不會當成已確認為 0。", style = MaterialTheme.typography.bodySmall)
                        if (monthlyExpanded) completed.filter { it.category in subsidyCategories || it.category == "日照" }.forEach { ride ->
                            val kind = if (ride.category == "日照") DAYCARE_RECEIPT else SUBSIDY_RECEIPT
                            val billed = if (kind == DAYCARE_RECEIPT) ride.daycareMonthly else ride.subsidyDue
                            Text(rideReviewLabel(ride), style = MaterialTheme.typography.labelLarge)
                            Text("月結 ${billed.ifBlank { "未確認" }} · 截至本日已收 ${yuan(paidCents(ride, kind, date))} · 待收 ${yuan(remainingCents(ride, kind, date))} 元", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { onEdit(ride) }) { Text("記錄收款") }
                        }
                    }
                    ClosingSection("待確認（${summary.reviews.size} 趟）") {
                        if (summary.reviews.isEmpty()) Text("已填齊本日必要資料")
                        summary.reviews.forEach { review ->
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(rideReviewLabel(review.ride), style = MaterialTheme.typography.titleSmall)
                                    Text("${review.ride.pickup} → ${review.ride.destination}", style = MaterialTheme.typography.bodySmall)
                                    review.issues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                                    TextButton(onClick = { onEdit(review.ride) }) { Text("補填") }
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
            }
        }
    }
}

@Composable
private fun CaseIncomeChart(contributions: List<CaseIncomeContribution>) {
    val total = contributions.firstOrNull()?.total ?: BigDecimal.ZERO
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSurfaceVariant)
    Text("行程收入合計：${total.yuan()}", style = MaterialTheme.typography.titleSmall)
    Text("已完成行程的乘客付款（含小費）＋該趟月結應收。小費僅供紀錄，不另計收支；月結尚未入帳也屬於行程收入。", style = MaterialTheme.typography.bodySmall)
    if (total.signum() == 0) Text("尚無可分析的收入", style = MaterialTheme.typography.bodyMedium)
    contributions.forEachIndexed { index, row ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(row.category, style = MaterialTheme.typography.labelLarge)
                Text("${row.amount.yuan()} · ${row.percent}%", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (row.category == "自費") "乘客付款 ${row.passenger.yuan()}" else "乘客付款 ${row.passenger.yuan()} ＋ 月結 ${row.monthly.yuan()}",
                style = MaterialTheme.typography.bodySmall)
            if (row.tips.signum() > 0) Text("小費紀錄 ${row.tips.yuan()}（已含於乘客付款，不另計）", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)) {
                if (row.fraction > 0f) Box(Modifier.fillMaxWidth(row.fraction).fillMaxHeight()
                    .background(colors[index].copy(alpha = 0.8f), MaterialTheme.shapes.small))
            }
        }
    }
}
