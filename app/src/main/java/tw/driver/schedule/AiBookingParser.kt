package tw.driver.schedule

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiInputException(message: String) : Exception(message)

/** No tools or actions: the model only proposes drafts that the driver must review. */
object AiBookingParser {
    private val transport = AiTransport()
    private val fields = listOf("date", "pickupTime", "customer", "contact", "passengerPhone", "contactPhone",
        "pickup", "destination", "fare", "returnTime", "notes", "calendarTime", "uncertainties")
    private val flags = listOf("tentative", "singleTrip", "timeFlexible")

    internal fun schema(): JSONObject {
        val properties = JSONObject()
        fields.forEach { properties.put(it, JSONObject().put("type", "STRING")) }
        flags.forEach { properties.put(it, JSONObject().put("type", "BOOLEAN")) }
        return JSONObject().put("type", "OBJECT").put("required", JSONArray(listOf("imageTranscript", "orders")))
            .put("properties", JSONObject()
                .put("imageTranscript", JSONObject().put("type", "STRING"))
                .put("orders", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
                    .put("properties", properties).put("required", JSONArray(fields + flags)))))
    }

    internal suspend fun extract(text: String, imagePath: String?, settings: AiSettings = AiSettings(), terms: LocationTerms = LocationTerms(), onStatus: suspend (String) -> Unit = {}): List<RideOrder> = withContext(Dispatchers.IO) {
        if (text.length > 30000) throw AiInputException("文字過長，請分批辨識（每次最多 30,000 字）。")
        if (imagePath != null) {
            val file = File(imagePath)
            if (!file.exists() || file.length() > 8 * 1024 * 1024) throw AiInputException("圖片不存在或超過 8 MB，請重新選取。")
        }
        configuredAi(settings, { onStatus("${settings.provider.label} 暫時無法使用，正在切換 ${settings.backup.label}…") }) { provider ->
            onStatus("${provider.label} 辨識中（每次最多等待 15 秒）")
            when (provider) {
                AiProvider.GEMINI -> gemini(text, imagePath, settings, terms)
                AiProvider.DEEPSEEK -> deepseek(text, imagePath, settings, terms)
                AiProvider.CUSTOM -> {
                    val prompt = analysisPrompt(settings, terms, text, imagePath != null) + "\n僅輸出以下規格的 JSON：\n${schema()}"
                    val response = CustomAiClient().extract(settings, text, prompt, imagePath)
                    decode(response, text, imagePath.orEmpty(), terms)
                }
            }
        }
    }

    private fun imageMime(path: String) = when(File(path).extension) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }

    private suspend fun gemini(text: String, imagePath: String?, settings: AiSettings, terms: LocationTerms): List<RideOrder> {
        if (!settings.geminiModel.matches(Regex("[a-zA-Z0-9.-]+"))) throw AiInputException("GEMINI_MODEL 格式不正確。")
        if (text.length > 30000) throw AiInputException("文字過長，請分批辨識（每次最多 30,000 字）。")
        val parts = JSONArray().put(JSONObject().put("text", "以下是待辨識的預約資料：\n$text"))
        if (imagePath != null) {
            val file = File(imagePath)
            if (!file.exists() || file.length() > 8 * 1024 * 1024) throw AiInputException("圖片不存在或超過 8 MB，請重新選取。")
            val mime = when(file.extension) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))))
        }
        val body = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", analysisPrompt(settings, terms, text, imagePath != null)))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema()))
            val response = JSONObject(transport.post("https://generativelanguage.googleapis.com/v1beta/models/${settings.geminiModel}:generateContent",
                "x-goog-api-key", settings.geminiKey, body.toString(), "Gemini"))
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiInputException("AI 未回傳辨識結果，請更換文字或圖片重試。")
            if (candidate.optString("finishReason") != "STOP") throw AiInputException("AI 結果不完整或被服務阻擋；請縮短內容重試，尚未建立訂單。")
            val resultParts = candidate.getJSONObject("content").getJSONArray("parts")
            val resultText = (0 until resultParts.length()).map { resultParts.getJSONObject(it) }
                .filterNot { it.optBoolean("thought") }.joinToString("") { it.optString("text") }
            return decode(resultText, text, imagePath.orEmpty(), terms)
    }

    private suspend fun deepseek(text: String, imagePath: String?, settings: AiSettings, terms: LocationTerms): List<RideOrder> {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", "待辨識預約原文：\n$text"))
        imagePath?.let { path ->
            val data = Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
            content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                .put("url", "data:${imageMime(path)};base64,$data").put("detail", "high")))
        }
        val body = deepseekRequest(if (imagePath == null) text else content, settings, analysisPrompt(settings, terms, text, imagePath != null))
        val response = JSONObject(transport.post("https://api.deepseek.com/chat/completions", "Authorization",
            "Bearer ${settings.deepseekKey}", body.toString(), "DeepSeek"))
        return decodeDeepseek(response, text, imagePath.orEmpty(), terms)
    }

    internal fun deepseekRequest(content: Any, settings: AiSettings = AiSettings(), prompt: String = settings.prompt): JSONObject = JSONObject()
        .put("model", settings.deepseekModel)
        .put("thinking", JSONObject().put("type", "disabled"))
        .put("stream", false).put("max_tokens", 8192)
        .put("response_format", JSONObject().put("type", "json_object"))
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "$prompt\n請輸出 JSON，完整符合以下欄位規格（不得省略欄位）：\n${schema()}"))
            .put(JSONObject().put("role", "user").put("content", content)))

    internal fun decodeDeepseek(response: JSONObject, text: String, imagePath: String, terms: LocationTerms = LocationTerms()): List<RideOrder> {
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw AiInputException("DeepSeek 未回傳結果，請重試。")
        if (choice.optString("finish_reason") != "stop") throw AiInputException("DeepSeek 結果不完整，請分批辨識；尚未建立訂單。")
        return decode(choice.getJSONObject("message").getString("content"), text, imagePath, terms)
    }

    internal fun decode(json: String, original: String, imagePath: String = "", terms: LocationTerms = LocationTerms()): List<RideOrder> {
        val root = JSONObject(json)
        val orders = root.getJSONArray("orders")
        require(orders.length() <= 30) { "Too many orders" }
        return (0 until orders.length()).flatMap { index ->
            val item = orders.getJSONObject(index)
            fields.forEach { require(item.get(it) is String) }
            flags.forEach { require(item.get(it) is Boolean) }
            val date = bookingDate(item.getString("date"))
            val warnings = reminders(cleanWheelchairWarning(item.getString("uncertainties")))
            val outbound = RideOrder(date = date, pickupTime = item.getString("pickupTime").ifBlank { "時間待確認" },
                customer = item.getString("customer").ifBlank { "乘客姓名待確認" }, contact = item.getString("contact").ifBlank { "聯絡人待確認" },
                pickup = terms.preserveSource(item.getString("pickup"), original + "\n" + root.getString("imageTranscript")).ifBlank { "上車地點待確認" }, destination = terms.preserveSource(item.getString("destination"), original + "\n" + root.getString("imageTranscript")).ifBlank { "下車地點待確認" },
                wheelchair = true, wheelchairUnknown = false, notes = item.getString("notes"), fare = extractFare(item.getString("fare")), bookingId = java.util.UUID.randomUUID().toString(),
                raw = original, imageTranscript = root.getString("imageTranscript"), sourceImage = imagePath,
                contactPhone = item.getString("contactPhone"), passengerPhone = item.getString("passengerPhone"),
                uncertainties = warnings, tentative = item.getBoolean("tentative"),
                returnRide = false, timeFlexible = item.getBoolean("timeFlexible"),
                calendarTime = item.getString("calendarTime"), needsAddressCheck = true)
            expandBooking(outbound, item.getBoolean("singleTrip"), item.getString("returnTime"))
        }
    }
}
