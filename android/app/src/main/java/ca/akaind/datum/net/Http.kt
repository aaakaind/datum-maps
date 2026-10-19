package ca.akaind.datum.net

import ca.akaind.datum.Config
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class HttpException(val code: Int, val body: String) : IOException("HTTP $code")

/** Small blocking HTTP client for JSON APIs. Call from a background dispatcher. */
object Http {
    fun get(url: String): String = request(url, null)

    fun postJson(url: String, json: String): String = request(url, json)

    fun query(vararg params: Pair<String, Any?>): String = params
        .filter { it.second != null }
        .joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), "UTF-8") }

    private fun request(url: String, body: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", Config.USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            c.disconnect()
        }
    }
}
