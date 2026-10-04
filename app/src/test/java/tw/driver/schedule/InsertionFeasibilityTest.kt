package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class InsertionFeasibilityTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val now = at("10:20")
    private fun at(time: String, day: String = "2026-09-27") = LocalDate.parse(day).atTime(LocalTime.parse(time)).atZone(ZoneId.of("Asia/Taipei")).toInstant()
    private fun ride(id: Long, time: String, minutes: String = "30", completed: Boolean = false) = RideOrder(id = id, serviceDate = "2026-09-27", pickupTime = time,
        pickup = "起點$id", destination = "終點$id", rideMinutes = minutes, completed = completed)
    private fun case(time: String = "", asap: Boolean = true) = InsertionCase("插單起點", "插單終點", "2026-09-27", time, asap)
    private fun leg(minutes: Long) = RouteLeg(minutes * 60, 1000)
    private fun plan(nextTime: String = "11:20") = insertionSchedule(listOf(ride(2, nextTime)), now, emptyMap(), zone)

    @Test fun activeTripUsesDropoffAndWaitsForBoardingDrivingAndAlighting() {
        val current = ride(1, "10:00")
        val next = ride(2, "11:20")
        val plan = insertionSchedule(listOf(current, next), now, mapOf(1L to 1800), zone)
        assertEquals("終點1", plan.active!!.destination)
        assertEquals(at("10:40"), plan.availableAt)
        assertEquals("起點2", plan.next!!.pickup)
        assertEquals(at("11:20"), plan.nextAt)
    }
    @Test fun finishedOrCompletedTripDoesNotReplaceGpsOrigin() {
        val old = ride(1, "09:00")
        val complete = ride(2, "10:10", completed = true)
        val plan = insertionSchedule(listOf(old, complete, ride(3, "11:00")), now, mapOf(1L to 1800), zone)
        assertNull(plan.active)
        assertEquals(now, plan.availableAt)
        assertEquals(3L, plan.next!!.id)
    }
    @Test fun missingDurationOrTimeCannotClaimAFreeGap() {
        assertThrows(IllegalStateException::class.java) { insertionSchedule(listOf(ride(1, "10:00", "")), now, emptyMap(), zone) }
        assertThrows(IllegalArgumentException::class.java) { insertionSchedule(listOf(ride(1, "")), now, emptyMap(), zone) }
    }
    @Test fun allThreeLegsAndBothBuffersLeaveTenMinutesSlack() {
        val result = assessInsertion(case(), plan(), "GPS", now, leg(10), leg(20), leg(10), 5, zone)
        assertTrue(result.feasible)
        assertEquals(at("10:30"), result.pickupArrival)
        assertEquals(at("11:00"), result.dropoffReady)
        assertEquals(at("11:10"), result.nextArrival)
        assertEquals(600L, result.nextSlackSeconds)
    }
    @Test fun oneSecondLateForNextTripIsRedNotRoundedDown() {
        val result = assessInsertion(case(), plan("11:10"), "GPS", now, leg(10), leg(20), RouteLeg(601, 1000), 5, zone)
        assertFalse(result.feasible)
        assertEquals(-1L, result.nextSlackSeconds)
        assertTrue(result.summary.contains("延誤 1 分鐘"))
    }
    @Test fun waitingForScheduledNewPickupCanDelayTheNextTrip() {
        val result = assessInsertion(case("10:50", false), plan(), "GPS", now, leg(10), leg(20), leg(10), 5, zone)
        assertEquals(at("10:50"), result.pickupStart)
        assertEquals(at("11:30"), result.nextArrival)
        assertFalse(result.feasible)
    }
    @Test fun lateNewPickupIsInfeasibleEvenWithNoNextTrip() {
        val empty = insertionSchedule(emptyList(), now, emptyMap(), zone)
        val result = assessInsertion(case("10:25", false), empty, "GPS", now, leg(10), leg(20), null, 5, zone)
        assertFalse(result.feasible)
        assertEquals(300L, result.pickupLateSeconds)
    }
    @Test fun pickupRangeAllowsArrivalWithinWindow() {
        val result = assessInsertion(case("10:20–10:40", false), plan(), "GPS", now, leg(10), leg(20), leg(10), 5, zone)
        assertTrue(result.feasible)
        assertEquals(0L, result.pickupLateSeconds)
    }
    @Test fun currentTripWaitIsIncludedInReplyEta() {
        val plan = insertionSchedule(listOf(ride(1, "10:00")), now, mapOf(1L to 1800), zone)
        val result = assessInsertion(case(), plan, "終點1", now, leg(10), leg(20), null, 5, zone)
        assertEquals(30L, result.arrival.minutes)
        assertEquals("30 分 可到 插單起點", result.arrival.reply)
    }
    @Test fun scheduleEditsTimeExpiryAndTripBoundaryInvalidateGreen() {
        val rides = listOf(ride(2, "11:20"))
        val plan = insertionSchedule(rides, now, emptyMap(), zone)
        val result = assessInsertion(case(), plan, "GPS", now, leg(10), leg(20), leg(10), 5, zone)
        assertTrue(result.isFresh(rides, now.plusSeconds(60)))
        assertFalse(result.isFresh(rides.map { it.copy(pickup = "新起點") }, now))
        assertFalse(result.isFresh(rides.map { it.copy(completed = true) }, now))
        assertFalse(result.isFresh(rides, now.plusSeconds(301)))
        val near = listOf(ride(1, "10:00", "11"))
        val active = insertionSchedule(near, now, mapOf(1L to 660), zone)
        val activeResult = assessInsertion(case(), active, "終點", now, leg(1), leg(1), null, 0, zone)
        assertFalse(activeResult.isFresh(near, at("10:21")))
    }
    @Test fun overlappingActiveTripsCannotBeGreen() {
        val rides = listOf(ride(1, "10:00"), ride(2, "10:05"))
        val plan = insertionSchedule(rides, now, mapOf(1L to 1800, 2L to 1800), zone)
        assertFalse(assessInsertion(case(), plan, "終點", now, leg(1), leg(1), null, 5, zone).feasible)
    }
    @Test fun greenExpiresWhenWaitingUsesUpItsOneMinuteMargin() {
        val rides = listOf(ride(2, "11:11"))
        val plan = insertionSchedule(rides, now, emptyMap(), zone)
        val result = assessInsertion(case(), plan, "GPS", now, leg(10), leg(20), leg(10), 5, zone)
        assertTrue(result.isFresh(rides, now.plusSeconds(30)))
        assertFalse(result.isFresh(rides, now.plusSeconds(61)))
    }
    @Test fun explicitTimeAndSameDayAreRequiredUnlessAsap() {
        assertThrows(IllegalArgumentException::class.java) { case("", false).window(now, zone) }
        assertThrows(IllegalArgumentException::class.java) { case("25:00", false).window(now, zone) }
        assertThrows(IllegalArgumentException::class.java) { case().copy(date = "2026-09-28").window(now, zone) }
        assertThrows(IllegalArgumentException::class.java) { case("12:00-10:00", false).window(now, zone) }
    }
    @Test fun nextDayTripIsIncludedForMidnightConnections() {
        val late = at("23:40")
        val next = ride(1, "00:10").copy(serviceDate = "2026-09-28")
        val plan = insertionSchedule(listOf(next), late, emptyMap(), zone)
        assertEquals(at("00:10", "2026-09-28"), plan.nextAt)
        assertFalse(assessInsertion(case(), plan, "GPS", late, leg(10), leg(20), leg(10), 5, zone).feasible)
    }
    @Test fun parserPreservesPickupWindowAndRejectsInventedAddresses() {
        val raw = "即時可等 台大醫院 → 台北車站 自費"
        val parsed = InsertionParser.decode("""{"pickup":"台大醫院","destination":"台北車站","date":"","time":"","asap":true,"note":""}""", raw, LocalDate.parse("2026-09-27"))
        assertTrue(parsed.asap)
        assertEquals("2026-09-27", parsed.date)
        assertThrows(IllegalArgumentException::class.java) { InsertionParser.decode("""{"pickup":"假地址","destination":"台北車站"}""", raw, LocalDate.now()) }
    }
    @Test fun explicitWaitOverridesAiTimeAndOtherMessagesKeepPickupTime() {
        val json = """{"pickup":"台大醫院","destination":"台北車站","time":"14:30","asap":false}"""
        val today = LocalDate.parse("2026-09-27")
        val immediate = InsertionParser.decode(json, "即時 可等 台大醫院 台北車站", today)
        assertTrue(immediate.asap)
        assertEquals("", immediate.time)
        assertEquals(now, immediate.window(now, zone).first)
        val immediateOnly = InsertionParser.decode(json, "即時 台大醫院 台北車站", today)
        assertTrue(immediateOnly.asap)
        assertEquals("", immediateOnly.time)
        assertEquals(now, immediateOnly.window(now, zone).first)
        val scheduled = InsertionParser.decode(json.replace("false", "true"), "報分 台大醫院 台北車站 14:30", today)
        assertFalse(scheduled.asap)
        assertEquals("14:30", scheduled.time)
    }
}
