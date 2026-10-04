package tw.driver.schedule

import android.content.SharedPreferences
import java.math.BigDecimal
import java.time.LocalDate

internal data class DailyJourney(
    val startPoint: String = "",
    val endPoint: String = "",
    val startOdometer: String = "",
    val endOdometer: String = ""
) {
    fun distanceKm(): String? {
        val start = startOdometer.toBigDecimalOrNull() ?: return null
        val end = endOdometer.toBigDecimalOrNull() ?: return null
        return (end - start).takeIf { it >= BigDecimal.ZERO }?.stripTrailingZeros()?.toPlainString()
    }

    fun error(): String? {
        if (startPoint.length > 200 || endPoint.length > 200) return "起點與終點最多 200 字"
        val format = Regex("\\d{1,8}(?:\\.\\d{1,2})?")
        if (listOf(startOdometer, endOdometer).any { it.isNotBlank() && !format.matches(it) })
            return "里程表請輸入非負數字，最多兩位小數"
        if (startOdometer.isNotBlank() && endOdometer.isNotBlank() && distanceKm() == null)
            return "結束里程不可小於出發里程"
        return null
    }
}

internal fun loadDailyJourney(prefs: SharedPreferences, date: String) = DailyJourney(
    prefs.getString("day_start_point_$date", "").orEmpty(),
    prefs.getString("day_end_point_$date", "").orEmpty(),
    prefs.getString("day_start_odometer_$date", "").orEmpty(),
    prefs.getString("day_end_odometer_$date", "").orEmpty()
)

internal fun saveDailyJourney(prefs: SharedPreferences, date: String, journey: DailyJourney): Boolean =
    prefs.edit()
        .putString("day_start_point_$date", journey.startPoint)
        .putString("day_end_point_$date", journey.endPoint)
        .putString("day_start_odometer_$date", journey.startOdometer)
        .putString("day_end_odometer_$date", journey.endOdometer)
        .commit()

internal fun saveDailyEndpoint(prefs: SharedPreferences, date: String, departure: Boolean,
    time: String, point: String, odometer: String): Boolean {
    val prefix = if (departure) "day_start" else "day_end"
    return prefs.edit()
        .putString(if (departure) "departure_time_$date" else "return_home_time_$date", time)
        .putString("${prefix}_point_$date", point)
        .putString("${prefix}_odometer_$date", odometer)
        .commit()
}

/** Only adjacent calendar days can fill a missing odometer reading. */
internal fun effectiveDailyJourney(date: LocalDate, load: (LocalDate) -> DailyJourney): DailyJourney {
    val current = load(date)
    return current.copy(
        startOdometer = current.startOdometer.ifBlank { load(date.minusDays(1)).endOdometer },
        endOdometer = current.endOdometer.ifBlank { load(date.plusDays(1)).startOdometer }
    )
}
