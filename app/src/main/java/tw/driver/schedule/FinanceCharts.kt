package tw.driver.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Payments
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

internal data class FinanceChartEntry(val key: String, val name: String, val amount: Long,
    val details: List<String>, val batchId: String? = null, val roundTrip: Boolean = false,
    val batchIds: List<String> = listOfNotNull(batchId), val rideIds: List<Long> = emptyList())

private fun RideOrder.customerChartKey(): String = customer.trim().takeIf { it.isNotEmpty() } ?: "unnamed-$id"
private fun List<RideOrder>.isRoundTrip(): Boolean = any { it.returnRide } && any { !it.returnRide }

internal fun workRevenueEntries(rides: List<RideOrder>, date: LocalDate): List<FinanceChartEntry> = rides
    .filter { it.completed && it.serviceDate == date.toString() }
    .filter { operatingRevenueCents(it) > 0 }.groupBy { it.customerChartKey() }
    .map { (key, group) ->
        val amount = group.sumOf(::operatingRevenueCents)
        FinanceChartEntry("customer-$key", group.first().customer.trim().ifBlank { "未填姓名" }, amount,
            group.sortedBy { it.pickupTime }.flatMap { ride ->
                listOf("${if (ride.returnRide) "回程" else "去程"} · ${ride.serviceDate} ${ride.pickupTime} · ${ride.category}",
                    "${ride.pickup} → ${ride.destination}") +
                    receiptKinds.mapNotNull { kind -> dueCents(ride, kind).takeIf { it > 0 }?.let { "${receiptKindLabel(kind)}：${yuan(it)} 元" } } +
                    "這趟營收：${yuan(operatingRevenueCents(ride))} 元"
            } + "合併營收：${yuan(amount)} 元", roundTrip = group.isRoundTrip())
    }.sortedByDescending { it.amount }

internal fun cashIncomeEntries(rides: List<RideOrder>, date: LocalDate): List<FinanceChartEntry> {
    val all = rides.flatMap { ride -> effectiveReceipts(ride).filter { it.date == date.toString() && cents(it.amount) > 0 }.map { ride to it } }
    val batches = all.groupBy { it.second.batchId }
    return all.groupBy { it.first.customerChartKey() }.map { (key, records) ->
        val amount = records.sumOf { cents(it.second.amount) }
        val batchIds = records.map { it.second.batchId }.distinct().filter { batch -> batches.getValue(batch).all { it.first.receiptsManaged && it.second.kind in listOf(SUBSIDY_RECEIPT, DAYCARE_RECEIPT) } }
        FinanceChartEntry("customer-$key", records.first().first.customer.trim().ifBlank { "未填姓名" }, amount,
            listOf("收款日：$date", "收款方式：${records.map { it.second.method }.distinct().joinToString("／")}") +
                records.map { (ride, receipt) -> "${if (ride.returnRide) "回程" else "去程"} · ${ride.serviceDate} · ${receiptKindLabel(receipt.kind)}：${receipt.amount} 元" } +
                "實收合計：${yuan(amount)} 元", batchIds.singleOrNull(), records.map { it.first }.distinctBy { it.id }.isRoundTrip(), batchIds,
            records.map { it.first.id }.distinct())
    }.sortedByDescending { it.amount }
}

/** Same boundaries as the proportional strip; leaving its bounds clears the preview. */
internal fun revenueEntryAt(entries: List<FinanceChartEntry>, x: Float, width: Float, y: Float, height: Float): FinanceChartEntry? {
    if (width <= 0 || height <= 0 || x < 0 || x >= width || y < 0 || y >= height) return null
    val total = entries.sumOf { it.amount }
    if (total <= 0) return null
    val target = x.toDouble() / width * total
    var cumulative = 0L
    return entries.firstOrNull { entry -> cumulative += entry.amount; target < cumulative }
}

internal fun cashExpenseEntries(expenses: List<ExpenseItem>, rental: MonthlyRentalPlan, date: LocalDate): List<FinanceChartEntry> =
    (expenses.filter { it.paidDate == date.toString() && it.amountCents > 0 }.map { item ->
        FinanceChartEntry("expense-${item.id}", item.category, item.amountCents,
            listOf("付款日：${item.paidDate}", "付款方式：${item.paymentMethod}", "成本日期：${item.date}" +
                item.costEndDate.takeIf { it.isNotBlank() }?.let { "～$it" }.orEmpty(), "付款金額：${yuan(item.amountCents)} 元") +
                listOfNotNull(item.note.takeIf { it.isNotBlank() }))
    } + rental.allPlans().mapIndexedNotNull { index, plan ->
        if (plan.paidDate != date.toString() || plan.feeCents <= 0) null else FinanceChartEntry("rental-$index",
            plan.model.ifBlank { "租車" }, plan.feeCents,
            listOf("付款日：${plan.paidDate}", "付款方式：${plan.paymentMethod}", "租期：${plan.startDate}～${plan.endDate}",
                "租金全額付款：${yuan(plan.feeCents)} 元", "成本按租期每日分攤"))
    }).sortedByDescending { it.amount }

private fun share(amount: Long, total: Long): Float = if (total <= 0) 0f else
    BigDecimal.valueOf(amount).divide(BigDecimal.valueOf(total), 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
private fun percent(amount: Long, total: Long): String = if (total <= 0) "0" else
    BigDecimal.valueOf(amount).multiply(BigDecimal(100)).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

internal fun workCostEntries(expenses: List<ExpenseItem>, rental: MonthlyRentalPlan, date: LocalDate): List<FinanceChartEntry> {
    val entries = expenses.filter { it.costCentsOn(date) > 0 }.groupBy { it.category }.map { (category, group) ->
        val amount = group.sumOf { it.costCentsOn(date) }
        FinanceChartEntry("cost-$category", category, amount, group.flatMap { item -> listOf(
            "$category · ${item.date}", "費用總額：${yuan(item.amountCents)} 元",
            "成本期間：${item.date}～${item.costEndDate.ifBlank { item.date }}",
            "當日分攤：${yuan(item.costCentsOn(date))} 元") + listOfNotNull(item.note.takeIf { it.isNotBlank() }) } + "合併成本：${yuan(amount)} 元")
    }
    val plans = rental.allPlans().filter { it.costCentsOn(date) > 0 }
    val rentalEntry = if (plans.isEmpty()) emptyList() else listOf(FinanceChartEntry("rental-cost", "租金分攤",
        plans.sumOf { it.costCentsOn(date) }, plans.flatMap { plan -> listOf(
            "租車 · ${plan.model.ifBlank { "車輛" }}", "租期：${plan.startDate}～${plan.endDate}",
            "租期總額：${yuan(plan.feeCents)} 元", "當日分攤：${yuan(plan.costCentsOn(date))} 元") } +
            "租期平均分攤，餘分自首日依序補足"))
    return (entries + rentalEntry).sortedByDescending { it.amount }
}

private data class ChartSelection(val entry: FinanceChartEntry, val total: Long, val background: Color, val foreground: Color)

internal fun pendingChartEntries(rides: List<RideOrder>, date: LocalDate): List<FinanceChartEntry> =
    outstanding(rides, date).groupBy { it.ride.serviceDate }.map { (serviceDate, items) ->
        FinanceChartEntry("pending-$serviceDate", serviceDate, items.sumOf { it.remaining }, items.flatMap { item ->
            listOf("${item.ride.customer.ifBlank { "未填姓名" }} · ${receiptKindLabel(item.kind)} · ${if (item.ride.returnRide) "回程" else "去程"} ${item.ride.pickupTime}",
                "應收：${yuan(dueCents(item.ride, item.kind))} 元",
                "已收：${yuan(paidCents(item.ride, item.kind, date))} 元", "待收：${yuan(item.remaining)} 元")
        })
    }.sortedBy { it.name }

internal fun shouldPinChartTap(duration: Long, distance: Float, timeout: Long, slop: Float, endedInside: Boolean): Boolean =
    endedInside && duration < timeout && distance <= slop

@Composable
internal fun FinanceChartDialog(date: LocalDate, cash: Boolean, rides: List<RideOrder>, expenses: List<ExpenseItem>,
    rental: MonthlyRentalPlan, onDismiss: () -> Unit, onUndo: (String) -> Unit,
    pending: Boolean = false, onOpenRide: (Long) -> Unit = {}) {
    var selected by remember(date, cash) { mutableStateOf<FinanceChartEntry?>(null) }
    var preview by remember(date, cash) { mutableStateOf<ChartSelection?>(null) }
    var pinned by remember(date, cash) { mutableStateOf<ChartSelection?>(null) }
    val latestPinned by rememberUpdatedState(pinned)
    val income = if (pending) pendingChartEntries(rides, date) else if (cash) cashIncomeEntries(rides, date) else workRevenueEntries(rides, date)
    val outgoing = cashExpenseEntries(expenses, rental, date)
    val costs = workCostEntries(expenses, rental, date)
    val colors = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.errorContainer)
    val foregrounds = listOf(MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.onErrorContainer)
    fun selection(entry: FinanceChartEntry?, entries: List<FinanceChartEntry>): ChartSelection? = entry?.let {
        val index = entries.indexOfFirst { item -> item.key == it.key }.coerceAtLeast(0) % colors.size
        ChartSelection(it, entries.sumOf { item -> item.amount }, colors[index], foregrounds[index])
    }
    Dialog(onDismissRequest = {
        if (pinned != null) { pinned = null; preview = null } else onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.extraLarge) {
            Box(Modifier.fillMaxSize().pointerInput(date, cash) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (latestPinned != null) {
                        pinned = null; preview = null
                        down.consume()
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (pending) "尚未收款明細" else if (cash) "收付款明細" else "工作成果明細", style = MaterialTheme.typography.titleLarge)
                    if (cash) Icon(Icons.Default.Payments, contentDescription = "現金流", modifier = Modifier.size(22.dp))
                }
                Text(if (cash) "$date · 點選項目查看細節" else "$date · 點一下固定，再點關閉；長按滑動查看", style = MaterialTheme.typography.bodySmall)
                if (cash) {
                    val maximum = maxOf(income.sumOf { it.amount }, outgoing.sumOf { it.amount }, 1L)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CashChartColumn("收入", income, maximum, MaterialTheme.colorScheme.primary, Modifier.weight(1f)) { selected = it }
                        CashChartColumn("支出", outgoing, maximum, MaterialTheme.colorScheme.error, Modifier.weight(1f)) { selected = it }
                    }
                    Text("現金淨流入：${yuan(income.sumOf { it.amount } - outgoing.sumOf { it.amount })} 元", style = MaterialTheme.typography.titleMedium)
                } else {
                    val total = income.sumOf { it.amount }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (income.isNotEmpty()) item {
                            Text("${if (pending) "待收" else "營收"} ${yuan(total)} 元", style = MaterialTheme.typography.titleMedium)
                            RevenueStrip(income, colors, foregrounds, onPreview = { preview = selection(it, income) }, onSelect = { pinned = selection(it, income) })
                        }
                        if (income.isEmpty()) item { Text(if (pending) "目前沒有待收款" else "當天沒有非零營收") }
                        if (!pending && costs.isNotEmpty()) item {
                            HorizontalDivider()
                            Text("成本 ${yuan(costs.sumOf { it.amount })} 元", style = MaterialTheme.typography.titleMedium)
                            RevenueStrip(costs, colors, foregrounds, onPreview = { preview = selection(it, costs) }, onSelect = { pinned = selection(it, costs) })
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("關閉") }
            }
            (pinned ?: preview)?.let { active ->
                // Remains in this window so press-and-slide keeps receiving pointer events.
                Surface(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().heightIn(max = 340.dp),
                    color = active.background, contentColor = active.foreground,
                    shape = MaterialTheme.shapes.large, shadowElevation = 8.dp) {
                    Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${active.entry.name} · ${percent(active.entry.amount, active.total)}% · ${yuan(active.entry.amount)} 元", style = MaterialTheme.typography.titleMedium)
                        DetailLines(active.entry.details)
                    }
                }
            }
            }
        }
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("${entry.name} · ${yuan(entry.amount)} 元") },
            text = { LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { DetailLines(entry.details) }
                itemsIndexed(entry.rideIds) { _, rideId ->
                    val ride = rides.find { it.id == rideId }
                    if (ride != null) Button(onClick = { selected = null; onDismiss(); onOpenRide(rideId) }) {
                        Text("前往行程 · ${ride.serviceDate} ${ride.pickupTime} ${if (ride.returnRide) "回程" else "去程"}")
                    }
                }
                if (entry.batchIds.isNotEmpty()) item { Text("撤銷會處理整筆收款，包含同筆月結分配到的其他乘客。", style = MaterialTheme.typography.bodySmall) }
                itemsIndexed(entry.batchIds) { index, batch -> TextButton(onClick = { selected = null; onUndo(batch) }) {
                    Text(if (entry.batchIds.size == 1) "撤銷這筆收款" else "撤銷第 ${index + 1} 筆收款")
                } }
            } }, confirmButton = { TextButton(onClick = { selected = null }) { Text("關閉") } })
    }
}

@Composable
private fun DetailLines(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEachIndexed { index, line ->
            val heading = line.startsWith("去程") || line.startsWith("回程") ||
                (line.contains(" · ") && !line.contains("："))
            val total = line.startsWith("合併") || line.startsWith("實收合計") || line.startsWith("這趟營收")
            if (index > 0 && (heading || total)) HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.25f))
            Text(line, modifier = Modifier.padding(start = if (heading || total) 0.dp else 12.dp),
                style = if (heading || total) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RowScope.CashChartColumn(title: String, entries: List<FinanceChartEntry>, maximum: Long, color: Color,
    modifier: Modifier, onSelect: (FinanceChartEntry) -> Unit) {
    val total = entries.sumOf { it.amount }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color)
        Text("${yuan(total)} 元", style = MaterialTheme.typography.titleMedium)
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.fillMaxWidth(0.65f).fillMaxHeight(share(total, maximum)).background(color, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(entries, key = { _, entry -> entry.key }) { _, entry -> EntryBar(entry, maximum, color, null) { onSelect(entry) } }
            if (entries.isEmpty()) item { Text("沒有$title", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun EntryBar(entry: FinanceChartEntry, maximum: Long, color: Color, percentage: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (entry.roundTrip) Icon(Icons.Default.SwapHoriz, contentDescription = "去回程合併", modifier = Modifier.size(18.dp))
            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
        }
        Text("${yuan(entry.amount)} 元" + percentage?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.bodySmall)
        Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(share(entry.amount, maximum)).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun RevenueStrip(entries: List<FinanceChartEntry>, colors: List<Color>, foregrounds: List<Color>,
    onPreview: (FinanceChartEntry?) -> Unit, onSelect: (FinanceChartEntry) -> Unit) {
    val total = entries.sumOf { it.amount }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.bodySmall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(6.dp))
                .pointerInput(entries) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = true)
                        down.consume()
                        var distance = 0f
                        try {
                            onPreview(revenueEntryAt(entries, down.position.x, size.width.toFloat(), down.position.y, size.height.toFloat()))
                            do {
                                val event = awaitPointerEvent()
                                val pointer = event.changes.firstOrNull { it.id == down.id }
                                if (pointer == null) break
                                distance = maxOf(distance, (pointer.position - down.position).getDistance())
                                pointer.consume()
                                if (!pointer.pressed) {
                                    val entry = revenueEntryAt(entries, pointer.position.x, size.width.toFloat(), pointer.position.y, size.height.toFloat())
                                    if (shouldPinChartTap(pointer.uptimeMillis - down.uptimeMillis, distance,
                                            viewConfiguration.longPressTimeoutMillis, viewConfiguration.touchSlop, entry != null)) {
                                        entry?.let(onSelect)
                                    }
                                    break
                                }
                                onPreview(revenueEntryAt(entries, pointer.position.x, size.width.toFloat(), pointer.position.y, size.height.toFloat()))
                            } while (true)
                        } finally { onPreview(null) }
                    }
                }) {
                entries.forEachIndexed { index, entry ->
                    Box(Modifier.weight(entry.amount.toFloat()).fillMaxHeight().background(colors[index % colors.size])
                        .semantics {
                            contentDescription = "${entry.name} ${yuan(entry.amount)} 元，占 ${percent(entry.amount, total)}%"
                            onClick("查看細節") { onSelect(entry); true }
                        }, contentAlignment = Alignment.Center) {
                        val nameWidth = measurer.measure(AnnotatedString(entry.name), labelStyle).size.width
                        if (nameWidth + with(density) { 6.dp.toPx() } <= widthPx * share(entry.amount, total))
                            Text(entry.name, color = foregrounds[index % foregrounds.size], style = labelStyle, maxLines = 1, overflow = TextOverflow.Clip)
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                entries.forEach { entry ->
                    val ratio = "${percent(entry.amount, total)}%"
                    val amount = "${yuan(entry.amount)} 元"
                    val requiredWidth = maxOf(measurer.measure(AnnotatedString(ratio), labelStyle).size.width,
                        measurer.measure(AnnotatedString(amount), labelStyle).size.width) + with(density) { 4.dp.toPx() }
                    Column(Modifier.weight(entry.amount.toFloat()), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (requiredWidth <= widthPx * share(entry.amount, total)) {
                            Text(ratio, style = labelStyle, maxLines = 1, textAlign = TextAlign.Center)
                            Text(amount, style = labelStyle, maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
