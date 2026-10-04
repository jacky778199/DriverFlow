package tw.driver.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class PassengerReportRule(val customer: String, val target: String)
internal fun passengerNameKey(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
internal class PassengerReportRules(val items: List<PassengerReportRule> = emptyList()) {
    fun validate() {
        require(items.size <= 200) { "最多 200 組乘客回報對照" }
        require(items.all { it.customer.isNotBlank() && it.target.isNotBlank() }) { "每組請填乘客姓名與回報對象" }
        require(items.all { it.customer.length <= 100 && it.target.length <= 200 &&
            it.customer.none { char -> char == '\n' || char == '\r' } && it.target.none { char -> char == '\n' || char == '\r' } }) { "姓名最多 100 字、回報對象最多 200 字，且不能換行" }
        require(items.map { passengerNameKey(it.customer) }.distinct().size == items.size) { "同一乘客姓名只能設定一次" }
    }
    fun target(customer: String): String = items.firstOrNull { passengerNameKey(it.customer) == passengerNameKey(customer) }?.target.orEmpty()
    fun apply(ride: RideOrder): RideOrder = if (ride.reportTarget.isBlank()) ride.copy(reportTarget = target(ride.customer)) else ride
}
internal fun PassengerReportRules.toJson() = JSONArray(items.map { JSONObject().put("customer", it.customer).put("target", it.target) })
internal fun passengerReportRulesFromJson(text: String): PassengerReportRules {
    val array = JSONArray(text)
    require(array.length() <= 200) { "最多 200 組乘客回報對照" }
    return PassengerReportRules(List(array.length()) { index -> array.getJSONObject(index).let {
        PassengerReportRule(it.getString("customer"), it.getString("target"))
    } }).also { it.validate() }
}
internal class PassengerReportRulesStore(context: Context) {
    private val prefs = context.getSharedPreferences("passenger_report_rules", Context.MODE_PRIVATE)
    fun load() = passengerReportRulesFromJson(prefs.getString("items", "[]").orEmpty())
    fun save(rules: PassengerReportRules) {
        rules.validate()
        check(prefs.edit().putString("items", rules.toJson().toString()).commit()) { "儲存失敗，請重試" }
    }
}
