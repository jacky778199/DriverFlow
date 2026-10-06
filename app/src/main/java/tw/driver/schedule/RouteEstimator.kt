package tw.driver.schedule

import android.content.Context
import android.content.pm.PackageManager
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class RoutePlace(val id: String, val name: String, val address: String, val point: RoutePoint? = null,
    val approximate: Boolean = false, val selectionReason: String = "")
internal data class RouteLeg(val seconds: Long, val meters: Long)
internal data class RouteEstimate(
    val key: String, val rideSeconds: Long, val meters: Long, val transferSeconds: Long?,
    val departure: Instant, val transferDeparture: Instant?, val updated: Instant,
    val transferError: String = ""
) {
    fun json(): String = JSONObject().put("key", key).put("rideSeconds", rideSeconds).put("meters", meters)
        .put("transferSeconds", transferSeconds ?: JSONObject.NULL).put("departure", departure.toString())
        .put("transferDeparture", transferDeparture?.toString().orEmpty()).put("updated", updated.toString())
        .put("transferError", transferError).toString()
    companion object {
        fun parse(value: String): RouteEstimate? = runCatching {
            val o = JSONObject(value)
            RouteEstimate(o.getString("key"), o.getLong("rideSeconds"), o.getLong("meters"),
                if(o.isNull("transferSeconds")) null else o.getLong("transferSeconds"),
                Instant.parse(o.getString("departure")), o.optString("transferDeparture").takeIf { it.isNotBlank() }?.let(Instant::parse),
                Instant.parse(o.getString("updated")), o.optString("transferError"))
        }.getOrNull()
    }
}
internal fun routeInputKey(ride: RideOrder, previous: RideOrder?): String {
    fun fields(r: RideOrder) = listOf(r.id.toString(), r.serviceDate, r.pickupTime, r.pickup, r.destination, r.pickupPlaceId, r.destinationPlaceId)
    return MessageDigest.getInstance("SHA-256").digest(JSONArray(fields(ride) + (previous?.let(::fields) ?: emptyList())).toString().toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
internal fun scheduledPickup(ride: RideOrder, zone: ZoneId = ZoneId.systemDefault()): Instant {
    val minute = minuteOfDay(ride.pickupTime) ?: throw IOException("接客時間未填，請先修改訂單")
    return LocalDate.parse(ride.serviceDate).atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant()
}
internal fun routeDeparture(ride: RideOrder, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
    maxOf(scheduledPickup(ride, zone).plusSeconds(300), now.plusSeconds(5))
internal fun routeMinutes(seconds: Long): Long = ceil(seconds / 60.0).toLong()

/** A road/section/lane without a house number is enough for a rough insertion estimate. */
internal fun isStreetOnlyAddress(address: String): Boolean =
    Regex("(?:路|街|大道)(?:[一二三四五六七八九十百0-9０-９]+段)?(?:[一二三四五六七八九十百0-9０-９]+巷)?(?:[一二三四五六七八九十百0-9０-９]+弄)?(?:附近|周邊)?$")
        .containsMatchIn(address.trim()) && !address.contains("號")

internal class GoogleRouteClient(
    private val apiKey: String,
    private val androidPackage: String = "",
    private val androidCert: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).build(),
    private val routesUrl: String = "https://routes.googleapis.com/directions/v2:computeRoutes",
    private val placesUrl: String = "https://places.googleapis.com/v1/places:searchText",
    private val placesApiKey: String = apiKey,
    private val locationTerms: () -> LocationTerms = { LocationTerms() }
) {
    // Only selected Place IDs are cached, not transient traffic data.
    private val resolved = mutableMapOf<String, RoutePlace>()
    companion object {
        @Suppress("DEPRECATION")
        fun create(context: Context): GoogleRouteClient {
            val signature = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()?.toByteArray()
            val sha1 = signature?.let { MessageDigest.getInstance("SHA-1").digest(it).joinToString("") { b -> "%02X".format(b.toInt() and 255) } }.orEmpty()
            return GoogleRouteClient(BuildConfig.ROUTES_API_KEY, context.packageName, sha1, placesApiKey = BuildConfig.MAPS_API_KEY, locationTerms = { LocationTermsStore(context).load() })
        }
    }
    private suspend fun post(url: String, mask: String, data: JSONObject): JSONObject = request(url, mask, data)

    private suspend fun request(url: String, mask: String, data: JSONObject? = null): JSONObject {
        if (apiKey.isBlank()) throw IOException("尚未設定 Routes API 金鑰")
        return suspendCancellableCoroutine { continuation ->
            val builder = Request.Builder().url(url).header("X-Goog-Api-Key", if (url.startsWith(placesUrl.substringBefore(":searchText"))) placesApiKey else apiKey).header("X-Goog-FieldMask", mask)
            if (data != null) builder.post(data.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            else builder.get()
            if (androidPackage.isNotBlank()) builder.header("X-Android-Package", androidPackage).header("X-Android-Cert", androidCert)
            val call = client.newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val json = response.use {
                            if (!it.isSuccessful) throw IOException(when(it.code) {
                                401, 403 -> "Google API 權限不足：請檢查 Routes API、Places API (New)、帳單與金鑰限制（HTTP ${it.code}）"
                                429 -> "Google API 配額已用完，請稍後更新"
                                else -> "Google 路線查詢失敗（HTTP ${it.code}），請稍後重試或修改地點"
                            })
                            JSONObject(it.body?.string() ?: throw IOException("Google 回應為空"))
                        }
                        if (continuation.isActive) continuation.resume(json)
                    } catch(e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
    }
    fun expandedAddress(address: String): String = locationTerms().expand(addressForDisplay(address)).trim()

    suspend fun resolve(address: String, knownId: String, choose: suspend (String, List<RoutePlace>) -> RoutePlace): RoutePlace =
        resolve(address, knownId, choose, false)

    suspend fun resolve(address: String, knownId: String, choose: suspend (String, List<RoutePlace>) -> RoutePlace,
        approximateStreet: Boolean): RoutePlace {
        if (approximateStreet) return resolveInsertion(address, choose = choose)
        if (knownId.isNotBlank()) return RoutePlace(knownId, address, address)
        val query = expandedAddress(address)
        val cacheKey = query
        resolved[cacheKey]?.let { return it }
        if (query.isBlank() || query.contains("待填") || query.contains("地點待確認")) throw IOException("請先填寫完整地點")
        val result = post(placesUrl, "places.id,places.displayName,places.formattedAddress,places.location,nextPageToken",
            JSONObject().put("textQuery", query).put("languageCode", "zh-TW").put("regionCode", "TW").put("pageSize", 20))
        val array = result.optJSONArray("places") ?: JSONArray()
        val places = List(array.length()) { i -> routePlaceFromJson(array.getJSONObject(i)) }.distinctBy { it.id }
        if (places.isEmpty()) throw IOException("Google 找不到「$query」，請修改地址或使用 Google 搜尋選取")
        val selected = if (places.size == 1 && result.optString("nextPageToken").isBlank()) places.single() else choose(query, places)
        require(places.any { it.id == selected.id })
        resolved[cacheKey] = selected
        return selected
    }

    suspend fun placeDetails(place: RoutePlace): RoutePlace {
        if (place.point != null && place.address.isNotBlank()) return place
        return routePlaceFromJson(request(placesUrl.substringBefore(":searchText") + "/${place.id}",
            "id,displayName,formattedAddress,location"))
    }

    suspend fun resolveInsertion(address: String, reference: RoutePlace? = null, referencePoint: RoutePoint? = null,
        forceSelection: Boolean = false, choose: suspend (String, List<RoutePlace>) -> RoutePlace): RoutePlace {
        val query = expandedAddress(address)
        if (!isStreetOnlyAddress(query) && !forceSelection) return resolve(address, "", choose)
        require(query.isNotBlank()) { "請先填寫地點" }
        val explicitRegion = streetRegion(query)
        val referenceRegion = reference?.address?.let(::streetRegion)
        val region = if (explicitRegion != null && explicitRegion.city.isBlank() && referenceRegion?.city?.isNotBlank() == true)
            explicitRegion.copy(city = referenceRegion.city) else explicitRegion ?: referenceRegion
        val point = reference?.point ?: referencePoint
        val cacheKey = "insertion:$query:${region?.label}:${point?.latitude}:${point?.longitude}"
        if (!forceSelection) resolved[cacheKey]?.let { return it }
        val textQuery = if (explicitRegion == null && region != null) region.label + query
            else if (explicitRegion != null && explicitRegion.city.isBlank() && region?.city?.isNotBlank() == true) region.city + query else query
        val body = JSONObject().put("textQuery", textQuery).put("languageCode", "zh-TW").put("regionCode", "TW").put("pageSize", 20)
        if (point != null && explicitRegion?.city.isNullOrBlank()) body.put("locationBias", JSONObject().put("circle", JSONObject()
            .put("center", JSONObject().put("latitude", point.latitude).put("longitude", point.longitude)).put("radius", 20_000)))
        val result = post(placesUrl, "places.id,places.displayName,places.formattedAddress,places.location,nextPageToken", body)
        val array = result.optJSONArray("places") ?: JSONArray()
        val places = List(array.length()) { routePlaceFromJson(array.getJSONObject(it)) }.distinctBy { it.id }
        if (places.isEmpty()) throw IOException("Google 找不到「$query」，請修改地點")
        val qualified = if (isStreetOnlyAddress(query)) qualifiedStreetPlaces(query, places, region, point)
            else places.filter { explicitRegion == null || explicitRegion.contains(it.address) }
        if (explicitRegion != null && qualified.isEmpty()) throw IOException("找不到「${explicitRegion.label}」內的「${streetName(query)}」，請修改地點")
        val selected = if (!forceSelection && isStreetOnlyAddress(query) && qualified.isNotEmpty() &&
            (region?.city?.isNotBlank() == true || point != null)) {
            val source = if (query != addressForDisplay(address).trim()) "地點對照／區域" else if (explicitRegion != null) "訊息區域" else "出發地附近"
            qualified.first().copy(approximate = true, selectionReason = "${source}街道代表位置（粗估）")
        } else {
            val options = qualified.ifEmpty { places }
            val choice = choose(if (region == null && point == null) "$query（請確認所在區域）" else query, options)
            require(options.any { it.id == choice.id }) { "選取地點不在候選清單" }
            choice.copy(approximate = isStreetOnlyAddress(choice.name) || isStreetOnlyAddress(choice.address), selectionReason = "手動選取位置")
        }
        resolved[cacheKey] = selected
        return selected
    }
    suspend fun locateHospital(name: String, address: String): HospitalLocation {
        val result = post(placesUrl, "places.id,places.location,places.formattedAddress", JSONObject()
            .put("textQuery", "$name $address").put("languageCode", "zh-TW").put("regionCode", "TW").put("pageSize", 5))
        val places = result.optJSONArray("places") ?: throw IOException("找不到院區位置")
        if (places.length() != 1) throw IOException("院區位置不唯一，請用 Google Maps 查詢")
        val place = places.getJSONObject(0)
        val point = place.getJSONObject("location")
        val lat = point.getDouble("latitude"); val lng = point.getDouble("longitude")
        require(lat in 24.7..25.4 && lng in 121.2..122.1) { "位置不在雙北範圍" }
        return HospitalLocation(lat, lng, place.getString("id"))
    }
    suspend fun compute(originId: String, destinationId: String, departure: Instant): RouteLeg {
        return computeWaypoint(JSONObject().put("placeId", originId), destinationId, departure)
    }
    suspend fun computeFromPosition(latitude: Double, longitude: Double, destinationId: String, departure: Instant): RouteLeg {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        return computeWaypoint(JSONObject().put("location", JSONObject().put("latLng", JSONObject()
            .put("latitude", latitude).put("longitude", longitude))), destinationId, departure)
    }
    private suspend fun computeWaypoint(origin: JSONObject, destinationId: String, departure: Instant): RouteLeg {
        val result = post(routesUrl, "routes.duration,routes.distanceMeters", JSONObject()
            .put("origin", origin).put("destination", JSONObject().put("placeId", destinationId))
            .put("travelMode", "DRIVE").put("routingPreference", "TRAFFIC_AWARE")
            .put("departureTime", maxOf(departure, Instant.now().plusSeconds(5)).toString())
            .put("computeAlternativeRoutes", false).put("languageCode", "zh-TW").put("units", "METRIC"))
        return parseRouteLeg(result)
    }
}
internal fun parseRouteLeg(result: JSONObject): RouteLeg {
    val route = result.optJSONArray("routes")?.optJSONObject(0) ?: throw IOException("Google 無可行汽車路線，請檢查兩端地點")
    val raw = route.optString("duration")
    if (!Regex("\\d+(?:\\.\\d+)?s").matches(raw)) throw IOException("Google 未回傳有效車程")
    val seconds = ceil(raw.dropLast(1).toDouble()).toLong()
    val meters = route.optLong("distanceMeters", -1)
    if (meters < 0) throw IOException("Google 未回傳有效距離")
    return RouteLeg(seconds, meters)
}
