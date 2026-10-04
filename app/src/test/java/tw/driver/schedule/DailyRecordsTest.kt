package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DailyRecordsTest {
    private fun ride() = RideOrder(id=123, date="2026/09/25", pickupTime="09:30", pickup="甲地", destination="乙地", category="日照", completed=true, received="120.50")
    @Test fun fullDatePreservesYearAndRejectsInvalidDate() {
        assertEquals("2025-09-25", fullRideDate("2025/9/25"))
        assertEquals("2026-09-25", fullRideDate("9/25", LocalDate.of(2026,1,1)))
        assertTrue(recordValidation("2026/02/30", "").isNotEmpty())
        assertTrue(recordValidation("garbage", "").isNotEmpty())
    }
    @Test fun revenueOnlyCountsCompletedExplicitReceipts() {
        assertEquals("120.50", revenue(listOf(ride(), ride().copy(completed=false, received="500"), ride().copy(received="", fare="跳+300"))).toPlainString())
        assertTrue(recordValidation("2026-09-25", "-5").isNotEmpty())
        assertTrue(recordValidation("2026-09-25", "300", "-1").isNotEmpty())
    }
    @Test fun backupRoundTripsWithoutDeviceLocalImagePath() {
        val r = ride().copy(sourceImage="/private/image.png", notes="a,\"b\"\n中文")
        assertEquals(r.copy(sourceImage=""), importRecords(exportRecords(listOf(r))).single())
        assertTrue(exportCsv(listOf(r)).contains("\"a,\"\"b\"\"\n中文\""))
        assertTrue(exportCsv(listOf(r.copy(customer="=1+1"))).contains("'=1+1"))
    }
    @Test fun oldRecordsReceiveSafeDefaults() {
        val json=ride().toJson(); listOf("serviceDate", "category", "completed", "received").forEach { json.remove(it) }
        val old=json.toOrder()
        assertEquals("2026-09-25", old.serviceDate)
        assertEquals("未分類", old.category)
        assertFalse(old.completed)
    }
    @Test fun sortsSingleDigitHoursAndRangesNumerically() {
        assertEquals(570, minuteOfDay("9:30"))
        assertEquals(600, minuteOfDay("10:00–10:30"))
        assertNull(minuteOfDay("時間待填"))
    }
    @Test fun calculateEtaComputesArrivalCorrectly() {
        assertEquals("09:55", calculateEta("09:30", 25))
        assertEquals("10:20", calculateEta("09:45", 35))
        assertEquals("00:10", calculateEta("23:50", 20))
        assertNull(calculateEta("09:30", null))
        assertNull(calculateEta("待確認", 25))
    }
    @Test fun actualTimesCanBeEditedWithoutChangingThePlannedPickup() {
        val boarded = parseActualTimeInput("0942", "2026-09-25")!!
        val alighted = parseActualTimeInput("10:18", "2026-09-25")!!
        assertEquals("09-25 09:42", boarded)
        assertEquals("09:42", actualTimeEditValue(boarded))
        assertEquals("09:42", actualTimeLabel(boarded))
        assertEquals("10-01 09:42", parseActualTimeInput(actualTimeEditValue(boarded), "2026-10-01"))
        assertEquals("", parseActualTimeInput(" ", "2026-09-25"))
        assertNull(parseActualTimeInput("2560", "2026-09-25"))
        assertNull(parseActualTimeInput("09:42", "2026-02-30"))
        val saved = ride().copy(actualBoardedAt = boarded, actualAlightedAt = alighted, bookingId = "keep")
        assertEquals("09:30", saved.pickupTime)
        assertEquals(36L, actualRideMinutes(saved))
        assertEquals(saved, importRecords(exportRecords(listOf(saved))).single())
        val legacy = LocalDate.of(2026, 9, 25).atTime(9, 42).atZone(java.time.ZoneId.systemDefault()).toInstant()
        assertEquals("09:42", actualTimeEditValue(legacy.toString()))
        assertEquals(legacy, actualRideInstant(legacy.toString(), "2026-09-25"))
    }
    @Test fun acceptsThreeClockFormatsWithin24Hours() {
        assertEquals("09:30", normalizeTime("09:30"))
        assertEquals("09:30", normalizeTime("0930"))
        assertEquals("09:30", normalizeTime("09：30"))
        assertEquals("00:00", normalizeTime("0000"))
        assertNull(normalizeTime("24:00"))
        assertNull(normalizeTime("1260"))
        assertNull(normalizeTime("09:30-10:00"))
    }
    @Test fun caseFeesAndReportSettingsSurviveBackup() {
        val base = ride().copy(daycareMonthly="500", reportTarget="王先生")
        assertEquals("月結-日照: 500元", caseFeeText(base))
        assertEquals(base, importRecords(exportRecords(listOf(base))).single())
        assertEquals("實收: 120.50元", caseFeeText(base.copy(category="自費")))
        assertEquals("已收: 120.50元 · 小費: 20元 · 月結-補助: 80元",
            caseFeeText(base.copy(category="補助單", tip="20", subsidyDue="80")))
    }
}
