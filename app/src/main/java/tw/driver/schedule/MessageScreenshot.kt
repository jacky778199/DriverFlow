package tw.driver.schedule

import org.json.JSONObject
import java.util.Base64

/** Kept in memory only; no generated toString containing the image or screenshot contents. */
class ServerScreenshot(val bytes: ByteArray, val format: String, val width: Int, val height: Int)

internal object ScreenshotProtocol {
    private const val MAX_BYTES = 8 * 1024 * 1024
    fun request(id: String): String = JSONObject().put("action", "screenshot").put("request_id", id)
        .put("format", "jpeg").put("quality", 80).toString()

    fun result(json: JSONObject): LineFlowEvent.ScreenshotResult {
        val id = json.optString("request_id")
        if (json.optString("status") != "success") {
            val error = if (json.isNull("error_message")) "" else json.optString("error_message").take(300)
            return LineFlowEvent.ScreenshotResult(id, null, error.ifBlank { "伺服器無法取得截圖，請稍後重試" })
        }
        return try {
            val format = json.optString("format", "jpeg").lowercase()
            require(format in listOf("jpeg", "png")) { "伺服器回傳不支援的截圖格式" }
            val width = json.optInt("width"); val height = json.optInt("height")
            require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 20_000_000) { "截圖尺寸無效或過大" }
            val encoded = json.optString("image_base64").takeIf { it.isNotBlank() } ?: run {
                val uri = json.optString("data_uri")
                val prefix = "data:image/$format;base64,"
                require(uri.startsWith(prefix)) { "伺服器未提供截圖圖片" }
                uri.removePrefix(prefix)
            }
            require(encoded.length <= (MAX_BYTES * 4 / 3) + 4096) { "截圖檔案超過 8 MB" }
            val bytes = try { Base64.getDecoder().decode(encoded.replace(Regex("\\s"), "")) }
                catch (_: IllegalArgumentException) { throw IllegalArgumentException("截圖圖片編碼無效") }
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "截圖圖片為空或超過 8 MB" }
            val jpeg = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val png = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(pngHeader)
            require(if (format == "jpeg") jpeg else png) { "截圖內容與圖片格式不符" }
            LineFlowEvent.ScreenshotResult(id, ServerScreenshot(bytes, format, width, height), "")
        } catch (e: Exception) {
            LineFlowEvent.ScreenshotResult(id, null, (e.message ?: "截圖格式無法解析").take(300))
        }
    }
}
