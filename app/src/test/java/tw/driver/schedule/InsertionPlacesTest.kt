package tw.driver.schedule

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InsertionPlacesTest {
    private val taipei = RoutePoint(25.03, 121.55)
    private fun place(id: String, address: String, lat: Double = 25.031, lng: Double = 121.551) = RoutePlace(id, "中山路", address, RoutePoint(lat, lng))
    private val response = """{"places":[
        {"id":"remote","displayName":{"text":"中山路"},"formattedAddress":"高雄市前金區中山路","location":{"latitude":22.63,"longitude":120.30}},
        {"id":"local","displayName":{"text":"中山路"},"formattedAddress":"臺北市中正區中山路","location":{"latitude":25.031,"longitude":121.551}}
    ]}"""

    @Test fun regionsNormalizeTaiAndDoNotConfuseRoadNamesWithDistricts() {
        assertEquals(StreetRegion("台北市", "中正區"), streetRegion("100臺北市中正區中山路"))
        assertEquals(StreetRegion("新北市", "板橋區"), streetRegion("新北市板橋區文化路一段"))
        assertNull(streetRegion("中山路"))
        assertEquals("文化路一段", streetName("新北市板橋區文化路一段附近"))
    }
    @Test fun explicitRegionFiltersRemoteSameNameAndWrongStreet() {
        val candidates = listOf(place("a", "高雄市中正區中山路"), place("b", "台北市中正區中山路"),
            RoutePlace("c", "中山北路", "台北市中正區中山北路", taipei))
        assertEquals(listOf("b"), qualifiedStreetPlaces("台北市中正區中山路", candidates,
            StreetRegion("台北市", "中正區"), null).map { it.id })
    }
    @Test fun nearOriginRequiresCoordinatesAndRejectsRemoteResultsDespiteSearchBias() {
        val candidates = listOf(place("remote", "高雄市中山路", 22.63, 120.30), place("near", "台北市中山路"),
            RoutePlace("unknown", "中山路", "台北市中山路"))
        assertEquals(listOf("near"), qualifiedStreetPlaces("中山路", candidates, null, taipei).map { it.id })
        assertTrue(qualifiedStreetPlaces("中山路", candidates, null, null).isEmpty())
    }
    @Test fun locationTermsRegionTakesPriorityOverGpsAndReferenceCity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            val terms = LocationTerms(listOf(LocationTerm("中山路", "台北市中正區中山路")))
            val client = GoogleRouteClient("key", placesUrl = server.url("/places").toString(), locationTerms = { terms })
            val selected = client.resolveInsertion("中山路", reference = place("origin", "高雄市前金區原點"),
                referencePoint = RoutePoint(22.63, 120.30)) { _, _ -> error("region is explicit") }
            assertEquals("local", selected.id)
            assertTrue(selected.approximate)
            assertTrue(selected.selectionReason.contains("地點對照"))
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("台北市中正區中山路", body.getString("textQuery"))
            assertFalse(body.has("locationBias"))
        }
    }
    @Test fun gpsBiasStillVerifiesLocalDistanceBeforeChoosing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            val client = GoogleRouteClient("key", placesUrl = server.url("/places").toString())
            val selected = client.resolveInsertion("中山路", referencePoint = taipei) { _, _ -> error("nearby match exists") }
            assertEquals("local", selected.id)
            val body = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(25.03, body.getJSONObject("locationBias").getJSONObject("circle").getJSONObject("center").getDouble("latitude"), 0.001)
            assertTrue(server.requestCount == 1)
        }
    }
    @Test fun noRegionPromptsEvenWithOnlyOneSearchResult() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"places":[{"id":"a","displayName":{"text":"中山路"},"formattedAddress":"台北市中山路"}]}"""))
            val client = GoogleRouteClient("key", placesUrl = server.url("/places").toString())
            var asked = false
            val selected = client.resolveInsertion("中山路") { query, places ->
                asked = true; assertTrue(query.contains("區域")); places.single()
            }
            assertTrue(asked)
            assertEquals("a", selected.id)
        }
    }
    @Test fun wrongExplicitRegionAndEmptyResultsCannotProduceAnEstimate() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            server.enqueue(MockResponse().setBody("{}"))
            val client = GoogleRouteClient("key", placesUrl = server.url("/places").toString())
            try { client.resolveInsertion("新北市中山路") { _, _ -> error("must not accept a different city") }; fail("wrong city") }
            catch (e: IOException) { assertTrue(e.message!!.contains("新北市")) }
            try { client.resolveInsertion("台北市信義路") { _, _ -> error("no options") }; fail("empty response") }
            catch (e: IOException) { assertTrue(e.message!!.contains("找不到")) }
        }
    }
    @Test fun manualReplacementBypassesRoughCacheAndReusesExplicitChoice() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"places":[{"id":"a","displayName":{"text":"中山路"},"formattedAddress":"台北市中山路"},
                {"id":"b","displayName":{"text":"接客點"},"formattedAddress":"台北市中山路10號"}]}"""
            server.enqueue(MockResponse().setBody(body)); server.enqueue(MockResponse().setBody(body))
            val client = GoogleRouteClient("key", placesUrl = server.url("/places").toString())
            assertEquals("a", client.resolveInsertion("台北市中山路") { _, _ -> error("rough selection") }.id)
            val precise = client.resolveInsertion("台北市中山路", forceSelection = true) { _, options -> options.first { it.id == "b" } }
            assertEquals("b", precise.id)
            assertFalse(precise.approximate)
            assertEquals("b", client.resolveInsertion("台北市中山路") { _, _ -> error("reuse") }.id)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun roughCacheDoesNotCrossReferenceRegions() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setBody(response)) }
            val client = GoogleRouteClient("key", placesUrl = server.url("/places").toString())
            assertEquals("local", client.resolveInsertion("中山路", reference = RoutePlace("t", "原點", "台北市中正區原點")) { _, _ -> error("known region") }.id)
            assertEquals("remote", client.resolveInsertion("中山路", reference = RoutePlace("k", "原點", "高雄市前金區原點")) { _, _ -> error("known region") }.id)
            assertEquals(2, server.requestCount)
        }
    }
    @Test fun districtWithoutCityUsesReferenceCityAndDoesNotChooseAnotherCity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response.replace("前金區", "中正區")))
            val client = GoogleRouteClient("key", placesUrl = server.url("/places").toString())
            val selected = client.resolveInsertion("中正區中山路", reference = RoutePlace("origin", "原點", "台北市信義區原點")) { _, _ -> error("city known") }
            assertEquals("local", selected.id)
            assertEquals("台北市中正區中山路", JSONObject(server.takeRequest().body.readUtf8()).getString("textQuery"))
        }
    }
}
