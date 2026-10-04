package tw.driver.schedule

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun screenshotPreview(image: ServerScreenshot): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 20_000_000) { "截圖圖片損壞或尺寸過大，請重新取得" }
    require(bounds.outWidth == image.width && bounds.outHeight == image.height) { "截圖尺寸與伺服器回報不符，請重新取得" }
    val options = BitmapFactory.Options().apply {
        inSampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 2400) inSampleSize *= 2
    }
    return BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size, options)
        ?: error("無法顯示截圖，請重新取得")
}

@Composable internal fun MessageScreenshotDialog(source: String, onDismiss: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var dimensions by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var scale by remember(source, revision) { mutableFloatStateOf(1f) }
    var offset by remember(source, revision) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(source, revision) {
        busy = true; preview = null; error = ""; dimensions = ""
        try {
            val image = MessageGateway.screenshot(source)
            preview = withContext(Dispatchers.IO) { screenshotPreview(image) }
            dimensions = "${image.width} × ${image.height} · ${image.format.uppercase()}"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "無法取得截圖，請稍後重試" }
        finally { busy = false }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("查看截圖", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("關閉") }
                }
                Text("伺服器目前畫面 · 雙指縮放，放大後可拖曳查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(preview) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 5f)
                        offset = (offset + pan).let { value ->
                            Offset(value.x.coerceIn(-size.width * (next - 1) / 2, size.width * (next - 1) / 2),
                                value.y.coerceIn(-size.height * (next - 1) / 2, size.height * (next - 1) / 2))
                        }
                        scale = next
                    }
                }, contentAlignment = Alignment.Center) {
                    when {
                        busy -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("正在向伺服器取得截圖…")
                        }
                        error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                        else -> preview?.let { bitmap ->
                            Image(bitmap.asImageBitmap(), "伺服器目前截圖", modifier = Modifier.fillMaxSize().graphicsLayer {
                                scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                            })
                        }
                    }
                }
                if (preview != null) {
                    Text(dimensions, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); offset = Offset.Zero }, enabled = scale > 1f) { Text("縮小") }
                        Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { scale = (scale * 1.5f).coerceAtMost(5f) }, enabled = scale < 5f) { Text("放大") }
                        TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("重設") }
                    }
                }
                OutlinedButton(onClick = { revision++ }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (busy) "取得中…" else if (error.isNotBlank()) "重試取得截圖" else "重新取得截圖")
                }
            }
        }
    }
}
