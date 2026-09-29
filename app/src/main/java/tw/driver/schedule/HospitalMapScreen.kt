package tw.driver.schedule

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.*
import com.google.maps.android.compose.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal data class HospitalLocation(val lat: Double, val lng: Double, val placeId: String = "")
internal data class Hospital(val id: String, val name: String, val city: String, val address: String,
    val location: HospitalLocation?, val note: String = "", val closedFrom: String = "")
internal fun parseHospitals(text: String): List<Hospital> {
    val a = JSONArray(text)
    return List(a.length()) { index ->
        val o = a.getJSONObject(index)
        Hospital(o.getString("id"), o.getString("name"), o.getString("city"), o.getString("address"),
            if (o.has("lat") && o.has("lng")) HospitalLocation(o.getDouble("lat"), o.getDouble("lng")) else null,
            o.optString("note"), o.optString("closedFrom"))
    }
}

class HospitalMapViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("hospital_cache", Context.MODE_PRIVATE)
    internal val hospitals = parseHospitals(application.assets.open("hospitals.json").bufferedReader().use { it.readText() })
    internal var locations by mutableStateOf(loadInitialLocations()); private set
    internal var errors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set
    private var job: Job? = null

    private fun loadInitialLocations(): Map<String, HospitalLocation> {
        val base = hospitals.mapNotNull { h -> h.location?.let { h.id to it } }.toMap()
        val cachedJson = prefs.getString("cached_locations", null) ?: return base
        return runCatching {
            val obj = JSONObject(cachedJson)
            val cached = mutableMapOf<String, HospitalLocation>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val item = obj.getJSONObject(id)
                cached[id] = HospitalLocation(item.getDouble("lat"), item.getDouble("lng"), item.optString("placeId"))
            }
            base + cached
        }.getOrDefault(base)
    }

    private fun saveLocations(currentLocations: Map<String, HospitalLocation>) {
        runCatching {
            val obj = JSONObject()
            currentLocations.forEach { (id, loc) ->
                obj.put(id, JSONObject().put("lat", loc.lat).put("lng", loc.lng).put("placeId", loc.placeId))
            }
            prefs.edit().putString("cached_locations", obj.toString()).apply()
        }
    }

    fun loadMissing(force: Boolean = false) {
        if (loading) return
        job = viewModelScope.launch {
            loading = true
            try {
                val client = GoogleRouteClient.create(getApplication())
                val toQuery = if (force) {
                    hospitals.filter { it.closedFrom.isBlank() || LocalDate.now() < LocalDate.parse(it.closedFrom) }
                } else {
                    hospitals.filter { it.id !in locations && (it.closedFrom.isBlank() || LocalDate.now() < LocalDate.parse(it.closedFrom)) }
                }
                for (h in toQuery) {
                    try {
                        val loc = client.locateHospital(h.name, h.address)
                        locations = locations + (h.id to loc)
                        errors = errors - h.id
                    }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { errors = errors + (h.id to (e.message ?: "位置查詢失敗")) }
                }
                saveLocations(locations)
            } finally { loading = false }
        }
    }
}

// Google Maps 官方標準暗黑風格 JSON，水體、道路、綠地清晰可辨，解決全黑異常
private val GOOGLE_MAPS_DARK_STYLE = """
[
  {"elementType":"geometry","stylers":[{"color":"#242f3e"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#242f3e"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#746855"}]},
  {"featureType":"administrative.locality","elementType":"labels.text.fill","stylers":[{"color":"#d59563"}]},
  {"featureType":"poi","elementType":"labels.text.fill","stylers":[{"color":"#d59563"}]},
  {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#263c3f"}]},
  {"featureType":"poi.park","elementType":"labels.text.fill","stylers":[{"color":"#6b9a76"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#38414e"}]},
  {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#212a37"}]},
  {"featureType":"road","elementType":"labels.text.fill","stylers":[{"color":"#9ca5b3"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#746855"}]},
  {"featureType":"road.highway","elementType":"geometry.stroke","stylers":[{"color":"#1f2835"}]},
  {"featureType":"road.highway","elementType":"labels.text.fill","stylers":[{"color":"#f3d19c"}]},
  {"featureType":"transit","elementType":"geometry","stylers":[{"color":"#2f3948"}]},
  {"featureType":"transit.station","elementType":"labels.text.fill","stylers":[{"color":"#d59563"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#17263c"}]},
  {"featureType":"water","elementType":"labels.text.fill","stylers":[{"color":"#515c6d"}]},
  {"featureType":"water","elementType":"labels.text.stroke","stylers":[{"color":"#17263c"}]}
]
""".trimIndent()

@Composable internal fun HospitalMapScreen(rides: List<RideOrder>, context: Context, onSearch: () -> Unit, selectedPlace: String?, darkMode: Boolean) {
    val vm: HospitalMapViewModel = viewModel()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Hospital?>(null) }
    var showSources by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    val hospitals = vm.hospitals.filter { it.closedFrom.isBlank() || LocalDate.now() < LocalDate.parse(it.closedFrom) }
    val filtered = hospitals.filter { query.isBlank() || it.name.contains(query) || it.address.contains(query) }
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(LatLng(25.08, 121.53), 10f) }
    val mapStyle = remember(darkMode) { if (darkMode) MapStyleOptions(GOOGLE_MAPS_DARK_STYLE) else null }
    fun overview() {
        val points = filtered.mapNotNull { vm.locations[it.id] }
        if (points.isNotEmpty() && mapLoaded) scope.launch {
            val bounds = LatLngBounds.builder().apply { points.forEach { include(LatLng(it.lat, it.lng)) } }.build()
            camera.animate(CameraUpdateFactory.newLatLngBounds(bounds, 60))
        }
    }
    // 已從本機快取與靜態清單載入，不強制自動啟動遠端查詢；使用者可手動點擊「手動更新位置」
    LaunchedEffect(mapLoaded, vm.loading) { if(mapLoaded && !vm.loading) overview() }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("雙北醫院地圖", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.loadMissing(force = true) }, enabled = !vm.loading) {
                Text(if (vm.loading) "更新中…" else "手動更新位置")
            }
            TextButton(onClick = ::overview) { Text("總覽") }
            TextButton(onClick = { showSources = true }) { Text("範圍") }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("搜尋醫院／院區／地區") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("${filtered.count { it.id in vm.locations }}/${filtered.size} 院區已定位${if(vm.loading) " · 補齊位置中…" else ""}", style = MaterialTheme.typography.labelMedium)
        GoogleMap(Modifier.fillMaxWidth().weight(1f), cameraPositionState = camera, properties = MapProperties(mapStyleOptions = mapStyle), onMapLoaded = { mapLoaded = true }) {
            filtered.forEach { hospital -> vm.locations[hospital.id]?.let { point ->
                key(hospital.id, point) {
                    Marker(state = remember { MarkerState(LatLng(point.lat, point.lng)) }, title = hospital.name, snippet = hospital.address,
                        icon = BitmapDescriptorFactory.defaultMarker(if(hospital.city == "臺北市") BitmapDescriptorFactory.HUE_AZURE else BitmapDescriptorFactory.HUE_ORANGE),
                        onClick = { selected = hospital; false })
                }
            } }
        }
        selected?.let { h ->
            Text(h.name, style = MaterialTheme.typography.titleSmall)
            Text(h.address, style = MaterialTheme.typography.bodySmall)
            if(h.note.isNotBlank()) Text(h.note, style = MaterialTheme.typography.labelSmall)
            Row {
                TextButton(onClick = { launchNavigation(context, h.address, vm.locations[h.id]?.placeId.orEmpty()) }) { Text("Google Maps 導航") }
                TextButton(onClick = { selected = null }) { Text("收合") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onSearch) { Text("搜尋其他地點") }
            if (vm.errors.isNotEmpty() && !vm.loading) TextButton(onClick = { vm.loadMissing(true) }) { Text("重試 ${vm.errors.size} 筆位置") }
        }
        selectedPlace?.let { Text("搜尋結果：$it", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall) }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
            items(filtered, key = { it.id }) { h ->
                TextButton(onClick = {
                    selected = h
                    vm.locations[h.id]?.let { point -> scope.launch { camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(point.lat, point.lng), 14f)) } }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("${h.name}${if(h.id !in vm.locations) "（位置待查）" else ""}", modifier = Modifier.fillMaxWidth())
                }
            }
            item { Text("訂單目的地", style = MaterialTheme.typography.labelMedium) }
            items(rides.filter { !it.returnRide }.distinctBy { it.destination }, key = { "ride-${it.id}" }) { ride ->
                TextButton(onClick = { launchNavigation(context, ride.destination, ride.destinationPlaceId) }) { Text(ride.destination) }
            }
        }
    }
    if (showSources) AlertDialog(onDismissRequest = { showSources = false }, title = { Text("醫院收錄範圍") }, text = { Column {
        Text("臺北公私立醫院 36 筆＋聯醫分院 8 筆；新北急救責任醫院 19 筆＋板橋院區。含主要大型醫院及部分地區醫院，並非依病床數定義的完整大型醫院評鑑名冊。")
        Text("名單整理：2026/09/25。藍色為臺北、橘色為新北。衛生局提供的座標先顯示；缺少座標由 Google Maps 查詢。未能唯一定位者不會放置猜測標記。")
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://data.gov.tw/dataset/133342"))) }) { Text("臺北市資料來源") }
        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://data.gov.tw/dataset/125827"))) }) { Text("新北市資料來源") }
    } }, confirmButton = { TextButton(onClick = { showSources = false }) { Text("關閉") } })
}
