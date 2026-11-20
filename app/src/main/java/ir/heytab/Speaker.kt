package ir.heytab

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * گوینده: از موتور TTS خود اندروید استفاده می‌کنه.
 * اگه فارسی روی تبلت نصب نباشه، فقط بوق می‌زنه و متن جواب توی اپ نشون داده میشه.
 */
class Speaker(context: Context, private val onSpeakingChanged: (Boolean) -> Unit) {

    private var tts: TextToSpeech? = null
    @Volatile var ready = false
    @Volatile var persianAvailable = false
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 80)
    } catch (e: RuntimeException) {
        null
    }

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("fa", "IR"))
                persianAvailable = r != null &&
                    r != TextToSpeech.LANG_MISSING_DATA &&
                    r != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = onSpeakingChanged(true)
                    override fun onDone(utteranceId: String?) = onSpeakingChanged(false)

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = onSpeakingChanged(false)
                })
                ready = true
            }
        }
    }

    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    fun speak(text: String) {
        if (ready && persianAvailable) {
            onSpeakingChanged(true)
            val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
            if (r != TextToSpeech.SUCCESS) onSpeakingChanged(false)
        } else {
            beep()
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tone?.release()
    }
}
