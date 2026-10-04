package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test

class ScheduleReadingPositionTest {
    @Test fun allUnevenCardsAreReachableWithoutTrailingSpace() {
        val heights = listOf(200, 60, 400, 80, 150, 220)
        val viewport = 300
        val offsets = heights.runningFold(0) { total, height -> total + height }
        val reached = mutableSetOf<Int>()
        var previous = -1
        for (scroll in 0..(heights.sum() - viewport)) {
            val items = heights.mapIndexed { index, height -> ScheduleVisibleItem(index, offsets[index] - scroll, height) }
                .filter { it.offset < viewport && it.offset + it.size > 0 }
            val selected = scheduleReadingIndex(items, 0, viewport, heights.size)!!
            assertTrue(selected >= previous)
            assertTrue(items.any { it.index == selected })
            reached.add(selected)
            previous = selected
            if (scroll == 0) assertEquals(0, selected)
            if (scroll == heights.sum() - viewport) assertEquals(heights.lastIndex, selected)
        }
        assertEquals(heights.indices.toSet(), reached)
    }
    @Test fun emptyAndShortListsHaveStableSelection() {
        assertNull(scheduleReadingIndex(emptyList(), 0, 300, 0))
        assertEquals(0, scheduleReadingIndex(listOf(ScheduleVisibleItem(0, 4, 60), ScheduleVisibleItem(1, 70, 80)), 0, 300, 2))
    }
    @Test fun enteringTodaySelectsCurrentThenUpcomingWithoutRepositioningRules() {
        val date = java.time.LocalDate.parse("2026-10-03")
        val rides = listOf(
            RideOrder(id = 1, date = "2026/10/03", pickup = "甲", destination = "乙", pickupTime = "09:00", rideMinutes = "30"),
            RideOrder(id = 2, date = "2026/10/03", pickup = "甲", destination = "乙", pickupTime = "10:00", rideMinutes = "30"),
            RideOrder(id = 3, date = "2026/10/03", pickup = "甲", destination = "乙", pickupTime = "11:00", rideMinutes = "30")
        )
        fun at(time: String) = initialScheduleRideIndex(rides, date, java.time.LocalDateTime.parse("2026-10-03T$time"))
        assertEquals(0, at("08:00:00"))
        assertEquals(1, at("10:15:00"))
        assertEquals(2, at("10:45:00"))
        assertEquals(2, at("12:00:00"))
        assertEquals(0, initialScheduleRideIndex(rides, date.minusDays(1), date.atTime(10, 15)))
        assertNull(initialScheduleRideIndex(emptyList(), date, date.atTime(10, 15)))
        val actual = rides[0].copy(actualBoardedAt = "10-03 10:10")
        assertEquals(0, initialScheduleRideIndex(listOf(actual) + rides.drop(1), date, date.atTime(10, 15)))
    }
}
