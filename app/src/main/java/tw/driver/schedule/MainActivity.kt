package tw.driver.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.view.WindowCompat
import android.widget.Toast
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import org.json.JSONArray
import org.json.JSONObject

data class RideOrder(
    val id: Long = System.nanoTime(), val date: String = bookingDate(""), val pickupTime: String,
    val customer: String = "", val contact: String = "", val pickup: String,
    val destination: String, val wheelchair: Boolean = true, val notes: String = "", val raw: String = "",
    val tentative: Boolean = false, val returnRide: Boolean = false, val timeFlexible: Boolean = false,
    val calendarTime: String = "", val needsAddressCheck: Boolean = false,
    val contactPhone: String = "", val passengerPhone: String = "", val uncertainties: String = "",
    val wheelchairUnknown: Boolean = false, val sourceImage: String = "", val imageTranscript: String = "",
    val fare: String = "", val bookingId: String = "",
    val serviceDate: String = fullRideDate(date), val category: String = "未分類",
    val completed: Boolean = false, val received: String = "",
    val rideMinutes: String = "", val transferMinutes: String = "",
    val pickupPlaceId: String = "", val destinationPlaceId: String = "", val routeEstimate: String = "", val subsidyDue: String = "",
    val tip: String = ""
)

class OrderStore(context: Context) {
    private val prefs = context.getSharedPreferences("rides", Context.MODE_PRIVATE)
    fun load(): List<RideOrder> = runCatching {
        val array = JSONArray(prefs.getString("items", "[]"))
        List(array.length()) { i -> array.getJSONObject(i).toOrder() }.also { migrated ->
            if ((0 until array.length()).any { !array.getJSONObject(it).has("serviceDate") }) save(migrated)
        }
    }.getOrDefault(emptyList())
    fun save(items: List<RideOrder>) {
        val array = JSONArray(); items.forEach { array.put(it.toJson()) }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
internal fun RideOrder.toJson() = JSONObject().apply {
    put("id", id); put("date", date); put("time", pickupTime); put("customer", customer); put("contact", contact)
    put("pickup", pickup); put("destination", destination); put("wheelchair", wheelchair); put("notes", notes); put("raw", raw)
    put("tentative", tentative); put("returnRide", returnRide); put("flexible", timeFlexible); put("calendar", calendarTime); put("check", needsAddressCheck)
    put("contactPhone", contactPhone); put("passengerPhone", passengerPhone); put("uncertainties", uncertainties)
    put("wheelchairUnknown", wheelchairUnknown); put("sourceImage", sourceImage); put("imageTranscript", imageTranscript)
    put("fare", fare); put("bookingId", bookingId)
    put("serviceDate", serviceDate); put("category", category); put("completed", completed)
    put("pickupPlaceId", pickupPlaceId); put("destinationPlaceId", destinationPlaceId); put("routeEstimate", routeEstimate)
    put("subsidyDue", subsidyDue)
    put("received", received); put("rideMinutes", rideMinutes); put("transferMinutes", transferMinutes)
    put("tip", tip)
}
internal fun JSONObject.toOrder() = RideOrder(
    id = getLong("id"),
    date = getString("date"),
    pickupTime = getString("time"),
    customer = getString("customer"),
    contact = getString("contact"),
    pickup = getString("pickup"),
    destination = getString("destination"),
    wheelchair = true,
    notes = getString("notes"),
    raw = getString("raw"),
    tentative = getBoolean("tentative"),
    returnRide = getBoolean("returnRide"),
    timeFlexible = getBoolean("flexible"),
    calendarTime = getString("calendar"),
    needsAddressCheck = getBoolean("check"),
    contactPhone = optString("contactPhone"),
    passengerPhone = optString("passengerPhone"),
    uncertainties = cleanWheelchairWarning(optString("uncertainties")),
    wheelchairUnknown = false,
    sourceImage = optString("sourceImage"),
    imageTranscript = optString("imageTranscript"),
    fare = optString("fare").ifBlank { extractFare(getString("notes")) },
    bookingId = optString("bookingId"),
    serviceDate = optString("serviceDate").ifBlank { fullRideDate(getString("date")) },
    category = optString("category", "未分類"),
    completed = optBoolean("completed", false),
    received = optString("received"),
    rideMinutes = optString("rideMinutes"),
    transferMinutes = optString("transferMinutes"),
    pickupPlaceId = optString("pickupPlaceId"),
    destinationPlaceId = optString("destinationPlaceId"),
    routeEstimate = optString("routeEstimate"),
    subsidyDue = optString("subsidyDue"),
    tip = optString("tip")
)

/** 移除預設的提示性文字，讓使用者在手動新增時欄位完全空白，不需手動 backspace 刪除 */
internal fun stripPlaceholder(str: String): String {
    val trimmed = str.trim()
    return if (trimmed.contains("待確認") || trimmed.contains("待填") || trimmed.contains("手動輸入草稿")) "" else trimmed
}

/** 手動輸入建立空白草稿：不預填任何需要手動刪除的文字 */
private fun localParse(raw: String): RideOrder {
    val clean = raw.trim()
    val time = Regex("\\d{1,2}:\\d{2}(?:\\s*[–-]\\s*\\d{1,2}:\\d{2})?").find(clean)?.value ?: ""
    val arrow = Regex("(.+?)\\s*(?:→|到|至)\\s*(.+)").find(clean)
    return RideOrder(
        date = "",
        pickupTime = time,
        customer = "",
        contact = "",
        pickup = arrow?.groupValues?.get(1)?.trim() ?: "",
        destination = arrow?.groupValues?.get(2)?.trim() ?: "",
        raw = clean,
        notes = "",
        needsAddressCheck = true,
        fare = extractFare(clean),
        serviceDate = ""
    )
}

class MainActivity : ComponentActivity() {
    override fun onResume() { super.onResume(); InsertionAnalysis.foreground = true }
    override fun onPause() { InsertionAnalysis.foreground = false; super.onPause() }

    private var messageRequest by mutableLongStateOf(0L)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (BuildConfig.MAPS_API_KEY.isNotBlank() && !Places.isInitialized()) {
            Places.initializeWithNewPlacesApiEnabled(applicationContext, BuildConfig.MAPS_API_KEY)
        }
        if (intent.getBooleanExtra(MessageService.OPEN_MESSAGE, false)) messageRequest = System.nanoTime()
        setContent { DriverApp(messageRequest) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(MessageService.OPEN_MESSAGE, false)) messageRequest = System.nanoTime()
    }
}

private data class PlacePick(val target: String, val label: String, val placeId: String = "", val token: Long = System.nanoTime())

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun DriverApp(messageRequest: Long = 0L) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { OrderStore(context) }
    val preferences = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var darkMode by remember { mutableStateOf(preferences.getBoolean("dark_mode", false)) }
    SideEffect {
        (context as? android.app.Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkMode
                isAppearanceLightNavigationBars = !darkMode
            }
        }
    }
    var rides by remember { mutableStateOf(store.load()) }
    var tab by remember { mutableIntStateOf(1) }
    val messageStore = remember { MessageStore.get(context) }
    val messageUnread by messageStore.unread.collectAsState()
    LaunchedEffect(messageRequest) { if (messageRequest != 0L) tab = 4 }
    LaunchedEffect(Unit) {
        runCatching {
            val settings = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                MessageSettingsStore(context).load().also { messageStore.select(it.source) }
            }
            if (settings.enabled && !messageStore.active.value) MessageService.start(context)
        }.onFailure { messageStore.status.value = "無法啟動接收，請檢查 Message 連線設定" }
    }
    var editing by remember { mutableStateOf<RideOrder?>(null) }
    val ai: AiInputViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    var placeTarget by remember { mutableStateOf<String?>(null) }
    var placePick by remember { mutableStateOf<PlacePick?>(null) }
    var showSyncDialog by remember { mutableStateOf(false) }
    val currentUser by FirebaseSyncManager.currentUser.collectAsState()

    LaunchedEffect(Unit) {
        FirebaseSyncManager.init(context.applicationContext)
        FirebaseSyncManager.onRemoteRidesUpdated = { remoteRides, deletedIds ->
            val currentLocal = store.load()
            val remainingLocal = currentLocal.filterNot { it.id in deletedIds }
            val mergedMap = remainingLocal.associateBy { it.id }.toMutableMap()
            remoteRides.forEach { mergedMap[it.id] = it }
            val mergedList = mergedMap.values.toList()
            rides = mergedList
            store.save(mergedList)

            // 自動將本機獨有且未刪除之資料備份至雲端
            val remoteIdSet = remoteRides.map { it.id }.toSet()
            remainingLocal.filterNot { it.id in remoteIdSet }.forEach {
                FirebaseSyncManager.uploadRide(it)
            }
        }
    }

    val placeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) result.data?.let { intent ->
            val place = Autocomplete.getPlaceFromIntent(intent)
            val label = listOfNotNull(place.name, place.address).filter { it.isNotBlank() }.distinct().joinToString(" · ")
            val target = placeTarget
            if (!label.isNullOrBlank() && target != null) placePick = PlacePick(target, label, place.id.orEmpty())
        }
    }
    fun searchPlace(target: String) {
        if (!Places.isInitialized()) return
        placeTarget = target
        placeLauncher.launch(Autocomplete.IntentBuilder(AutocompleteActivityMode.FULLSCREEN, listOf(Place.Field.ID, Place.Field.NAME, Place.Field.ADDRESS)).build(context))
    }
    fun update(list: List<RideOrder>) { rides = list; store.save(list) }
    MaterialTheme(colorScheme = if (darkMode) darkColorScheme(primary = Color(0xFF8BD5BF)) else lightColorScheme(primary = Color(0xFF176B5A))) {
        Scaffold(topBar = { TopAppBar(title = { Text("DriverRoutine", maxLines = 1, overflow = TextOverflow.Ellipsis) }, actions = {
            IconButton(
                onClick = { showSyncDialog = true },
                modifier = Modifier.semantics { contentDescription = "雲端同步設定" }
            ) {
                Icon(
                    imageVector = if (currentUser != null) Icons.Default.CloudDone else Icons.Default.Cloud,
                    contentDescription = "雲端同步狀態",
                    tint = if (currentUser != null) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("深色", style = MaterialTheme.typography.labelMedium)
            Switch(checked = darkMode, onCheckedChange = {
                darkMode = it
                preferences.edit().putBoolean("dark_mode", it).apply()
            }, modifier = Modifier.padding(horizontal = 8.dp).semantics { contentDescription = "深色模式" })
        }) }, bottomBar = {
            NavigationBar { listOf("新增 / 擷取", "當日排程", "花費", "地圖入口", "Message").forEachIndexed { index, label ->
                val urgent = index == 4 && messageUnread > 0
                NavigationBarItem(selected = tab == index, onClick = { tab = index },
                    modifier = if (urgent) Modifier.background(MaterialTheme.colorScheme.tertiaryContainer) else Modifier,
                    icon = { BadgedBox(badge = { if (urgent) Badge { Text(if (messageUnread > 99) "99+" else messageUnread.toString()) } }) { Text((index + 1).toString()) } },
                    label = { Text(label, maxLines = 1, fontWeight = if (urgent) FontWeight.Bold else FontWeight.Normal) })
            } }
        }) { padding -> Box(Modifier.padding(padding)) {
            when(tab) {
                0 -> AiInputScreen(ai, onEdit = { placePick = null; editing = it }, onManual = { editing = localParse(ai.text) }, onSamples = {
                    val samples = sampleOrders()
                    update(rides + samples)
                    samples.forEach { FirebaseSyncManager.uploadRide(it) }
                    tab = 1
                })
                1 -> DailyScreen(rides, onEdit = { placePick = null; editing = it }, onUpdate = { order ->
                    update(rides.map { if (it.id == order.id) order else it })
                    FirebaseSyncManager.uploadRide(order)
                }, onImport = { importedList ->
                    update(importedList)
                    importedList.forEach { FirebaseSyncManager.uploadRide(it) }
                })
                2 -> ExpenseScreen(rides)
                4 -> MessageScreen(rides)
                else -> MapScreen(rides, context, onSearch = { searchPlace("map") }, selectedPlace = placePick?.takeIf { it.target == "map" }?.label, darkMode = darkMode)
            }
            editing?.let { order -> EditDialog(order, onDismiss = { editing = null }, onSave = { saved, partnerTime ->
                val batch = ai.ordersToSave(saved).map { it.copy(needsAddressCheck = false, notes = reminders(it.notes, it.uncertainties), uncertainties = "", pickupTime = if(it.id != saved.id && partnerTime != null) partnerTime else it.pickupTime) }
                update(rides.filterNot { existing -> batch.any { it.id == existing.id } } + batch)
                batch.forEach {
                    FirebaseSyncManager.uploadRide(it)
                    ai.removeDraft(it.id)
                }
                editing = null; tab = if (ai.drafts.isEmpty()) 1 else 0
            }, onDelete = if (rides.any { it.id == order.id }) ({
                update(rides.filterNot { it.id == order.id })
                FirebaseSyncManager.deleteRide(order.id)
                editing = null
            }) else null, onSearchPlace = ::searchPlace, placePick = placePick, partner = ai.drafts.firstOrNull { it.id != order.id && it.bookingId.isNotBlank() && it.bookingId == order.bookingId }) }

            if (showSyncDialog) {
                SyncAccountDialog(onDismiss = { showSyncDialog = false })
            }
        } }
    }
}

@Composable private fun ScheduleScreen(rides: List<RideOrder>, onEdit: (RideOrder)->Unit, onDelete: (RideOrder)->Unit) = Column(Modifier.padding(horizontal = 12.dp)) {
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    fun copy(label: String, value: String) {
        clipboard.setText(AnnotatedString(value))
        Toast.makeText(context, "已複製$label", Toast.LENGTH_SHORT).show()
    }
    Text("接送排程（依日期／時間排列）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
    Text("車程及銜接待估算；時間排序不代表可行。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (rides.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("尚無訂單，請從「新增 / 擷取」加入。") }
    else LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(rides.sortedWith(compareBy<RideOrder> { bookingDate(it.date) }.thenBy { it.pickupTime.take(5) }), key = { it.id }) { order ->
        ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text("${bookingDate(order.date)}  ${order.pickupTime} · ${if (order.returnRide) "回程" else "去程"}${if(order.tentative) "（暫定）" else ""}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            CopyableLine("乘客", order.customer) { copy("姓名", order.customer) }
            CopyableLine("聯絡人", order.contact) { copy("聯絡人", order.contact) }
            if (order.contactPhone.isNotBlank()) CopyableLine("聯絡電話", order.contactPhone) { copy("聯絡人電話", order.contactPhone) }
            if (order.passengerPhone.isNotBlank()) CopyableLine("乘客電話", order.passengerPhone) { copy("乘客電話", order.passengerPhone) }
            if (order.contactPhone.isBlank() && order.passengerPhone.isBlank()) Text("電話：未提供", style = MaterialTheme.typography.bodySmall)
            CopyableLine("上車", addressForDisplay(order.pickup), onNavigate = { launchNavigation(context, order.pickup) }) { copy("上車地址", addressForDisplay(order.pickup)) }
            CopyableLine("下車", addressForDisplay(order.destination), onNavigate = { launchNavigation(context, order.destination) }) { copy("下車地址", addressForDisplay(order.destination)) }
            SelectableText("費用：${order.fare.ifBlank { "未提供" }}")
            if(order.timeFlexible) Text("時間可調", style = MaterialTheme.typography.bodySmall)
            if(order.notes.isNotBlank()) Text(order.notes, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { copy("行程", order.copyText()) }) { Text("複製行程") }
                TextButton(onClick = { onEdit(order) }) { Text("修改") }
                TextButton(onClick = { onDelete(order) }) { Text("刪除") }
            }
        } }
    } }
}

internal fun launchNavigation(context: Context, rawAddress: String, placeId: String = "") {
    val cleanAddress = addressForDisplay(rawAddress).replace(Regex("[（(].*?[）)]"), "").trim()
    if (cleanAddress.isBlank() || cleanAddress.contains("待確認") || cleanAddress.contains("待填")) {
        Toast.makeText(context, "地址待確認，無法開啟導航", Toast.LENGTH_SHORT).show()
        return
    }
    val uri = if (placeId.isBlank()) Uri.parse("google.navigation:q=${Uri.encode(cleanAddress)}&mode=d") else Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(cleanAddress)}&destination_place_id=${Uri.encode(placeId)}&travelmode=driving&dir_action=navigate")
    val mapIntent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
    }
    runCatching {
        context.startActivity(mapIntent)
    }.onFailure {
        val geoUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(cleanAddress)}&travelmode=driving&dir_action=navigate" + if (placeId.isNotBlank()) "&destination_place_id=${Uri.encode(placeId)}" else "")
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
        }.onFailure {
            Toast.makeText(context, "無法開啟地圖導航應用程式", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable private fun CopyableLine(
    label: String,
    value: String,
    onNavigate: (() -> Unit)? = null,
    onCopy: () -> Unit
) = Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
    Box(Modifier.weight(1f).clipToBounds()) {
        SelectionContainer { Text("$label：$value", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
            maxLines = 3, overflow = TextOverflow.Ellipsis) }
    }
    if (onNavigate != null) {
        TextButton(onClick = onNavigate, modifier = Modifier.requiredWidth(64.dp).heightIn(min = 48.dp)
            .semantics { contentDescription = "導航$label" }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text("導航", maxLines = 1, softWrap = false)
        }
    }
    TextButton(onClick = onCopy, modifier = Modifier.requiredWidth(64.dp).heightIn(min = 48.dp)
        .semantics { contentDescription = "複製$label" }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
        Text("複製", maxLines = 1, softWrap = false)
    }
}

@Composable private fun MapScreen(rides: List<RideOrder>, context: Context, onSearch: () -> Unit, selectedPlace: String?, darkMode: Boolean) =
    HospitalMapScreen(rides, context, onSearch, selectedPlace, darkMode)

@Composable private fun EditDialog(
    order: RideOrder,
    onDismiss: () -> Unit,
    onSave: (RideOrder, String?) -> Unit,
    onSearchPlace: (String) -> Unit,
    placePick: PlacePick?,
    partner: RideOrder?,
    onDelete: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val routeClient = remember { GoogleRouteClient.create(context.applicationContext) }

    var confirmDelete by remember(order.id) { mutableStateOf(false) }
    var validationMessage by remember(order) { mutableStateOf<String?>(null) }

    // 0. 服務日期與接客時間
    var serviceDate by remember(order) { mutableStateOf(stripPlaceholder(order.serviceDate.ifBlank { fullRideDate(order.date) })) }
    var pickupTime by remember(order) {
        val single = Regex("^([01]?\\d|2[0-3]):[0-5]\\d").find(order.pickupTime.trim())?.value
        mutableStateOf(stripPlaceholder(single ?: order.pickupTime.trim()))
    }
    var timeFlexible by remember(order) { mutableStateOf(order.timeFlexible) }

    // 1. 乘客與聯絡人
    var customer by remember(order) { mutableStateOf(stripPlaceholder(order.customer)) }
    var contact by remember(order) { mutableStateOf(stripPlaceholder(order.contact.ifBlank { order.customer })) }
    var contactPhone by remember(order) { mutableStateOf(stripPlaceholder(order.contactPhone.ifBlank { order.passengerPhone })) }
    var passengerPhone by remember(order) { mutableStateOf(stripPlaceholder(order.passengerPhone)) }
    var bookingId by remember(order) { mutableStateOf(order.bookingId) }

    // 2. 上車地址 & 3. 下車地址
    var pickup by remember(order) { mutableStateOf(stripPlaceholder(addressForDisplay(order.pickup))) }
    var destination by remember(order) { mutableStateOf(stripPlaceholder(addressForDisplay(order.destination))) }
    var pickupPlaceId by remember(order) { mutableStateOf(order.pickupPlaceId) }
    var destinationPlaceId by remember(order) { mutableStateOf(order.destinationPlaceId) }

    // 4. 車程與交通銜接時間
    var rideMinutes by remember(order) {
        val initial = order.rideMinutes.ifBlank {
            RouteEstimate.parse(order.routeEstimate)?.let { routeMinutes(it.rideSeconds).toString() }.orEmpty()
        }
        mutableStateOf(initial)
    }
    var transferMinutes by remember(order) { mutableStateOf(order.transferMinutes) }
    var isEstimatingRoute by remember { mutableStateOf(false) }

    // 5. 類型與行程屬性
    var category by remember(order) {
        mutableStateOf(
            if (order.category in listOf("自費", "日照")) order.category
            else "補助單"
        )
    }
    var returnRide by remember(order) { mutableStateOf(order.returnRide) }
    var wheelchair by remember(order) { mutableStateOf(order.wheelchair) }
    var tentative by remember(order) { mutableStateOf(order.tentative) }
    var completed by remember(order) { mutableStateOf(order.completed) }

    // 6. 費用與款項
    var fare by remember(order) { mutableStateOf(order.fare) }
    var received by remember(order) { mutableStateOf(order.received) }
    var tip by remember(order) { mutableStateOf(order.tip) }
    var subsidyDue by remember(order) { mutableStateOf(order.subsidyDue) }

    // 7. 備註與補充
    var notes by remember(order) { mutableStateOf(stripPlaceholder(order.notes)) }
    var uncertainties by remember(order) { mutableStateOf(order.uncertainties) }
    var calendarTime by remember(order) { mutableStateOf(order.calendarTime) }
    var rawText by remember(order) { mutableStateOf(order.raw) }

    fun autoCalculateSubsidy(recvStr: String, cat: String) {
        if (cat in listOf("補助", "補助單")) {
            val r = recvStr.toDoubleOrNull()
            if (r != null && r > 0) {
                fare = kotlin.math.ceil(r * 3.3).toInt().toString()
                subsidyDue = kotlin.math.ceil(r * 2.3).toInt().toString()
            }
        }
    }

    // 根據地址自動估算車程
    fun autoEstimate(from: String, to: String, fromId: String = "", toId: String = "") {
        val cleanFrom = addressForDisplay(from).trim()
        val cleanTo = addressForDisplay(to).trim()
        if (cleanFrom.isBlank() || cleanFrom.contains("待確認") || cleanFrom.contains("待填") ||
            cleanTo.isBlank() || cleanTo.contains("待確認") || cleanTo.contains("待填")) {
            return
        }
        coroutineScope.launch {
            isEstimatingRoute = true
            try {
                val start = routeClient.resolve(cleanFrom, fromId) { _, places -> places.first() }
                val end = routeClient.resolve(cleanTo, toId) { _, places -> places.first() }
                val leg = routeClient.compute(start.id, end.id, Instant.now())
                rideMinutes = routeMinutes(leg.seconds).toString()
            } catch (_: Exception) {
                // 若網路或 API 未就緒則保留既有數值，供手動填寫
            } finally {
                isEstimatingRoute = false
            }
        }
    }

    // 開啟且車程為空時自動依地址填入
    LaunchedEffect(order.id) {
        if (rideMinutes.isBlank()) {
            autoEstimate(pickup, destination, pickupPlaceId, destinationPlaceId)
        }
    }

    // 搜尋地點回傳後自動更新地址與車程
    LaunchedEffect(placePick?.token) {
        placePick?.let { pick ->
            when (pick.target) {
                "pickup" -> {
                    pickup = pick.label
                    pickupPlaceId = pick.placeId
                    autoEstimate(pick.label, destination, pick.placeId, destinationPlaceId)
                }
                "destination" -> {
                    destination = pick.label
                    destinationPlaceId = pick.placeId
                    autoEstimate(pickup, pick.label, pickupPlaceId, pick.placeId)
                }
            }
        }
    }

    fun performSave() {
        val issues = mutableListOf<String>()
        val singleTimeMatch = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(pickupTime.trim())
        val isRange = pickupTime.contains("-") || pickupTime.contains("–") || pickupTime.contains("~")
        if (pickupTime.isBlank()) {
            issues += "請填寫接客時間"
        } else if (isRange || singleTimeMatch == null) {
            issues += "接客時間請填寫單一時間（例如 09:30），不可為範圍"
        }
        val cleanDate = fullRideDate(serviceDate.trim())
        if (serviceDate.isNotBlank() && runCatching { java.time.LocalDate.parse(cleanDate) }.isFailure) {
            issues += "請填寫有效服務日期（例如 2026-09-26）"
        }
        if (customer.isBlank()) issues += "請填寫乘客姓名"
        if (pickup.isBlank() || pickup.contains("待確認") || pickup.contains("待填")) issues += "請填寫上車地址"
        if (destination.isBlank() || destination.contains("待確認") || destination.contains("待填")) issues += "請填寫下車地址"
        if (received.isNotBlank() && !Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(received.trim())) issues += "實收金額請填非負數字"
        if (tip.isNotBlank() && !Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(tip.trim())) issues += "小費請填非負數字"
        if (subsidyDue.isNotBlank() && !Regex("\\d{1,9}(?:\\.\\d{1,2})?").matches(subsidyDue.trim())) issues += "待收補助請填非負數字"
        if (rideMinutes.isNotBlank() && (rideMinutes.toIntOrNull() == null || rideMinutes.toInt() !in 0..1440)) issues += "車程請填 0 到 1440 分鐘"
        if (transferMinutes.isNotBlank() && (transferMinutes.toIntOrNull() == null || transferMinutes.toInt() !in 0..1440)) issues += "交通車程請填 0 到 1440 分鐘"

        if (issues.isNotEmpty()) {
            validationMessage = issues.joinToString("\n")
        } else {
            val formattedPickupTime = "%02d:%02d".format(
                singleTimeMatch!!.groupValues[1].toInt(),
                singleTimeMatch.groupValues[2].toInt()
            )
            val addressChanged = formattedPickupTime != order.pickupTime || pickup != order.pickup || destination != order.destination || pickupPlaceId != order.pickupPlaceId || destinationPlaceId != order.destinationPlaceId
            val saved = order.copy(
                date = cleanDate,
                serviceDate = cleanDate,
                pickupTime = formattedPickupTime,
                timeFlexible = timeFlexible,
                customer = customer.trim(),
                contact = contact.trim().ifBlank { customer.trim() },
                contactPhone = contactPhone.trim(),
                passengerPhone = passengerPhone.trim().ifBlank { contactPhone.trim() },
                bookingId = bookingId.trim(),
                pickup = pickup.trim(),
                destination = destination.trim(),
                pickupPlaceId = pickupPlaceId,
                destinationPlaceId = destinationPlaceId,
                category = category,
                returnRide = returnRide,
                wheelchair = wheelchair,
                tentative = tentative,
                completed = completed,
                fare = fare.trim(),
                received = received.trim(),
                tip = tip.trim(),
                subsidyDue = subsidyDue.trim(),
                rideMinutes = rideMinutes.trim(),
                transferMinutes = transferMinutes.trim(),
                notes = notes.trim(),
                uncertainties = uncertainties.trim(),
                calendarTime = calendarTime.trim(),
                raw = rawText.trim(),
                needsAddressCheck = false,
                routeEstimate = if (addressChanged) "" else order.routeEstimate
            )
            onSave(saved, null)
        }
    }

    val focusManager = LocalFocusManager.current

    fun performDuplicate() {
        val issues = mutableListOf<String>()
        val singleTimeMatch = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(pickupTime.trim())
        val isRange = pickupTime.contains("-") || pickupTime.contains("–") || pickupTime.contains("~")
        if (pickupTime.isBlank() || isRange || singleTimeMatch == null) issues += "請填寫有效單一接客時間"
        if (customer.isBlank()) issues += "請填寫乘客姓名"
        val cleanDate = fullRideDate(serviceDate.trim())
        if (serviceDate.isNotBlank() && runCatching { java.time.LocalDate.parse(cleanDate) }.isFailure) {
            issues += "請填寫有效服務日期（例如 2026-09-26）"
        }
        if (issues.isNotEmpty()) {
            validationMessage = issues.joinToString("\n")
        } else {
            val formattedPickupTime = "%02d:%02d".format(singleTimeMatch!!.groupValues[1].toInt(), singleTimeMatch.groupValues[2].toInt())
            val duplicate = order.copy(
                id = System.nanoTime(),
                date = cleanDate,
                serviceDate = cleanDate,
                pickupTime = formattedPickupTime,
                timeFlexible = timeFlexible,
                customer = customer.trim(),
                contact = contact.trim().ifBlank { customer.trim() },
                contactPhone = contactPhone.trim(),
                passengerPhone = passengerPhone.trim().ifBlank { contactPhone.trim() },
                bookingId = bookingId.trim(),
                pickup = pickup.trim(),
                destination = destination.trim(),
                pickupPlaceId = pickupPlaceId,
                destinationPlaceId = destinationPlaceId,
                category = category,
                returnRide = returnRide,
                wheelchair = wheelchair,
                tentative = tentative,
                completed = false,
                fare = fare.trim(),
                received = received.trim(),
                tip = tip.trim(),
                subsidyDue = subsidyDue.trim(),
                rideMinutes = rideMinutes.trim(),
                transferMinutes = transferMinutes.trim(),
                notes = notes.trim(),
                uncertainties = uncertainties.trim(),
                calendarTime = calendarTime.trim(),
                raw = rawText.trim(),
                needsAddressCheck = false,
                routeEstimate = ""
            )
            onSave(duplicate, null)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // 頂部列：標題 + 圖示按鈕（刪除、複製新增、取消、儲存）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("行程確認／修改", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (onDelete != null) {
                            IconButton(
                                onClick = { confirmDelete = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "刪除",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = ::performDuplicate,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = "複製新增",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "取消",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        FilledIconButton(
                            onClick = ::performSave,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "儲存",
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 可滑動內容區（限制在視窗可見高度內）
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                // 0. 服務日期 與 接客時間
                item {
                    val eta = calculateEta(pickupTime, rideMinutes.toLongOrNull())
                    val isRange = pickupTime.contains("-") || pickupTime.contains("–") || pickupTime.contains("~")
                    val isInvalid = pickupTime.isNotBlank() && (isRange || !Regex("^([01]?\\d|2[0-3]):[0-5]\\d$").matches(pickupTime.trim()))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val parsedSvcDate = runCatching { java.time.LocalDate.parse(serviceDate.trim().replace('/', '-')) }.getOrNull()
                            OutlinedTextField(
                                value = serviceDate,
                                onValueChange = { serviceDate = it },
                                label = { Text("服務日期${parsedSvcDate?.let { " (${formatWeekday(it)})" } ?: ""}") },
                                placeholder = { Text("例如 2026-09-26") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = pickupTime,
                                onValueChange = { pickupTime = it },
                                label = { Text("接客時間") },
                                placeholder = { Text("例如 09:30") },
                                singleLine = true,
                                isError = isInvalid,
                                trailingIcon = {
                                    if (!eta.isNullOrBlank()) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                            modifier = Modifier.padding(end = 4.dp)
                                        ) {
                                            Text(
                                                "ETA $eta",
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (isInvalid) {
                            Text(
                                if (isRange) "不可填寫時間範圍，請填單一時間（例如 09:30）" else "時間格式不正確，請輸入例如 09:30",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (!eta.isNullOrBlank()) {
                            Text(
                                "接客 $pickupTime + 車程 $rideMinutes 分鐘 ➔ 預計抵達 $eta",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 狀態快速切換 Chips: 時間可調 / 暫定行程 / 完成狀態
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            FilterChip(
                                selected = timeFlexible,
                                onClick = { timeFlexible = !timeFlexible },
                                label = { Text("🕒 時間可調", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(32.dp)
                            )
                            FilterChip(
                                selected = tentative,
                                onClick = { tentative = !tentative },
                                label = { Text("⏳ 暫定", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(32.dp)
                            )
                            FilterChip(
                                selected = completed,
                                onClick = { completed = !completed },
                                label = { Text(if (completed) "✓ 已完成" else "○ 未完成", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                }

                // 1. 乘客與聯絡人姓名
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = customer,
                            onValueChange = { customer = it },
                            label = { Text("乘客姓名") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = contact,
                            onValueChange = { contact = it },
                            label = { Text("聯絡人姓名") },
                            placeholder = { Text("選填 (同乘客可不填)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 2. 聯絡電話 與 乘客電話
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = contactPhone,
                            onValueChange = { contactPhone = it },
                            label = { Text("聯絡人電話") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = passengerPhone,
                            onValueChange = { passengerPhone = it },
                            label = { Text("乘客電話") },
                            placeholder = { Text("選填") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 3. 預約單號 / 訂單編號
                item {
                    OutlinedTextField(
                        value = bookingId,
                        onValueChange = { bookingId = it },
                        label = { Text("訂單編號 / 預約單號 (bookingId)") },
                        placeholder = { Text("選填，例如長照派單代號") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 4. Case 類型 | 行程方向 | 輪椅需求
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Case 類型 (自費 補助 日照)
                            Column(
                                modifier = Modifier.weight(1.35f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("Case 類型", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf("自費", "補助", "日照").forEach { label ->
                                        val isSelected = when (label) {
                                            "補助" -> category in listOf("補助", "補助單")
                                            else -> category == label
                                        }
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                val targetCat = if (label == "補助") "補助單" else label
                                                category = targetCat
                                                autoCalculateSubsidy(received, targetCat)
                                            },
                                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(34.dp)
                                        )
                                    }
                                }
                            }

                            // 分隔線
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(46.dp)
                                    .align(Alignment.CenterVertically)
                                    .background(MaterialTheme.colorScheme.outlineVariant)
                            )

                            // 行程方向 (去 or 回)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("行程方向", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    FilterChip(
                                        selected = !returnRide,
                                        onClick = { returnRide = false },
                                        label = { Text("去", style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(34.dp)
                                    )
                                    FilterChip(
                                        selected = returnRide,
                                        onClick = { returnRide = true },
                                        label = { Text("回", style = MaterialTheme.typography.labelSmall) },
                                        modifier = Modifier.height(34.dp)
                                    )
                                }
                            }
                        }

                        // 輪椅需求
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("乘車需求：", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilterChip(
                                selected = wheelchair,
                                onClick = { wheelchair = true },
                                label = { Text("♿ 需輪椅", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(32.dp)
                            )
                            FilterChip(
                                selected = !wheelchair,
                                onClick = { wheelchair = false },
                                label = { Text("🚶 一般行走", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                }

                // 5. 上車地址 與 下車地址 (右側直立式互換按鈕)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = pickup,
                                onValueChange = { pickup = it; pickupPlaceId = "" },
                                label = { Text("上車地址") },
                                trailingIcon = {
                                    TextButton(onClick = { onSearchPlace("pickup") }) {
                                        Text("搜尋")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = destination,
                                onValueChange = { destination = it; destinationPlaceId = "" },
                                label = { Text("下車地址") },
                                trailingIcon = {
                                    TextButton(onClick = { onSearchPlace("destination") }) {
                                        Text("搜尋")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(
                            onClick = {
                                val tempAddr = pickup
                                pickup = destination
                                destination = tempAddr
                                val tempId = pickupPlaceId
                                pickupPlaceId = destinationPlaceId
                                destinationPlaceId = tempId
                                autoEstimate(pickup, destination, pickupPlaceId, destinationPlaceId)
                            },
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SwapVert,
                                contentDescription = "交換上下車地址",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // 6. 本趟車程 (壓縮欄位)、實收金額 與 小費/TIP
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = rideMinutes,
                            onValueChange = { rideMinutes = it },
                            label = { Text("本趟車程") },
                            suffix = { Text("分") },
                            placeholder = { Text(if (isEstimatingRoute) "…" else "分") },
                            trailingIcon = {
                                if (isEstimatingRoute) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    TextButton(
                                        onClick = { autoEstimate(pickup, destination, pickupPlaceId, destinationPlaceId) },
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.size(width = 36.dp, height = 32.dp)
                                    ) {
                                        Text("估", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f)
                        )
                        OutlinedTextField(
                            value = received,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() }) {
                                    received = input
                                    autoCalculateSubsidy(input, category)
                                }
                            },
                            label = { Text("實收金額") },
                            suffix = { Text("元") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = tip,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() }) tip = input
                            },
                            label = { Text("小費/TIP") },
                            suffix = { Text("元") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(0.9f)
                        )
                    }
                }

                // 7. 交通銜接、待收補助 與 報價/費用
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = transferMinutes,
                            onValueChange = { transferMinutes = it },
                            label = { Text("交通銜接") },
                            suffix = { Text("分") },
                            placeholder = { Text("上一趟") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.9f)
                        )
                        OutlinedTextField(
                            value = subsidyDue,
                            onValueChange = { input ->
                                if (input.all { it.isDigit() }) subsidyDue = input
                            },
                            label = { Text(if (category in listOf("補助", "補助單")) "待收補助 (自動)" else "待收補助") },
                            suffix = { Text("元") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = fare,
                            onValueChange = { fare = it },
                            label = { Text(if (category in listOf("補助", "補助單")) "報價/費用 (自動)" else "報價/費用") },
                            suffix = { Text("元") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 8. 備註 與 待確認事項
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text("備註") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uncertainties,
                            onValueChange = { uncertainties = it },
                            label = { Text("待確認事項") },
                            placeholder = { Text("例如：等待確認是否需要爬梯機") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 9. 原始資料 (RAW data) 摺疊區 (含日曆時段與原始文字，亦可修改)
                item {
                    var rawExpanded by remember(order.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        TextButton(
                            onClick = { rawExpanded = !rawExpanded },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (rawExpanded) "▼ RAW data／原始資料（點擊收合）" else "▶ RAW data／原始資料（點擊展開）",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (rawExpanded) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                            ) {
                                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = calendarTime,
                                        onValueChange = { calendarTime = it },
                                        label = { Text("日曆原始時段 (calendarTime)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = rawText,
                                        onValueChange = { rawText = it },
                                        label = { Text("保留原文 (raw)") },
                                        minLines = 2,
                                        maxLines = 4,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (order.imageTranscript.isNotBlank()) {
                                        SelectableText("AI 圖片轉錄：\n${order.imageTranscript}")
                                    }
                                    if (order.sourceImage.isNotBlank()) {
                                        SourceImage(order.sourceImage)
                                    }
                                }
                            }
                        }
                    }
                }
            }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 底部常駐固定列：取消 / 儲存（永遠在螢幕最底端，不需滑動就能點選）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = ::performSave) {
                        Text("儲存")
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("刪除此行程？") },
            text = { Text("${order.serviceDate} ${order.pickupTime} · ${order.customer}\n確定要刪除此行程嗎？") },
            confirmButton = {
                TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("刪除")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消")
                }
            }
        )
    }

    validationMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { validationMessage = null },
            title = { Text("請完成以下資料") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { validationMessage = null }) {
                    Text("確定")
                }
            }
        )
    }
}
@Composable internal fun SelectableText(text: String) = SelectionContainer { Text(text) }

private fun sampleOrders(): List<RideOrder> {
    val date = "2026/09/21（測試採用；原文未寫年份）"; val base = "蘆洲（出發地，待定位）"
    return listOf(
        RideOrder(date=date, pickupTime="09:30", customer="乘客姓名待確認", contact="林先生（假資料）", pickup="大同區哈密街59巷78弄9號11之3樓", destination="中興醫院（院名/定位待確認）", notes="樓層：3樓。時間可調，依最新決定先按最早 09:30 試排。車資總額180，補助126，自付54。", raw="日曆09:30–11:00；時間可調", calendarTime="09:30–11:00", needsAddressCheck=true, timeFlexible=true),
        RideOrder(date=date, pickupTime="09:50", customer="爸爸（姓名待確認）", contact="陳女士（假資料）", pickup="新莊昌盛街43號1樓", destination="新莊署立醫院（正式院名/定位待確認）", notes="輪椅、陪同2位；抵達按門鈴，訊號不好可能接不到電話。自費「跳+300」意思待確認。", raw="09:50用車…11:50可能回程", calendarTime="09:50–11:50", needsAddressCheck=true),
        RideOrder(date=date, pickupTime="10:00–10:30", customer="乘客姓名待確認", contact="邱先生（假資料）", pickup="新北市永和區民生路21號", destination="臺安醫院（院區/定位待確認）", notes="原文台北縣永和市已正規化。自費「跳+300」意思待確認。", raw="10:00–10:30都可，回程13:00左右", calendarTime="10:00–13:10", needsAddressCheck=true),
        RideOrder(date=date, pickupTime="11:00", customer="乘客姓名待確認", contact="林先生（假資料）", pickup="中興醫院（反向地址待確認）", destination="大同區哈密街59巷78弄9號", notes="回程僅暫定，反向地址需使用者確認。", tentative=true, returnRide=true, needsAddressCheck=true),
        RideOrder(date=date, pickupTime="11:50", customer="爸爸（姓名待確認）", contact="陳女士（假資料）", pickup="新莊署立醫院（反向地址待確認）", destination="新莊昌盛街43號1樓", notes="可能回程，先納入規劃；反向地址需確認。", tentative=true, returnRide=true, needsAddressCheck=true),
        RideOrder(date=date, pickupTime="13:00", customer="乘客姓名待確認", contact="邱先生（假資料）", pickup="臺安醫院（反向地址待確認）", destination="新北市永和區民生路21號", notes="13:00左右暫定回程（非日曆13:10）；反向地址需確認。", tentative=true, returnRide=true, needsAddressCheck=true)
    )
}
