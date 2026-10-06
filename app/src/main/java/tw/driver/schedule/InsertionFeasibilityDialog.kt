package tw.driver.schedule

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

internal fun insertionClock(value: Instant, zone: ZoneId = ZoneId.systemDefault()) =
    value.atZone(zone).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
internal val FeasibleGreen = Color(0xFF237B43)
internal val InfeasibleRed = Color(0xFFB3261E)

@Composable internal fun insertionColor(level: InsertionLevel, isDark: Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f): Color =
    when (level) {
        InsertionLevel.AVAILABLE -> if (isDark) Color(0xFF7ED99E) else FeasibleGreen
        InsertionLevel.CAUTION -> if (isDark) Color(0xFFFFCE73) else Color(0xFF8C5700)
        InsertionLevel.INFEASIBLE -> if (isDark) Color(0xFFFF8A80) else InfeasibleRed
        InsertionLevel.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun originModeLabel(mode: InsertionOrigin): String = when (mode) {
    InsertionOrigin.AUTO -> "自動"
    InsertionOrigin.SCHEDULE -> "上一趟下車"
    InsertionOrigin.CURRENT -> "現在位置"
    InsertionOrigin.MANUAL -> "手動位置"
}

@Composable internal fun InsertionFeasibilityDialog(message: LineMessage, rides: List<RideOrder>, onDismiss: () -> Unit,
    onEvaluated: (InsertionResult) -> Unit) {
    val context = LocalContext.current
    val terms = remember { LocationTermsStore(context).load() }
    val scope = rememberCoroutineScope()
    val source = remember { MessageStore.get(context).currentSource() }
    val latestRides by rememberUpdatedState(rides)
    val client = remember { GoogleRouteClient.create(context.applicationContext) }
    var showDate by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    var case by remember { mutableStateOf(InsertionCase("", "", LocalDate.now().toString())) }
    var buffer by remember { mutableStateOf("5") }
    var warning by remember { mutableStateOf("10") }
    var mode by remember { mutableStateOf(InsertionOrigin.AUTO) }
    var manualBasis by remember { mutableStateOf(InsertionOrigin.AUTO) }
    var origin by remember { mutableStateOf("") }
    var pickupOverride by remember { mutableStateOf<RoutePlace?>(null) }
    var destinationOverride by remember { mutableStateOf<RoutePlace?>(null) }
    var result by remember { mutableStateOf<InsertionResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    var choices by remember { mutableStateOf<Pair<String, List<RoutePlace>>?>(null) }
    var selection by remember { mutableStateOf<CompletableDeferred<RoutePlace>?>(null) }
    var permissionAnswer by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        permissionAnswer?.complete(granted.values.any { it })
    }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1000) } }
    DisposableEffect(Unit) { onDispose { job?.cancel(); selection?.cancel(); permissionAnswer?.cancel() } }
    suspend fun choose(query: String, places: List<RoutePlace>): RoutePlace {
        val answer = CompletableDeferred<RoutePlace>()
        selection = answer; choices = query to places
        return try { answer.await() } finally { selection = null; choices = null }
    }
    suspend fun dialogLocation(): RoutePoint {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            val answer = CompletableDeferred<Boolean>()
            permissionAnswer = answer
            try {
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                check(answer.await()) { "定位未授權，可改用手動位置" }
            } finally { permissionAnswer = null }
        }
        return currentMessageLocation(context).let { RoutePoint(it.latitude, it.longitude, it.accuracy.toInt()) }
    }
    val evaluator = InsertionEvaluator(client, ::choose, ::dialogLocation, { status = it })
    fun invalidate() {
        result = null; edited = true; error = ""
        InsertionAnalysis.save(source, message.seq, InsertionAnalysisEntry(case, status = "資料已修改，請重新評估"))
    }
    fun analyze(force: Boolean = false) {
        if (busy) return
        busy = true; error = ""; result = null
        pickupOverride = null; destinationOverride = null
        job = scope.launch {
            try {
                status = "解析起訖點與接客時間…"
                val entry = InsertionAnalysis.analyze(context, source, message, force)
                case = entry.case ?: error("訊息解析失敗，請手動填寫")
                result = entry.result?.takeIf { it.isFresh(latestRides) }
                result?.let {
                    case = it.case; buffer = it.bufferMinutes.toString(); warning = it.warningMinutes.toString()
                    mode = it.originMode; manualBasis = it.basisMode
                    if (mode == InsertionOrigin.MANUAL) origin = it.origin
                    pickupOverride = it.pickupPlace?.takeIf { place -> place.selectionReason == "手動選取位置" }
                    destinationOverride = it.destinationPlace?.takeIf { place -> place.selectionReason == "手動選取位置" }
                }
                edited = false
                status = if (result != null) "評估完成" else "可修正資料，再評估眼前空檔"
            } catch (e: CancellationException) { status = "已取消" }
            catch (e: Exception) { error = "尚無法判斷：${e.message ?: "請手動填寫"}"; status = "" }
            finally { busy = false }
        }
    }
    fun evaluate() {
        if (busy) return
        invalidate(); busy = true
        job = scope.launch {
            try {
                val padding = buffer.toLongOrNull()?.takeIf { it in 0..60 } ?: error("上下車緩衝請填 0–60 分鐘")
                val threshold = warning.toLongOrNull()?.takeIf { it in 0..60 } ?: error("餘裕提醒門檻請填 0–60 分鐘")
                val evaluated = evaluator.evaluate(case, latestRides, { latestRides }, padding, mode, origin, manualBasis,
                    threshold, pickupOverride, destinationOverride)
                result = evaluated; edited = false; status = "評估完成"
                InsertionAnalysis.save(source, message.seq, InsertionAnalysisEntry(case, evaluated, "評估完成"))
            } catch (e: CancellationException) { status = "已取消" }
            catch (e: Exception) {
                error = "尚無法判斷：${e.message ?: "請確認資料或重試"}"; status = ""
                InsertionAnalysis.save(source, message.seq, InsertionAnalysisEntry(case, status = error))
            } finally { busy = false }
        }
    }
    fun changePlace(pickup: Boolean) {
        if (busy) return
        val reference = (if (pickup) result?.pickupPlace else result?.destinationPlace) ?: pickupOverride
        invalidate(); busy = true
        job = scope.launch {
            try {
                status = "選擇${if (pickup) "上車" else "下車"}位置…"
                val place = client.resolveInsertion(if (pickup) case.pickup else case.destination, reference = reference,
                    forceSelection = true, choose = ::choose)
                if (pickup) pickupOverride = place else destinationOverride = place
                status = "位置已選取，請重新評估"
            } catch (e: CancellationException) { status = "已取消選址" }
            catch (e: Exception) { error = "尚無法判斷：${e.message ?: "請修改地點"}" }
            finally { busy = false }
        }
    }
    fun setMode(selected: InsertionOrigin) {
        if (selected == InsertionOrigin.MANUAL && mode != InsertionOrigin.MANUAL) manualBasis = mode
        mode = selected; invalidate()
    }
    LaunchedEffect(Unit) { analyze() }
    val fresh = result?.takeIf { it.isFresh(rides, now) }
    fun close() {
        job?.cancel()
        result?.takeIf { it.isFresh(latestRides) }?.let(onEvaluated)
        onDismiss()
    }
    val buttonColor = fresh?.let { insertionColor(it.level) } ?: MaterialTheme.colorScheme.primary
    AlertDialog(onDismissRequest = ::close, title = { Text("插單可行性評估") }, text = {
        Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "收起原文" else "訊息原文") }
                TextButton(enabled = !busy, onClick = { analyze(true) }) { Text("重新解析") }
            }
            if (showOriginal) Text(message.content, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(case.pickup, { case = case.copy(pickup = it); pickupOverride = null; invalidate() },
                label = { Text("1. 上車地點") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            val pickupPlace = pickupOverride ?: fresh?.pickupPlace
            pickupPlace?.let { Text("採用：${it.name} ${it.address}${if (it.approximate) "（粗估）" else ""}", style = MaterialTheme.typography.labelSmall) }
            TextButton(enabled = !busy && case.pickup.isNotBlank(), onClick = { changePlace(true) }) { Text("更換上車位置") }
            OutlinedTextField(case.destination, { case = case.copy(destination = it); destinationOverride = null; invalidate() },
                label = { Text("下車地點") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            val destinationPlace = destinationOverride ?: fresh?.destinationPlace
            destinationPlace?.let { Text("採用：${it.name} ${it.address}${if (it.approximate) "（粗估）" else ""}", style = MaterialTheme.typography.labelSmall) }
            TextButton(enabled = !busy && case.destination.isNotBlank(), onClick = { changePlace(false) }) { Text("更換下車位置") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("2. 接客", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showDate = !showDate }) { Text(if (showDate) "收起日期" else if (case.date == LocalDate.now().toString()) "今天 · 修改" else "${case.date} · 修改") }
            }
            if (showDate) OutlinedTextField(case.date, { case = case.copy(date = it); invalidate() }, label = { Text("日期 YYYY-MM-DD") },
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            FilterChip(selected = case.asap, onClick = { case = case.copy(asap = !case.asap); invalidate() },
                enabled = !busy, label = { Text("即時可等") })
            if (case.asap) Text("只評估眼前空檔，不往後找其他時段", style = MaterialTheme.typography.bodySmall)
            else OutlinedTextField(case.time, { case = case.copy(time = it); invalidate() }, label = { Text("時間 HH:mm 或 HH:mm–HH:mm") },
                enabled = !busy, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("3. 評估基準與出發位置", style = MaterialTheme.typography.titleSmall)
            listOf(listOf(InsertionOrigin.AUTO, InsertionOrigin.CURRENT), listOf(InsertionOrigin.SCHEDULE, InsertionOrigin.MANUAL)).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        FilterChip(selected = mode == item, enabled = !busy, onClick = { setMode(item) }, label = { Text(originModeLabel(item)) })
                    }
                }
            }
            if (mode == InsertionOrigin.MANUAL) {
                OutlinedTextField(origin, { origin = it; invalidate() }, label = { Text("手動出發地址") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("沿用時間基準：${originModeLabel(manualBasis)}", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(InsertionOrigin.AUTO, InsertionOrigin.CURRENT, InsertionOrigin.SCHEDULE).forEach { item ->
                        FilterChip(selected = manualBasis == item, enabled = !busy,
                            onClick = { manualBasis = item; invalidate() }, label = { Text(originModeLabel(item)) })
                    }
                }
            }
            Text(fresh?.plan?.originReason ?: when (mode) {
                InsertionOrigin.AUTO -> "實際客上／客下優先，沒有紀錄才依排程推估；已結束的眼前案件使用 GPS"
                InsertionOrigin.CURRENT -> "從現在時間與 GPS 出發，檢查最近待接案件"
                InsertionOrigin.SCHEDULE -> "從插單前一趟下車地點出發，時間不早於現在"
                InsertionOrigin.MANUAL -> "只修改出發地址，時間沿用上述基準"
            }, style = MaterialTheme.typography.bodySmall)
            fresh?.plan?.let { plan ->
                Text("${insertionClock(plan.availableAt)} 可出發 · ${plan.evidence}", style = MaterialTheme.typography.labelSmall)
                Text(plan.next?.let { "下一趟：${insertionClock(plan.nextAt!!)} · ${terms.expand(it.pickup)}" } ?: "後續無待接行程", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "收起選項" else "上下車各 $buffer 分 · 餘裕提醒 $warning 分 · 修改") }
            if (showOptions) {
                OutlinedTextField(buffer, { buffer = it; invalidate() }, label = { Text("上下車各預留分鐘（0–60）") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(warning, { warning = it; invalidate() }, label = { Text("餘裕不足幾分鐘提醒（0–60）") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("提醒門檻只改變提示顏色，不增加行程耗時。", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            fresh?.let { InsertionRouteTimeline(it, terms, initialExpanded = true) }
                ?: Text(if (result == null) "尚未完成評估" else "結果已失效，請重新評估", style = MaterialTheme.typography.bodySmall)
            if (busy && status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(enabled = !busy && case.pickup.isNotBlank() && case.destination.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = buttonColor,
                contentColor = if (buttonColor.luminance() > 0.45f) Color.Black else Color.White),
            onClick = ::evaluate) { Text(if (busy) "評估中…" else "評估可行性") }
    }, dismissButton = { TextButton(onClick = ::close) { Text(if (busy) "取消" else "關閉") } })
    choices?.let { (query, places) ->
        AlertDialog(onDismissRequest = { selection?.cancel() }, title = { Text("選擇「$query」") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                places.forEach { place -> TextButton(onClick = { selection?.complete(place) }) { Text("${place.name}\n${place.address}") } }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { selection?.cancel() }) { Text("取消") } })
    }
}

@Composable internal fun InsertionRouteTimeline(value: InsertionResult, terms: LocationTerms, isDark: Boolean? = null,
    initialExpanded: Boolean = false) {
    val dark = isDark ?: (MaterialTheme.colorScheme.surface.luminance() < 0.5f)
    val color = insertionColor(value.level, dark)
    var expanded by remember(value) { mutableStateOf(initialExpanded) }
    val zone = ZoneId.systemDefault()
    fun time(at: Instant): String = at.atZone(zone).format(DateTimeFormatter.ofPattern(
        if (at.atZone(zone).toLocalDate() == value.calculatedAt.atZone(zone).toLocalDate()) "HH:mm" else "MM/dd HH:mm"))
    fun address(place: RoutePlace?, fallback: String): String =
        place?.let { "${it.name} ${it.address}${if (it.approximate) "（粗估）" else ""}" } ?: terms.expand(fallback)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(color = color.copy(alpha = 0.10f), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(value.level.label, color = color, fontWeight = FontWeight.Bold)
                if (value.level != InsertionLevel.UNKNOWN) Text("預計 ${time(value.pickupStart)} 可接 · ${value.nextSlackSeconds?.let {
                    if (it >= 0) "下一趟前餘 ${it / 60} 分" else "下一趟延誤 ${routeMinutes(-it)} 分"
                } ?: "後續無待接行程"}", style = MaterialTheme.typography.bodySmall, color = color)
                if (value.reasons.isNotEmpty()) Text(value.reasons.joinToString("；"), style = MaterialTheme.typography.bodySmall, color = color)
                if (value.level == InsertionLevel.CAUTION) Text(
                    (value.plan.warnings + (if (value.locationEstimates.isNotEmpty()) listOf("位置為街道粗估") else emptyList()) +
                        (if (value.nextSlackSeconds != null && value.nextSlackSeconds < value.warningMinutes * 60) listOf("餘裕不足 ${value.warningMinutes} 分鐘") else emptyList())).joinToString("；"),
                    style = MaterialTheme.typography.labelSmall, color = color)
                Text("基準：${originModeLabel(value.originMode)} · ${value.plan.evidence}", style = MaterialTheme.typography.labelSmall)
                Text(value.plan.originReason + if (value.originMode == InsertionOrigin.MANUAL) "；地址已手動指定" else "",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (value.level == InsertionLevel.UNKNOWN) Text("以下時間為暫估，需先確認上述資料。", style = MaterialTheme.typography.labelSmall)
        InsertionStop(time(value.plan.availableAt), if (value.plan.active != null && value.originMode != InsertionOrigin.MANUAL) "上一趟下車後出發" else "出發",
            value.origin, color)
        if (expanded) {
            value.plan.previousReadyAt?.takeIf { it < value.plan.availableAt }?.let {
                InsertionLeg("上一趟下車 ${time(it)} · 至出發已過 ${routeMinutes(Duration.between(it, value.plan.availableAt).seconds)} 分")
            }
            InsertionLeg("前往插單 ${routeMinutes(value.toPickup.seconds)} 分鐘")
        }
        InsertionStop(time(value.pickupStart), "插單上車", address(value.pickupPlace, value.case.pickup), color)
        if (expanded) {
            val waiting = Duration.between(value.pickupArrival, value.pickupStart).seconds
            Text("抵達 ${time(value.pickupArrival)} · 接客要求：${if (value.case.asap) "即時可等" else value.case.time}",
                modifier = Modifier.padding(start = 20.dp), style = MaterialTheme.typography.labelSmall)
            if (waiting > 0) InsertionLeg("等候 ${routeMinutes(waiting)} 分鐘")
            InsertionLeg("上車 ${value.bufferMinutes} 分 ＋ 載客 ${routeMinutes(value.ride.seconds)} 分 ＋ 下車 ${value.bufferMinutes} 分")
        }
        InsertionStop(time(value.dropoffReady), "插單下車完成", address(value.destinationPlace, value.case.destination), color)
        value.toNext?.let { leg ->
            if (expanded) {
                InsertionLeg("前往下一趟 ${routeMinutes(leg.seconds)} 分鐘")
                InsertionStop(time(value.nextArrival!!), "抵達下一趟", terms.expand(value.plan.next!!.pickup), color)
            }
            InsertionStop(time(value.plan.nextAt!!), "下一趟預約上車", terms.expand(value.plan.next!!.pickup), color)
        }
        value.locationEstimates.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
            Text(if (expanded) "收起車程、等候與緩衝" else "展開車程、等候與緩衝", style = MaterialTheme.typography.labelSmall)
        }
        if (expanded) Text("路況為估算 · 結果最長有效 5 分鐘", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun InsertionStop(time: String, title: String, address: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 5.dp).size(10.dp).background(color, CircleShape))
        Column(Modifier.weight(1f)) {
            Text("$time  $title", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            Text(address, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun InsertionLeg(label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(2.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
