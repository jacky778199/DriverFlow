package tw.driver.schedule

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.semantics.Role
import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

internal enum class TodayJumpSide { LEFT, RIGHT, NONE }

internal fun todayJumpSide(date: LocalDate, today: LocalDate): TodayJumpSide = when {
    date > today -> TodayJumpSide.LEFT
    date < today -> TodayJumpSide.RIGHT
    else -> TodayJumpSide.NONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun DayNavigation(date: LocalDate, onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier, calendarEnabled: Boolean = false) {
    var showingCalendar by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val side = todayJumpSide(date, today)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(88.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onSelect(date.minusDays(1)) },
                modifier = Modifier.width(36.dp), contentPadding = PaddingValues(0.dp)) { Text("◀") }
            if (side == TodayJumpSide.LEFT) TextButton(onClick = { onSelect(LocalDate.now()) },
                modifier = Modifier.width(52.dp), contentPadding = PaddingValues(0.dp)) {
                Text("到今天", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text(formatDateWithWeekday(date), modifier = Modifier.weight(1f).then(
            if (calendarEnabled) Modifier.clickable(onClickLabel = "開啟月曆選日期", role = Role.Button) { showingCalendar = true } else Modifier),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.width(88.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End) {
            if (side == TodayJumpSide.RIGHT) TextButton(onClick = { onSelect(LocalDate.now()) },
                modifier = Modifier.width(52.dp), contentPadding = PaddingValues(0.dp)) {
                Text("到今天", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { onSelect(date.plusDays(1)) },
                modifier = Modifier.width(36.dp), contentPadding = PaddingValues(0.dp)) { Text("▶") }
        }
    }
    if (showingCalendar) {
        // Material calendar dates are UTC midnight, independent of the phone timezone.
        val picker = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showingCalendar = false },
            confirmButton = {
                TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                    picker.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    showingCalendar = false
                }) { Text("確定") }
            }, dismissButton = { TextButton(onClick = { showingCalendar = false }) { Text("取消") } }) {
            DatePicker(state = picker)
        }
    }

}
