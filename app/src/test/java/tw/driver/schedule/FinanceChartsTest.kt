package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FinanceChartsTest {
    @Test fun pendingTypesAndAllDetailRowsReconcileIncludingLongDaysAndLaterPayments() {
        val subsidy = (1L..20L).map { monthlyRide(it, "100.01") }
        val daycare = ride(30, "0").copy(category = "日照", daycareMonthly = "200.50")
        val future = monthlyRide(31, "999").copy(serviceDate = date.plusDays(1).toString())
        val incomplete = monthlyRide(32, "999").copy(completed = false)
        val records = subsidy + daycare + future + incomplete
        val partial = allocateCollection(records, mapOf("1:subsidy" to 5000L, "30:daycare" to 5050L), date, "轉帳", "partial")
        val paid = allocateCollection(partial, mapOf("2:subsidy" to 10001L), date.plusDays(1), "轉帳", "later")
        val subsidyEntries = pendingChartEntries(paid, date, SUBSIDY_RECEIPT)
        val daycareEntries = pendingChartEntries(paid, date, DAYCARE_RECEIPT)
        val all = pendingChartEntries(paid, date)
        assertEquals(2, all.size)
        assertEquals(195020L, subsidyEntries.single().amount)
        assertEquals(15000L, daycareEntries.single().amount)
        assertEquals(210020L, all.sumOf { it.amount })
        assertEquals(outstanding(paid, date).sumOf { it.remaining }, all.sumOf { it.amount })
        assertEquals(20, subsidyEntries.single().details.count { it.startsWith("待收：") })
        assertTrue(subsidyEntries.single().details.any { it.startsWith("乘客20 ·") })
        assertTrue(subsidyEntries.single().details.contains("待收合計：1950.20 元"))
        all.forEach { entry ->
            val displayed = entry.details.filter { it.startsWith("待收：") }
                .sumOf { cents(it.removePrefix("待收：").removeSuffix(" 元")) }
            assertEquals(entry.amount, displayed)
        }
        assertEquals(185019L, pendingChartEntries(paid, date.plusDays(1), SUBSIDY_RECEIPT)
            .first { it.name == date.toString() }.amount)
    }
    @Test fun pendingStripGroupsByDateAndShowsAsOfBalances() {
        val records = listOf(monthlyRide(1, "100.50"), monthlyRide(2, "200").copy(serviceDate = "2026-10-03"),
            monthlyRide(3, "50"), monthlyRide(4, "999").copy(completed = false))
        val paid = allocateCollection(records, mapOf("1:subsidy" to 5000L), date, "現金", "partial")
        val entries = pendingChartEntries(paid, date)
        assertEquals(listOf("2026-10-03", "2026-10-04"), entries.map { it.name })
        assertEquals(30050L, entries.sumOf { it.amount })
        assertTrue(entries.last().details.contains("應收：100.50 元"))
        assertTrue(entries.last().details.contains("已收：50 元"))
        assertTrue(entries.last().details.contains("待收：50.50 元"))
        assertEquals(listOf(1L), cashIncomeEntries(paid, date).single().rideIds)
    }
    @Test fun collectionStartIsInclusiveAndCanCrossMonths() {
        val records = listOf(monthlyRide(1, "100").copy(serviceDate = "2026-09-29"),
            monthlyRide(2, "200").copy(serviceDate = "2026-09-30"), monthlyRide(3, "50"),
            monthlyRide(4, "999").copy(serviceDate = "2026-10-05"))
        val entries = collectionOutstanding(records, LocalDate.parse("2026-09-30"), date, setOf(SUBSIDY_RECEIPT))
        assertEquals(listOf(2L, 3L), entries.map { it.ride.id })
        assertTrue(collectionOutstanding(records, date.plusDays(1), date, setOf(SUBSIDY_RECEIPT)).isEmpty())
    }
    @Test fun shortTapPinsButHoldingDraggingAndLeavingDoNot() {
        assertTrue(shouldPinChartTap(100, 3f, 500, 8f, true))
        assertFalse(shouldPinChartTap(500, 0f, 500, 8f, true))
        assertFalse(shouldPinChartTap(100, 9f, 500, 8f, true))
        assertFalse(shouldPinChartTap(100, 0f, 500, 8f, false))
    }
    @Test fun costStripGroupsCategoriesAndUsesDailyAllocation() {
        val expenses = listOf(
            ExpenseItem(id = 1, date = date.toString(), category = "停車費", amountCents = 10050),
            ExpenseItem(id = 2, date = date.toString(), category = "停車費", amountCents = 50),
            ExpenseItem(id = 3, date = date.toString(), category = "其他", amountCents = 0),
            ExpenseItem(id = 4, date = "2026-10-03", category = "其他", amountCents = 9900))
        val rental = MonthlyRentalPlan(startDate = date.toString(), endDate = "2026-10-06", feeCents = 10000)
        val entries = workCostEntries(expenses, rental, date)
        assertEquals(listOf("停車費", "租金分攤"), entries.map { it.name })
        assertEquals(10100L, entries.first().amount)
        assertEquals(3334L, entries.last().amount)
        assertEquals(13434L, entries.sumOf { it.amount })
        assertTrue(entries.first().details.any { it.startsWith("合併成本") })
        assertEquals("乘客付款", receiptKindLabel(CUSTOMER_RECEIPT))
    }
    private val date = LocalDate.parse("2026-10-04")
    private fun ride(id: Long, amount: String) = RideOrder(id = id, pickupTime = "09:00", pickup = "A", destination = "B",
        customer = "乘客$id", serviceDate = date.toString(), completed = true, receiptsManaged = true, amountDue = amount)

    private fun monthlyRide(id: Long, amount: String) = ride(id, "0").copy(category = "補助", subsidyDue = amount)

    @Test fun workChartExcludesZerosIncompleteAndOtherDatesAndKeepsExactTotals() {
        val entries = workRevenueEntries(listOf(ride(1, "100.50"), ride(2, "0"), ride(3, "200"),
            ride(4, "999").copy(completed = false), ride(5, "999").copy(serviceDate = "2026-10-03")), date)
        assertEquals(listOf("乘客3", "乘客1"), entries.map { it.name })
        assertEquals(30050L, entries.sumOf { it.amount })
        assertTrue(entries.all { entry -> entry.details.none { it.endsWith("：0 元") } })
    }
    @Test fun cashChartsUsePaymentDateAndGroupPeopleWithoutLosingBatchLinks() {
        val records = listOf(monthlyRide(1, "100"), monthlyRide(2, "200"))
        val collected = allocateCollection(records, mapOf("1:subsidy" to 10000L, "2:subsidy" to 20000L), date, "轉帳", "batch")
        val income = cashIncomeEntries(collected, date)
        assertEquals(2, income.size); assertEquals(30000L, income.sumOf { it.amount })
        assertTrue(income.all { it.batchIds == listOf("batch") })
        assertTrue(cashIncomeEntries(collected, date.minusDays(1)).isEmpty())
        val expenses = listOf(ExpenseItem(id = 1, date = "2026-09-01", category = "停車費", amountCents = 10050, paidDate = date.toString()),
            ExpenseItem(id = 2, date = date.toString(), category = "其他", amountCents = 99900, paidDate = ""))
        val rental = MonthlyRentalPlan(startDate = "2026-09-01", endDate = "2026-09-30", feeCents = 2400000, paidDate = date.toString())
        assertEquals(2410050L, cashExpenseEntries(expenses, rental, date).sumOf { it.amount })
    }
    @Test fun roundTripsAreCombinedAndDetailedSeparately() {
        val outgoing = ride(1, "100.50").copy(customer = "王先生")
        val returning = ride(2, "200").copy(customer = " 王先生 ", returnRide = true)
        val entries = workRevenueEntries(listOf(outgoing, returning), date)
        assertEquals(1, entries.size); assertEquals("王先生", entries.single().name)
        assertEquals(30050L, entries.single().amount); assertTrue(entries.single().roundTrip)
        assertTrue(entries.single().details.any { it.startsWith("去程") })
        assertTrue(entries.single().details.any { it.startsWith("回程") })
        val first = allocateCollection(listOf(outgoing.copy(category = "補助", amountDue = "0", subsidyDue = "100.50"), returning.copy(category = "補助", amountDue = "0", subsidyDue = "200")), mapOf("1:subsidy" to 10050L), date, "現金", "out")
        val paid = allocateCollection(first, mapOf("2:subsidy" to 20000L), date, "轉帳", "return")
        val cash = cashIncomeEntries(paid, date).single()
        assertEquals(30050L, cash.amount); assertTrue(cash.roundTrip)
        assertEquals(listOf("out", "return"), cash.batchIds)
    }
    @Test fun unknownNamesRemainSeparateAndScrubUsesExactBoundaries() {
        val entries = workRevenueEntries(listOf(ride(1, "300").copy(customer = ""), ride(2, "100").copy(customer = "")), date)
        assertEquals(2, entries.size)
        assertEquals(entries[0], revenueEntryAt(entries, 74.9f, 100f, 20f, 44f))
        assertEquals(entries[1], revenueEntryAt(entries, 75f, 100f, 20f, 44f))
        assertNull(revenueEntryAt(entries, 100f, 100f, 20f, 44f))
        assertNull(revenueEntryAt(entries, 75f, 100f, 44f, 44f))
        assertNull(revenueEntryAt(entries, -1f, 100f, 20f, 44f))
        assertEquals(entries[0], revenueEntryAt(entries, 10f, 100f, 20f, 44f))
    }
}
