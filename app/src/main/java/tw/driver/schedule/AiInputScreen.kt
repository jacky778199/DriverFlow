package tw.driver.schedule

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class AiInputViewModel : ViewModel() {
    var text by mutableStateOf("")
    var imagePath by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var status by mutableStateOf(""); private set
    var drafts by mutableStateOf<List<RideOrder>>(emptyList()); private set
    private var work: Job? = null

    fun importImage(context: Context, uri: Uri) {
        if (busy) return
        work = viewModelScope.launch {
            busy = true; error = null; status = "讀取圖片中…"
            try {
                imagePath = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8 * 1024 * 1024 + 1)
                        var total = 0
                        while (total < buffer.size) {
                            val n = input.read(buffer, total, buffer.size - total)
                            if (n < 0) break
                            total += n
                        }
                        if (total > 8 * 1024 * 1024) throw AiInputException("圖片需小於 8 MB，請先裁切預約區域。")
                        buffer.copyOf(total)
                    } ?: throw AiInputException("無法開啟圖片，請重新選取。")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val extension = when(bounds.outMimeType) { "image/png" -> "png"; "image/jpeg" -> "jpg"; "image/webp" -> "webp"; else -> throw AiInputException("請選擇 JPEG、PNG 或 WebP 圖片。") }
                    val folder = File(context.filesDir, "booking_sources").apply { mkdirs() }
                    File(folder, "${UUID.randomUUID()}.$extension").apply { writeBytes(bytes) }.absolutePath
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? AiInputException)?.message ?: "圖片讀取失敗，請重新選擇。" }
            finally { busy = false }
        }
    }

    fun recognize(context: Context) {
        if (busy || drafts.isNotEmpty() || (text.isBlank() && imagePath == null)) return
        work = viewModelScope.launch {
            busy = true; error = null; status = "準備辨識…"
            try {
                val settings = withContext(Dispatchers.IO) { AiSettingsStore(context).load() }
                val terms = withContext(Dispatchers.IO) { LocationTermsStore(context).load() }
                drafts = AiBookingParser.extract(text, imagePath, settings, terms) { message ->
                    withContext(Dispatchers.Main) { status = message }
                }
                if (drafts.isEmpty()) error = "沒有找到可辨識的接送預約。請補上文字說明或更清楚的圖片。"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = when(e) {
                is AiInputException -> e.message
                is AiHttpException -> when(e.status) {
                    400 -> "${e.message}：請檢查模型名稱、模型是否支援圖片及提示詞長度。"
                    401, 403 -> "${e.message}：請檢查該服務的 Key 與權限。"
                    402 -> "${e.message}：請檢查帳戶餘額。"
                    429 -> "${e.message}：用量或頻率已達上限。"
                    else -> "${e.message}。備援未設定或也無法完成，請稍後重試。"
                }
                is java.net.SocketTimeoutException -> "辨識逾時，請稍後重試；尚未儲存任何訂單。"
                is java.io.InterruptedIOException -> "辨識已超過等待上限，請稍後重試；尚未儲存任何訂單。"
                is java.io.IOException -> "無法連線至 AI 服務，請檢查網路後重試。"
                else -> "AI 回傳格式無法完整解析，請重試或手動輸入；尚未儲存訂單。"
            } }
            finally { busy = false }
        }
    }
    fun clearAll() {
        val previous = work
        previous?.cancel()
        work = viewModelScope.launch {
            previous?.join()
            text = ""; imagePath = null; drafts = emptyList()
            error = null; status = ""; busy = false
        }
    }
    fun removeDraft(id: Long) { drafts = remainingDrafts(drafts, id) }
    fun ordersToSave(edited: RideOrder): List<RideOrder> = pairedOrdersToSave(drafts, edited)
    fun skipBooking(draft: RideOrder) { removeDraft(draft.id) }
}

@Composable fun SourceImage(path: String) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 1600) inSampleSize *= 2
            }
            BitmapFactory.decodeFile(path, options)
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), "預約原始圖片（預覽）", Modifier.fillMaxWidth().heightIn(max = 300.dp)) }
}

@Composable fun AiInputScreen(vm: AiInputViewModel, onEdit: (RideOrder) -> Unit, onManual: () -> Unit, onSamples: () -> Unit, onSettings: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = LocalClipboardManager.current
    val aiAccess by produceState(false to "讀取 AI 設定中…") {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val settings = AiSettingsStore(context).load()
                settings.ready to if (settings.ready) "使用 ${settings.provider.label} · ${settings.model(settings.provider)}" else "尚未設定 ${settings.provider.label} API key，請先到設定 → Tab 1 填寫並儲存。"
            }.getOrDefault(false to "無法讀取 AI 設定，請到設定 → Tab 1 重新儲存。")
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importImage(context.applicationContext, uri)
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("AI 辨識接送預約", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearAll, enabled = vm.busy || vm.text.isNotEmpty() || vm.imagePath != null || vm.drafts.isNotEmpty() || vm.error != null) { Text("清空全部") }
            }
        }
        item { Text("貼上文字或選擇預約截圖。全車輪椅接送；除明確單程外，自動產生去回程，儲存一次建立兩張單。") }
        item { OutlinedTextField(vm.text, { vm.text = it }, enabled = !vm.busy && vm.drafts.isEmpty(), label = { Text("預約原文／圖片補充說明") }, minLines = 4, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedButton(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp")) }, enabled = !vm.busy && vm.drafts.isEmpty()) { Text("選擇圖片／截圖") } }
        vm.imagePath?.let { path -> item {
            SourceImage(path)
            TextButton(onClick = { vm.imagePath = null }, enabled = !vm.busy && vm.drafts.isEmpty()) { Text("移除此圖片") }
        } }
        item { Text("按下辨識會將文字與圖片傳送至 Tab 1 所設定的 AI 供應商。每家服務最多等待 15 秒；啟用備援時最多約 30 秒另加圖片處理時間。請比對原文，確認儲存後才加入排程。", style = MaterialTheme.typography.bodySmall) }
        item { Text(aiAccess.second, style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = onSettings, enabled = !vm.busy) { Text("設定 AI 供應商、模型與 API key") } }
        item { Button(onClick = { vm.recognize(context.applicationContext) }, enabled = aiAccess.first && !vm.busy && vm.drafts.isEmpty() && (vm.text.isNotBlank() || vm.imagePath != null), modifier = Modifier.fillMaxWidth()) { Text(if(vm.busy) "正在處理…" else "傳送並使用 AI 辨識") } }
        if(vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(vm.status) }
        vm.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (vm.drafts.isNotEmpty()) item { Text("${vm.drafts.size} 筆待確認（尚未加入排程）") }
        items(vm.drafts, key = { it.id }) { draft ->
            ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                val summary = "${bookingDate(draft.date)} ${draft.pickupTime} · ${if(draft.returnRide) "回程" else "去程"}${if(draft.tentative) "（暫定）" else ""}\n乘客：${draft.customer}\n聯絡人：${draft.contact}\n${draft.pickup} → ${draft.destination}\n費用：${draft.fare.ifBlank { "未提供" }}\n${reminders(draft.notes, draft.uncertainties)}"
                SelectableText(summary)
                LocationTermComment("${draft.pickup}\n${draft.destination}")
                if (draft.imageTranscript.isNotBlank()) SelectableText("辨識文字：\n${draft.imageTranscript}")
                Row { TextButton(onClick = { onEdit(draft) }) { Text("確認／修改") }; TextButton(onClick = { vm.skipBooking(draft) }) { Text("略過此張") } }
                TextButton(onClick = { clipboard.setText(AnnotatedString(summary + if(draft.imageTranscript.isNotBlank()) "\n${draft.imageTranscript}" else "")) }) { Text("複製辨識結果") }
            } }
        }
        item { TextButton(onClick = onManual, enabled = !vm.busy) { Text("手動輸入（不使用 AI）") } }
        item { OutlinedButton(onClick = onSamples, enabled = !vm.busy) { Text("載入匿名範例（加入排程）") } }
    }
}
