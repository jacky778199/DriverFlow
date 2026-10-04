package tw.driver.schedule

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal class AiHttpException(val status: Int, provider: String) : IOException("$provider 回傳 HTTP $status")

/** callTimeout bounds the whole call, even if the server sends keep-alive whitespace. */
internal class AiTransport(private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(5, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    .callTimeout(15, TimeUnit.SECONDS)
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()) {
    suspend fun post(url: String, header: String, credential: String, body: String, provider: String): String =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url(url).header(header, credential)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            if (!it.isSuccessful) throw AiHttpException(it.code, provider)
                            it.body?.string() ?: throw IOException("Empty response")
                        }
                        continuation.resume(value)
                    } catch (e: Exception) { continuation.resumeWithException(e) }
                }
            })
        }
}

internal object AiFailover {
    fun eligible(error: Exception): Boolean = when(error) {
        is AiHttpException -> error.status == 408 || error.status == 429 || error.status in 500..599
        is IOException -> true
        else -> false
    }

    suspend fun <T> run(primary: (suspend () -> T)?, backup: (suspend () -> T)?,
                        onFallback: suspend () -> Unit): T {
        if (primary == null) return backup?.invoke() ?: throw AiInputException("請先到設定 → Tab 1 填寫 API key 並儲存。")
        try { return primary() }
        catch (e: Exception) {
            if (!eligible(e) || backup == null) throw e
            onFallback()
            return backup()
        }
    }
}
