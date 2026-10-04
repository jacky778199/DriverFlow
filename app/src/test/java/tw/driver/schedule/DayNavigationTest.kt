package tw.driver.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DayNavigationTest {
    private val today = LocalDate.parse("2026-10-01")

    @Test fun jumpPointsTowardTodayAndHidesOnToday() {
        assertEquals(TodayJumpSide.RIGHT, todayJumpSide(today.minusDays(1), today))
        assertEquals(TodayJumpSide.LEFT, todayJumpSide(today.plusDays(1), today))
        assertEquals(TodayJumpSide.NONE, todayJumpSide(today, today))
    }
}
