package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class FinanceTest {
    @Test fun tipRecordNeverChangesRevenueCashOrOutstandingAndSurvivesBackup() {
        val original = ride(1, subsidy = "100").copy(amountDue = "250")
        val tipped = original.copy(tip = "50", monthlyReceipts = listOf(
            MonthlyReceipt("old-tip", september.toString(), TIP_RECEIPT, "50", "現金")))
        assertEquals(operatingRevenueCents(original), operatingRevenueCents(tipped))
        assertEquals(collectedOn(listOf(original), september), collectedOn(listOf(tipped), september))
        assertEquals(outstanding(listOf(original), september).sumOf { it.remaining }, outstanding(listOf(tipped), september).sumOf { it.remaining })
        assertEquals(25000L, collectedOn(listOf(tipped), september))
        assertEquals(0L, dueCents(tipped, TIP_RECEIPT))
        assertTrue(effectiveReceipts(tipped).none { it.kind == TIP_RECEIPT })
        assertEquals("50", importRecords(exportRecords(listOf(tipped))).single().tip)
        val recordOnly = ride(2).copy(amountDue = "0", tip = "50")
        assertEquals(0L, operatingRevenueCents(recordOnly))
        assertEquals(0L, collectedOn(listOf(recordOnly), september))
    }
    @Test fun switchingTypeClearsOtherMonthlyAmountsAndReceiptAllocations() {
        val subsidy = ride(1, subsidy = "600").copy(amountDue = "200", tip = "20", daycareMonthly = "900",
            monthlyReceipts = listOf(MonthlyReceipt("s", payday.toString(), SUBSIDY_RECEIPT, "100"),
                MonthlyReceipt("d", payday.toString(), DAYCARE_RECEIPT, "200")))
        val self = changeRideIncomeType(subsidy, "自費")
        assertEquals("", self.subsidyDue); assertEquals("", self.daycareMonthly)
        assertTrue(self.monthlyReceipts.isEmpty()); assertEquals("200", self.amountDue)
        assertEquals("20", self.tip); assertEquals(20000L, operatingRevenueCents(self))
        val daycare = changeRideIncomeType(subsidy, "日照")
        assertEquals("", daycare.subsidyDue); assertEquals("900", daycare.daycareMonthly)
        assertEquals(listOf("d"), daycare.monthlyReceipts.map { it.id })
        assertEquals(110000L, operatingRevenueCents(daycare))
        val back = changeRideIncomeType(daycare, "補助單")
        assertEquals("", back.daycareMonthly); assertEquals("", back.subsidyDue)
        assertTrue(back.monthlyReceipts.isEmpty())
        assertEquals(80000L, operatingRevenueCents(subsidy))
        assertEquals(10000L, collectedOn(listOf(subsidy), payday))
    }
    private val september = LocalDate.parse("2026-09-30")
    private val payday = LocalDate.parse("2026-10-03")
    private fun ride(id: Long, subsidy: String = "", daycare: String = "") = RideOrder(
        id = id, date = september.toString(), serviceDate = september.toString(), pickupTime = "09:00",
        pickup = "A", destination = "B", completed = true, customer = "乘客$id",
        category = if (daycare.isNotEmpty()) "日照" else "補助單", receiptsManaged = true,
        amountDue = "0", subsidyDue = subsidy, daycareMonthly = daycare)

    @Test fun moneyParsesExactCentsAndRejectsHiddenRounding() {
        assertEquals(20050L, Money.parse("200.50")!!.cents)
        assertEquals(30L, (Money.parse("0.10")!! + Money.parse("0.20")!!).cents)
        assertEquals("200.50", Money(20050).yuan())
        listOf("1.001", "-1", "NaN", "1e3", "9999999999", "").forEach { assertNull(Money.parse(it)) }
    }
    @Test fun combinedMonthlyCollectionIsAllocatedWithoutIncreasingRevenue() {
        val rides = listOf(ride(1, subsidy = "1200.25"), ride(2, daycare = "500.50"))
        val before = rides.sumOf(::operatingRevenueCents)
        val updated = allocateCollection(rides, mapOf("1:subsidy" to 120025L, "2:daycare" to 50050L), payday, "轉帳", "batch")
        assertEquals(before, updated.sumOf(::operatingRevenueCents))
        assertEquals(170075L, collectedOn(updated, payday))
        assertEquals(0L, collectedOn(updated, september))
        assertTrue(outstanding(updated, payday).isEmpty())
        assertEquals(170075L, outstanding(updated, september).sumOf { it.remaining })
        assertEquals(1, updated.flatMap { it.monthlyReceipts }.map { it.batchId }.distinct().size)
        assertEquals(updated, importRecords(exportRecords(updated)))
    }
    @Test fun partialPaymentLeavesCorrectOutstandingAndCanBeReversed() {
        val original = listOf(ride(1, subsidy = "1200"), ride(2, daycare = "500"))
        val updated = allocateCollection(original, mapOf("1:subsidy" to 100000L, "2:daycare" to 50000L), payday, "現金", "partial")
        assertEquals(20000L, outstanding(updated, payday).sumOf { it.remaining })
        val reversed = updated.map { it.copy(monthlyReceipts = it.monthlyReceipts.filterNot { r -> r.batchId == "partial" }) }
        assertEquals(170000L, outstanding(reversed, payday).sumOf { it.remaining })
        assertEquals(0L, collectedOn(reversed, payday))
    }
    @Test fun blocksOverpaymentRepeatedCollectionAndBackdatedOverpayment() {
        val original = listOf(ride(1, subsidy = "100"))
        val updated = allocateCollection(original, mapOf("1:subsidy" to 10000L), payday, "轉帳")
        assertThrows(IllegalArgumentException::class.java) { allocateCollection(updated, mapOf("1:subsidy" to 1L), payday, "轉帳") }
        assertThrows(IllegalArgumentException::class.java) { allocateCollection(updated, mapOf("1:subsidy" to 1L), september, "轉帳") }
        assertThrows(IllegalArgumentException::class.java) { allocateCollection(original, mapOf("1:subsidy" to 10001L), payday, "轉帳") }
        assertThrows(IllegalArgumentException::class.java) { allocateCollection(original, mapOf("1:subsidy" to 1L), september.minusDays(1), "轉帳") }
    }
    @Test fun completedAndMonthFiltersAreApplied() {
        val rides = listOf(ride(1, subsidy = "100"), ride(2, daycare = "200").copy(completed = false),
            ride(3, subsidy = "300").copy(serviceDate = "2026-08-31"))
        assertEquals(listOf(1L), outstanding(rides, payday, YearMonth.parse("2026-09")).map { it.ride.id })
        assertEquals(40000L, outstanding(rides, payday).sumOf { it.remaining })
    }
    @Test fun rentalAllocationPreservesEveryCentAcrossMonths() {
        val rental = MonthlyRentalPlan(startDate = "2026-09-30", endDate = "2026-10-02", feeCents = 10000,
            paidDate = "2026-09-29")
        assertEquals(listOf(3334L, 3333L, 3333L), (0L..2L).map { rental.costCentsOn(september.plusDays(it)) })
        assertEquals(10000L, (0L..2L).sumOf { rental.costCentsOn(september.plusDays(it)) })
        assertEquals(0L, rental.costCentsOn(payday))
        val backup = importExpenseData(exportExpenseData(emptyList(), rental, emptyList()))
        assertEquals(rental, backup.rentalPlan)
    }
    @Test fun storageUsesCentsAndRetainsPaymentDates() {
        val expense = ExpenseItem(date = september.toString(), category = "停車費", amountCents = 10050,
            paidDate = payday.toString(), paymentMethod = "轉帳")
        val json = expense.toJson()
        assertFalse(json.has("amount")); assertEquals(10050L, json.getLong("amountCents"))
        assertEquals(expense, json.toExpenseItem())
        val r = ride(1, subsidy = "1200.25")
        assertFalse(r.toJson().has("subsidyDue")); assertEquals(120025L, r.toJson().getLong("subsidyDueCents"))
    }
    @Test fun passengerAndTipsAreCollectedOnceOnCompletedServiceDate() {
        val r = ride(1).copy(amountDue = "200.50", tip = "50", subsidyDue = "1200")
        assertEquals(140050L, operatingRevenueCents(r))
        assertEquals(20050L, collectedOn(listOf(r), september))
        assertEquals(0L, collectedOn(listOf(r), payday))
        assertEquals(120000L, outstanding(listOf(r), september).sumOf { it.remaining })
        assertEquals(0L, collectedOn(listOf(r.copy(completed = false)), september))
        val oldReceipt = MonthlyReceipt("old", payday.toString(), CUSTOMER_RECEIPT, "200.50", "現金")
        assertEquals(20050L, collectedOn(listOf(r.copy(monthlyReceipts = listOf(oldReceipt))), september))
        assertEquals(0L, collectedOn(listOf(r.copy(monthlyReceipts = listOf(oldReceipt))), payday))
        assertThrows(IllegalArgumentException::class.java) {
            allocateCollection(listOf(r), mapOf("1:customer" to 1L), september, "現金")
        }
        val monthly = allocateCollection(listOf(r), mapOf("1:subsidy" to 120000L), payday, "轉帳")
        assertEquals(20050L, collectedOn(monthly, september))
        assertEquals(120000L, collectedOn(monthly, payday))
        assertEquals(140050L, monthly.sumOf(::operatingRevenueCents))
    }
    @Test fun reducingDueBelowPaidIsRejected() {
        val r = allocateCollection(listOf(ride(1, subsidy = "100")), mapOf("1:subsidy" to 10000L), payday, "轉帳").single()
        assertTrue(rideMoneyValidation(r.copy(subsidyDue = "99.99")).any { it.contains("不可超過") })
    }
    @Test fun multipleRentalContractsKeepHistoricalCostsAndPayments() {
        val first = MonthlyRentalPlan(startDate = "2026-09-01", endDate = "2026-09-30", feeCents = 2400000, paidDate = "2026-09-01")
        val second = MonthlyRentalPlan(startDate = "2026-10-01", endDate = "2026-10-31", feeCents = 2480000,
            paidDate = "2026-10-01", archivedPlans = listOf(first))
        assertEquals(80000L, second.totalCostCentsOn(september))
        assertEquals(80000L, second.totalCostCentsOn(payday))
        assertEquals(2400000L, second.paymentCentsOn(LocalDate.parse("2026-09-01")))
        assertEquals(2480000L, second.paymentCentsOn(LocalDate.parse("2026-10-01")))
        assertEquals(second, importExpenseData(exportExpenseData(emptyList(), second, emptyList())).rentalPlan)
    }
    @Test fun periodExpenseIsAllocatedIndependentlyOfPaymentDay() {
        val expense = ExpenseItem(date = "2026-09-30", category = "其他", amountCents = 10001,
            costEndDate = "2026-10-02", paidDate = "2026-10-03")
        assertEquals(listOf(3334L, 3334L, 3333L), (0L..2L).map { expense.costCentsOn(september.plusDays(it)) })
        assertEquals(0L, expense.costCentsOn(payday))
        assertEquals(expense, expense.toJson().toExpenseItem())
    }
    @Test fun daySummaryUsesActualCustomerAndTipCollections() {
        val original = ride(1, subsidy = "100").copy(amountDue = "200", tip = "50")
        assertEquals("200", daySummary(listOf(original)).cashTotal.toPlainString())
        assertEquals(30000L, operatingRevenueCents(original))
    }
}
