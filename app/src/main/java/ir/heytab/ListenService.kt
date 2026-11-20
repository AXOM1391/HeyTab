package ir.heytab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.Executors

/**
 * سرویسی که همیشه گوش میده. مراحل:
 * 1) WAKE:    منتظر کلمه‌ی بیدارباش («هی تب»)
 * 2) COMMAND: بعد از بوق، دستور رو گوش میده
 * 3) BUSY:    دستور رو اجرا می‌کنه و جواب میده
 */
class ListenService : Service() {

    companion object {
        const val ACTION_STOP = "ir.heytab.STOP"
        const val WAKE_PHRASE = "هی تب"   // برای عوض کردن کلمه‌ی بیدارباش اینجا رو عوض کن
        private const val CHANNEL_ID = "heytab_listen"
        private const val SAMPLE_RATE = 16000
        private const val COMMAND_TIMEOUT_MS = 10_000L

        @Volatile var running = false
        @Volatile var onLog: (() -> Unit)? = null
        private val history = ArrayDeque<String>()

        fun log(line: String) {
            synchronized(history) {
                history.addFirst(line)
                while (history.size > 8) history.removeLast()
            }
            onLog?.invoke()
        }

        fun historyText(): String = synchronized(history) { history.joinToString("\n") }
    }

    private enum class State { WAKE, COMMAND, BUSY }

    @Volatile private var state = State.WAKE
    @Volatile private var stopFlag = false
    @Volatile private var speaking = false
    @Volatile private var mutedUntil = 0L
    @Volatile private var needWakeReset = false

    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speaker: Speaker? = null
    private var commands: CommandHandler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        createChannel()
        try {
            startForeground(
                1,
                buildNotification("در حال گوش دادن… بگو «$WAKE_PHRASE»"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            log("خطا در شروع سرویس: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        stopFlag = false

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "heytab:listen").apply { acquire() }

        val sp = Speaker(this) { speaking = it }
        speaker = sp
        commands = CommandHandler(this) { reply ->
            log("جواب: $reply")
            if (!sp.persianAvailable) {
                mainHandler.post { Toast.makeText(this, reply, Toast.LENGTH_LONG).show() }
            }
            sp.speak(reply)
        }

        worker = Thread { listenLoop() }.also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopFlag = true
        worker?.join(2000)
        executor.shutdownNow()
        speaker?.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        log("متوقف شد")
        super.onDestroy()
    }

    // ---------- حلقه‌ی گوش دادن ----------

    private fun listenLoop() {
        val modelDir = File(filesDir, "model")
        if (!File(modelDir, "am").exists()) {
            log("مدل فارسی پیدا نشد. اول از توی اپ فایل مدل رو انتخاب کن")
            stopSelf()
            return
        }

        var model: Model? = null
        var wakeRec: Recognizer? = null
        var cmdRec: Recognizer? = null
        var audio: AudioRecord? = null

        try {
            log("در حال بارگذاری مدل…")
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            model = Model(modelDir.absolutePath)
            wakeRec = Recognizer(model, SAMPLE_RATE.toFloat(), "[\"$WAKE_PHRASE\", \"[unk]\"]")
            cmdRec = Recognizer(model, SAMPLE_RATE.toFloat())

            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audio = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 8192)
            )
            audio.startRecording()
            log("آماده‌ام. بگو «$WAKE_PHRASE»")

            val buf = ShortArray(2048)
            var deadline = 0L

            while (!stopFlag) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                val now = System.currentTimeMillis()

                when (state) {
                    State.WAKE -> {
                        if (needWakeReset) {
                            wakeRec.reset()
                            needWakeReset = false
                        }
                        if (speaking || now < mutedUntil) continue

                        val isFinal = wakeRec.acceptWaveForm(buf, n)
                        val heard = if (isFinal) {
                            JSONObject(wakeRec.result).optString("text")
                        } else {
                            JSONObject(wakeRec.partialResult).optString("partial")
                        }
                        if (heard.contains(WAKE_PHRASE)) {
                            wakeRec.reset()
                            cmdRec.reset()
                            speaker?.beep()
                            log("بیدار شدم، بگو…")
                            deadline = now + COMMAND_TIMEOUT_MS
                            state = State.COMMAND
                        }
                    }

                    State.COMMAND -> {
                        if (cmdRec.acceptWaveForm(buf, n)) {
                            process(JSONObject(cmdRec.result).optString("text"))
                        } else if (now > deadline) {
                            val text = JSONObject(cmdRec.finalResult).optString("text")
                            if (text.isBlank()) {
                                log("چیزی نشنیدم")
                                needWakeReset = true
                                state = State.WAKE
                            } else {
                                process(text)
                            }
                        }
                    }

                    State.BUSY -> {
                        // صدای میکروفون در حین جواب دادن دور ریخته میشه
                    }
                }
            }
        } catch (e: Exception) {
            log("خطا: ${e.message}")
        } finally {
            try { audio?.stop() } catch (e: Exception) { /* مهم نیست */ }
            audio?.release()
            wakeRec?.close()
            cmdRec?.close()
            model?.close()
        }
    }

    private fun process(text: String) {
        state = State.BUSY
        log("شنیدم: $text")
        executor.execute {
            try {
                commands?.handle(text)
                val end = System.currentTimeMillis() + 20_000
                while (speaking && System.currentTimeMillis() < end) Thread.sleep(100)
            } catch (e: Exception) {
                log("خطا: ${e.message}")
            }
            mutedUntil = System.currentTimeMillis() + 600
            needWakeReset = true
            state = State.WAKE
        }
    }

    // ---------- اعلان ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "هی تب", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("هی تب")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "توقف", stop)
            .build()
    }
}
