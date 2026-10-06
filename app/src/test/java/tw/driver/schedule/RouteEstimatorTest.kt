package tw.driver.schedule

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.io.IOException

class RouteEstimatorTest {
    @Test fun streetOnlyRecognizesSectionsAndLanesButNotFullAddressesOrHospitals() {
        assertTrue(isStreetOnlyAddress("台北市中山北路二段"))
        assertTrue(isStreetOnlyAddress("民生東路三段20巷附近"))
        assertTrue(isStreetOnlyAddress("信義路"))
        assertFalse(isStreetOnlyAddress("信義路100號"))
        assertFalse(isStreetOnlyAddress("台大醫院"))
    }
    @Test fun roughStreetSelectsRepresentativeWithoutPromptAndKeepsExactCacheSeparate() = runBlocking {
        MockWebServer().use { server ->
            val response = """{"places":[{"id":"a","displayName":{"text":"信義路"},"formattedAddress":"台北市信義路"},{"id":"b","displayName":{"text":"信義路"},"formattedAddress":"新北市信義路"}]}"""
            server.enqueue(MockResponse().setBody(response))
            server.enqueue(MockResponse().setBody(response))
            val client = GoogleRouteClient("test-key", placesUrl = server.url("/places").toString())
            val rough = client.resolve("台北市信義路", "", { _, _ -> error("rough street must not prompt") }, true)
            assertEquals("a", rough.id)
            val exact = client.resolve("台北市信義路", "") { _, places -> places[1] }
            assertEquals("b", exact.id)
            assertEquals(2, server.requestCount)
        }
    }
    private fun ride() = RideOrder(id=1, date="2026/09/25", pickupTime="09:00", pickup="甲", destination="乙")
    @Test fun departureUsesPickupBufferOrCurrentTraffic() {
        val zone=ZoneId.of("Asia/Taipei")
        assertEquals(Instant.parse("2026-09-25T01:05:00Z"), routeDeparture(ride(), Instant.parse("2026-09-25T00:00:00Z"), zone))
        assertEquals(Instant.parse("2026-09-25T02:00:05Z"), routeDeparture(ride(), Instant.parse("2026-09-25T02:00:00Z"), zone))
    }
    @Test fun estimateInvalidatesForAddressTimeOrPreviousRideButNotCompletion() {
        val r=ride(); val prev=r.copy(id=2,pickupTime="08:00")
        assertEquals(routeInputKey(r,prev),routeInputKey(r.copy(completed=true, received="50"),prev))
        assertNotEquals(routeInputKey(r,prev),routeInputKey(r.copy(pickup="新地點"),prev))
        assertNotEquals(routeInputKey(r,prev),routeInputKey(r,prev.copy(destination="新下車點")))
        assertNotEquals(routeInputKey(r,prev),routeInputKey(r,null))
        assertNotEquals(routeInputKey(r,prev),routeInputKey(r.copy(pickupPlaceId="selected"),prev))
    }
    @Test fun roundTripsGoogleEstimateAndSelectedIds() {
        val time=Instant.parse("2026-09-25T01:00:00Z")
        val estimate=RouteEstimate("key",901,4000,300,time,time,time)
        val r=ride().copy(pickupPlaceId="a",destinationPlaceId="b",routeEstimate=estimate.json())
        assertEquals(r, r.toJson().toOrder())
        assertEquals(estimate,RouteEstimate.parse(r.routeEstimate))
        assertEquals(16L,routeMinutes(901))
    }
    @Test fun missingRouteIsNotReportedAsZeroMinutes() {
        try { parseRouteLeg(JSONObject("{}")); fail("must reject empty routes") } catch(expected: IOException) {}
        try { parseRouteLeg(JSONObject("""{"routes":[{"duration":"oops","distanceMeters":100}]}""")); fail("must reject malformed duration") } catch(expected: IOException) {}
        assertEquals(RouteLeg(61,100),parseRouteLeg(JSONObject("""{"routes":[{"duration":"60.1s","distanceMeters":100}]}""")))
    }
    @Test fun computeUsesTrafficPlaceIdsTimeAndMinimalMask() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"routes":[{"duration":"120s","distanceMeters":900}]}"""))
            val client=GoogleRouteClient("test-key",routesUrl=server.url("/routes").toString())
            assertEquals(RouteLeg(120,900),client.compute("origin-id","end-id",Instant.now().plusSeconds(300)))
            val request=server.takeRequest(); val body=JSONObject(request.body.readUtf8())
            assertEquals("origin-id",body.getJSONObject("origin").getString("placeId"))
            assertEquals("TRAFFIC_AWARE",body.getString("routingPreference"))
            assertTrue(body.has("departureTime"))
            assertEquals("routes.duration,routes.distanceMeters",request.getHeader("X-Goog-FieldMask"))
        }
    }
    @Test fun multiplePlacesRequireExplicitSelectionAndThenReuseSelectedId() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"places":[{"id":"a","displayName":{"text":"甲院"},"formattedAddress":"甲址"},{"id":"b","displayName":{"text":"乙院"},"formattedAddress":"乙址"}]}"""))
            val client=GoogleRouteClient("test-key",placesUrl=server.url("/places").toString())
            var asked=false
            val selected=client.resolve("醫院","") { _, options -> asked=true; options[1] }
            assertTrue(asked); assertEquals("b",selected.id)
            assertEquals("b",client.resolve("醫院","") { _, _ -> error("should reuse selection") }.id)
            assertEquals(1,server.requestCount)
        }
    }
    @Test fun singlePlaceDoesNotAskForConfirmation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"places":[{"id":"a","displayName":{"text":"甲院"},"formattedAddress":"甲址"}]}"""))
            val client=GoogleRouteClient("test-key",placesUrl=server.url("/places").toString())
            assertEquals("a",client.resolve("甲址","") { _, _ -> error("must not prompt") }.id)
        }
    }
    @Test fun deniedApiReturnsActionableErrorWithoutExposingKey() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("secret"))
            val client=GoogleRouteClient("secret-key",routesUrl=server.url("/routes").toString())
            try { client.compute("a","b",Instant.now()); fail("expected denied response") }
            catch(e: IOException) { assertTrue(e.message!!.contains("權限")); assertFalse(e.message!!.contains("secret")) }
        }
    }
}
