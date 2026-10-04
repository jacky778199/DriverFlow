package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test

class PassengerReportRulesTest {
    private val rules = PassengerReportRules(listOf(PassengerReportRule("王小明", "王家群組"), PassengerReportRule("Alice Chen", "Alice Family")))
    private fun ride(customer: String, target: String = "") = RideOrder(customer = customer, reportTarget = target,
        pickupTime = "09:00", pickup = "甲", destination = "乙")
    @Test fun usesFullPassengerNameAndNormalizesWhitespaceAndCase() {
        assertEquals("王家群組", rules.target(" 王小明 "))
        assertEquals("Alice Family", rules.target(" ALICE   Chen "))
        assertEquals("", rules.target("王"))
        assertEquals("", rules.target("王小明家屬"))
        assertEquals("", rules.target(""))
    }
    @Test fun fillsMissingTargetAndPreservesManualOverrides() {
        assertEquals("王家群組", rules.apply(ride("王小明")).reportTarget)
        assertEquals("個別回報對象", rules.apply(ride("王小明", "個別回報對象")).reportTarget)
        assertEquals("", rules.apply(ride("陌生乘客")).reportTarget)
        assertEquals("王家群組", rules.apply(ride("王小明").copy(returnRide = true)).reportTarget)
    }
    @Test fun validatesDuplicateNamesEmptyFieldsAndLimits() {
        rules.validate()
        for (invalid in listOf(
            listOf(PassengerReportRule("", "群組")), listOf(PassengerReportRule("王先生", "")),
            listOf(PassengerReportRule("Alice", "甲"), PassengerReportRule(" ALICE ", "乙")),
            listOf(PassengerReportRule("王\n先生", "群組")),
            List(201) { PassengerReportRule("乘客$it", "群組") }
        )) assertTrue(runCatching { PassengerReportRules(invalid).validate() }.isFailure)
    }
    @Test fun roundTripsRulesAndAutomaticallyFilledRide() {
        assertEquals(rules.items, passengerReportRulesFromJson(rules.toJson().toString()).items)
        val filled = rules.apply(ride("王小明"))
        assertEquals(filled.reportTarget, filled.toJson().toOrder().reportTarget)
        assertEquals(emptyList<PassengerReportRule>(), passengerReportRulesFromJson("[]").items)
    }
    @Test fun pairedNewCasesEachGetTheCustomerDefault() {
        val outbound = ride("王小明").copy(id = 1, bookingId = "booking")
        val inbound = outbound.copy(id = 2, returnRide = true)
        val saved = pairedOrdersToSave(listOf(outbound, inbound), outbound).map { rules.apply(it) }
        assertEquals(listOf("王家群組", "王家群組"), saved.map { it.reportTarget })
    }
}
