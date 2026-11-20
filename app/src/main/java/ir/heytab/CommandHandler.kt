package ir.heytab

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import java.net.URLEncoder
import java.util.Calendar

/**
 * اینجا متن گفته‌شده تبدیل به کار میشه.
 * برای اضافه کردن دستور جدید، یه شرط به تابع handle اضافه کن.
 */
class CommandHandler(private val ctx: Context, private val say: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val timers = mutableListOf<Runnable>()
    private val llm = LlmClient(ctx)

    // اسم فارسی برنامه -> اسم پکیج‌های ممکن (اولین نصب‌شده باز میشه)
    private val aliases: Map<String, List<String>> = linkedMapOf(
        "یوتیوب" to listOf("com.google.android.youtube"),
        "کروم" to listOf("com.android.chrome"),
        "مرورگر" to listOf("com.sec.android.app.sbrowser", "com.android.chrome"),
        "دوربین" to listOf("camera"),
        "تنظیمات" to listOf("settings"),
        "گالری" to listOf("com.sec.android.gallery3d", "com.google.android.apps.photos"),
        "ماشین حساب" to listOf("com.sec.android.app.popupcalculator", "com.google.android.calculator"),
        "تقویم" to listOf("com.samsung.android.calendar", "com.google.android.calendar"),
        "تلگرام" to listOf("org.telegram.messenger"),
        "اینستاگرام" to listOf("com.instagram.android"),
        "واتساپ" to listOf("com.whatsapp"),
        "نقشه" to listOf("com.google.android.apps.maps"),
        "فایل" to listOf("com.sec.android.app.myfiles", "com.google.android.apps.nbu.files")
    )

    private val numberWords: Map<String, Long> = mapOf(
        "صفر" to 0, "یک" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5,
        "شش" to 6, "شیش" to 6, "هفت" to 7, "هشت" to 8, "نه" to 9, "ده" to 10,
        "یازده" to 11, "دوازده" to 12, "سیزده" to 13, "چهارده" to 14,
        "پانزده" to 15, "پونزده" to 15, "شانزده" to 16, "شونزده" to 16,
        "هفده" to 17, "هجده" to 18, "نوزده" to 19,
        "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50, "شصت" to 60,
        "هفتاد" to 70, "هشتاد" to 80, "نود" to 90,
        "صد" to 100, "یکصد" to 100, "دویست" to 200, "سیصد" to 300,
        "چهارصد" to 400, "پانصد" to 500, "ششصد" to 600, "هفتصد" to 700,
        "هشتصد" to 800, "نهصد" to 900
    )

    fun handle(raw: String) {
        val text = normalize(raw)
        val tokens = text.split(" ").filter { it.isNotEmpty() }

        when {
            has(text, "تایمر", "زمان سنج", "زمانسنج") && has(text, "لغو", "کنسل", "قطع", "نذار") -> cancelTimers()
            has(text, "تایمر", "زمان سنج", "زمانسنج") -> startTimer(text, tokens)
            has(text, "چه کارا", "چه کار", "کمک", "راهنما") && has(text, "بلدی", "میتونی", "می تونی", "کمک", "راهنما") -> help()
            text.contains("صدا") && has(text, "بیشتر", "زیاد", "بلند", "بالا", "کم", "آروم", "آهسته", "پایین", "قطع", "ساکت", "بی صدا") -> volume(text)
            has(text, "باز کن", "اجرا کن", "باز کنید", "بیار بالا") -> openApp(text)
            has(text, "جستجو", "سرچ", "گوگل کن") -> search(text)
            has(text, "تاریخ", "امروز چندم", "چندمه", "چه روزی", "چه روزیه") -> date()
            text.contains("ساعت") && has(text, "چند", "کیه") -> time()
            text.isBlank() -> say("نشنیدم، دوباره بگو")
            else -> fallback(raw)
        }
    }

    // ---------- دستورها ----------

    private fun time() {
        val c = Calendar.getInstance()
        val h = c.get(Calendar.HOUR_OF_DAY)
        val m = c.get(Calendar.MINUTE)
        say(if (m == 0) "ساعت $h است" else "ساعت $h و $m دقیقه است")
    }

    private fun date() {
        val c = Calendar.getInstance()
        val (jy, jm, jd) = Jalali.fromGregorian(
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
        say("امروز ${Jalali.weekdayName(c.get(Calendar.DAY_OF_WEEK))} $jd ${Jalali.months[jm - 1]} $jy است")
    }

    private fun startTimer(text: String, tokens: List<String>) {
        val n = extractNumber(tokens)
        val unit = when {
            text.contains("ثانیه") -> 1L
            text.contains("دقیقه") -> 60L
            text.contains("ساعت") -> 3600L
            else -> null
        }
        val seconds: Long? = when {
            text.contains("نیم ساعت") -> 1800L
            text.contains("ربع ساعت") || text.contains("یک ربع") -> 900L
            n != null && unit != null -> n * unit
            else -> null
        }
        if (seconds == null || seconds <= 0) {
            say("مدت تایمر رو متوجه نشدم. مثلا بگو «تایمر پنج دقیقه»")
            return
        }
        val r = Runnable {
            say("تایمر تموم شد")
            ring()
        }
        synchronized(timers) { timers.add(r) }
        main.postDelayed(r, seconds * 1000)
        say("باشه، تایمر ${describe(seconds)} شروع شد")
    }

    private fun cancelTimers() {
        synchronized(timers) {
            timers.forEach { main.removeCallbacks(it) }
            timers.clear()
        }
        say("تایمرها لغو شد")
    }

    private fun ring() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val tone = RingtoneManager.getRingtone(ctx, uri)
            tone.play()
            main.postDelayed({ tone.stop() }, 5000)
        } catch (e: Exception) {
            // مهم نیست
        }
    }

    private fun volume(text: String) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when {
            has(text, "بی صدا", "ساکت", "قطع") -> {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                say("صدا قطع شد")
            }
            has(text, "بیشتر", "زیاد", "بلند", "بالا") -> {
                repeat(2) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                say("صدا بیشتر شد")
            }
            else -> {
                repeat(2) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
                say("صدا کم شد")
            }
        }
    }

    private fun openApp(text: String) {
        if (!Settings.canDrawOverlays(ctx)) {
            say("برای باز کردن برنامه‌ها باید مجوز نمایش روی برنامه‌های دیگه رو توی اپ هی تب فعال کنی")
            return
        }
        val pm = ctx.packageManager
        var intent: Intent? = null

        val alias = aliases.entries.firstOrNull { text.contains(it.key) }
        if (alias != null) {
            for (target in alias.value) {
                intent = when (target) {
                    "camera" -> Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                    "settings" -> Intent(Settings.ACTION_SETTINGS)
                    else -> pm.getLaunchIntentForPackage(target)
                }
                if (intent != null) break
            }
        } else {
            // اگه اسم برنامه انگلیسی باشه و توی متن بیاد (مثلا «اسپاتیفای»)، این بخش کاری نمی‌کنه؛
            // ولی اگه اسم لیبل برنامه فارسی باشه پیداش می‌کنه.
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
                val label = it.loadLabel(pm).toString().lowercase()
                label.length >= 3 && text.contains(label)
            }
            if (match != null) intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
        }

        if (intent == null) {
            say("این برنامه رو پیدا نکردم")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            say("باز شد")
        } catch (e: Exception) {
            say("نتونستم باز کنم")
        }
    }

    private fun search(text: String) {
        if (!Settings.canDrawOverlays(ctx)) {
            say("برای جستجو باید مجوز نمایش روی برنامه‌های دیگه رو توی اپ هی تب فعال کنی")
            return
        }
        val q = text.replace(Regex("جستجو|سرچ|گوگل|بکن|کن"), " ").replace(Regex("\\s+"), " ").trim()
        if (q.isEmpty()) {
            say("چی رو جستجو کنم؟")
            return
        }
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            say("دارم $q رو جستجو می‌کنم")
        } catch (e: Exception) {
            say("نتونستم مرورگر رو باز کنم")
        }
    }

    private fun help() {
        say("می‌تونم ساعت و تاریخ رو بگم، تایمر بذارم، صدا رو کم و زیاد کنم، برنامه‌ها رو باز کنم و توی گوگل جستجو کنم")
    }

    private fun fallback(raw: String) {
        if (llm.configured) {
            val answer = llm.ask(raw)
            say(answer ?: "نتونستم به سرویس هوش مصنوعی وصل بشم")
        } else {
            say("متوجه نشدم. بگو «چه کارایی بلدی» تا دستورهام رو بگم")
        }
    }

    // ---------- ابزارهای کمکی ----------

    private fun has(text: String, vararg words: String) = words.any { text.contains(it) }

    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(
                when (ch) {
                    'ي' -> 'ی'
                    'ك' -> 'ک'
                    '‌' -> ' '
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    '.', ',', '!', '?', '؟', '،', '«', '»' -> ' '
                    else -> ch
                }
            )
        }
        return sb.toString().lowercase().replace(Regex("\\s+"), " ").trim()
    }

    /** اولین عدد توی جمله رو پیدا می‌کنه: «بیست و پنج» -> 25 */
    private fun extractNumber(tokens: List<String>): Long? {
        var total = 0L
        var found = false
        for (t in tokens) {
            val v = numberWords[t] ?: t.toLongOrNull()
            if (v != null) {
                total += v
                found = true
            } else if (t == "و" && found) {
                continue
            } else if (found) {
                break
            }
        }
        return if (found) total else null
    }

    private fun describe(seconds: Long): String = when {
        seconds % 3600 == 0L -> "${seconds / 3600} ساعته"
        seconds % 60 == 0L -> "${seconds / 60} دقیقه‌ای"
        else -> "$seconds ثانیه‌ای"
    }
}
