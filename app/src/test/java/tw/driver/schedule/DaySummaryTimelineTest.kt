package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DaySummaryTimelineTest {
    private fun ride(id: Long, time: String) = RideOrder(id=id,date="2026/09/25",pickupTime=time,pickup="甲",destination="乙")
    @Test fun summaryOnlyCountsCompletedTripsAndExcludesTransferAndBuffers() {
        val a=ride(1,"09:00").copy(completed=true,category="自費",received="120.50",rideMinutes="20",transferMinutes="90")
        val original=ride(2,"10:00").copy(completed=true,category="補助單",subsidyDue="300",received="50")
        val time=Instant.parse("2026-09-25T01:00:00Z")
        val b=original.copy(routeEstimate=RouteEstimate(routeInputKey(original,a),901,10000,1800,time,time,time).json())
        val c=ride(3,"11:00").copy(completed=false,category="日照",received="999",subsidyDue="999",rideMinutes="99")
        val summary=daySummary(listOf(c,b,a))
        assertEquals(3,summary.scheduled);assertEquals(2,summary.completed)
        assertEquals(1,summary.selfPay);assertEquals(1,summary.subsidized);assertEquals(0,summary.daycare)
        assertEquals(2101L,summary.seconds)
        assertEquals("170.50",summary.receipts.toPlainString());assertEquals("300",summary.subsidy.toPlainString())
    }
    @Test fun missingDurationsAndMoneyAreFlaggedRatherThanAssumedKnownZero() {
        val result=daySummary(listOf(ride(1,"09:00").copy(completed=true,category="補助單")))
        assertEquals(1,result.missingTime);assertEquals(1,result.missingReceipts);assertEquals(1,result.missingSubsidy)
        assertTrue(subsidyValidation("-2").isNotEmpty())
        assertTrue(subsidyValidation("12.50").isEmpty())
        val r=ride(1,"09:00").copy(subsidyDue="12.50")
        assertEquals(r,importRecords(exportRecords(listOf(r))).single())
        assertTrue(exportCsv(listOf(r)).contains("subsidy_due_twd"))
    }
    @Test fun passengerClipboardIncludesDirectionAndBoardingState() {
        val r=ride(1,"09:00").copy(customer="王小明")
        assertEquals("王小明 去程 客上",passengerStatusText(r,true))
        assertEquals("王小明 回程 客下",passengerStatusText(r.copy(returnRide=true),false))
    }
    @Test fun timelineUsesExactDurationAndSeparateOverlapLanes() {
        val a=ride(1,"09:00").copy(rideMinutes="60")
        val b=ride(2,"09:30").copy(rideMinutes="30")
        val c=ride(3,"10:00")
        val segments=timelineTrips(listOf(c,b,a))
        assertEquals(540.0,segments[0].start,0.0);assertEquals(600.0,segments[0].end!!,0.0)
        assertNotEquals(segments[0].lane,segments[1].lane)
        assertNull(segments[2].end)
        val (start,end)=timelineRange(segments)
        assertTrue(start<=540);assertTrue(end>=600)
        assertEquals(2.0,(segments[0].end!!-segments[0].start)/(segments[1].end!!-segments[1].start),0.0)
    }
    @Test fun transferUsesExactDurationAndPreservesWaitingGap() {
        val a = ride(1, "09:00").copy(rideMinutes = "20")
        val b = ride(2, "10:00").copy(transferMinutes = "15")
        val transfer = timelineTransfers(listOf(b, a)).single()
        assertEquals(560.0, transfer.start, 0.0)
        assertEquals(575.0, transfer.end, 0.0)
        assertFalse(transfer.conflicts)
        assertTrue(timelineTransfers(listOf(a, b.copy(transferMinutes = ""))).isEmpty())
        assertTrue(timelineTransfers(listOf(a.copy(rideMinutes = ""), b)).isEmpty())
        assertTrue(timelineTransfers(listOf(a, b.copy(transferMinutes = "45"))).single().conflicts)
    }
    @Test fun transferRejectsStaleEstimatesAndRetainsSeconds() {
        val a = ride(1, "09:00").copy(rideMinutes = "20")
        val b = ride(2, "10:00")
        val time = Instant.parse("2026-09-25T01:00:00Z")
        val estimated = b.copy(routeEstimate = RouteEstimate(routeInputKey(b, a), 600, 1000, 901, time, time, time).json())
        assertEquals(575.0166666667, timelineTransfers(listOf(a, estimated)).single().end, 0.000001)
        assertTrue(timelineTransfers(listOf(a, estimated.copy(pickup = "新地址"))).isEmpty())
    }
    @Test fun draggingTimelineSelectsCaseByAbsoluteTimeRatherThanScrollDistance() {
        val trips = listOf(
            TimelineTrip(1, 540.0, 570.0, 0, false, "自費"),
            TimelineTrip(2, 600.0, 630.0, 0, false, "自費"),
            TimelineTrip(3, 720.0, null, 0, false, "自費")
        )
        assertEquals(1L, timelineRideAtMinute(trips, 500.0))
        assertEquals(1L, timelineRideAtMinute(trips, 555.0))
        assertEquals(2L, timelineRideAtMinute(trips, 610.0))
        assertEquals(3L, timelineRideAtMinute(trips, 720.0))
        assertEquals(3L, timelineRideAtMinute(trips, 900.0))
        assertEquals(1L, timelineRideAtMinute(trips, 540.0))
        assertNull(timelineRideAtMinute(emptyList(), 600.0))
    }
    @Test fun timelineHandlesMidnightAndUnknownTimes() {
        val segments=timelineTrips(listOf(ride(1,"23:50").copy(rideMinutes="30"),ride(2,"時間待填")))
        assertEquals(1,segments.size)
        assertEquals(1460.0,segments.single().end!!,0.0)
        assertTrue(timelineRange(segments).second>=1460)
        assertEquals("+1 00:20",timelineClock(1460.0))
    }
    @Test fun timelineCustomDepartureAndReturnHomeTimeOverridesRange() {
        val segments = timelineTrips(listOf(ride(1, "09:00").copy(rideMinutes = "60")))
        val (start, end) = timelineRange(segments, "07:30", "18:00")
        assertEquals(450.0, start, 0.0)
        assertEquals(1080.0, end, 0.0)
    }
    @Test fun actualScheduleUsesRecordedBoardingAndAlightingInsteadOfPlannedTime() {
        val date = java.time.LocalDate.parse("2026-09-25")
        val recorded = ride(1, "09:00").copy(
            actualBoardedAt = recordActualTime("2026-09-25", java.time.LocalTime.of(10, 12)),
            actualAlightedAt = recordActualTime("2026-09-25", java.time.LocalTime.of(10, 47))
        )
        assertEquals(35L, actualRideMinutes(recorded))
        val actual = actualTimelineTrips(listOf(recorded, ride(2, "11:00")), date).single()
        assertEquals(612.0, actual.start, 0.0)
        assertEquals(647.0, actual.end!!, 0.0)
        assertEquals(recorded, importRecords(exportRecords(listOf(recorded))).single())
    }
    @Test fun actualTransfersUseRecordedGapAndRequireBothEndpoints() {
        val date = java.time.LocalDate.parse("2026-09-25")
        val a = ride(1, "09:00").copy(actualAlightedAt = "09-25 09:30")
        val b = ride(2, "10:00").copy(actualBoardedAt = "09-25 10:05", transferMinutes = "15")
        val segments = actualTimelineTransfers(listOf(b, a), date)
        val travel = segments.first()
        val wait = segments.last()
        assertEquals("交通", travel.kind)
        assertEquals(570.0, travel.start, 0.0)
        assertEquals(585.0, travel.end, 0.0)
        assertEquals("等待", wait.kind)
        assertEquals(585.0, wait.start, 0.0)
        assertEquals(605.0, wait.end, 0.0)
        assertEquals(2L, travel.rideId)
        assertTrue(travel.estimated)
        val unknown = actualTimelineTransfers(listOf(a, b.copy(transferMinutes = "")), date).single()
        assertEquals("未分類", unknown.kind)
        val insufficient = actualTimelineTransfers(listOf(a, b.copy(transferMinutes = "60")), date).single()
        assertTrue(insufficient.conflicts)
        assertEquals(605.0, insufficient.end, 0.0)
        val onlyWaiting = actualTimelineTransfers(listOf(a, b.copy(transferMinutes = "0")), date).single()
        assertEquals("等待", onlyWaiting.kind)
        assertTrue(actualTimelineTransfers(listOf(a, b.copy(actualBoardedAt = "")), date).isEmpty())
        assertTrue(actualTimelineTransfers(listOf(a, b.copy(actualBoardedAt = "09-25 09:20")), date).isEmpty())
        assertTrue(actualTimelineTransfers(listOf(a, ride(3, "09:45"), b), date).isEmpty())
    }
    @Test fun actualTransferRetainsGapAcrossMidnight() {
        val date = java.time.LocalDate.parse("2026-09-25")
        val a = ride(1, "23:00").copy(actualAlightedAt = "09-25 23:50")
        val b = ride(2, "23:30").copy(actualBoardedAt = "09-26 00:10")
        val gap = actualTimelineTransfers(listOf(a, b), date).single()
        assertEquals(1430.0, gap.start, 0.0)
        assertEquals(1450.0, gap.end, 0.0)
    }
    @Test fun hospitalCatalogKeepsBranchesDistinctAndUnknownCoordinatesUnset() {
        val hospitals=parseHospitals("""[{"id":"a","name":"同院甲區","city":"臺北市","address":"甲","lat":25.0,"lng":121.5},{"id":"b","name":"同院乙區","city":"新北市","address":"乙"}]""")
        assertEquals(2,hospitals.size)
        assertEquals(25.0,hospitals[0].location!!.lat,0.0)
        assertNull(hospitals[1].location)
    }
    @Test fun weeklyEfficiencyStatCalculatesCorrectly() {
        val stat = WeekDayEfficiencyStat(
            date = java.time.LocalDate.parse("2026-09-25"),
            dayName = "五",
            revenue = 3000,
            expense = 1000,
            netProfit = 2000,
            workDurationMins = 480,
            tripDurationMins = 300,
            completedCount = 4,
            isSelected = true
        )
        assertEquals(2000, stat.netProfit)
        val rate = (stat.tripDurationMins.toDouble() / stat.workDurationMins * 100).toInt()
        assertEquals(62, rate)
        val hourlyNet = (stat.netProfit / (stat.workDurationMins / 60.0)).toInt()
        assertEquals(250, hourlyNet)
    }
}
