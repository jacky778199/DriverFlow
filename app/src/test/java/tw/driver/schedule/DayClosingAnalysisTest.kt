package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test

class DayClosingAnalysisTest {
    @Test fun managedSelfPayContributionReconcilesWithPassengerCashAndTipsExactly() {
        val rides = listOf(ride("自費").copy(receiptsManaged = true, amountDue = "180.10", received = "0", tip = "20.20"),
            ride("自費").copy(receiptsManaged = true, amountDue = "50", tip = "0.01"),
            ride("自費").copy(completed = false, receiptsManaged = true, amountDue = "999", tip = "999"))
        val row = caseIncomeContributions(rides).first()
        val summary = daySummary(rides)
        assertEquals(23010L, cents(row.passenger.toPlainString()))
        assertEquals(2021L, cents(row.tips.toPlainString()))
        assertEquals(23010L, cents(row.amount.toPlainString()))
        assertEquals(0, row.passenger.compareTo(summary.selfPayCash))
        assertEquals(0, row.amount.compareTo(summary.selfPayCash))
        assertEquals(rides.filter { it.completed }.sumOf(::operatingRevenueCents), cents(row.total.toPlainString()))
    }
    @Test fun selfPayIgnoresStaleMonthlyFields() {
        val r = ride("自費").copy(receiptsManaged = true, amountDue = "100", subsidyDue = "600", tip = "20")
        val row = caseIncomeContributions(listOf(r)).first()
        assertEquals(0L, cents(row.monthly.toPlainString()))
        assertEquals(10000L, cents(row.amount.toPlainString()))
        assertEquals(10000L, cents(daySummary(listOf(r)).selfPayCash.toPlainString()))
    }
    private fun ride(category: String) = RideOrder(pickupTime = "09:00", pickup = "甲", destination = "乙", category = category, completed = true)
    @Test fun incomeSharesIncludeTipsAndBilledMonthlyAmountsButNotCollections() {
        val self = ride("自費").copy(received = "180", tip = "20")
        val subsidy = ride("補助").copy(received = "40", tip = "10", subsidyDue = "250",
            monthlyReceipts = listOf(MonthlyReceipt("a", "2026-10-02", SUBSIDY_RECEIPT, "250")))
        val daycare = ride("日照").copy(daycareMonthly = "500")
        val incomplete = self.copy(completed = false, received = "10000")
        val rows = caseIncomeContributions(listOf(self, subsidy, daycare, incomplete))
        assertEquals(listOf("自費", "補助單", "日照"), rows.map { it.category })
        assertEquals(listOf("180", "290", "500"), rows.map { it.amount.toPlainString() })
        assertEquals(listOf("18.6", "29.9", "51.5"), rows.map { it.percent })
        assertEquals("970", rows.first().total.toPlainString())
        assertEquals(1f, rows.sumOf { it.fraction.toDouble() }.toFloat(), 0.0001f)
    }
    @Test fun emptyRevenueShowsZeroSharesAndUnclassifiedRevenueIsIncluded() {
        val empty = caseIncomeContributions(emptyList())
        assertEquals(3, empty.size)
        assertTrue(empty.all { it.percent == "0" && it.fraction == 0f })
        val other = caseIncomeContributions(listOf(ride("未分類").copy(received = "30.50")))
        assertEquals("其他", other.last().category)
        assertEquals("100", other.last().percent)
        assertEquals("30.50", other.last().total.toPlainString())
    }
    @Test fun legacyDaycareIsNotCountedTwiceAndOldDueDoesNotContribute() {
        val rows = caseIncomeContributions(listOf(ride("日照").copy(received = "500", daycareMonthly = "500"),
            ride("自費").copy(amountDue = "1000", received = "100")))
        assertEquals("600", rows.first().total.toPlainString())
        assertEquals("500", rows.last().amount.toPlainString())
    }
    @Test fun homeToHomeShowsFullElapsedTimeAndHandlesMidnight() {
        assertEquals("10 小時 30 分", homeToHomeDurationLabel("07:30", "18:00"))
        assertEquals("2 小時 45 分（跨日）", homeToHomeDurationLabel("22:30", "01:15"))
        assertEquals("10 小時 30 分", homeToHomeDurationLabel("0730", "1800"))
    }
    @Test fun homeToHomeNamesMissingOrInvalidFields() {
        assertTrue(homeToHomeDurationLabel("", "18:00").contains("出門時間未填"))
        assertTrue(homeToHomeDurationLabel("07:30", "").contains("回家時間未填"))
        assertTrue(homeToHomeDurationLabel("", "").contains("出門時間未填、回家時間未填"))
        assertTrue(homeToHomeDurationLabel("25:00", "18:00").contains("出門時間格式不正確"))
    }
}
