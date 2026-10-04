package tw.driver.schedule

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

internal fun insertionClock(value: Instant, zone: ZoneId = ZoneId.systemDefault()) = value.atZone(zone).format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))
internal val FeasibleGreen = Color(0xFF237B43)
internal val InfeasibleRed = Color(0xFFB3261E)

@Composable internal fun InsertionFeasibilityDialog(message: LineMessage, rides: List<RideOrder>, onDismiss: () -> Unit, onEvaluated: (InsertionResult) -> Unit) {
    val context = LocalContext.current
    val terms = remember { LocationTermsStore(context).load() }
    val scope = rememberCoroutineScope()
    val source = remember { MessageStore.get(context).currentSource() }
    var showDate by remember { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    val latestRides by rememberUpdatedState(rides)
    val client = remember { GoogleRouteClient.create(context.applicationContext) }
    var case by remember { mutableStateOf(InsertionCase("", "", LocalDate.now().toString())) }
    var buffer by remember { mutableStateOf("5") }
    var manualOrigin by remember { mutableStateOf(false) }
    var origin by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<InsertionSchedule?>(null) }
    var result by remember { mutableStateOf<InsertionResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var now by remember { mutableStateOf(Instant.now()) }
    var choices by remember { mutableStateOf<Pair<String, List<RoutePlace>>?>(null) }
    var selection by remember { mutableStateOf<CompletableDeferred<RoutePlace>?>(null) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(15000) } }
    suspend fun choose(query: String, places: List<RoutePlace>): RoutePlace {
        val answer = CompletableDeferred<RoutePlace>()
        selection = answer; choices = query to places
        return try { answer.await() } finally { selection = null; choices = null }
    }
    val evaluator = InsertionEvaluator(client, ::choose, { currentMessageLocation(context) }, { status = it })
    fun analyze(force: Boolean = false) {
        if (busy) return
        busy = true; error = ""; result = null
        job = scope.launch {
            try {
                status = "解析插單起訖點與接客時間…"
                try { case = (InsertionAnalysis.analyze(context, source, message, force).case ?: error("訊息解析失敗，請重試或手動填寫")).translated(terms) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = "${e.message ?: "辨識失敗"}；可直接手動填寫" }
                result = InsertionAnalysis.entries.value[source to message.seq]?.result?.takeIf { it.isFresh(latestRides) }
                plan = result?.plan ?: evaluator.prepare(latestRides, Instant.now())
                result?.let { case = it.case.translated(terms); buffer = it.bufferMinutes.toString() }
                status = "資料可修正，請確認後評估"
            } catch (e: CancellationException) { status = "已取消" }
            catch (e: Exception) { error = e.message ?: "排程資料不足"; status = "" }
            finally { busy = false }
        }
    }
    fun evaluate() {
        if (busy) return
        busy = true; result = null; error = ""
        job = scope.launch {
            try {
                val padding = buffer.toLongOrNull()?.takeIf { it in 0..60 } ?: error("上下車緩衝請填 0–60 分鐘")
                val evaluated = evaluator.evaluate(case, latestRides, { latestRides }, padding, manualOrigin, origin)
                plan = evaluated.plan
                result = evaluated; status = "評估完成"
            } catch (e: CancellationException) { status = "已取消" }
            catch (e: Exception) { error = e.message ?: "無法取得完整路線，尚不能判斷可行性"; status = "" }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) evaluate() else error = "定位未授權，請改用手動出發位置"
    }
    LaunchedEffect(Unit) { analyze() }
    val fresh = result?.takeIf { it.isFresh(rides, now) }
    fun close() {
        job?.cancel()
        val valid = result?.takeIf { it.isFresh(latestRides) }
        if (valid != null) onEvaluated(valid)
        else if (edited) InsertionAnalysis.save(source, message.seq, InsertionAnalysisEntry(case, status = "資料已修改，請重新評估"))
        onDismiss()
    }
    AlertDialog(onDismissRequest = ::close, title = { Text("插單可行性評估") }, text = {
        Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { showOriginal = !showOriginal }) { Text(if (showOriginal) "收起原文" else "訊息原文") }
                TextButton(enabled = !busy, onClick = { analyze(true) }) { Text("重新解析") }
            }
            if (showOriginal) Text(message.content, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(case.pickup, { case = case.copy(pickup = it); result = null; edited = true }, label = { Text("1. 上車地點") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall, maxLines = 2)
            OutlinedTextField(case.destination, { case = case.copy(destination = it); result = null; edited = true }, label = { Text("下車地點") }, enabled = !busy,
                modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall, maxLines = 2)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("2. 接客", style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = { showDate = !showDate }) { Text(if (showDate) "收起日期" else if (case.date == LocalDate.now().toString()) "今天 · 修改" else "${case.date} · 修改") }
            }
            if (showDate) OutlinedTextField(case.date, { case = case.copy(date = it); result = null; edited = true }, label = { Text("日期 YYYY-MM-DD") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
            FilterChip(selected = case.asap, onClick = { case = case.copy(asap = !case.asap); result = null; edited = true }, enabled = !busy, label = { Text("即時可等") })
            if (case.asap) Text("以評估當下時間作為最早接客時間", style = MaterialTheme.typography.bodySmall)
            if (!case.asap) OutlinedTextField(case.time, { case = case.copy(time = it); result = null; edited = true }, label = { Text("時間 HH:mm 或 HH:mm–HH:mm") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("3. 出發位置", style = MaterialTheme.typography.titleSmall)
                TextButton(enabled = !busy, onClick = { manualOrigin = !manualOrigin; result = null; edited = true }) { Text(if (manualOrigin) "改用自動" else "手動修改") }
            }
            if (manualOrigin) OutlinedTextField(origin, { origin = it; result = null; edited = true }, label = { Text("出發地址") }, enabled = !busy, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall)
            else Text(plan?.active?.destination?.let(terms::expand) ?: if (plan != null) "現在位置" else "判斷中…", style = MaterialTheme.typography.bodySmall)
            plan?.let { schedule ->
                Text(if (schedule.active != null) "${insertionClock(schedule.availableAt)} 可出發 · 目前行程下車後" else "現在可出發", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("4. 下一趟", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
            Text(plan?.let { schedule -> schedule.next?.let { "${insertionClock(schedule.nextAt!!)} 接客 · ${terms.expand(it.pickup)}" } ?: "無待接行程" } ?: "判斷中…", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showOptions = !showOptions }) { Text(if (showOptions) "收起緩衝設定" else "上下車各 $buffer 分鐘 · 修改") }
            if (showOptions) {
                OutlinedTextField(buffer, { buffer = it; result = null; edited = true }, label = { Text("上下車各預留分鐘") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Text("目前行程結束時間：接客時間＋車程＋上下車各 5 分鐘。", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("5. 路程分析", style = MaterialTheme.typography.titleSmall)
            fresh?.let { InsertionRouteTimeline(it, terms) }
                ?: Text(if (result == null) "尚未完成評估" else "結果已失效，請重新評估", style = MaterialTheme.typography.bodySmall)
            if (busy && status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = {
        Button(enabled = !busy && case.pickup.isNotBlank() && case.destination.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = fresh?.let { if (it.feasible) FeasibleGreen else InfeasibleRed } ?: MaterialTheme.colorScheme.primary,
            contentColor = if (fresh != null) Color.White else MaterialTheme.colorScheme.onPrimary), onClick = {
            val needsLocation = !manualOrigin && plan?.active == null
            if (needsLocation && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else evaluate()
        }) { Text(if (busy) "評估中…" else "評估可行性") }
    }, dismissButton = { TextButton(onClick = ::close) { Text(if (busy) "取消" else "關閉") } })
    choices?.let { (query, places) ->
        AlertDialog(onDismissRequest = { selection?.cancel() }, title = { Text("選擇「$query」") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                places.forEach { place -> TextButton(onClick = { selection?.complete(place) }) { Text("${place.name}\n${place.address}") } }
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { selection?.cancel() }) { Text("取消") } })
    }
}

/** Ordered stops with driving time between them; waiting and boarding time remain explicit. */
@Composable private fun InsertionRouteTimeline(value: InsertionResult, terms: LocationTerms) {
    val color = if (value.feasible) FeasibleGreen else InfeasibleRed
    val zone = ZoneId.systemDefault()
    fun time(at: Instant): String = at.atZone(zone).format(DateTimeFormatter.ofPattern(
        if (at.atZone(zone).toLocalDate() == value.calculatedAt.atZone(zone).toLocalDate()) "HH:mm" else "MM/dd HH:mm"))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Surface(color = color.copy(alpha = 0.10f), shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(if (value.feasible) "可插單" else "不建議插單", color = color, fontWeight = FontWeight.Bold)
                Text(if (value.feasible) value.nextSlackSeconds?.let { "下一趟前還有 ${it / 60} 分鐘空檔" } ?: "後續無待接行程"
                    else value.reasons.joinToString("；"), style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
        InsertionStop(time(value.plan.availableAt), "出發", if (value.origin.startsWith("現在位置")) "現在位置" else value.origin, color)
        InsertionLeg("開車 ${routeMinutes(value.toPickup.seconds)} 分鐘")
        InsertionStop(time(value.pickupArrival), "抵達上車點", terms.expand(value.case.pickup), color)
        LocationTermComment(value.case.pickup)
        val waiting = Duration.between(value.pickupArrival, value.pickupStart).seconds
        if (waiting > 0) Text("等候至 ${time(value.pickupStart)} 接客", modifier = Modifier.padding(start = 20.dp), style = MaterialTheme.typography.labelSmall)
        InsertionLeg("上車 ${value.bufferMinutes} 分 ＋ 開車 ${routeMinutes(value.ride.seconds)} 分 ＋ 下車 ${value.bufferMinutes} 分")
        InsertionStop(time(value.dropoffReady), "下車完成", terms.expand(value.case.destination), color)
        LocationTermComment(value.case.destination)
        value.toNext?.let { leg ->
            InsertionLeg("開車 ${routeMinutes(leg.seconds)} 分鐘")
            InsertionStop(time(value.nextArrival!!), "抵達下一趟", terms.expand(value.plan.next!!.pickup), color)
            Text("預約接客 ${time(value.plan.nextAt!!)}", modifier = Modifier.padding(start = 20.dp), style = MaterialTheme.typography.labelSmall)
        }
        Text("路況為估算 · 結果最長有效 5 分鐘", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
