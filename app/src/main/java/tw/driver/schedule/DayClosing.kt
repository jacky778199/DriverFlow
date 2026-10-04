package tw.driver.schedule

import java.math.BigDecimal
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

internal val cashCategories = setOf("自費", "補助", "補助單")
internal val subsidyCategories = setOf("補助", "補助單")
data class MonthlyReceipt(val id: String, val date: String, val kind: String, val amount: String,
    val method: String = "轉帳", val batchId: String = id)
internal const val SUBSIDY_RECEIPT = "subsidy"
internal const val DAYCARE_RECEIPT = "daycare"
internal fun receiptKindLabel(kind: String) = when (kind) {
    CUSTOMER_RECEIPT -> "乘客付款"
    TIP_RECEIPT -> "小費"
    SUBSIDY_RECEIPT -> "月結補助"
    else -> "月結日照"
}
internal fun money(value: String): BigDecimal = value.toBigDecimalOrNull()?.takeIf { it >= BigDecimal.ZERO } ?: BigDecimal.ZERO
internal fun monthlyPaid(ride: RideOrder, kind: String): BigDecimal = ride.monthlyReceipts.filter { it.kind == kind }
    .fold(BigDecimal.ZERO) { total, receipt -> total + money(receipt.amount) }
internal fun monthlyRemaining(ride: RideOrder, kind: String): BigDecimal =
    (money(if (kind == SUBSIDY_RECEIPT) ride.subsidyDue else ride.daycareMonthly) - monthlyPaid(ride, kind)).max(BigDecimal.ZERO)

internal fun migrateRideMoney(ride: RideOrder, legacy: Boolean): RideOrder {
    if (!legacy) return ride
    return when (ride.category) {
        in subsidyCategories -> ride.copy(amountDue = ride.received, received = "")
        "日照" -> ride.copy(daycareMonthly = ride.daycareMonthly.ifBlank { ride.received }, received = "")
        else -> ride.copy(amountDue = ride.received)
    }
}
internal fun monthlyReceiptsJson(receipts: List<MonthlyReceipt>) = JSONArray(receipts.map {
    JSONObject().put("id", it.id).put("date", it.date).put("kind", it.kind).put("amountCents", cents(it.amount))
        .put("method", it.method).put("batchId", it.batchId)
})
internal fun parseMonthlyReceipts(array: JSONArray?): List<MonthlyReceipt> = if (array == null) emptyList() else {
    require(array.length() <= 1000) { "月結收款紀錄過多" }
    List(array.length()) { i -> array.getJSONObject(i).let { MonthlyReceipt(it.getString("id"), it.getString("date"), it.getString("kind"),
        it.readMoney("amount"), it.optString("method", "轉帳"), it.optString("batchId", it.getString("id"))) } }
}
internal fun rideMoneyValidation(ride: RideOrder): List<String> = buildList {
    for ((label, value) in listOf("乘客付款" to (if (ride.receiptsManaged) ride.amountDue else ""), "已收金額" to ride.received, "小費" to ride.tip,
        "月結補助" to ride.subsidyDue, "月結日照" to ride.daycareMonthly)) {
        if (value.isNotBlank() && !Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(value)) add("$label 請填非負金額，最多兩位小數")
    }
    if (ride.monthlyReceipts.map { it.id }.distinct().size != ride.monthlyReceipts.size) add("月結收款紀錄 ID 重複")
    ride.monthlyReceipts.forEach {
        if (it.id.isBlank() || runCatching { LocalDate.parse(it.date) }.isFailure || it.kind !in receiptKinds && it.kind != TIP_RECEIPT || it.method !in listOf("現金", "轉帳") ||
            !Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(it.amount) || money(it.amount) <= BigDecimal.ZERO) add("月結收款日期或金額無效")
    }
    for ((kind, total) in listOf(SUBSIDY_RECEIPT to ride.subsidyDue, DAYCARE_RECEIPT to ride.daycareMonthly)) {
        if (monthlyPaid(ride, kind) > money(total)) add("${receiptKindLabel(kind)}已收款不可超過該趟月結金額")
    }
    if (ride.receiptsManaged) for (kind in listOf(CUSTOMER_RECEIPT)) {
        if (paidCents(ride, kind) > dueCents(ride, kind)) add("${receiptKindLabel(kind)}已收不可超過應收金額")
    }
}

internal data class DayReviewItem(val ride: RideOrder, val issues: List<String>)
internal fun dayReviewItems(rides: List<RideOrder>): List<DayReviewItem> {
    val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    return sorted.mapIndexedNotNull { index, ride ->
        val issues = buildList {
            if (!ride.completed) add("尚未完成：請確認行程完成狀態")
            if (ride.customer.isBlank()) add("乘客姓名未填")
            if (ride.pickup.isBlank() || ride.pickup.contains("待填") || ride.pickup.contains("待確認")) add("上車地址未填或待確認")
            if (ride.destination.isBlank() || ride.destination.contains("待填") || ride.destination.contains("待確認")) add("下車地址未填或待確認")
            if (ride.category !in cashCategories && ride.category != "日照") add("行程類型未分類")
            if (minuteOfDay(ride.pickupTime) == null) add("接客時間未填或格式不正確")
            if (ride.category in cashCategories) {
                if (ride.receiptsManaged) {
                    if (ride.amountDue.isBlank()) add("乘客付款未填（沒有請填 0）")
                } else if (ride.received.isBlank()) add("已收金額未填（沒有收款請填 0）")
            }
            if (ride.category in subsidyCategories && ride.subsidyDue.isBlank()) add("月結補助金額未填（沒有請填 0）")
            if (ride.category == "日照" && ride.daycareMonthly.isBlank()) add("月結日照金額未填（沒有請填 0）")
            if (ride.completed) {
                val boarded = actualRideInstant(ride.actualBoardedAt, ride.serviceDate)
                val alighted = actualRideInstant(ride.actualAlightedAt, ride.serviceDate)
                if (boarded == null) add("實際上車時間${if (ride.actualBoardedAt.isBlank()) "未填" else "格式不正確"}")
                if (alighted == null) add("實際下車時間${if (ride.actualAlightedAt.isBlank()) "未填" else "格式不正確"}")
                if (boarded != null && alighted != null && alighted < boarded) add("實際下車時間早於上車時間")
                if (billedRideSeconds(ride, sorted.getOrNull(index - 1)) == null) add("載客計費時間未填，且沒有有效路線估算")
            }
            addAll(rideMoneyValidation(ride))
        }
        if (issues.isEmpty()) null else DayReviewItem(ride, issues)
    }
}
internal fun rideReviewLabel(ride: RideOrder) = "${ride.pickupTime.ifBlank { "時間未填" }} · ${ride.customer.ifBlank { "乘客未填" }} · ${if (ride.returnRide) "回程" else "去程"}"
internal data class DatedMonthlyReceipt(val ride: RideOrder, val receipt: MonthlyReceipt)
internal fun monthlyReceiptsOn(rides: List<RideOrder>, date: LocalDate): List<DatedMonthlyReceipt> = rides.flatMap { ride ->
    ride.monthlyReceipts.filter { it.date == date.toString() }.map { DatedMonthlyReceipt(ride, it) }
}
