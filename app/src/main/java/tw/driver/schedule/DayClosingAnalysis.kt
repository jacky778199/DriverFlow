package tw.driver.schedule

import java.math.BigDecimal
import java.math.RoundingMode

internal data class CaseIncomeContribution(val category: String, val amount: BigDecimal, val total: BigDecimal,
    val passenger: BigDecimal = BigDecimal.ZERO, val tips: BigDecimal = BigDecimal.ZERO,
    val monthly: BigDecimal = BigDecimal.ZERO) {
    val fraction: Float get() = if (total.signum() == 0) 0f else amount.divide(total, 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
    val percent: String get() = if (total.signum() == 0) "0" else amount.multiply(BigDecimal(100)).divide(total, 1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

internal fun caseIncomeContributions(rides: List<RideOrder>): List<CaseIncomeContribution> {
    val totals = linkedMapOf("自費" to longArrayOf(0, 0, 0), "補助單" to longArrayOf(0, 0, 0),
        "日照" to longArrayOf(0, 0, 0), "其他" to longArrayOf(0, 0, 0))
    rides.filter { it.completed }.forEach { ride ->
        val category = when (ride.category) {
            in subsidyCategories -> "補助單"
            "自費", "日照" -> ride.category
            else -> "其他"
        }
        val parts = totals.getValue(category)
        val duplicate = !ride.receiptsManaged && ride.category == "日照" && ride.daycareMonthly.isNotBlank() && cents(ride.received) == cents(ride.daycareMonthly)
        parts[0] += if (duplicate) 0L else dueCents(ride, CUSTOMER_RECEIPT)
        parts[1] += cents(ride.tip) // Informational subset of passenger payment, never added to income.
        parts[2] += dueCents(ride, SUBSIDY_RECEIPT) + dueCents(ride, DAYCARE_RECEIPT)
    }
    val total = BigDecimal(yuan(totals.values.sumOf { it[0] + it[2] }))
    return totals.filter { (category, parts) -> category != "其他" || parts[0] + parts[2] > 0 }
        .map { (category, parts) -> CaseIncomeContribution(category, BigDecimal(yuan(parts[0] + parts[2])), total,
            BigDecimal(yuan(parts[0])), BigDecimal(yuan(parts[1])), BigDecimal(yuan(parts[2]))) }
}

internal fun homeToHomeDurationLabel(departure: String, home: String): String {
    val start = normalizeTime(departure)
    val finish = normalizeTime(home)
    val issues = buildList {
        if (start == null) add(if (departure.isBlank()) "出門時間未填" else "出門時間格式不正確")
        if (finish == null) add(if (home.isBlank()) "回家時間未填" else "回家時間格式不正確")
    }
    if (issues.isNotEmpty()) return "待確認（${issues.joinToString("、")}）"
    val minutes = netWorkMinutes(start!!, finish!!) ?: return "待確認"
    return "${minutes / 60} 小時 ${minutes % 60} 分${if (minuteOfDay(finish)!! < minuteOfDay(start)!!) "（跨日）" else ""}"
}
