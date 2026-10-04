package tw.driver.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DailyJourneyTest {
    @Test fun optionalFieldsAndOdometerDifference() {
        assertNull(DailyJourney().error())
        assertNull(DailyJourney().distanceKm())
        assertNull(DailyJourney(startPoint = "家", endPoint = "醫院", startOdometer = "1200").distanceKm())
        assertEquals("83.5", DailyJourney(startOdometer = "1200.25", endOdometer = "1283.75").distanceKm())
    }

    @Test fun rejectsInvalidOrDecreasingOdometer() {
        assertEquals("結束里程不可小於出發里程", DailyJourney(startOdometer = "100", endOdometer = "99").error())
        assertEquals("里程表請輸入非負數字，最多兩位小數", DailyJourney(startOdometer = "12abc").error())
    }

    @Test fun expenseBackupPreservesDailyJourney() {
        val journey = WorkHourRecord("2026-09-30", "", "", startPoint = "家", endPoint = "車場",
            startOdometer = "1200.25", endOdometer = "1283.75")
        val restored = importExpenseData(exportExpenseData(emptyList(), MonthlyRentalPlan(), listOf(journey)))
        assertEquals(journey, restored.workHours.single())
    }

    @Test fun fillsMissingReadingsFromAdjacentDaysOnly() {
        val day = LocalDate.parse("2026-10-02")
        val records = mapOf(
            day.minusDays(1) to DailyJourney(endOdometer = "100"),
            day to DailyJourney(),
            day.plusDays(1) to DailyJourney(startOdometer = "145")
        )
        val effective = effectiveDailyJourney(day) { records[it] ?: DailyJourney() }
        assertEquals("100", effective.startOdometer)
        assertEquals("145", effective.endOdometer)
        assertEquals("45", effective.distanceKm())
        assertNull(effectiveDailyJourney(day.plusDays(2)) { records[it] ?: DailyJourney() }.distanceKm())
    }
}
