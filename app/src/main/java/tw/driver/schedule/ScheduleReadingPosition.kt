package tw.driver.schedule

import kotlin.math.roundToInt

internal data class ScheduleVisibleItem(val index: Int, val offset: Int, val size: Int)

// Express the content above/below the viewport in card units, so uneven card heights
// do not keep the reading anchor fixed in the middle or strand the final cards.
internal fun scheduleReadingIndex(items: List<ScheduleVisibleItem>, start: Int, end: Int, count: Int): Int? {
    if (items.isEmpty() || count <= 0 || end <= start) return null
    val visible = items.filter { it.offset < end && it.offset + it.size > start }
    val first = visible.firstOrNull() ?: return null
    val last = visible.last()
    val before = first.index + ((start - first.offset).toDouble() / first.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val after = count - 1 - last.index + ((last.offset + last.size - end).toDouble() / last.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val progress = if (before + after > 0.0) before / (before + after) else 0.0
    return (progress * (count - 1)).roundToInt().coerceIn(first.index, last.index)
}

internal fun initialScheduleRideIndex(rides: List<RideOrder>, date: java.time.LocalDate, now: java.time.LocalDateTime): Int? {
    if (rides.isEmpty()) return null
    if (date != now.toLocalDate()) return 0
    val instant = now.atZone(java.time.ZoneId.systemDefault()).toInstant()
    val recorded = rides.indexOfLast { ride ->
        val boarded = actualRideInstant(ride.actualBoardedAt, ride.serviceDate)
        val alighted = actualRideInstant(ride.actualAlightedAt, ride.serviceDate)
        !ride.completed && boarded != null && !boarded.isAfter(instant) && (alighted == null || alighted.isAfter(instant))
    }
    if (recorded >= 0) return recorded
    val minute = now.hour * 60.0 + now.minute + now.second / 60.0
    val trips = timelineTrips(rides).associateBy { it.id }
    val current = rides.indexOfLast { ride ->
        val trip = trips[ride.id]
        !ride.completed && trip != null && minute >= trip.start && minute < (trip.end
            ?: rides.dropWhile { it.id != ride.id }.drop(1).firstNotNullOfOrNull { minuteOfDay(it.pickupTime)?.toDouble() }
            ?: 1440.0)
    }
    if (current >= 0) return current
    return rides.indexOfFirst { !it.completed && (minuteOfDay(it.pickupTime)?.toDouble() ?: -1.0) > minute }
        .takeIf { it >= 0 } ?: rides.lastIndex
}
