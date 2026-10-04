package tw.driver.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable internal fun DayEndpointDialog(
    date: String,
    departure: Boolean,
    initialTime: String,
    initialPoint: String,
    initialOdometer: String,
    suggestedOdometer: String,
    counterpartOdometer: String,
    onDismiss: () -> Unit,
    onSave: (time: String, point: String, odometer: String) -> Boolean
) {
    var time by remember(date, departure) { mutableStateOf(initialTime) }
    var point by remember(date, departure) { mutableStateOf(initialPoint) }
    var odometer by remember(date, departure) { mutableStateOf(initialOdometer.ifBlank { suggestedOdometer }) }
    var error by remember(date, departure) { mutableStateOf("") }
    val autoFilled = initialOdometer.isBlank() && suggestedOdometer.isNotBlank()
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (departure) "設定出門時間（$date）" else "設定回家時間（$date）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("時間、地點與里程皆可選填。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(time, { time = it; error = "" }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (departure) "出門時間 HH:mm" else "回家時間 HH:mm") },
                    singleLine = true)
                OutlinedTextField(point, { point = it; error = "" }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (departure) "當日起點" else "當日終點") }, singleLine = true)
                OutlinedTextField(odometer, { odometer = it; error = "" }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (departure) "出發里程表 km" else "結束里程表 km") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = if (autoFilled) {{ Text(if (departure) "由前一日結束里程帶入" else "由後一日出發里程帶入") }} else null)
                val draft = if (departure) DailyJourney(startPoint = point.trim(), startOdometer = odometer.trim(), endOdometer = counterpartOdometer)
                    else DailyJourney(endPoint = point.trim(), startOdometer = counterpartOdometer, endOdometer = odometer.trim())
                draft.distanceKm()?.let { Text("當日行駛 $it km") }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = {
            val normalizedTime = if (time.isBlank()) "" else normalizeTime(time) ?: run {
                error = "請輸入有效時間，例如 07:30 或 0730"
                return@TextButton
            }
            val draft = if (departure) DailyJourney(startPoint = point.trim(), startOdometer = odometer.trim(), endOdometer = counterpartOdometer)
                else DailyJourney(endPoint = point.trim(), startOdometer = counterpartOdometer, endOdometer = odometer.trim())
            error = draft.error().orEmpty()
            if (error.isEmpty()) {
                if (onSave(normalizedTime, point.trim(), odometer.trim())) onDismiss()
                else error = "儲存失敗，請重試"
            }
        }) { Text("儲存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
