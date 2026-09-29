package tw.driver.schedule

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class BookingEntryTest {
    @Test fun missingDateUsesTodayAndYearIsNotDisplayed() {
        assertEquals("02/03", bookingDate("", LocalDate.of(2027, 2, 3)))
        assertEquals("09/21", bookingDate("2026/9/21"))
        assertEquals("09/21", bookingDate("9月21日"))
        assertEquals("按門鈴", reminders("年份待確認；按門鈴"))
    }
    @Test fun validationReportsEachMissingRequirement() {
        val issues = bookingValidation("", "時間待填", "上車地點待確認", "乙院", false, false, "")
        assertEquals(3, issues.size)
        assertTrue(issues.any { it.contains("另一張") })
        assertTrue(bookingValidation("", "10:00–10:30", "甲地", "乙院", true, true, "13:00").isEmpty())
        assertTrue(bookingValidation("02/30", "25:70", "甲地", "乙院", true, true).isNotEmpty())
    }
    @Test fun skippingOneTripKeepsPartnerAndSavesOnlyRemainingTrip() {
        val pair = expandBooking(RideOrder(pickupTime="09:30", pickup="甲地", destination="乙院", bookingId="pair"), false, "11:00")
        val remaining = remainingDrafts(pair, pair.first().id)
        assertEquals(listOf(pair.last()), remaining)
        assertEquals(1, pairedOrdersToSave(remaining, remaining.single()).size)
        assertEquals(listOf(pair.first()), remainingDrafts(pair, pair.last().id))
    }
}
