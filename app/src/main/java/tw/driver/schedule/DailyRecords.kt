package tw.driver.schedule

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

internal fun formatWeekday(date: LocalDate): String = when (date.dayOfWeek) {
    DayOfWeek.MONDAY -> "一"
    DayOfWeek.TUESDAY -> "二"
    DayOfWeek.WEDNESDAY -> "三"
    DayOfWeek.THURSDAY -> "四"
    DayOfWeek.FRIDAY -> "五"
    DayOfWeek.SATURDAY -> "六"
    DayOfWeek.SUNDAY -> "日"
    else -> ""
}

internal fun formatDateWithWeekday(date: LocalDate): String =
    "${date.format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))} (${formatWeekday(date)})"

internal fun fullRideDate(value: String, today: LocalDate = LocalDate.now()): String {
    val m = Regex("^(?:(\\d{4})[/年.-])?(\\d{1,2})[/月.-](\\d{1,2})").find(value.trim()) ?: return if (value.isBlank() || value == "今天" || value.contains("日期待")) today.toString() else value
    return runCatching { LocalDate.of(m.groupValues[1].toIntOrNull() ?: today.year, m.groupValues[2].toInt(), m.groupValues[3].toInt()).toString() }.getOrDefault(value)
}
internal fun recordValidation(date: String, received: String, vararg minutes: String): List<String> = buildList {
    if (runCatching { LocalDate.parse(fullRideDate(date)) }.isFailure) add("請填寫有效完整日期")
    if (received.isNotBlank() && !Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(received)) add("實收金額請填非負數字，最多兩位小數")
    if (minutes.any { it.isNotBlank() && (it.toIntOrNull() == null || it.toInt() !in 0..1440) }) add("車程請填 0 到 1440 分鐘")
}
internal fun minuteOfDay(value: String): Int? = Regex("^(\\d{1,2}):(\\d{2})").find(value)?.let {
    val h = it.groupValues[1].toInt(); val m = it.groupValues[2].toInt()
    if (h in 0..23 && m in 0..59) h * 60 + m else null
}
internal fun calculateEta(pickupTime: String, minutes: Long?): String? {
    if (minutes == null) return null
    val start = minuteOfDay(pickupTime) ?: return null
    val total = (start + minutes) % 1440
    return "%02d:%02d".format(total / 60, total % 60)
}
internal fun revenue(rides: List<RideOrder>): BigDecimal = rides.filter { it.completed }.fold(BigDecimal.ZERO) { total, ride -> total + (ride.received.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
internal fun exportRecords(rides: List<RideOrder>): String = JSONObject().put("schemaVersion", 1).put("exportedAt", java.time.Instant.now().toString()).put("rides", JSONArray().apply { rides.forEach { put(it.copy(sourceImage = "").toJson()) } }).toString(2)
internal fun importRecords(text: String): List<RideOrder> {
    val root = JSONObject(text)
    require(root.getInt("schemaVersion") == 1) { "不支援的資料版本" }
    val a = root.getJSONArray("rides")
    require(a.length() <= 10000) { "最多匯入一萬筆" }
    val rides = List(a.length()) { a.getJSONObject(it).toOrder().copy(sourceImage = "") }
    require(rides.map { it.id }.distinct().size == rides.size) { "檔案含重複訂單 ID" }
    rides.forEach { require((recordValidation(it.serviceDate, it.received, it.rideMinutes, it.transferMinutes) + subsidyValidation(it.subsidyDue)).isEmpty()) { "日期或金額格式錯誤" } }
    return rides
}
internal fun exportCsv(rides: List<RideOrder>): String {
    fun cell(v: String): String = "\"" + (if (v.trimStart().firstOrNull() in listOf('=', '+', '-', '@')) "'" + v else v).replace("\"", "\"\"") + "\""
    val rows = rides.map { listOf(it.id.toString(), it.serviceDate, it.pickupTime, it.category, it.customer, it.pickup, it.destination, it.completed.toString(), it.fare, it.received, it.tip, it.rideMinutes, it.transferMinutes, it.notes, it.subsidyDue) }
    return "\uFEFF" + (listOf(listOf("id", "date", "pickup_time", "category", "customer", "pickup", "destination", "completed", "quoted_fare", "received_twd", "tip_twd", "ride_minutes", "transfer_minutes", "notes", "subsidy_due_twd")) + rows).joinToString("\r\n") { it.joinToString(",", transform = ::cell) }
}


internal fun subsidyValidation(value: String): List<String> =
    if (value.isBlank() || Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(value)) emptyList() else listOf("待收補助請填非負金額，最多兩位小數")

internal fun passengerStatusText(ride: RideOrder, boarding: Boolean): String =
    "${ride.customer} ${if (ride.returnRide) "回程" else "去程"} ${if (boarding) "客上" else "客下"}"

/** Manual billing duration takes precedence; only a matching route estimate can fill a blank. */
internal fun billedRideSeconds(ride: RideOrder, previous: RideOrder?): Long? =
    ride.rideMinutes.toLongOrNull()?.takeIf { it in 0..1440 }?.times(60)
        ?: RouteEstimate.parse(ride.routeEstimate)?.takeIf { it.key == routeInputKey(ride, previous) && it.rideSeconds >= 0 }?.rideSeconds

internal data class DaySummary(val scheduled: Int, val completed: Int, val selfPay: Int, val subsidized: Int,
    val daycare: Int, val seconds: Long, val missingTime: Int, val receipts: BigDecimal, val subsidy: BigDecimal,
    val tips: BigDecimal, val missingReceipts: Int, val missingSubsidy: Int)
internal fun daySummary(rides: List<RideOrder>): DaySummary {
    val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    val completed = sorted.filter { it.completed }
    val seconds = sorted.mapIndexedNotNull { i, r -> if (r.completed) billedRideSeconds(r, sorted.getOrNull(i - 1)) else null }
    return DaySummary(sorted.size, completed.size, completed.count { it.category == "自費" },
        completed.count { it.category in listOf("補助", "補助單") }, completed.count { it.category == "日照" },
        seconds.sum(), completed.size - seconds.size, revenue(completed),
        completed.fold(BigDecimal.ZERO) { total, ride -> total + (ride.subsidyDue.toBigDecimalOrNull() ?: BigDecimal.ZERO) },
        completed.fold(BigDecimal.ZERO) { total, ride -> total + (ride.tip.toBigDecimalOrNull() ?: BigDecimal.ZERO) },
        completed.count { it.received.isBlank() }, completed.count { it.category in listOf("補助", "補助單") && it.subsidyDue.isBlank() })
}
