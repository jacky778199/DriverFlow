package tw.driver.schedule

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.Duration
import kotlin.math.ceil
import kotlin.math.floor

internal data class TimelineTrip(val id: Long, val start: Double, val end: Double?, val lane: Int, val completed: Boolean, val category: String)
internal fun timelineTrips(rides: List<RideOrder>): List<TimelineTrip> {
    val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    val laneEnds = mutableListOf<Double>()
    return sorted.mapIndexedNotNull { index, ride ->
        val start = minuteOfDay(ride.pickupTime)?.toDouble() ?: return@mapIndexedNotNull null
        val seconds = billedRideSeconds(ride, sorted.getOrNull(index - 1))
        val end = seconds?.let { start + it / 60.0 }
        val lane = laneEnds.indexOfFirst { it <= start }.let { if (it < 0) laneEnds.size else it }
        if (lane == laneEnds.size) laneEnds.add(end ?: start) else laneEnds[lane] = end ?: start
        TimelineTrip(ride.id, start, end, lane, ride.completed, ride.category)
    }
}
internal fun timelineRideAtMinute(trips: List<TimelineTrip>, minute: Double): Long? =
    trips.minWithOrNull(compareBy<TimelineTrip> {
        val end = it.end ?: it.start
        kotlin.math.abs(minute - minute.coerceIn(it.start, maxOf(it.start, end)))
    }.thenBy { kotlin.math.abs(minute - it.start) })?.id

internal data class TimelineTransfer(val rideId: Long, val start: Double, val end: Double, val conflicts: Boolean, val kind: String = "交通", val estimated: Boolean = false)
internal fun timelineTransfers(rides: List<RideOrder>): List<TimelineTransfer> {
    val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    val trips = timelineTrips(sorted).associateBy { it.id }
    return sorted.mapIndexedNotNull { index, ride ->
        val previous = sorted.getOrNull(index - 1) ?: return@mapIndexedNotNull null
        val start = trips[previous.id]?.end ?: return@mapIndexedNotNull null
        val pickup = trips[ride.id]?.start ?: return@mapIndexedNotNull null
        val seconds = ride.transferMinutes.toLongOrNull()?.takeIf { it in 0..1440 }?.times(60)
            ?: RouteEstimate.parse(ride.routeEstimate)?.takeIf { it.key == routeInputKey(ride, previous) }
                ?.transferSeconds?.takeIf { it >= 0 }
            ?: return@mapIndexedNotNull null
        if (seconds == 0L) return@mapIndexedNotNull null
        TimelineTransfer(ride.id, start, start + seconds / 60.0, start + seconds / 60.0 > pickup)
    }
}
internal fun actualTimelineTrips(rides: List<RideOrder>, date: LocalDate): List<TimelineTrip> {
    val midnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
    val timed = rides.mapNotNull { ride ->
        val boarded = actualRideInstant(ride.actualBoardedAt, ride.serviceDate)
        val alighted = actualRideInstant(ride.actualAlightedAt, ride.serviceDate)
        val start = boarded ?: alighted ?: return@mapNotNull null
        val startMinute = Duration.between(midnight, start).toMinutes().toDouble()
        val endMinute = alighted?.takeIf { !it.isBefore(start) }?.let { Duration.between(midnight, it).toMinutes().toDouble() }
        ride to (startMinute to endMinute)
    }.sortedBy { it.second.first }
    val laneEnds = mutableListOf<Double>()
    return timed.map { (ride, interval) ->
        val (start, end) = interval
        val lane = laneEnds.indexOfFirst { it <= start }.let { if (it < 0) laneEnds.size else it }
        if (lane == laneEnds.size) laneEnds.add(end ?: start) else laneEnds[lane] = end ?: start
        TimelineTrip(ride.id, start, end, lane, ride.completed, ride.category)
    }
}
internal fun transferSeconds(ride: RideOrder, previous: RideOrder): Long? =
    ride.transferMinutes.toLongOrNull()?.takeIf { it in 0..1440 }?.times(60)
        ?: RouteEstimate.parse(ride.routeEstimate)?.takeIf { it.key == routeInputKey(ride, previous) }
            ?.transferSeconds?.takeIf { it >= 0 }

// The recorded gap is exact; its driving/waiting split remains an estimate.
internal fun actualTimelineTransfers(rides: List<RideOrder>, date: LocalDate): List<TimelineTransfer> {
    val midnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant()
    val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    return sorted.zipWithNext().flatMap { (previous, ride) ->
        val start = actualRideInstant(previous.actualAlightedAt, previous.serviceDate)
        val end = actualRideInstant(ride.actualBoardedAt, ride.serviceDate)
        if (start == null || end == null || !end.isAfter(start)) return@flatMap emptyList()
        val startMinute = Duration.between(midnight, start).seconds / 60.0
        val endMinute = Duration.between(midnight, end).seconds / 60.0
        val travel = transferSeconds(ride, previous)
            ?: return@flatMap listOf(TimelineTransfer(ride.id, startMinute, endMinute, false, "未分類"))
        val gapSeconds = Duration.between(start, end).seconds
        val split = startMinute + minOf(travel, gapSeconds) / 60.0
        buildList {
            if (split > startMinute) add(TimelineTransfer(ride.id, startMinute, split, travel > gapSeconds, "交通", true))
            if (endMinute > split) add(TimelineTransfer(ride.id, split, endMinute, false, "等待", true))
        }
    }
}
internal fun timelineRange(
    trips: List<TimelineTrip>,
    departureTime: String = "",
    returnHomeTime: String = ""
): Pair<Double, Double> {
    val autoStart = if (trips.isEmpty()) 360.0 else floor((trips.minOf { it.start } - 30).coerceAtLeast(0.0) / 60) * 60
    val autoEnd = if (trips.isEmpty()) 1320.0 else ceil((trips.maxOf { it.end ?: it.start } + 30) / 60) * 60

    val customStart = minuteOfDay(departureTime)?.toDouble()
    val customEnd = minuteOfDay(returnHomeTime)?.toDouble()

    val start = customStart ?: autoStart
    val end = customEnd ?: maxOf(autoEnd, start + 240)
    return if (end > start) start to end else start to (start + 240)
}
internal fun timelineClock(minute: Double): String {
    val m = minute.toInt().coerceAtLeast(0)
    return (if (m >= 1440) "+${m / 1440} " else "") + "%02d:%02d".format((m / 60) % 24, m % 60)
}

@Composable internal fun ScheduleTimeline(
    rides: List<RideOrder>,
    date: LocalDate,
    now: LocalDateTime,
    modifier: Modifier,
    departureTime: String = "",
    returnHomeTime: String = "",
    journey: DailyJourney = DailyJourney(),
    suggestedStartOdometer: String = "",
    suggestedEndOdometer: String = "",
    highlightedRideId: Long? = null,
    actualMode: Boolean = false,
    onSelectRide: (Long) -> Unit = {},
    initialTransferSeconds: Long? = null,
    onSaveDeparture: (String, String, String) -> Boolean = { _, _, _ -> true },
    onSaveReturnHome: (String, String, String) -> Boolean = { _, _, _ -> true }
) {
    val selectRide by rememberUpdatedState(onSelectRide)
    val trips = if (actualMode) actualTimelineTrips(rides, date) else timelineTrips(rides)
    val betweenTransfers = if (actualMode) actualTimelineTransfers(rides, date) else timelineTransfers(rides)
    val first = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }.firstOrNull()
    val firstTrip = trips.firstOrNull { it.id == first?.id }
    val initialTransfer = initialTransferSeconds?.takeIf { it > 0 }?.let { seconds ->
        firstTrip?.let { trip ->
            val start = minuteOfDay(departureTime)?.toDouble() ?: (trip.start - seconds / 60.0)
            TimelineTransfer(trip.id, start, start + seconds / 60.0, start + seconds / 60.0 > trip.start, "起始交通", true)
        }
    }
    val transfers = betweenTransfers + listOfNotNull(initialTransfer)
    val rangeTrips = trips + transfers.map { TimelineTrip(it.rideId, it.start, it.end, 0, false, "交通") }
    val (start, end) = timelineRange(rangeTrips, departureTime, returnHomeTime)
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.outlineVariant
    val active = if (actualMode) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val darkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val endOdometer = journey.endOdometer.ifBlank { suggestedEndOdometer }

    var showDepartureDialog by remember { mutableStateOf(false) }
    var showReturnHomeDialog by remember { mutableStateOf(false) }

    val description = transfers.joinToString("；") { "${it.kind}${if (it.estimated) "（推算）" else ""} ${timelineClock(it.start)} 至 ${timelineClock(it.end)}${if (it.conflicts) "，銜接不足" else ""}" } + (if (actualMode) "實際排班：" else "預計排班：") + trips.joinToString("；") { "${timelineClock(it.start)}，${it.category}，${it.end?.let { end -> "${routeMinutes(((end - it.start) * 60).toLong())} 分鐘" } ?: "車程未知"}${if (it.id == highlightedRideId) "，目前顯示" else ""}" }

    var scrubMinute by remember(trips, start, end) { mutableStateOf<Double?>(null) }
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().pointerInput(trips, start, end, endOdometer) {
            detectTapGestures { point ->
                val top = 20.dp.toPx()
                val bottom = size.height - (if (endOdometer.isNotBlank()) 34.dp else 20.dp).toPx()
                if (bottom > top) {
                    fun y(minute: Double) = top + ((minute - start) / (end - start)).toFloat() * (bottom - top)
                    val lanes = (trips.maxOfOrNull { it.lane } ?: 0) + 1
                    val laneWidth = (size.width - 39.dp.toPx() - 5.dp.toPx()) / lanes
                    trips.minByOrNull { trip ->
                        val dy = kotlin.math.abs(point.y - point.y.coerceIn(y(trip.start), y(trip.end ?: trip.start)))
                        val dx = kotlin.math.abs(point.x - (42.dp.toPx() + (trip.lane + 0.5f) * laneWidth))
                        dy + dx
                    }?.let { selectRide(it.id) }
                }
            }
        }.pointerInput(trips, start, end, endOdometer) {
            var lastSelected: Long? = null
            fun scrubAt(position: Offset) {
                val top = 20.dp.toPx()
                val bottom = size.height - (if (endOdometer.isNotBlank()) 34.dp else 20.dp).toPx()
                if (bottom <= top) return
                val minute = start + ((position.y - top) / (bottom - top)).coerceIn(0f, 1f) * (end - start)
                scrubMinute = minute
                timelineRideAtMinute(trips, minute)?.let { id ->
                    if (id != lastSelected) { lastSelected = id; selectRide(id) }
                }
            }
            detectDragGestures(
                onDragStart = { lastSelected = null; scrubAt(it) },
                onDragEnd = { scrubMinute = null; lastSelected = null },
                onDragCancel = { scrubMinute = null; lastSelected = null },
                onDrag = { change, _ -> change.consume(); scrubAt(change.position) }
            )
        }.semantics { contentDescription = "行程時間軸：$description，可拖曳到時間定位行程" }) {
            val top = 20.dp.toPx(); val bottom = size.height - (if (endOdometer.isNotBlank()) 34.dp else 20.dp).toPx()
            if (bottom <= top) return@Canvas
            fun y(min: Double) = top + ((min - start) / (end - start)).toFloat() * (bottom - top)
            val axisX = 39.dp.toPx()
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = foreground.toArgb(); textSize = 10.sp.toPx() }
            drawLine(background, Offset(axisX, top), Offset(axisX, bottom), 1.dp.toPx())
            // Hour ticks use the same scale as every trip; overlap uses separate lanes, never added duration.
            for (hour in (start.toInt() / 60)..(end.toInt() / 60)) {
                val pos = y(hour * 60.0)
                drawLine(background, Offset(axisX, pos), Offset(size.width, pos), 1.dp.toPx())
            }
            val startLabel = if (departureTime.isNotBlank()) "${timelineClock(start)}出門" else timelineClock(start)
            val endLabel = if (returnHomeTime.isNotBlank()) "${timelineClock(end)}回家" else timelineClock(end)
            drawContext.canvas.nativeCanvas.drawText(startLabel, 0f, top - 4.dp.toPx(), paint)
            drawContext.canvas.nativeCanvas.drawText(endLabel, 0f,
                size.height - (if (endOdometer.isNotBlank()) 17.dp else 4.dp).toPx(), paint)
            if (endOdometer.isNotBlank()) {
                val odometerPaint = android.graphics.Paint(paint).apply {
                    textSize = 8.sp.toPx()
                    alpha = 140
                }
                drawContext.canvas.nativeCanvas.drawText("${endOdometer}km", 0f,
                    size.height - 3.dp.toPx(), odometerPaint)
            }
            val laneCount = (trips.maxOfOrNull { it.lane } ?: 0) + 1
            val laneWidth = (size.width - axisX - 5.dp.toPx()) / laneCount
            transfers.forEach { transfer ->
                val color = when { transfer.conflicts -> Color(0xFFE45C57); transfer.kind == "等待" -> Color(0xFFB58A42); else -> foreground }
                val x = axisX + 3.dp.toPx()
                drawRect(color.copy(alpha = 0.10f), Offset(x, y(transfer.start)),
                    Size((size.width - x).coerceAtLeast(1f), y(transfer.end) - y(transfer.start)))
                drawLine(color, Offset(x + 2.dp.toPx(), y(transfer.start)), Offset(x + 2.dp.toPx(), y(transfer.end)), 2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                if (y(transfer.end) - y(transfer.start) >= 11.sp.toPx()) {
                    drawContext.canvas.nativeCanvas.drawText("${if (transfer.kind == "等待") "等" else if (transfer.kind == "未分類") "?" else "交"}${routeMinutes(kotlin.math.round((transfer.end - transfer.start) * 60).toLong())}", x + 5.dp.toPx(), (y(transfer.start) + y(transfer.end)) / 2 + 3.dp.toPx(), paint)
                }
            }
            trips.forEach { trip ->
                val x = axisX + 3.dp.toPx() + trip.lane * laneWidth
                val color = when (trip.category) {
                    "自費" -> if (darkMode) Color(0xFFFFA640) else Color(0xFFB95500)
                    "補助", "補助單" -> Color(0xFF5282BE)
                    "日照" -> Color(0xFF4C9A87)
                    else -> active
                }
                val selected = trip.id == highlightedRideId
                val width = (laneWidth - 1.dp.toPx()).coerceAtLeast(1f)
                if (trip.end == null || trip.end == trip.start) {
                    drawCircle(color, (if (selected) 5 else 3).dp.toPx(), Offset(x + width / 2, y(trip.start)))
                } else {
                    val height = (y(trip.end) - y(trip.start)).coerceAtLeast(2.dp.toPx())
                    drawRect(color.copy(alpha = if (actualMode) 0.65f else if (trip.completed) 0.5f else 0.85f), Offset(x, y(trip.start)), Size(width, height))
                    if (selected) drawRect(active, Offset(x, y(trip.start)), Size(width, height), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
                }
                if (selected) drawCircle(color, 4.dp.toPx(), Offset(axisX, y(trip.start)))
            }
            var lastLabel = top - 14.dp.toPx()
            trips.map { it.start }.distinct().forEach { minute ->
                val anchor = y(minute)
                val labelY = maxOf(anchor, lastLabel + 12.dp.toPx()).coerceAtMost(bottom)
                drawLine(foreground.copy(alpha = 0.5f), Offset(32.dp.toPx(), labelY), Offset(axisX, anchor), 0.6.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(timelineClock(minute), 0f, labelY + 3.dp.toPx(), paint)
                lastLabel = labelY
            }
            scrubMinute?.let { minute ->
                drawLine(active.copy(alpha = 0.75f), Offset(0f, y(minute)), Offset(size.width, y(minute)), 1.dp.toPx())
                drawCircle(active, 4.dp.toPx(), Offset(axisX, y(minute)))
            }
            if (date == now.toLocalDate()) {
                val current = now.hour * 60.0 + now.minute
                if (current in start..end) drawLine(Color(0xFFE45C57), Offset(axisX - 3.dp.toPx(), y(current)), Offset(size.width, y(current)), 2.dp.toPx())
            }
        }

        // 點擊最上面設定出門時間
        Box(
            modifier = Modifier
                .width(39.dp)
                .height(20.dp)
                .align(Alignment.TopStart)
                .clickable { showDepartureDialog = true }
        )

        // 點擊最下面設定回家時間
        Box(
            modifier = Modifier
                .width(39.dp)
                .height(20.dp)
                .align(Alignment.BottomStart)
                .clickable { showReturnHomeDialog = true }
        )
    }

    if (showDepartureDialog) {
        DayEndpointDialog(
            date = date.toString(), departure = true, initialTime = departureTime,
            initialPoint = journey.startPoint, initialOdometer = journey.startOdometer,
            suggestedOdometer = suggestedStartOdometer,
            counterpartOdometer = endOdometer,
            onDismiss = { showDepartureDialog = false },
            onSave = onSaveDeparture
        )
    }

    if (showReturnHomeDialog) {
        DayEndpointDialog(
            date = date.toString(), departure = false, initialTime = returnHomeTime,
            initialPoint = journey.endPoint, initialOdometer = journey.endOdometer,
            suggestedOdometer = suggestedEndOdometer,
            counterpartOdometer = journey.startOdometer.ifBlank { suggestedStartOdometer },
            onDismiss = { showReturnHomeDialog = false },
            onSave = onSaveReturnHome
        )
    }
}

@Composable
internal fun TimeSettingDialog(
    title: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onClear: () -> Unit,
    supportingHint: String = "設定後將作為左側時間軸起訖點",
    clearLabel: String = "清除（恢復自動）"
) {
    var text by remember { mutableStateOf(initialValue) }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = false
                    },
                    label = { Text("時間 (HH:mm)") },
                    placeholder = { Text("例如 07:30") },
                    singleLine = true,
                    isError = error,
                    supportingText = {
                        if (error) Text("請填有效 24 小時時間，例如 07:30、0730 或 07：30", color = MaterialTheme.colorScheme.error)
                        else Text(supportingHint)
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val formatted = normalizeTime(text)
                if (formatted != null) {
                    onConfirm(formatted)
                } else {
                    error = true
                }
            }) {
                Text("確定")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onClear) {
                    Text(clearLabel)
                }
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        }
    )
}
