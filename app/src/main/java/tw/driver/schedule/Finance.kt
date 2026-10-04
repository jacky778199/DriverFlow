package tw.driver.schedule

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.json.JSONObject

internal const val CUSTOMER_RECEIPT = "customer"
internal const val TIP_RECEIPT = "tip"
internal val receiptKinds = listOf(CUSTOMER_RECEIPT, SUBSIDY_RECEIPT, DAYCARE_RECEIPT)
internal fun monthlyKindForCategory(category: String): String? = when (category) {
    in subsidyCategories -> SUBSIDY_RECEIPT
    "日照" -> DAYCARE_RECEIPT
    else -> null
}
internal fun changeRideIncomeType(ride: RideOrder, category: String): RideOrder = ride.copy(
    category = category,
    subsidyDue = if (category in subsidyCategories) ride.subsidyDue else "",
    daycareMonthly = if (category == "日照") ride.daycareMonthly else "",
    monthlyReceipts = ride.monthlyReceipts.filter { it.kind == monthlyKindForCategory(category) })

internal fun dueCents(ride: RideOrder, kind: String): Long = cents(when (kind) {
    CUSTOMER_RECEIPT -> if (ride.receiptsManaged) ride.amountDue else ride.received
    TIP_RECEIPT -> "0"
    SUBSIDY_RECEIPT -> if (ride.category in subsidyCategories) ride.subsidyDue else "0"
    DAYCARE_RECEIPT -> if (ride.category == "日照") ride.daycareMonthly else "0"
    else -> ""
})
internal fun effectiveReceipts(ride: RideOrder): List<MonthlyReceipt> {
    val monthly = ride.monthlyReceipts.filter { it.kind == monthlyKindForCategory(ride.category) }
    if (!ride.completed) return monthly
    val duplicate = !ride.receiptsManaged && ride.category == "日照" && ride.daycareMonthly.isNotBlank() && cents(ride.received) == cents(ride.daycareMonthly)
    return monthly + listOfNotNull(
        dueCents(ride, CUSTOMER_RECEIPT).takeIf { it > 0 && !duplicate }?.let {
            MonthlyReceipt("automatic-customer-${ride.id}", ride.serviceDate, CUSTOMER_RECEIPT, yuan(it), "現金") })
}
internal fun paidCents(ride: RideOrder, kind: String, through: LocalDate? = null): Long = effectiveReceipts(ride)
    .filter { it.kind == kind && (through == null || it.date <= through.toString()) }.sumOf { cents(it.amount) }
internal fun remainingCents(ride: RideOrder, kind: String, through: LocalDate? = null): Long =
    (dueCents(ride, kind) - paidCents(ride, kind, through)).coerceAtLeast(0)
internal fun operatingRevenueCents(ride: RideOrder): Long {
    val duplicate = !ride.receiptsManaged && ride.category == "日照" && ride.daycareMonthly.isNotBlank() && cents(ride.received) == cents(ride.daycareMonthly)
    return receiptKinds.sumOf { if (duplicate && it == CUSTOMER_RECEIPT) 0L else dueCents(ride, it) }
}
internal fun collectedOn(rides: List<RideOrder>, date: LocalDate): Long = rides.sumOf { ride ->
    effectiveReceipts(ride).filter { it.date == date.toString() }.sumOf { cents(it.amount) } }
internal fun MonthlyRentalPlan.costCentsOn(date: LocalDate): Long {
    if (!covers(date)) return 0
    val start = runCatching { LocalDate.parse(startDate) }.getOrNull()
        ?: runCatching { YearMonth.parse(periodMonth).atDay(1) }.getOrNull() ?: return dailyCostCents
    val end = runCatching { LocalDate.parse(endDate) }.getOrNull()
        ?: YearMonth.from(start).atEndOfMonth()
    val days = ChronoUnit.DAYS.between(start, end) + 1
    if (days <= 0) return 0
    val offset = ChronoUnit.DAYS.between(start, date)
    return feeCents / days + if (offset < feeCents % days) 1 else 0
}

internal data class Outstanding(val ride: RideOrder, val kind: String, val remaining: Long) {
    val key get() = "${ride.id}:$kind"
}
internal fun collectionOutstanding(rides: List<RideOrder>, start: LocalDate, through: LocalDate,
    kinds: Set<String>): List<Outstanding> = if (start > through) emptyList() else
    outstanding(rides, through, kinds = kinds).filter { LocalDate.parse(it.ride.serviceDate) >= start }
internal fun outstanding(rides: List<RideOrder>, through: LocalDate, month: YearMonth? = null,
    kinds: Set<String> = receiptKinds.toSet()): List<Outstanding> = rides.filter { ride ->
    ride.completed && runCatching { LocalDate.parse(ride.serviceDate).let { it <= through && (month == null || YearMonth.from(it) == month) } }.getOrDefault(false)
}.flatMap { ride -> kinds.filter { it in listOf(SUBSIDY_RECEIPT, DAYCARE_RECEIPT) }.mapNotNull { kind -> remainingCents(ride, kind, through).takeIf { it > 0 }?.let { Outstanding(ride, kind, it) } } }
    .sortedWith(compareBy({ it.ride.serviceDate }, { it.ride.pickupTime }, { it.ride.id }, { it.kind }))

/** A batch is one receipt with allocations stored on each ride; shared batchId keeps it reversible. */
internal fun allocateCollection(rides: List<RideOrder>, amounts: Map<String, Long>, date: LocalDate,
    method: String, batchId: String = UUID.randomUUID().toString()): List<RideOrder> {
    require(method in listOf("現金", "轉帳")) { "請選擇收款方式" }
    require(amounts.isNotEmpty() && amounts.values.all { it > 0 }) { "請填寫有效收款金額" }
    val entries = outstanding(rides, date).associateBy { it.key }
    amounts.forEach { (key, amount) -> require(entries[key]?.remaining?.let { amount <= it } == true) { "收款不可超過待收，請重新確認明細" } }
    amounts.forEach { (key, amount) -> val entry = entries.getValue(key)
        require(amount <= remainingCents(entry.ride, entry.kind)) { "已有其他日期收款，請重新確認待收" } }
    return rides.map { ride ->
        val allocation = amounts.filterKeys { it.startsWith("${ride.id}:") }
        if (allocation.isEmpty()) ride else ride.copy(receiptsManaged = true,
            amountDue = if (ride.receiptsManaged) ride.amountDue else if (ride.category != "日照") ride.received else "",
            received = yuan(dueCents(ride, CUSTOMER_RECEIPT)),
            monthlyReceipts = effectiveReceipts(ride).filter { it.kind in listOf(SUBSIDY_RECEIPT, DAYCARE_RECEIPT) } + allocation.map { (key, amount) ->
                MonthlyReceipt("$batchId-$key", date.toString(), key.substringAfter(':'), yuan(amount), method, batchId) })
    }
}

internal fun serviceCashCents(ride: RideOrder, kind: String): Long = effectiveReceipts(ride)
    .filter { it.kind == kind && it.date == ride.serviceDate }.sumOf { cents(it.amount) }

internal fun MonthlyRentalPlan.allPlans(): List<MonthlyRentalPlan> = archivedPlans + listOf(copy(archivedPlans = emptyList()))
internal fun MonthlyRentalPlan.totalCostCentsOn(date: LocalDate): Long = allPlans().sumOf { it.costCentsOn(date) }
internal fun MonthlyRentalPlan.paymentCentsOn(date: LocalDate): Long = allPlans().filter { it.paidDate == date.toString() }.sumOf { it.feeCents }
internal fun ExpenseItem.costCentsOn(day: LocalDate): Long {
    val start = runCatching { LocalDate.parse(date) }.getOrNull() ?: return 0
    val end = runCatching { LocalDate.parse(costEndDate) }.getOrNull() ?: start
    if (end < start || day !in start..end) return 0
    val days = ChronoUnit.DAYS.between(start, end) + 1
    return amountCents / days + if (ChronoUnit.DAYS.between(start, day) < amountCents % days) 1 else 0
}
internal fun rentalPlanMap(plan: MonthlyRentalPlan): Map<String, Any> = mapOf(
    "model" to plan.model, "periodMonth" to plan.periodMonth, "startDate" to plan.startDate,
    "endDate" to plan.endDate, "feeCents" to plan.feeCents, "daysInMonth" to plan.daysInMonth,
    "dailyCostCents" to plan.dailyCostCents, "note" to plan.note, "paidDate" to plan.paidDate,
    "paymentMethod" to plan.paymentMethod, "archivedPlans" to plan.archivedPlans.map(::rentalPlanMap))
internal fun JSONObject.toRentalPlan(): MonthlyRentalPlan = MonthlyRentalPlan(
    model = optString("model"), periodMonth = optString("periodMonth"), startDate = optString("startDate"), endDate = optString("endDate"),
    feeCents = if (has("feeCents")) getLong("feeCents") else optLong("monthlyFee") * 100,
    daysInMonth = optInt("daysInMonth", 30),
    dailyCostCents = if (has("dailyCostCents")) getLong("dailyCostCents") else optLong("dailyCost") * 100,
    note = optString("note"), paidDate = optString("paidDate"), paymentMethod = optString("paymentMethod", "轉帳"),
    archivedPlans = optJSONArray("archivedPlans")?.let { a -> List(a.length()) { a.getJSONObject(it).toRentalPlan().copy(archivedPlans = emptyList()) } }.orEmpty())
