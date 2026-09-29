package tw.driver.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkDurationTest {
    @Test fun subtractsBreakFromSameDayShift() {
        assertEquals(450, netWorkMinutes("08:00", "17:00", "12:00", "13:30"))
    }

    @Test fun countsOvernightShiftAndBreak() {
        assertEquals(390, netWorkMinutes("22:00", "06:00", "01:00", "02:30"))
    }

    @Test fun subtractsOnlyOverlappingMinutes() {
        assertEquals(420, netWorkMinutes("09:00", "17:00", "08:00", "10:00"))
        assertEquals(480, netWorkMinutes("09:00", "17:00", "18:00", "19:00"))
    }

    @Test fun incompleteBreakDoesNotChangeWorkTime() {
        assertEquals(540, netWorkMinutes("08:00", "17:00", "12:00", ""))
        assertEquals(null, netWorkMinutes("", "17:00", "12:00", "13:00"))
    }
}
