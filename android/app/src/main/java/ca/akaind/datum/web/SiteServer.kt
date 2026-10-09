package ca.akaind.datum.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Serves the bundled web app to the WebView from https://appassets.androidplatform.net,
 * a secure origin, so Cache Storage (region packs) and geolocation work as on the web.
 *
 * Requests the page makes to cdnjs and Google Fonts are answered from local copies
 * listed in assets/vendor/vendor.json, so the app opens offline and sends nothing to
 * a CDN. Map tiles, search and routing still go to the network.
 */
class SiteServer(context: Context) {
    private val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    /** "scheme://host/path" of a CDN file (query ignored) -> asset path. */
    private val vendor: Map<String, String> = runCatching {
        val json = JSONObject(context.assets.open("vendor/vendor.json").bufferedReader().use { it.readText() })
        json.keys().asSequence().associate { key(Uri.parse(it)) to json.getString(it) }
    }.getOrDefault(emptyMap())

    fun isLocal(url: Uri) = url.host == WebViewAssetLoader.DEFAULT_DOMAIN

    fun intercept(url: Uri): WebResourceResponse? {
        if (isLocal(url)) return loader.shouldInterceptRequest(url)?.also { fixMime(url, it) }
        val local = vendor[key(url)] ?: return null
        val res = loader.shouldInterceptRequest(Uri.parse("https://${WebViewAssetLoader.DEFAULT_DOMAIN}/$local")) ?: return null
        fixMime(Uri.parse(local), res)
        res.responseHeaders = (res.responseHeaders ?: emptyMap()) + mapOf("Access-Control-Allow-Origin" to "*")
        return res
    }

    private fun fixMime(url: Uri, res: WebResourceResponse) {
        val path = url.path ?: return
        MIME.entries.firstOrNull { path.endsWith(it.key) }?.let { res.mimeType = it.value }
    }

    companion object {
        private val MIME = mapOf(".js" to "text/javascript", ".css" to "text/css", ".json" to "application/json",
            ".woff2" to "font/woff2", ".html" to "text/html")

        fun key(u: Uri) = "${u.scheme}://${u.host}${u.path}"
    }
}
