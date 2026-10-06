package tw.driver.schedule

import kotlin.math.*
import org.json.JSONObject

internal data class RoutePoint(val latitude: Double, val longitude: Double, val accuracyMeters: Int? = null) {
    init { require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0) }
    fun distanceTo(other: RoutePoint): Double {
        val lat = Math.toRadians(other.latitude - latitude)
        val lng = Math.toRadians(other.longitude - longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) * sin(lng / 2).pow(2)
        return 6_371_000 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

internal data class StreetRegion(val city: String = "", val district: String = "") {
    val label get() = city + district
    fun contains(address: String): Boolean {
        val normalized = normalizePlaceText(address)
        return (city.isBlank() || normalized.contains(city)) && (district.isBlank() || normalized.contains(district))
    }
}

internal fun normalizePlaceText(value: String): String = value.replace('臺', '台').replace(Regex("\\s+"), "")

internal fun streetRegion(value: String): StreetRegion? {
    val text = normalizePlaceText(value)
    val city = Regex("台北市|新北市|桃園市|台中市|台南市|高雄市|基隆市|新竹[縣市]|苗栗縣|彰化縣|南投縣|雲林縣|嘉義[縣市]|屏東縣|宜蘭縣|花蓮縣|台東縣|澎湖縣|金門縣|連江縣").find(text)
    val after = if (city != null) text.substring(city.range.last + 1) else text
    val district = Regex(if (city != null) "^([\\p{IsHan}]{1,4}[區鄉鎮市])" else "^([\\p{IsHan}]{1,4}[區鄉鎮])")
        .find(after)?.value.orEmpty()
    return if (city == null && district.isBlank()) null else StreetRegion(city?.value.orEmpty(), district)
}

internal fun streetName(query: String): String {
    val normalized = normalizePlaceText(query).removeSuffix("附近").removeSuffix("周邊")
    val region = streetRegion(normalized) ?: return normalized
    return normalized.substringAfter(region.label)
}

/** A Google location bias can return remote results, so distance/region is checked again locally. */
internal fun qualifiedStreetPlaces(query: String, places: List<RoutePlace>, region: StreetRegion?, point: RoutePoint?): List<RoutePlace> {
    val street = streetName(query)
    return places.filter { place ->
        val matchesStreet = normalizePlaceText(place.name).contains(street) || normalizePlaceText(place.address).contains(street)
        matchesStreet && when {
            region != null -> region.contains(place.address) &&
                (region.city.isNotBlank() || point == null || place.point?.let { point.distanceTo(it) <= 20_000 } == true)
            point != null -> place.point?.let { point.distanceTo(it) <= 20_000 } == true && streetRegion(place.address) != null
            else -> false
        }
    }.let { matched -> if (point != null) matched.sortedBy { it.point?.let(point::distanceTo) ?: Double.MAX_VALUE } else matched }
}

internal fun routePlaceFromJson(value: JSONObject): RoutePlace {
    val location = value.optJSONObject("location")
    val point = location?.let {
        runCatching { RoutePoint(it.getDouble("latitude"), it.getDouble("longitude")) }.getOrNull()
    }
    return RoutePlace(value.getString("id"), value.optJSONObject("displayName")?.optString("text").orEmpty(),
        value.optString("formattedAddress"), point)
}
