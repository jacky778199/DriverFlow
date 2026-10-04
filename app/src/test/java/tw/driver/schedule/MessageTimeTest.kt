package tw.driver.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class MessageTimeTest {
    @Test fun unixSecondsDisplayTaiwanTimeAcrossMidnight() {
        val timestamp = Instant.parse("2026-09-30T16:30:00Z").epochSecond
        assertEquals("10/01 00:30", messageTimeTaiwan(timestamp, "09/30 16:30"))
    }

    @Test fun offsetTimeIsConvertedWhenTimestampIsMissing() {
        assertEquals("10/01 00:30", messageTimeTaiwan(0, "2026-09-30T16:30:00Z"))
        assertEquals("09:15", messageTimeTaiwan(0, "09:15"))
        assertEquals("—", messageTimeTaiwan(0, ""))
    }
}
