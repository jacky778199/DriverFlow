package tw.driver.schedule

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import java.time.LocalDateTime

@Composable internal fun DailyScreen(rides: List<RideOrder>, onEdit: (RideOrder) -> Unit, onUpdate: (RideOrder) -> Unit, onImport: (List<RideOrder>) -> Unit) {
    val context = LocalContext.current
    val voiceCaller = remember { VoiceCallHelper(context) }
    DisposableEffect(voiceCaller) {
        onDispose { voiceCaller.shutdown() }
    }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun copy(label: String, value: String) {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(value))
        Toast.makeText(context, "已複製$label：$value", Toast.LENGTH_SHORT).show()
    }
    val prefs = remember { context.getSharedPreferences("appearance", 0) }
    var compact by remember { mutableStateOf(prefs.getBoolean("compact", true)) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var departureTime by remember(date) { mutableStateOf(prefs.getString("departure_time_$date", "") ?: "") }
    var returnHomeTime by remember(date) { mutableStateOf(prefs.getString("return_home_time_$date", "") ?: "") }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var report by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf(false) }
    var showSummarySchedule by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<List<RideOrder>?>(null) }

    val scheduleTxtExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            val text = ScheduleSummaryHelper.generateScheduleText(date, rides.filter { it.serviceDate == date.toString() })
            val success = ScheduleSummaryHelper.saveTextToUri(context, text, uri)
            Toast.makeText(context, if (success) "已匯出行程文字檔！" else "匯出文字檔失敗", Toast.LENGTH_SHORT).show()
        }
    }

    val scheduleImgExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            val bmp = ScheduleSummaryHelper.generateScheduleBitmap(date, rides.filter { it.serviceDate == date.toString() })
            val success = ScheduleSummaryHelper.saveBitmapToUri(context, bmp, uri)
            Toast.makeText(context, if (success) "已匯出行程總結圖片！" else "匯出圖片失敗", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) { while (true) { val fresh = LocalDateTime.now(); if (date == now.toLocalDate() && fresh.toLocalDate() != now.toLocalDate()) date = fresh.toLocalDate(); now = fresh; delay(15000) } }
    val day = rides.filter { it.serviceDate == date.toString() }.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    val visible = day
    val scope = rememberCoroutineScope()
    val messageStore = remember { MessageStore.get(context) }
    val sentMessages by messageStore.outgoing.collectAsState()
    var reportTarget by remember { mutableStateOf(prefs.getString("passenger_report_target", "小明").orEmpty()) }
    var showReportSettings by remember { mutableStateOf(false) }
    var showReportHistory by remember { mutableStateOf(false) }
    fun passengerReport(order: RideOrder, boarding: Boolean) {
        val text = passengerStatusText(order, boarding)
        copy(if (boarding) "客上訊息" else "客下訊息", text)
        val target = reportTarget.trim()
        if (target.isBlank()) { message = "已複製。請先設定回報對象"; return }
        val source = messageStore.currentSource()
        scope.launch {
            try {
                val result = MessageGateway.submit(context, target, text, "passenger:${order.id}:$boarding:$target:$text", source)
                if (result.state != "queued") message = "已複製。${result.label}；可在回報紀錄查看或確認重送。"
            } catch (e: Exception) { message = "已複製，但未回報：${e.message ?: "請檢查 Message 連線"}" }
        }
    }
    val latestRides by rememberUpdatedState(rides)
    val latestUpdate by rememberUpdatedState(onUpdate)
    val routeClient = remember { GoogleRouteClient.create(context.applicationContext) }
    var estimatingId by remember { mutableStateOf<Long?>(null) }
    var estimateJob by remember { mutableStateOf<Job?>(null) }
    var estimateStatus by remember { mutableStateOf("") }
    var routeErrors by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var placeChoice by remember { mutableStateOf<RoutePlaceChoice?>(null) }
    suspend fun choosePlace(query: String, places: List<RoutePlace>): RoutePlace {
        val result = CompletableDeferred<RoutePlace>()
        placeChoice = RoutePlaceChoice(query, places, result)
        return try { result.await() } finally { placeChoice = null }
    }
    fun previousFor(ride: RideOrder): RideOrder? {
        val sorted = latestRides.filter { it.serviceDate == ride.serviceDate }.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
        return sorted.getOrNull(sorted.indexOfFirst { it.id == ride.id } - 1)
    }
    suspend fun estimateOne(id: Long) {
        val original = latestRides.firstOrNull { it.id == id } ?: return
        val previous = previousFor(original)
        val originalKey = routeInputKey(original, previous)
        estimatingId = id
        routeErrors = routeErrors - id
        val start = routeClient.resolve(original.pickup, original.pickupPlaceId, ::choosePlace)
        val end = routeClient.resolve(original.destination, original.destinationPlaceId, ::choosePlace)
        val departure = routeDeparture(original, Instant.now())
        val own = routeClient.compute(start.id, end.id, departure)
        var transfer: RouteLeg? = null
        var transferDeparture: Instant? = null
        var transferError = ""
        if (previous != null) try {
            val previousEnd = routeClient.resolve(previous.destination, previous.destinationPlaceId, ::choosePlace)
            val old = RouteEstimate.parse(previous.routeEstimate)?.takeIf {
                it.key == routeInputKey(previous, previousFor(previous)) && java.time.Duration.between(it.updated, Instant.now()).seconds in 0..900
            }
            val previousOwnSeconds = old?.rideSeconds ?: run {
                val previousStart = routeClient.resolve(previous.pickup, previous.pickupPlaceId, ::choosePlace)
                routeClient.compute(previousStart.id, previousEnd.id, routeDeparture(previous, Instant.now())).seconds
            }
            // If the previous scheduled trip is already in the past, estimate transfer starting now.
            val plannedDropoff = scheduledPickup(previous).plusSeconds(300 + previousOwnSeconds + 300)
            transferDeparture = maxOf(plannedDropoff, Instant.now().plusSeconds(5))
            transfer = routeClient.compute(previousEnd.id, start.id, transferDeparture!!)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { transferError = e.message ?: "銜接查詢失敗" }
        val current = latestRides.firstOrNull { it.id == id } ?: return
        val currentPrevious = previousFor(current)
        if (routeInputKey(current, currentPrevious) != originalKey) throw java.io.IOException("行程已變更，請重新估算")
        val resolved = current.copy(pickupPlaceId = start.id, destinationPlaceId = end.id)
        val result = RouteEstimate(routeInputKey(resolved, currentPrevious), own.seconds, own.meters, transfer?.seconds,
            departure, transferDeparture, Instant.now(), transferError)
        latestUpdate(resolved.copy(routeEstimate = result.json()))
    }
    fun estimate(ids: List<Long>) {
        if (estimateJob?.isActive == true) return
        estimateJob = scope.launch {
            var failed = 0
            try {
                ids.forEachIndexed { index, id ->
                    estimateStatus = "估算 ${index + 1}/${ids.size}…"
                    try { estimateOne(id) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failed++; routeErrors = routeErrors + (id to (e.message ?: "連線失敗，請重試")) }
                }
                estimateStatus = "估算完成：${ids.size - failed} 筆成功${if(failed > 0) "，$failed 筆失敗（見卡片）" else ""}"
            } catch (e: CancellationException) { estimateStatus = "已取消估算" }
            finally { estimatingId = null; placeChoice = null }
        }
    }
    val jsonExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) message = runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(exportRecords(rides)) } ?: error("無法寫入"); "已匯出全部紀錄（不含原圖）" }.getOrElse { "匯出失敗：${it.message}" }
    }
    val csvExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) message = runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(exportCsv(rides)) } ?: error("無法寫入"); "已匯出 CSV" }.getOrElse { "匯出失敗：${it.message}" }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input -> val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= 8 * 1024 * 1024) { "檔案超過 8 MB" }; out.write(buffer, 0, count) }; out.toByteArray() } ?: error("無法讀取")
            require(bytes.size <= 8 * 1024 * 1024) { "檔案超過 8 MB" }
            pendingImport = importRecords(bytes.toString(Charsets.UTF_8))
        }.onFailure { message = "匯入失敗：${it.message}" }
    }
    fun route(from: String, to: String) {
        if (listOf(from, to).any { it.isBlank() || it.contains("待確認") || it.contains("待填") }) { message = "請先修改並確認兩端地址"; return }
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(addressForDisplay(from))}&destination=${Uri.encode(addressForDisplay(to))}&travelmode=driving"))) }.onFailure { message = "無法開啟 Google Maps" }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { date = date.minusDays(1) }) { Text("‹") }
            Text(formatDateWithWeekday(date), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            TextButton(onClick = { date = LocalDate.now() }) { Text("今天") }
            TextButton(onClick = { date = date.plusDays(1) }) { Text("›") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("精簡", style = MaterialTheme.typography.labelMedium)
            Switch(
                checked = compact,
                onCheckedChange = { compact = it; prefs.edit().putBoolean("compact", it).apply() },
                modifier = Modifier.height(28.dp)
            )
            OutlinedButton(
                onClick = { estimate(day.map { it.id }) },
                enabled = day.isNotEmpty() && estimateJob?.isActive != true,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    if (estimateJob?.isActive == true) "估算中…"
                    else if (date == now.toLocalDate()) "估算今日車程"
                    else "估算此日車程",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (estimateJob?.isActive == true) {
                TextButton(
                    onClick = { estimateJob?.cancel() },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("取消", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(
                onClick = { report = true },
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("日結 ${day.count { it.completed }}/${day.size}", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = { transfer = true },
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text("同步/匯出", style = MaterialTheme.typography.labelMedium)
            }
            TextButton(
                onClick = { showSummarySchedule = true },
                contentPadding = PaddingValues(horizontal = 6.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Icon(Icons.Default.ListAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                Text("總結行程表", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (estimateStatus.isNotBlank()) Text(estimateStatus, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { showReportSettings = true }) { Text("回報對象：${reportTarget.ifBlank { "未設定" }}") }
            TextButton(onClick = { showReportHistory = true }) { Text("回報紀錄") }
        }
        sentMessages.firstOrNull { it.actionKey.startsWith("passenger:") }?.let { Text("${it.target}：${it.text} · ${it.label}", style = MaterialTheme.typography.labelSmall) }
        Text("${visible.size} 趟 · 實收 ${revenue(day).toPlainString()} 元", style = MaterialTheme.typography.bodyMedium)
        if (visible.isEmpty()) Text("這天沒有行程。其他日期紀錄仍保留。", modifier = Modifier.padding(16.dp))
        Row(Modifier.weight(1f)) {
            ScheduleTimeline(
                rides = day,
                date = date,
                now = now,
                modifier = Modifier.width(72.dp).fillMaxHeight(),
                departureTime = departureTime,
                returnHomeTime = returnHomeTime,
                onSetDepartureTime = {
                    departureTime = it
                    prefs.edit().putString("departure_time_$date", it).apply()
                    FirebaseSyncManager.uploadWorkHour(date.toString(), it, returnHomeTime)
                },
                onSetReturnHomeTime = {
                    returnHomeTime = it
                    prefs.edit().putString("return_home_time_$date", it).apply()
                    FirebaseSyncManager.uploadWorkHour(date.toString(), departureTime, it)
                }
            )
            LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
                itemsIndexed(visible, key = { _, it -> it.id }) { index, order ->
                    if (index > 0) {
                        val transferEstimate = RouteEstimate.parse(order.routeEstimate)
                        val transferMins = transferEstimate?.transferSeconds?.let { routeMinutes(it) }
                            ?: order.transferMinutes.toLongOrNull()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = if (compact) 1.dp else 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            )
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.padding(horizontal = 6.dp)
                            ) {
                                Text(
                                    "🚗 交通車程：${transferMins?.let { "$it 分鐘" } ?: "待估算"}",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            )
                        }
                    }
                    val minute = minuteOfDay(order.pickupTime)
                    val elapsed = date == now.toLocalDate() && minute != null && minute <= now.hour * 60 + now.minute
                    val billedSeconds = billedRideSeconds(order, day.getOrNull(day.indexOf(order) - 1))
                    val durationMins = billedSeconds?.let { routeMinutes(it) } ?: order.rideMinutes.toLongOrNull()
                    val durationColor = when {
                        durationMins == null -> Color(0xFF9E9E9E)
                        durationMins <= 20 -> Color(0xFF2E7D32) // 短程 (綠)
                        durationMins <= 40 -> Color(0xFFE65100) // 中程 (橙)
                        else -> Color(0xFFC62828)               // 長程 (紅)
                    }

                    ElevatedCard(
                        onClick = { onEdit(order) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (order.completed) 0.65f else 1.0f)
                            .then(if (order.completed) Modifier.border(1.5.dp, Color(0xFF4CAF50).copy(alpha = 0.65f), CardDefaults.elevatedShape) else Modifier),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (order.completed) {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            } else if (elapsed) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainer
                            }
                        )
                    ) {
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (compact) 40.dp else 48.dp) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            // 根據時間長短用不同顏色表達指示條
                            Box(
                                Modifier
                                    .width(5.dp)
                                    .fillMaxHeight()
                                    .background(if (order.completed) Color(0xFF81C784).copy(alpha = 0.5f) else durationColor)
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = if(compact) 2.dp else 8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        order.pickupTime,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        textDecoration = if (order.completed) TextDecoration.LineThrough else null,
                                        color = if (order.completed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                                    )
                                    // 時間長短彩色標籤
                                    durationMins?.let { mins ->
                                        Surface(
                                            color = durationColor.copy(alpha = if (order.completed) 0.10f else 0.16f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                when {
                                                    mins <= 20 -> "${mins}分·短程"
                                                    mins <= 40 -> "${mins}分·中程"
                                                    else -> "${mins}分·長程"
                                                },
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = durationColor
                                            )
                                        }
                                    }

                                    if (order.completed) {
                                        Surface(
                                            color = Color(0xFF43A047).copy(alpha = 0.18f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "已完成",
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                    RideCategoryTag(if(order.returnRide) "回程" else "去程")
                                    RideCategoryTag(if(order.category == "補助單") "補助" else order.category)
                                    CompletionToggle(order.completed, compact) { onUpdate(order.copy(completed = it)) }
                                }
                                if (!compact && elapsed && !order.completed) Text("已到接客時間", style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(order.customer, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                                    IconButton(
                                        onClick = {
                                            val phone = order.contactPhone.ifBlank { order.passengerPhone }
                                            voiceCaller.speakAndCall(order.customer, phone, scope)
                                        },
                                        modifier = Modifier
                                            .size(if (compact) 36.dp else 40.dp)
                                            .semantics { contentDescription = "語音撥打給${order.customer}" }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Phone,
                                            contentDescription = "撥打電話",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    TextButton(onClick = { copy("乘客名稱", order.customer) }, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(44.dp).height(if(compact) 40.dp else 48.dp).semantics { contentDescription = "複製乘客名稱" }) { Text("複製") }
                                    PassengerMessageButton(boarding = true, compact = compact) { passengerReport(order, true) }
                                    PassengerMessageButton(boarding = false, compact = compact) { passengerReport(order, false) }
                                }
                                ScheduleAddressLine("上車", order.pickup, compact, onCopy = { copy("上車地點", addressForDisplay(order.pickup)) }, onNavigate = { launchNavigation(context, order.pickup, order.pickupPlaceId) })
                                ScheduleAddressLine("下車", order.destination, compact, onCopy = { copy("下車地點", addressForDisplay(order.destination)) }, onNavigate = { launchNavigation(context, order.destination, order.destinationPlaceId) })
                                if (!compact) {
                                    val feeParts = buildList {
                                        if (order.fare.isNotBlank()) add("費用: ${order.fare}元")
                                        if (order.received.isNotBlank()) add("實收: ${order.received}元")
                                        if (order.tip.isNotBlank()) add("TIP: ${order.tip}元")
                                        if (order.subsidyDue.isNotBlank()) add("待收補助: ${order.subsidyDue}元")
                                    }.joinToString(" · ")
                                    Text("${if(order.returnRide) "回程" else "去程"}${if(order.tentative) " · 暫定" else ""}${if(order.timeFlexible) " · 時間可調" else ""}${if(order.wheelchair) " · 需輪椅" else ""}${if (feeParts.isNotBlank()) " · $feeParts" else " · 費用未填"}", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (!compact) {
                                    Text("聯絡人：${order.contact} ${order.contactPhone}")
                                    if (order.passengerPhone.isNotBlank()) Text("乘客電話：${order.passengerPhone}")
                                    if (order.bookingId.isNotBlank()) Text("訂單編號：${order.bookingId}")
                                    if (order.transferMinutes.isNotBlank() && order.transferMinutes != "0") Text("上一趟交通銜接：${order.transferMinutes} 分鐘")
                                    if (order.subsidyDue.isNotBlank()) Text("待收補助：NT$ ${order.subsidyDue}")
                                    if (order.uncertainties.isNotBlank()) Text("待確認：${order.uncertainties}", color = MaterialTheme.colorScheme.error)
                                    if (order.notes.isNotBlank()) Text(order.notes)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(
                                            onClick = { onEdit(order) },
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                            modifier = Modifier.height(36.dp)
                                        ) {
                                            Text("修改行程", style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(order.copyText())); Toast.makeText(context, "已複製行程", Toast.LENGTH_SHORT).show() }) { Text("複製文字") }
                                        OutlinedButton(
                                            onClick = {
                                                val duplicate = order.copy(
                                                    id = System.nanoTime(),
                                                    completed = false,
                                                    received = "",
                                                    routeEstimate = ""
                                                )
                                                onEdit(duplicate)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(36.dp)
                                        ) {
                                            Text("複製新增此單", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                if (compact) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val eta = calculateEta(order.pickupTime, durationMins)
                                    Text("車程 ${durationMins?.let { "$it 分" } ?: "待估"}${if(routeErrors[order.id] != null) " · 失敗" else ""}", style = MaterialTheme.typography.bodyMedium, color = durationColor)
                                    if (order.received.isNotBlank()) {
                                        Surface(
                                            color = Color(0xFFE8F5E9),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "實收 $${order.received}${if (order.tip.isNotBlank()) "+TIP$${order.tip}" else ""}",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                    }
                                    if (eta != null) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (order.completed) 0.45f else 0.85f),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "ETA $eta",
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (order.completed) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSecondaryContainer
                                            )
                                        }
                                    }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { estimate(listOf(order.id)) }, enabled = estimateJob?.isActive != true, modifier = Modifier.height(40.dp), contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if(estimatingId == order.id) "更新中…" else "更新車程") }
                                } else RouteEstimateInfo(order, day.getOrNull(day.indexOf(order) - 1), routeErrors[order.id])
                                if (!compact) TextButton(onClick = { estimate(listOf(order.id)) }, enabled = estimateJob?.isActive != true) {
                                    Text(if (estimatingId == order.id) "更新車程中…" else "更新此趟車程")
                                }
                                if (!compact) {
                                    TextButton(onClick = { route(order.pickup, order.destination) }) { Text("查本趟車程") }
                                    val previous = day.getOrNull(day.indexOf(order)-1)
                                    if (previous != null) TextButton(onClick = { route(previous.destination, order.pickup) }) { Text("查上一趟銜接") }
                                    Text("車程由 Google Maps 提供；交通狀況會變動。", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
        if (!compact) Text("時間軸按車程等比例顯示；圓點表示車程未知。", style = MaterialTheme.typography.labelMedium)
    }
    if (showReportSettings) {
        var target by remember { mutableStateOf(reportTarget) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showReportSettings = false }, title = { Text("客上／客下回報對象") }, text = {
            Column {
                OutlinedTextField(target, { target = it }, label = { Text("LINE 聊天室或聯絡人名稱") }, singleLine = true)
                Text("點客上／客下會複製字串，並透過 Message 連線回報到此對象。請填寫完整名稱。", style = MaterialTheme.typography.bodySmall)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = target.isNotBlank(), onClick = {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { prefs.edit().putString("passenger_report_target", target.trim()).commit() }
                if (saved) { reportTarget = target.trim(); showReportSettings = false } else error = "儲存失敗，請重試"
            }
        }) { Text("儲存") } }, dismissButton = { TextButton(onClick = { showReportSettings = false }) { Text("取消") } })
    }
    if (showReportHistory) OutgoingHistoryDialog(onDismiss = { showReportHistory = false }, passengerOnly = true)
    placeChoice?.let { choice ->
        AlertDialog(onDismissRequest = { choice.result.cancel(); estimateJob?.cancel() }, title = { Text("請選擇一個地點") },
            text = { Column {
                Text("Google Maps 找到多個「${choice.query}」候選地點，選擇後才能繼續估算。")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(choice.places, key = { it.id }) { place ->
                        TextButton(onClick = { choice.result.complete(place) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${place.name}\n${place.address}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                Text("若沒有正確地點，請取消並修改地址。", style = MaterialTheme.typography.bodySmall)
            } }, confirmButton = {}, dismissButton = { TextButton(onClick = { choice.result.cancel(); estimateJob?.cancel() }) { Text("取消估算") } })
    }
    if (report) {
        val summary = daySummary(day)
        AlertDialog(onDismissRequest = { report = false }, title = { Text("$date 日結") }, text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("排程趟數：${summary.scheduled}")
            Text("實際完成趟數：${summary.completed}")
            Text("自費趟數：${summary.selfPay}")
            Text("補助趟數：${summary.subsidized}")
            Text("日照趟數：${summary.daycare}")
            Text("實際車程計費時間：${summary.seconds / 3600} 小時 ${(summary.seconds % 3600) / 60} 分 ${summary.seconds % 60} 秒${if(summary.missingTime > 0) "（${summary.missingTime} 趟待填）" else ""}")
            Text("實收費用：${summary.receipts.toPlainString()} 元${if(summary.missingReceipts > 0) "（${summary.missingReceipts} 趟待填）" else ""}")
            Text("小費 (TIP)：${summary.tips.toPlainString()} 元")
            Text("待收補助：${summary.subsidy.toPlainString()} 元${if(summary.missingSubsidy > 0) "（${summary.missingSubsidy} 趟待填）" else ""}")
            Text("已完成載客路段加總；手填優先，否則採有效估算，非 GPS 實測。不含空車銜接及上下車緩衝。", style = MaterialTheme.typography.labelSmall)
        } }, confirmButton = { TextButton(onClick = { report = false }) { Text("關閉") } })
    }
    if (transfer) AlertDialog(onDismissRequest = { transfer = false }, title = { Text("資料同步與匯出") }, text = { Column {
        Text("雲端同步尚未設定。預計使用 Firebase＋外部瀏覽器 Google 登入，以支援無 Google 服務的裝置。目前可用 JSON 交換資料。")
        TextButton(onClick = { jsonExport.launch("driver-records.json") }) { Text("匯出全部 JSON（可還原）") }
        TextButton(onClick = { csvExport.launch("driver-records.csv") }) { Text("匯出全部 CSV（分析用）") }
        TextButton(onClick = { importer.launch(arrayOf("application/json", "text/plain")) }) { Text("匯入 JSON") }
        Text("JSON 保留文字欄位，不含原始圖片。相同 ID 的不同資料會請你選擇。")
    } }, confirmButton = { TextButton(onClick = { transfer = false }) { Text("關閉") } })

    // 總結行程表 Dialog (最簡潔的條列式行程表，支援圖片/文字檔匯出與直接分享)
    if (showSummarySchedule) {
        val scheduleText = remember(date, day) { ScheduleSummaryHelper.generateScheduleText(date, day) }
        AlertDialog(
            onDismissRequest = { showSummarySchedule = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ListAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("今日總結行程表（${date}）")
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("簡潔條列式行程表，包含今日各趟 人、時間、上下車地點，可直接複製、分享給別人或匯出圖文：", style = MaterialTheme.typography.bodySmall)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)
                    ) {
                        SelectionContainer {
                            LazyColumn(modifier = Modifier.padding(10.dp)) {
                                item {
                                    Text(scheduleText, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                ScheduleSummaryHelper.shareText(context, scheduleText)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("分享文字", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = {
                                val bmp = ScheduleSummaryHelper.generateScheduleBitmap(date, day)
                                ScheduleSummaryHelper.shareBitmap(context, bmp, date)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("分享圖片", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = {
                                scheduleTxtExport.launch("Schedule_${date}.txt")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("匯出 .txt", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = {
                                scheduleImgExport.launch("Schedule_${date}.png")
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("匯出 .png", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(scheduleText))
                    Toast.makeText(context, "已複製行程表文字至剪貼簿！", Toast.LENGTH_SHORT).show()
                }) {
                    Text("複製文字")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSummarySchedule = false }) {
                    Text("關閉")
                }
            }
        )
    }
    pendingImport?.let { incoming ->
        val conflicts = incoming.count { item -> rides.any { it.id == item.id && it.copy(sourceImage = "") != item } }
        AlertDialog(onDismissRequest = { pendingImport = null }, title = { Text("匯入 ${incoming.size} 筆／衝突 $conflicts 筆") }, text = { Text("未出現在檔案中的本機紀錄會保留。請選擇相同 ID 的資料來源。") }, confirmButton = { TextButton(onClick = { onImport(rides.filterNot { old -> incoming.any { it.id == old.id } } + incoming.map { row -> row.copy(sourceImage = rides.firstOrNull { it.id == row.id }?.sourceImage.orEmpty()) }); pendingImport = null }) { Text("使用匯入版本") } }, dismissButton = { TextButton(onClick = { onImport(rides + incoming.filterNot { row -> rides.any { it.id == row.id } }); pendingImport = null }) { Text("保留本機版本") } })
    }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("確定") } }) }
}


@Composable private fun RideCategoryTag(category: String) {
    val tint = when (category) {
        "自費" -> Color(0xFFD79939)
        "補助單", "補助" -> Color(0xFF598ED8)
        "日照" -> Color(0xFF53A88B)
        "去程" -> Color(0xFF7B8FC4)
        "回程" -> Color(0xFFAD82B6)
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(color = tint.copy(alpha = 0.17f), shape = RoundedCornerShape(6.dp)) {
        Text(category, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable private fun CompletionToggle(completed: Boolean, compact: Boolean = false, onChange: (Boolean) -> Unit) {
    val tint = if (completed) Color(0xFF43A85B) else MaterialTheme.colorScheme.outline
    IconToggleButton(checked = completed, onCheckedChange = onChange,
        modifier = Modifier.size(if(compact) 40.dp else 48.dp).semantics {
            contentDescription = if (completed) "取消完成此趟" else "標記此趟完成"
            stateDescription = if (completed) "已完成" else "未完成"
        }) {
        Canvas(Modifier.size(26.dp)) {
            drawCircle(tint.copy(alpha = if (completed) 0.16f else 0.07f))
            drawLine(tint, Offset(size.width * 0.24f, size.height * 0.51f), Offset(size.width * 0.43f, size.height * 0.70f), strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.43f, size.height * 0.70f), Offset(size.width * 0.77f, size.height * 0.31f), strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}


@Composable private fun ScheduleAddressLine(label: String, address: String, compact: Boolean, onCopy: () -> Unit, onNavigate: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$label：${addressForDisplay(address)}", modifier = Modifier.weight(1f), maxLines = if (compact) 2 else 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onCopy, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(44.dp).height(if(compact) 40.dp else 48.dp).semantics { contentDescription = "複製${label}地點" }) { Text("複製") }
        TextButton(onClick = onNavigate, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(44.dp).height(if(compact) 40.dp else 48.dp).semantics { contentDescription = "導航至${label}地點" }) { Text("導航") }
    }
}

@Composable private fun PassengerMessageButton(boarding: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary
    IconButton(onClick = onClick, modifier = Modifier.size(if(compact) 40.dp else 48.dp).semantics {
        contentDescription = if (boarding) "客上：複製並回報" else "客下：複製並回報"
    }) {
        Canvas(Modifier.size(28.dp)) {
            val stroke = 2.dp.toPx()
            drawCircle(tint, size.width * 0.105f, Offset(size.width * 0.28f, size.height * 0.23f))
            drawLine(tint, Offset(size.width * 0.28f, size.height * 0.44f), Offset(size.width * 0.28f, size.height * 0.68f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.10f, size.height * 0.50f), Offset(size.width * 0.46f, size.height * 0.50f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.28f, size.height * 0.68f), Offset(size.width * 0.13f, size.height * 0.88f), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.28f, size.height * 0.68f), Offset(size.width * 0.43f, size.height * 0.88f), stroke, StrokeCap.Round)
            val tip = if (boarding) 0.22f else 0.82f
            val tail = if (boarding) 0.82f else 0.22f
            val wing = if (boarding) 0.40f else 0.64f
            drawLine(tint, Offset(size.width * 0.75f, size.height * tail), Offset(size.width * 0.75f, size.height * tip), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.59f, size.height * wing), Offset(size.width * 0.75f, size.height * tip), stroke, StrokeCap.Round)
            drawLine(tint, Offset(size.width * 0.91f, size.height * wing), Offset(size.width * 0.75f, size.height * tip), stroke, StrokeCap.Round)
        }
    }
}


private data class RoutePlaceChoice(val query: String, val places: List<RoutePlace>, val result: CompletableDeferred<RoutePlace>)

@Composable private fun RouteEstimateInfo(order: RideOrder, previous: RideOrder?, error: String?) {
    val estimate = RouteEstimate.parse(order.routeEstimate)
    val staleInputs = estimate != null && estimate.key != routeInputKey(order, previous)
    val clock = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())
    val billedSeconds = billedRideSeconds(order, previous)
    val durationMins = billedSeconds?.let { routeMinutes(it) } ?: order.rideMinutes.toLongOrNull()
    val eta = calculateEta(order.pickupTime, durationMins)
    if (estimate == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("車程：${order.rideMinutes.takeIf { it.isNotBlank() }?.let { "$it 分（手填）" } ?: "待估算"}", style = MaterialTheme.typography.bodyMedium)
            if (eta != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (order.completed) 0.45f else 0.85f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "ETA $eta",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (order.completed) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Text(if (previous == null) "首趟無上一趟銜接" else "上一趟銜接：${order.transferMinutes.takeIf { it.isNotBlank() }?.let { "$it 分（手填）" } ?: "待估算"}", style = MaterialTheme.typography.bodyMedium)
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${if(staleInputs) "舊估算 · " else ""}車程 ${routeMinutes(estimate.rideSeconds)} 分 · %.1f 公里".format(estimate.meters / 1000.0), style = MaterialTheme.typography.bodyMedium)
            if (eta != null) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = if (order.completed) 0.45f else 0.85f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        "ETA $eta",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (order.completed) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Text(if (previous == null) "首趟無上一趟銜接" else "上一趟銜接：${estimate.transferSeconds?.let { "${routeMinutes(it)} 分" } ?: "待更新"}", style = MaterialTheme.typography.bodyMedium)
        Text("出發 ${clock.format(estimate.departure)} · 更新 ${clock.format(estimate.updated)}", style = MaterialTheme.typography.labelMedium)
        if (estimate.transferSeconds != null && estimate.transferDeparture != null && previous != null && !staleInputs) {
            val arrival = estimate.transferDeparture.plusSeconds(estimate.transferSeconds)
            val gap = runCatching { java.time.Duration.between(arrival, scheduledPickup(order)).toMinutes() }.getOrNull()
            Text("銜接出發 ${clock.format(estimate.transferDeparture)} → 抵達 ${clock.format(arrival)}", style = MaterialTheme.typography.labelMedium)
            if (gap != null) Text(if(gap >= 0) "距接客尚有 $gap 分" else "可能晚到 ${-gap} 分", color = if(gap < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
        }
        if (!staleInputs && java.time.Duration.between(estimate.updated, Instant.now()).seconds > 900) Text("估算已超過 15 分鐘，可更新路況", style = MaterialTheme.typography.labelMedium)
        if (staleInputs) Text("地址、時間或前一趟已變更，請重新估算", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        if (estimate.transferError.isNotBlank()) Text("銜接：${estimate.transferError}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
        Text("Google Maps · 上下車各預留 5 分；過去時段採現在路況", style = MaterialTheme.typography.labelSmall)
    }
    if (error != null) Text("更新失敗：$error${if(estimate != null) "（保留上次估算）" else ""}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
}
