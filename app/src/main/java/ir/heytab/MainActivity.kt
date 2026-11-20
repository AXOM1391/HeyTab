package ir.heytab

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var toggleBtn: Button
    private val ui = Handler(Looper.getMainLooper())

    private val reqModel = 100
    private val reqPerm = 101

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, sizeSp: Float = 16f) = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun field(hint: String, value: String, password: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setSingleLine()
        inputType = if (password) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        layoutDirection = View.LAYOUT_DIRECTION_LTR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("heytab", Context.MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(label("هی تب", 28f))
        statusView = label("")
        root.addView(statusView)

        root.addView(label("مرحله ۱: مدل تشخیص گفتار فارسی", 18f))
        root.addView(button("انتخاب فایل مدل فارسی (zip)") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
            startActivityForResult(i, reqModel)
        })

        root.addView(label("مرحله ۲: مجوزها", 18f))
        root.addView(button("مجوز میکروفون و اعلان") {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), reqPerm)
        })
        root.addView(button("مجوز نمایش روی برنامه‌های دیگه (برای باز کردن اپ‌ها)") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("خاموش کردن بهینه‌سازی باتری برای هی تب") {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        })

        root.addView(label("مرحله ۳: شروع", 18f))
        toggleBtn = button("شروع گوش دادن") { toggleService() }
        root.addView(toggleBtn)

        root.addView(label("آخرین رویدادها", 18f))
        logView = label("", 14f)
        root.addView(logView)

        root.addView(label("اختیاری: اتصال به سرویس هوش مصنوعی", 18f))
        root.addView(label("برای جواب دادن به سوال‌های آزاد. هر سرویسی که API با فرمت chat/completions داشته باشه.", 13f))
        val urlField = field("آدرس پایه (مثلا https://example.com/v1)", prefs.getString("llm_url", "") ?: "")
        val keyField = field("کلید API", prefs.getString("llm_key", "") ?: "", password = true)
        val modelField = field("اسم مدل", prefs.getString("llm_model", "") ?: "")
        root.addView(urlField)
        root.addView(keyField)
        root.addView(modelField)
        root.addView(button("ذخیره‌ی تنظیمات") {
            prefs.edit()
                .putString("llm_url", urlField.text.toString().trim())
                .putString("llm_key", keyField.text.toString().trim())
                .putString("llm_model", modelField.text.toString().trim())
                .apply()
            Toast.makeText(this, "ذخیره شد", Toast.LENGTH_SHORT).show()
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        ListenService.onLog = { runOnUiThread { logView.text = ListenService.historyText() } }
        logView.text = ListenService.historyText()
        refreshUi()
    }

    override fun onPause() {
        ListenService.onLog = null
        super.onPause()
    }

    private fun hasModel() = File(filesDir, "model/am").exists()
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun refreshUi() {
        fun mark(ok: Boolean) = if (ok) "✅" else "❌"
        statusView.text = buildString {
            appendLine("${mark(hasModel())} مدل فارسی")
            appendLine("${mark(hasMic())} مجوز میکروفون")
            appendLine("${mark(Settings.canDrawOverlays(this@MainActivity))} مجوز باز کردن برنامه‌ها")
            append(if (ListenService.running) "🟢 در حال گوش دادن: بگو «${ListenService.WAKE_PHRASE}»" else "⚪ خاموش")
        }
        toggleBtn.text = if (ListenService.running) "توقف" else "شروع گوش دادن"
    }

    private fun toggleService() {
        if (ListenService.running) {
            startService(Intent(this, ListenService::class.java).setAction(ListenService.ACTION_STOP))
        } else {
            if (!hasModel()) {
                Toast.makeText(this, "اول فایل مدل فارسی رو انتخاب کن", Toast.LENGTH_LONG).show()
                return
            }
            if (!hasMic()) {
                Toast.makeText(this, "اول مجوز میکروفون رو بده", Toast.LENGTH_LONG).show()
                return
            }
            startForegroundService(Intent(this, ListenService::class.java))
        }
        ui.postDelayed({ refreshUi() }, 700)
        ui.postDelayed({ refreshUi() }, 2500)
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqModel && resultCode == RESULT_OK) {
            data?.data?.let { installModel(it) }
        }
    }

    /** فایل zip مدل رو باز می‌کنه و توی حافظه‌ی داخلی اپ می‌ذاره */
    private fun installModel(uri: Uri) {
        statusView.text = "در حال باز کردن مدل… چند دقیقه صبر کن"
        Thread {
            var error: String? = null
            try {
                val target = File(filesDir, "model")
                target.deleteRecursively()
                target.mkdirs()
                val base = target.canonicalPath + File.separator

                contentResolver.openInputStream(uri)!!.use { raw ->
                    ZipInputStream(raw.buffered()).use { zin ->
                        var entry = zin.nextEntry
                        while (entry != null) {
                            var name = entry.name
                            val first = name.substringBefore('/')
                            if (name.contains('/') && first.startsWith("vosk-model")) {
                                name = name.substringAfter('/')
                            }
                            if (name.isNotEmpty()) {
                                val out = File(target, name)
                                if (!out.canonicalPath.startsWith(base)) {
                                    throw SecurityException("مسیر نامعتبر توی فایل zip")
                                }
                                if (entry.isDirectory) {
                                    out.mkdirs()
                                } else {
                                    out.parentFile?.mkdirs()
                                    out.outputStream().use { zin.copyTo(it) }
                                }
                            }
                            zin.closeEntry()
                            entry = zin.nextEntry
                        }
                    }
                }
                if (!File(target, "am").exists()) error = "این فایل شبیه مدل Vosk نیست"
            } catch (e: Exception) {
                error = e.message ?: "خطای نامشخص"
            }
            runOnUiThread {
                refreshUi()
                Toast.makeText(
                    this,
                    if (error == null) "مدل نصب شد" else "خطا: $error",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}
