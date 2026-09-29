package tw.driver.schedule

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.ceil
import kotlin.math.floor

internal data class TimelineTrip(val id: Long, val start: Double, val end: Double?, val lane: Int, val completed: Boolean)
internal fun timelineTrips(rides: List<RideOrder>): List<TimelineTrip> {
    val sorted = rides.sortedBy { minuteOfDay(it.pickupTime) ?: Int.MAX_VALUE }
    val laneEnds = mutableListOf<Double>()
    return sorted.mapIndexedNotNull { index, ride ->
        val start = minuteOfDay(ride.pickupTime)?.toDouble() ?: return@mapIndexedNotNull null
        val seconds = billedRideSeconds(ride, sorted.getOrNull(index - 1))
        val end = seconds?.let { start + it / 60.0 }
        val lane = laneEnds.indexOfFirst { it <= start }.let { if (it < 0) laneEnds.size else it }
        if (lane == laneEnds.size) laneEnds.add(end ?: start) else laneEnds[lane] = end ?: start
        TimelineTrip(ride.id, start, end, lane, ride.completed)
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
    onSetDepartureTime: (String) -> Unit = {},
    onSetReturnHomeTime: (String) -> Unit = {}
) {
    val trips = timelineTrips(rides)
    val (start, end) = timelineRange(trips, departureTime, returnHomeTime)
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.outlineVariant
    val active = MaterialTheme.colorScheme.primary

    var showDepartureDialog by remember { mutableStateOf(false) }
    var showReturnHomeDialog by remember { mutableStateOf(false) }

    val description = trips.joinToString("；") { "${timelineClock(it.start)}，${it.end?.let { end -> "${routeMinutes(((end - it.start) * 60).toLong())} 分鐘" } ?: "車程未知"}" }

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = "行程時間軸：$description" }) {
            val top = 20.dp.toPx(); val bottom = size.height - 20.dp.toPx()
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
            drawContext.canvas.nativeCanvas.drawText(endLabel, 0f, size.height - 4.dp.toPx(), paint)
            val laneCount = (trips.maxOfOrNull { it.lane } ?: 0) + 1
            val laneWidth = (size.width - axisX - 5.dp.toPx()) / laneCount
            trips.forEach { trip ->
                val x = axisX + 3.dp.toPx() + trip.lane * laneWidth
                val color = if (trip.completed) Color(0xFF43A85B) else active
                if (trip.end == null || trip.end == trip.start) drawCircle(color, 2.dp.toPx(), Offset(x + laneWidth / 2, y(trip.start)))
                else drawRect(color.copy(alpha = 0.65f), Offset(x, y(trip.start)), Size((laneWidth - 1.dp.toPx()).coerceAtLeast(1f), y(trip.end) - y(trip.start)))
            }
            var lastLabel = top - 14.dp.toPx()
            trips.map { it.start }.distinct().forEach { minute ->
                val anchor = y(minute)
                val labelY = maxOf(anchor, lastLabel + 12.dp.toPx()).coerceAtMost(bottom)
                drawLine(foreground.copy(alpha = 0.5f), Offset(32.dp.toPx(), labelY), Offset(axisX, anchor), 0.6.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(timelineClock(minute), 0f, labelY + 3.dp.toPx(), paint)
                lastLabel = labelY
            }
            if (date == now.toLocalDate()) {
                val current = now.hour * 60.0 + now.minute
                if (current in start..end) drawLine(Color(0xFFE45C57), Offset(axisX - 3.dp.toPx(), y(current)), Offset(size.width, y(current)), 2.dp.toPx())
            }
        }

        // 點擊最上面設定出門時間
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.TopCenter)
                .clickable { showDepartureDialog = true }
        )

        // 點擊最下面設定回家時間
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .align(Alignment.BottomCenter)
                .clickable { showReturnHomeDialog = true }
        )
    }

    if (showDepartureDialog) {
        TimeSettingDialog(
            title = "設定出門時間（時間軸起點）",
            initialValue = departureTime,
            onDismiss = { showDepartureDialog = false },
            onConfirm = {
                onSetDepartureTime(it)
                showDepartureDialog = false
            },
            onClear = {
                onSetDepartureTime("")
                showDepartureDialog = false
            }
        )
    }

    if (showReturnHomeDialog) {
        TimeSettingDialog(
            title = "設定回家時間（時間軸終點）",
            initialValue = returnHomeTime,
            onDismiss = { showReturnHomeDialog = false },
            onConfirm = {
                onSetReturnHomeTime(it)
                showReturnHomeDialog = false
            },
            onClear = {
                onSetReturnHomeTime("")
                showReturnHomeDialog = false
            }
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
                        if (error) Text("格式錯誤，請填單一時間，例如 07:30", color = MaterialTheme.colorScheme.error)
                        else Text(supportingHint)
                    }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val match = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$").find(text.trim())
                if (match != null) {
                    val formatted = "%02d:%02d".format(match.groupValues[1].toInt(), match.groupValues[2].toInt())
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
