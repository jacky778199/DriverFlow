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
}
