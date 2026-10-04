package tw.driver.schedule

import android.util.Base64
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A Chat Completions compatible endpoint, without provider-specific extensions. */
internal class CustomAiClient(private val transport: AiTransport = AiTransport()) {
    internal fun request(settings: AiSettings, text: String, prompt: String, imagePath: String? = null): JSONObject {
        val content: Any = if (imagePath == null) text else {
            val file = File(imagePath)
            require(file.exists() && file.length() <= 8 * 1024 * 1024) { "圖片不存在或超過 8 MB" }
            val mime = when (file.extension.lowercase()) { "png" -> "image/png"; "webp" -> "image/webp"; else -> "image/jpeg" }
            JSONArray().put(JSONObject().put("type", "text").put("text", text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                    .put("url", "data:$mime;base64,${Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)}")))
        }
        return JSONObject().put("model", settings.customModel).put("stream", false).put("max_tokens", 8192)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", prompt))
                .put(JSONObject().put("role", "user").put("content", content)))
    }
    suspend fun extract(settings: AiSettings, text: String, prompt: String, imagePath: String? = null): String {
        val response = JSONObject(transport.post(settings.customUrl.trim(), "Authorization", "Bearer ${settings.customKey}",
            request(settings, text, prompt, imagePath).toString(), "自訂 AI"))
        val choice = response.optJSONArray("choices")?.optJSONObject(0) ?: throw AiInputException("自訂 AI 回應需符合 Chat Completions 格式（choices/message/content）。")
        if (choice.optString("finish_reason") != "stop") throw AiInputException("自訂 AI 回應未完整完成，請確認模型與輸出長度後重試。")
        return choice.getJSONObject("message").getString("content")
    }
}
