package tw.driver.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceCallHelper(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale.TAIWAN)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.setLanguage(Locale.CHINESE)
                    }
                    isReady = true
                }
            }
        } catch (_: Exception) {
            isReady = false
        }
    }

    fun speakAndCall(customerName: String, rawPhone: String, scope: CoroutineScope) {
        val cleanPhone = rawPhone.filter { it.isDigit() || it == '+' }
        if (cleanPhone.isBlank()) {
            Toast.makeText(context, "未提供電話號碼", Toast.LENGTH_SHORT).show()
            return
        }

        val textToSpeak = "打給 $customerName"
        var callLaunched = false

        fun launchCall() {
            if (callLaunched) return
            callLaunched = true
            scope.launch(Dispatchers.Main) {
                try {
                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanPhone")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(context, "無法開啟電話撥號應用程式", Toast.LENGTH_SHORT).show()
                }
            }
        }

        if (isReady && tts != null) {
            val utteranceId = "call_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) {
                    launchCall()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) {
                    launchCall()
                }
            })
            val speakRes = tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (speakRes != TextToSpeech.SUCCESS) {
                launchCall()
            } else {
                // 超時備案：若 TTS 2.5 秒內未完成回報，直接跳轉撥號，避免司機等待
                scope.launch(Dispatchers.Main) {
                    delay(2500)
                    launchCall()
                }
            }
        } else {
            launchCall()
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}
