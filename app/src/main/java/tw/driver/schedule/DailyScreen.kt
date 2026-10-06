package tw.driver.schedule

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.luminance
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

@Composable internal fun DailyScreen(rides: List<RideOrder>, onEdit: (RideOrder, Boolean) -> Unit, onUpdate: (RideOrder) -> Unit, onImport: (List<RideOrder>) -> Unit, onSettings: () -> Unit, initialRide: RideOrder? = null) {
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
    val compact = true
    var actualMode by remember { mutableStateOf(false) }
    var resetActualRide by remember { mutableStateOf<RideOrder?>(null) }
    var date by remember { mutableStateOf(initialRide?.serviceDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()) }
    var departureTime by remember(date) { mutableStateOf(prefs.getString("departure_time_$date", "") ?: "") }
    var returnHomeTime by remember(date) { mutableStateOf(prefs.getString("return_home_time_$date", "") ?: "") }
    var journey by remember(date) { mutableStateOf(loadDailyJourney(prefs, date.toString())) }
    var journeyRevision by remember { mutableIntStateOf(0) }
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
    val defaultStartPoint = prefs.getString("default_start_point", "").orEmpty()
    val effectiveStartPoint = journey.startPoint.ifBlank { defaultStartPoint }
    val firstRide = day.firstOrNull()
    val initialRouteKey = "$date|$effectiveStartPoint|${firstRide?.id}|${firstRide?.pickup}|$departureTime"
    var initialTransferSeconds by remember(initialRouteKey) { mutableStateOf(
        prefs.getString("initial_route_key_$date", "")?.takeIf { it == initialRouteKey }
            ?.let { prefs.getLong("initial_route_seconds_$date", -1).takeIf { it >= 0 } }) }
    var initialRouteRevision by remember { mutableIntStateOf(0) }
    var initialRouteError by remember(initialRouteKey) { mutableStateOf<String?>(null) }
    val actualTransfers = if (actualMode) actualTimelineTransfers(day, date).groupBy { it.rideId } else emptyMap()
    val visible = day
    val caseListState = androidx.compose.runtime.key(date) { rememberLazyListState() }
    var selectedRideId by remember(date, actualMode) { mutableStateOf<Long?>(null) }
    var initialPositioned by remember(date) { mutableStateOf(false) }
    val dragging by caseListState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragging) {
        if (dragging) { selectedRideId = null; initialPositioned = true }
    }
    LaunchedEffect(date, day.map { it.id }) {
        if (!initialPositioned) {
            (day.indexOfFirst { it.id == initialRide?.id }.takeIf { it >= 0 }
                ?: initialScheduleRideIndex(day, date, now))?.let { index ->
                initialPositioned = true
                selectedRideId = day[index].id
                caseListState.scrollToItem(index)
            }
        }
    }
    val readingRideId by remember(caseListState) {
        derivedStateOf {
            val layout = caseListState.layoutInfo
            val index = scheduleReadingIndex(layout.visibleItemsInfo.map { ScheduleVisibleItem(it.index, it.offset, it.size) },
                layout.viewportStartOffset, layout.viewportEndOffset, layout.totalItemsCount)
            layout.visibleItemsInfo.firstOrNull { it.index == index }?.key as? Long
        }
    }
    val highlightedRideId = selectedRideId?.takeIf { id -> day.any { it.id == id } } ?: readingRideId
    val scope = rememberCoroutineScope()
    DisposableEffect(date) {
        FirebaseSyncManager.onRemoteDailyJourneyUpdated = { updatedDate, updatedJourney ->
            scope.launch {
                journeyRevision++
                if (updatedDate == date.toString()) journey = updatedJourney
            }
        }
        onDispose { FirebaseSyncManager.onRemoteDailyJourneyUpdated = null }
    }
    val messageStore = remember { MessageStore.get(context) }
    val sentMessages by messageStore.outgoing.collectAsState()
    var reportTarget by remember { mutableStateOf(prefs.getString("passenger_report_target", "小明").orEmpty()) }
    val settingsRevision by AppSettingsSync.revision.collectAsState()
    LaunchedEffect(settingsRevision) { reportTarget = prefs.getString("passenger_report_target", "小明").orEmpty() }
    var showReportHistory by remember { mutableStateOf(false) }
    fun passengerReport(order: RideOrder, boarding: Boolean) {
        val timestamp = recordActualTime(order.serviceDate, java.time.LocalTime.now())
        val updated = if (boarding && order.actualBoardedAt.isBlank()) order.copy(actualBoardedAt = timestamp)
            else if (!boarding && order.actualAlightedAt.isBlank()) order.copy(actualAlightedAt = timestamp)
            else order
        if (updated != order) onUpdate(updated)
        val text = passengerStatusText(order, boarding)
        copy(if (boarding) "客上訊息" else "客下訊息", text)
        val target = order.reportTarget.trim().ifBlank { reportTarget.trim() }
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
            transferDeparture = maxOf(if (actualMode) actualRideInstant(previous.actualAlightedAt, previous.serviceDate) ?: plannedDropoff else plannedDropoff, Instant.now().plusSeconds(5))
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
    LaunchedEffect(initialRouteKey, initialRouteRevision, actualMode, day.map { routeInputKey(it, previousFor(it)) }) {
        if (estimateJob?.isActive == true) return@LaunchedEffect
        if (effectiveStartPoint.isNotBlank() && firstRide != null && initialTransferSeconds == null) {
            try {
                val origin = routeClient.resolve(effectiveStartPoint, "", ::choosePlace)
                val destination = routeClient.resolve(firstRide.pickup, firstRide.pickupPlaceId, ::choosePlace)
                val configuredDeparture = minuteOfDay(departureTime)?.let { date.atStartOfDay(ZoneId.systemDefault()).plusMinutes(it.toLong()).toInstant() }
                val seconds = routeClient.compute(origin.id, destination.id, maxOf(configuredDeparture ?: routeDeparture(firstRide, Instant.now()), Instant.now().plusSeconds(5))).seconds
                initialTransferSeconds = seconds
                prefs.edit().putString("initial_route_key_$date", initialRouteKey).putLong("initial_route_seconds_$date", seconds).apply()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { initialRouteError = e.message ?: "首趟交通估算失敗" }
        }
        if (actualMode) {
            val missing = day.zipWithNext().filter { (previous, ride) -> transferSeconds(ride, previous) == null }.map { it.second.id }
            if (missing.isNotEmpty()) estimate(missing)
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
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&origin=${Uri.encode(LocationTermsStore(context).load().expand(addressForDisplay(from)))}&destination=${Uri.encode(LocationTermsStore(context).load().expand(addressForDisplay(to)))}&travelmode=driving"))) }.onFailure { message = "無法開啟 Google Maps" }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        DayNavigation(date, onSelect = { date = it }, modifier = Modifier.fillMaxWidth(), calendarEnabled = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !actualMode, onClick = { actualMode = false }, label = { Text("預計排班") })
            FilterChip(selected = actualMode, onClick = { actualMode = true }, label = { Text("實際排班") }, colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
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
            TextButton(onClick = onSettings) { Text("回報對象：${reportTarget.ifBlank { "未設定" }}") }
            TextButton(onClick = { showReportHistory = true }) { Text("回報紀錄") }
        }
        sentMessages.firstOrNull { it.actionKey.startsWith("passenger:") }?.let { Text("${it.target}：${it.text} · ${it.label}", style = MaterialTheme.typography.labelSmall) }
        Text("${visible.size} 趟 · 當日實收（含月結） ${yuan(collectedOn(rides, date))} 元", style = MaterialTheme.typography.bodyMedium)
        Text(if (actualMode) "左側虛線：交通估算；金色：等待推算；點選或拖曳時間條可定位 case" else "左側虛線為交通時間，紅色表示銜接不足；點選或拖曳時間條可定位 case", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (visible.isEmpty()) Text("這天沒有行程。其他日期紀錄仍保留。", modifier = Modifier.padding(16.dp))
        val previousEndOdometer = remember(date, journeyRevision, journey) {
            loadDailyJourney(prefs, date.minusDays(1).toString()).endOdometer
        }
        val nextStartOdometer = remember(date, journeyRevision, journey) {
            loadDailyJourney(prefs, date.plusDays(1).toString()).startOdometer
        }
        Row(Modifier.weight(1f)) {
            ScheduleTimeline(
                rides = day,
                date = date,
                now = now,
                modifier = Modifier.width(72.dp).fillMaxHeight(),
                departureTime = departureTime,
                returnHomeTime = returnHomeTime,
                journey = journey.copy(startPoint = effectiveStartPoint),
                initialTransferSeconds = if (effectiveStartPoint.isNotBlank()) initialTransferSeconds else null,
                suggestedStartOdometer = previousEndOdometer,
                suggestedEndOdometer = nextStartOdometer,
                highlightedRideId = highlightedRideId,
                onSelectRide = { id ->
                    initialPositioned = true
                    selectedRideId = id
                    val index = visible.indexOfFirst { it.id == id }
                    if (index >= 0) scope.launch { caseListState.animateScrollToItem(index) }
                },
                actualMode = actualMode,
                onSaveDeparture = { time, point, odometer ->
                    if (saveDailyEndpoint(prefs, date.toString(), true, time, point, odometer)) {
                        departureTime = time
                        journey = journey.copy(startPoint = point, startOdometer = odometer)
                        FirebaseSyncManager.uploadWorkHour(date.toString(), time, returnHomeTime)
                        FirebaseSyncManager.uploadDailyJourney(date.toString(), journey)
                        true
                    } else false
                },
                onSaveReturnHome = { time, point, odometer ->
                    if (saveDailyEndpoint(prefs, date.toString(), false, time, point, odometer)) {
                        returnHomeTime = time
                        journey = journey.copy(endPoint = point, endOdometer = odometer)
                        FirebaseSyncManager.uploadWorkHour(date.toString(), departureTime, time)
                        FirebaseSyncManager.uploadDailyJourney(date.toString(), journey)
                        true
                    } else false
                }
            )
            LazyColumn(state = caseListState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp)) {
                itemsIndexed(visible, key = { _, it -> it.id }) { index, order ->
                    if (index == 0 && effectiveStartPoint.isNotBlank()) {
                        Text("起點：$effectiveStartPoint\n交通車程：${initialTransferSeconds?.let { "${routeMinutes(it)} 分鐘" } ?: initialRouteError ?: "自動估算中…"}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                        if (initialRouteError != null) TextButton(onClick = { initialRouteError = null; initialRouteRevision++ }, enabled = estimateJob?.isActive != true) { Text("重試起點交通估算") }
                    }
                    if (index > 0) {
                        val transferEstimate = RouteEstimate.parse(order.routeEstimate)
                        val transferMins = order.transferMinutes.toLongOrNull()
                            ?: transferEstimate?.takeIf { it.key == routeInputKey(order, day.getOrNull(index - 1)) }?.transferSeconds?.let { routeMinutes(it) }
                        val gapSegments = actualTransfers[order.id].orEmpty()
                        val knownSplit = gapSegments.isNotEmpty() && gapSegments.none { it.kind == "未分類" }
                        fun segmentMinutes(kind: String) = routeMinutes(kotlin.math.round(gapSegments.filter { it.kind == kind }.sumOf { (it.end - it.start) * 60 }).toLong())
                        val transferText = if (!actualMode) "🚗 交通車程：${transferMins?.let { "$it 分鐘" } ?: "待估算"}"
                            else if (knownSplit) "交通（估算）：${segmentMinutes("交通")} 分鐘\n等待（推算）：${segmentMinutes("等待")} 分鐘${if (gapSegments.any { it.conflicts }) " · 車程估算超過實際間隔" else ""}"
                            else "交通（估算）：${transferMins?.let { "$it 分鐘" } ?: routeErrors[order.id] ?: "待估算"}\n等待：${if (gapSegments.isEmpty()) "待客上／客下紀錄" else "待交通估算"}"
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
                                    transferText,
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
                    val elapsed = !actualMode && date == now.toLocalDate() && minute != null && minute <= now.hour * 60 + now.minute
                    val billedSeconds = billedRideSeconds(order, day.getOrNull(day.indexOf(order) - 1))
                    val durationMins = if (actualMode) actualRideMinutes(order)
                        else billedSeconds?.let { routeMinutes(it) } ?: order.rideMinutes.toLongOrNull()
                    val durationColor = when {
                        durationMins == null -> Color(0xFF9E9E9E)
                        durationMins <= 20 -> Color(0xFF2E7D32) // 短程 (綠)
                        durationMins <= 40 -> Color(0xFFE65100) // 中程 (橙)
                        else -> Color(0xFFC62828)               // 長程 (紅)
                    }
                    val caseColor = rideCaseColor(order.category)
                    val distanceColor = rideDistanceColor(RouteEstimate.parse(order.routeEstimate)?.meters)

                    ElevatedCard(
                        onClick = { onEdit(order, actualMode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (order.completed) 0.82f else 1.0f)
                            .border(if (order.id == highlightedRideId) 3.dp else 2.dp, if (order.id == highlightedRideId) (if (actualMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary) else distanceColor.copy(alpha = if (order.completed) 0.65f else 0.9f), CardDefaults.elevatedShape),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (actualMode) {
                                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (order.completed) 0.35f else 0.55f)
                            } else if (order.completed) {
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
                            // 左側邊框表示距離；Case 類型仍由標籤表示。
                            Box(
                                Modifier
                                    .width(7.dp)
                                    .fillMaxHeight()
                                    .background(distanceColor)
                            )
                            Column(Modifier.weight(1f).padding(horizontal = 6.dp, vertical = if(compact) 2.dp else 8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        if (actualMode) actualTimeLabel(order.actualBoardedAt) else order.pickupTime,
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
                                    RideCaseTag(order.category)
                                    CompletionToggle(order.completed, compact) { onUpdate(order.copy(completed = it)) }
                                }
                                if (!compact && elapsed && !order.completed) Text("已到接客時間", style = MaterialTheme.typography.labelMedium)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(order.customer, modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 18.sp)
                                        Icon(
                                            imageVector = if (order.wheelchair) Icons.Default.Accessible else Icons.Default.DirectionsWalk,
                                            contentDescription = if (order.wheelchair) "輪椅" else "一般行走",
                                            modifier = Modifier.size(20.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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
                                    val feeParts = caseFeeText(order)
                                    Text("${if(order.returnRide) "回程" else "去程"}${if(order.tentative) " · 暫定" else ""}${if(order.timeFlexible) " · 時間可調" else ""}${if(order.wheelchair) " · 需輪椅" else ""}${if (feeParts.isNotBlank()) " · $feeParts" else " · 費用未填"}", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (!compact) {
                                    Text("聯絡人：${order.contact} ${order.contactPhone}")
                                    if (order.passengerPhone.isNotBlank()) Text("乘客電話：${order.passengerPhone}")
                                    if (order.transferMinutes.isNotBlank() && order.transferMinutes != "0") Text("上一趟交通銜接：${order.transferMinutes} 分鐘")
                                    if (order.reportTarget.isNotBlank()) Text("客上／客下回報對象：${order.reportTarget}")
                                    if (order.uncertainties.isNotBlank()) Text("待確認：${order.uncertainties}", color = MaterialTheme.colorScheme.error)
                                    if (order.notes.isNotBlank()) Text(order.notes)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(
                                            onClick = { onEdit(order, actualMode) },
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
                                                    tip = "",
                                                    monthlyReceipts = emptyList(),
                                                    actualBoardedAt = "",
                                                    actualAlightedAt = "",
                                                    routeEstimate = ""
                                                )
                                                onEdit(duplicate, false)
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(36.dp)
                                        ) {
                                            Text("複製新增此單", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                if (compact) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    val eta = if (actualMode) null else calculateEta(order.pickupTime, durationMins)
                                    Text("${if (actualMode) "實際載客" else "車程"} ${durationMins?.let { "$it 分" } ?: if (actualMode) "待記錄" else "待估"}${if(!actualMode && routeErrors[order.id] != null) " · 失敗" else ""}", style = MaterialTheme.typography.bodyMedium, color = durationColor)
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
                                }
                                if (actualMode) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("客上 ${actualTimeLabel(order.actualBoardedAt)}\n客下 ${actualTimeLabel(order.actualAlightedAt)}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                    if (order.actualBoardedAt.isNotBlank() || order.actualAlightedAt.isNotBlank())
                                        TextButton(onClick = { resetActualRide = order }) { Text("重設時間") }
                                }
                                if (caseFeeText(order).isNotBlank()) Surface(
                                    color = caseColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        caseFeeText(order),
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = caseColor
                                    )
                                }
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
    resetActualRide?.let { ride ->
        AlertDialog(onDismissRequest = { resetActualRide = null }, title = { Text("重設實際上下車時間") },
            text = { Text("${ride.customer.ifBlank { ride.pickup }} 的客上、客下時間會清空，可再次按按鈕重新記錄。") },
            confirmButton = { TextButton(onClick = { onUpdate(ride.copy(actualBoardedAt = "", actualAlightedAt = "")); resetActualRide = null }) { Text("重設") } },
            dismissButton = { TextButton(onClick = { resetActualRide = null }) { Text("取消") } })
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
    if (report) DayClosingDialog(date, day, departureTime, returnHomeTime, onDismiss = { report = false },
        onEdit = { ride -> report = false; onEdit(ride, true) })
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


private fun rideDistanceColor(meters: Long?): Color = when {
    meters == null || meters < 0 -> Color(0xFF69717C) // 尚無距離
    meters < 5_000 -> Color(0xFF7154A4) // 短程
    meters < 15_000 -> Color(0xFFA34687) // 中程
    else -> Color(0xFFB32635) // 長程
}

@Composable private fun rideCaseColor(category: String): Color = when (category) {
    "自費" -> if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFFFA640) else Color(0xFFB95500)
    "補助單", "補助" -> Color(0xFF1747A6)
    "日照" -> Color(0xFF08745B)
    else -> Color(0xFF596579)
}

@Composable private fun RideCaseTag(category: String) {
    val label = if (category == "補助單") "補助" else category
    val darkOrange = category == "自費" && MaterialTheme.colorScheme.background.luminance() < 0.5f
    Surface(color = rideCaseColor(category), shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.ExtraBold,
            color = if (darkOrange) Color.Black else Color.White
        )
    }
}

@Composable private fun RideCategoryTag(category: String) {
    val tint = when (category) {
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
    LocationTermComment(address)
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
