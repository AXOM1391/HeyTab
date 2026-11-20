package ir.heytab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * اتصال اختیاری به هر سرویس هوش مصنوعی که API سازگار با فرمت
 * «chat/completions» داشته باشه. آدرس، کلید و اسم مدل از توی اپ تنظیم میشه.
 * اگه تنظیم نشده باشه، دستیار فقط دستورهای محلی رو انجام میده.
 */
class LlmClient(private val ctx: Context) {

    private val prefs get() = ctx.getSharedPreferences("heytab", Context.MODE_PRIVATE)

    val configured: Boolean
        get() = !prefs.getString("llm_url", "").isNullOrBlank() &&
            !prefs.getString("llm_key", "").isNullOrBlank() &&
            !prefs.getString("llm_model", "").isNullOrBlank()

    fun ask(question: String): String? {
        val base = prefs.getString("llm_url", "")!!.trim().trimEnd('/')
        val key = prefs.getString("llm_key", "")!!.trim()
        val model = prefs.getString("llm_model", "")!!.trim()

        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", 300)
            put(
                "messages",
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", "تو یه دستیار صوتی فارسی‌زبانی. جواب‌ها رو خودمونی و کوتاه (حداکثر دو سه جمله) بده.")
                    )
                    put(JSONObject().put("role", "user").put("content", question))
                }
            )
        }

        return try {
            val conn = URL("$base/chat/completions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            if (conn.responseCode !in 200..299) return null
            val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val content = JSONObject(resp)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
            if (content.length > 400) content.take(400) else content
        } catch (e: Exception) {
            null
        }
    }
}
