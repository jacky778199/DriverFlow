package tw.driver.schedule

import java.math.BigDecimal
import java.math.RoundingMode
import org.json.JSONObject

/** Money is always stored and added as integer cents. Yuan strings are UI boundaries only. */
@JvmInline
value class Money(val cents: Long) {
    operator fun plus(other: Money) = Money(Math.addExact(cents, other.cents))
    operator fun minus(other: Money) = Money(Math.subtractExact(cents, other.cents))
    fun yuan(): String = if (cents % 100 == 0L) (cents / 100).toString() else BigDecimal.valueOf(cents, 2).toPlainString()
    companion object {
        fun parse(value: String): Money? = if (!Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(value.trim())) null
            else runCatching { Money(BigDecimal(value.trim()).movePointRight(2).longValueExact()) }.getOrNull()
    }
}
internal fun cents(value: String): Long = Money.parse(value)?.cents ?: 0L
internal fun yuan(value: Long): String = Money(value).yuan()
internal fun hourlyYuan(value: Long, minutes: Int): String? = if (minutes <= 0) null else
    BigDecimal.valueOf(value).multiply(BigDecimal(60)).divide(BigDecimal(minutes), 0, RoundingMode.HALF_UP)
        .let { yuan(it.longValueExact()) }
internal fun JSONObject.putMoney(key: String, value: String) {
    put("${key}Cents", if (value.isBlank()) JSONObject.NULL else Money.parse(value)?.cents
        ?: error("$key 金額格式不正確"))
}
internal fun JSONObject.readMoney(key: String): String = if (has("${key}Cents")) {
    if (isNull("${key}Cents")) "" else yuan(getLong("${key}Cents"))
} else optString(key)
