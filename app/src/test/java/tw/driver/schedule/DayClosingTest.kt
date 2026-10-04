package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DayClosingTest {
    private val date = LocalDate.parse("2026-10-01")
    private fun ride(id: Long = 1, category: String = "自費") = RideOrder(
        id = id, date = date.toString(), serviceDate = date.toString(), pickupTime = "09:00",
        customer = "王先生", pickup = "起點", destination = "終點", category = category, completed = true,
        amountDue = "0", received = "0", rideMinutes = "20",
        actualBoardedAt = "10-01 09:00", actualAlightedAt = "10-01 09:20"
    )
    @Test fun separatesCashTipsMonthlyPendingAndCompletedRides() {
        val self = ride().copy(amountDue = "200", received = "120.50", tip = "20")
        val subsidy = ride(2, "補助單").copy(amountDue = "50", received = "50", subsidyDue = "600", tip = "10",
            monthlyReceipts = listOf(MonthlyReceipt("a", "2026-10-02", SUBSIDY_RECEIPT, "100")))
        val daycare = ride(3, "日照").copy(received = "", daycareMonthly = "400")
        val incomplete = ride(4).copy(completed = false, received = "900", tip = "900")
        val result = daySummary(listOf(self, subsidy, daycare, incomplete))
        assertEquals("120.50", result.selfPayCash.toPlainString())
        assertEquals("50", result.subsidyCash.toPlainString())
        assertEquals("170.50", result.cashTotal.toPlainString())
        assertEquals("500", result.subsidyPending.toPlainString())
        assertEquals("400", result.daycarePending.toPlainString())
        assertEquals(3, result.completed)
        assertEquals(0, result.missingReceipts)
        assertTrue(result.reviews.any { it.ride.id == 4L && it.issues.any { issue -> issue.contains("尚未完成") } })
    }
    @Test fun reviewNamesEachMissingFieldAndIdentifiesTheRide() {
        val missing = ride().copy(amountDue = "", received = "", actualBoardedAt = "", actualAlightedAt = "", rideMinutes = "")
        val row = dayReviewItems(listOf(missing)).single()
        assertEquals(missing, row.ride)
        assertTrue(rideReviewLabel(row.ride).contains("王先生"))
        for (field in listOf("已收金額", "實際上車時間", "實際下車時間", "載客計費時間"))
            assertTrue("Missing $field", row.issues.any { it.contains(field) })
    }
    @Test fun explicitZeroIsConfirmedAndDaycareDoesNotRequireCash() {
        assertTrue(dayReviewItems(listOf(ride())).isEmpty())
        assertTrue(dayReviewItems(listOf(ride(category = "日照").copy(received = "", amountDue = "", daycareMonthly = "0"))).isEmpty())
        assertTrue(dayReviewItems(listOf(ride(category = "補助單").copy(subsidyDue = "0"))).isEmpty())
        val missing = ride().copy(customer = "", pickup = "", destination = "待確認")
        val issues = dayReviewItems(listOf(missing)).single().issues
        assertTrue(issues.contains("乘客姓名未填"))
        assertTrue(issues.contains("上車地址未填或待確認"))
        assertTrue(issues.contains("下車地址未填或待確認"))
    }
    @Test fun monthlyReceiptUsesPaymentDateAndNeverAddsRevenueAgain() {
        val historical = ride(category = "補助單").copy(serviceDate = "2026-09-20", amountDue = "100", received = "100", subsidyDue = "600",
            monthlyReceipts = listOf(MonthlyReceipt("a", date.toString(), SUBSIDY_RECEIPT, "200"),
                MonthlyReceipt("b", "2026-10-02", SUBSIDY_RECEIPT, "300")))
        assertEquals("200", monthlyReceiptsOn(listOf(historical), date).single().receipt.amount)
        assertTrue(monthlyReceiptsOn(listOf(historical), date.minusDays(1)).isEmpty())
        assertEquals("100", monthlyRemaining(historical, SUBSIDY_RECEIPT).toPlainString())
        assertEquals(70000L, rideOperatingRevenue(historical))
        assertEquals(70000L, rideOperatingRevenue(historical.copy(monthlyReceipts = emptyList())))
        assertEquals("100", daySummary(listOf(historical)).cashTotal.toPlainString())
    }
    @Test fun validatesPartialReceiptsDatesAndOverpayments() {
        val base = ride(category = "日照").copy(daycareMonthly = "600")
        val first = MonthlyReceipt("a", "2026-10-01", DAYCARE_RECEIPT, "100.50")
        val second = MonthlyReceipt("b", "2026-10-02", DAYCARE_RECEIPT, "499.50")
        assertTrue(rideMoneyValidation(base.copy(monthlyReceipts = listOf(first, second))).isEmpty())
        assertEquals("0.00", monthlyRemaining(base.copy(monthlyReceipts = listOf(first, second)), DAYCARE_RECEIPT).toPlainString())
        assertTrue(rideMoneyValidation(base.copy(monthlyReceipts = listOf(first, second.copy(amount = "500")))).any { it.contains("不可超過") })
        for (invalid in listOf(first.copy(date = "2026-02-30"), first.copy(amount = "-1"), first.copy(amount = "0"), first.copy(kind = "other")))
            assertTrue(rideMoneyValidation(base.copy(monthlyReceipts = listOf(invalid))).isNotEmpty())
        assertTrue(rideMoneyValidation(base.copy(monthlyReceipts = listOf(first, first))).any { it.contains("ID 重複") })
    }
    @Test fun legacySubsidyKeepsDueWithoutInventingPaymentAndMigrationRunsOnce() {
        val old = ride(category = "補助單").copy(received = "120", amountDue = "", subsidyDue = "580").toJson()
        old.remove("moneyVersion"); old.remove("amountDue")
        val migrated = old.toOrder()
        assertEquals("120", migrated.amountDue)
        assertEquals("", migrated.received)
        assertEquals("580", migrated.subsidyDue)
        assertEquals(migrated, migrated.toJson().toOrder())
        assertEquals("0", daySummary(listOf(migrated)).cashTotal.toPlainString())
        assertEquals(58000L, rideOperatingRevenue(migrated))
    }
    @Test fun legacyDaycarePreservesBilledAmountAndLegacySelfPreservesActualCash() {
        val daycare = migrateRideMoney(ride(category = "日照").copy(received = "450", daycareMonthly = ""), true)
        assertEquals("450", daycare.daycareMonthly)
        assertEquals("", daycare.received)
        val self = migrateRideMoney(ride().copy(amountDue = "", received = "120.50"), true)
        assertEquals("120.50", self.amountDue)
        assertEquals("120.50", self.received)
        assertEquals("120.50", daySummary(listOf(self)).cashTotal.toPlainString())
    }
    @Test fun exportsAndImportsAllPaymentFieldsAndCsvIncludesThem() {
        val original = ride(category = "補助單").copy(amountDue = "200", received = "100", subsidyDue = "600",
            monthlyReceipts = listOf(MonthlyReceipt("a", "2026-10-02", SUBSIDY_RECEIPT, "150")))
        assertEquals(original, importRecords(exportRecords(listOf(original))).single())
        val csv = exportCsv(listOf(original))
        assertFalse(csv.contains("cash_due_twd")); assertTrue(csv.contains("monthly_receipts_json"))
        assertTrue(csv.contains("2026-10-02"))
        val firestoreShape = org.json.JSONArray(listOf(mapOf("id" to "a", "date" to "2026-10-02", "kind" to SUBSIDY_RECEIPT, "amount" to "150")))
        assertEquals(original.monthlyReceipts, parseMonthlyReceipts(firestoreShape))
    }
    @Test fun operatingRevenueUsesOnlyCollectedCashAndIgnoresHistoricalDue() {
        val r = ride().copy(amountDue = "300", received = "100", tip = "20")
        assertEquals(10000L, rideOperatingRevenue(r))
        assertEquals("100", daySummary(listOf(r)).cashTotal.toPlainString())
        assertEquals(30000L, rideOperatingRevenue(r.copy(received = "300")))
    }
    @Test fun removedDueIsNotRequiredOrValidatedInDayClosing() {
        val confirmed = ride().copy(amountDue = "", received = "0")
        assertTrue(dayReviewItems(listOf(confirmed)).isEmpty())
        assertTrue(rideMoneyValidation(confirmed.copy(amountDue = "legacy malformed value")).isEmpty())
        val unknown = confirmed.copy(received = "")
        assertEquals(listOf("已收金額未填（沒有收款請填 0）"), dayReviewItems(listOf(unknown)).single().issues)
        assertFalse(caseFeeText(confirmed.copy(amountDue = "200")).contains("應收"))
    }
}
