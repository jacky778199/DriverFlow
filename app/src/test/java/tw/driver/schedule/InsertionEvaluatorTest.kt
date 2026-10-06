package tw.driver.schedule

import java.time.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsertionEvaluatorTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private fun at(time: String) = LocalDate.parse("2026-10-06").atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private val now = at("10:20")
    private val input = InsertionCase("上車", "下車", "2026-10-06", asap = true)
    private val pickup = RoutePlace("new-pickup", "上車", "台北市信義路10號")
    private val dropoff = RoutePlace("new-dropoff", "下車", "台北市信義路20號")
    private fun ride(id: Long, time: String) = RideOrder(id = id, serviceDate = "2026-10-06", pickupTime = time,
        pickup = "台北市原點10號", destination = "台北市終點20號", pickupPlaceId = "pickup$id", destinationPlaceId = "dropoff$id", rideMinutes = "30")
    private fun response(seconds: Long) = MockResponse().setBody("""{"routes":[{"duration":"${seconds}s","distanceMeters":1000}]}""")
    private fun client(server: MockWebServer) = GoogleRouteClient("key", routesUrl = server.url("/routes").toString(), placesUrl = server.url("/places").toString())
    private fun evaluator(server: MockWebServer, location: suspend () -> RoutePoint = { RoutePoint(25.03, 121.55) }) =
        InsertionEvaluator(client(server), { _, _ -> error("unexpected choice") }, location, clock = { now }, zone = zone)

    @Test fun finishedTripUsesGpsAndComputesExactlyThreeLegsWithCorrectDepartures() = runBlocking {
        MockWebServer().use { server ->
            listOf(600L, 1200L, 600L).forEach { server.enqueue(response(it)) }
            val rides = listOf(ride(1, "09:00").copy(completed = true, rideMinutes = ""), ride(2, "11:20"))
            val result = evaluator(server).evaluate(input, rides, { rides }, pickupOverride = pickup, destinationOverride = dropoff)
            assertNull(result.plan.active)
            assertEquals(InsertionLevel.AVAILABLE, result.level)
            assertEquals(600L, result.nextSlackSeconds)
            val requests = List(3) { JSONObject(server.takeRequest().body.readUtf8()) }
            assertTrue(requests[0].getJSONObject("origin").has("location"))
            assertEquals("new-pickup", requests[1].getJSONObject("origin").getString("placeId"))
            assertEquals("new-dropoff", requests[2].getJSONObject("origin").getString("placeId"))
            assertTrue(Instant.parse(requests[1].getString("departureTime")) >= at("10:35"))
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun boardedPassengerUsesGpsToExistingDropoffThenRoutesFromDropoff() = runBlocking {
        MockWebServer().use { server ->
            listOf(480L, 300L, 600L, 300L).forEach { server.enqueue(response(it)) }
            val rides = listOf(ride(1, "09:00").copy(actualBoardedAt = at("09:00").toString()), ride(2, "11:20"))
            val result = evaluator(server).evaluate(input, rides, { rides }, pickupOverride = pickup, destinationOverride = dropoff)
            assertEquals(at("10:33"), result.plan.availableAt)
            assertEquals("GPS 剩餘車程", result.plan.evidence)
            assertEquals(InsertionLevel.AVAILABLE, result.level)
            val first = JSONObject(server.takeRequest().body.readUtf8())
            assertTrue(first.getJSONObject("origin").has("location"))
            assertEquals("dropoff1", first.getJSONObject("destination").getString("placeId"))
            val second = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("dropoff1", second.getJSONObject("origin").getString("placeId"))
        }
    }
    @Test fun missingGpsFallsBackToActualBoardingAndMarksCaution() = runBlocking {
        MockWebServer().use { server ->
            listOf(300L, 600L, 300L).forEach { server.enqueue(response(it)) }
            val rides = listOf(ride(1, "10:00").copy(actualBoardedAt = at("10:15").toString()), ride(2, "12:00"))
            val result = evaluator(server) { error("no GPS") }.evaluate(input, rides, { rides }, pickupOverride = pickup, destinationOverride = dropoff)
            assertEquals(at("10:50"), result.plan.availableAt)
            assertEquals(InsertionLevel.CAUTION, result.level)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun futureScheduledGapDoesNotUseCurrentGpsOrRequireFinishedTripRoutes() = runBlocking {
        MockWebServer().use { server ->
            listOf(600L, 1200L, 600L).forEach { server.enqueue(response(it)) }
            val rides = listOf(ride(1, "09:00").copy(completed = true, rideMinutes = ""), ride(2, "13:00"), ride(3, "15:00"))
            val result = evaluator(server) { error("future anchor must not use GPS") }.evaluate(
                input.copy(asap = false, time = "14:00"), rides, { rides }, pickupOverride = pickup, destinationOverride = dropoff)
            assertEquals(2L, result.plan.active!!.id)
            assertEquals(3L, result.plan.next!!.id)
            assertEquals(at("13:40"), result.plan.availableAt)
            assertEquals(at("14:00"), result.pickupStart)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun manualAddressKeepsFutureGapTimeAndNextBooking() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"places":[{"id":"manual","displayName":{"text":"原點"},"formattedAddress":"台北市原點10號"}]}"""))
            listOf(600L, 1200L, 600L).forEach { server.enqueue(response(it)) }
            val rides = listOf(ride(2, "13:00"), ride(3, "15:00"))
            val result = evaluator(server) { error("no GPS needed") }.evaluate(input.copy(asap = false, time = "14:00"), rides, { rides },
                mode = InsertionOrigin.MANUAL, origin = "手動原點", manualBasis = InsertionOrigin.AUTO,
                pickupOverride = pickup, destinationOverride = dropoff)
            assertEquals(at("13:40"), result.plan.availableAt)
            assertEquals(InsertionOrigin.MANUAL, result.originMode)
            server.takeRequest()
            assertEquals("manual", JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("origin").getString("placeId"))
        }
    }
    @Test fun routeFailureCannotReturnAZeroDurationResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            try {
                evaluator(server).evaluate(input, emptyList(), { emptyList() }, pickupOverride = pickup, destinationOverride = dropoff)
                fail("route failure must remain unknown")
            } catch (e: java.io.IOException) { assertTrue(e.message!!.contains("無可行")) }
        }
    }
}
