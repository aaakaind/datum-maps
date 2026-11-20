package ca.akaind.datum

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ca.akaind.datum.web.SiteServer

/**
 * The phone app: DATUM's web app, bundled in the APK and shown full screen in a
 * WebView. The car screen (Android Auto) is a separate native app, see car/.
 */
class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var site: SiteServer
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null

    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val granted = result.values.any { it }
        geoCallback?.invoke(geoOrigin, granted, false)
        geoCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        site = SiteServer(this)

        val root = FrameLayout(this).apply { setBackgroundColor(background()) }
        web = WebView(this).apply {
            setBackgroundColor(background())
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                setGeolocationEnabled(true)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                userAgentString = "$userAgentString ${Config.USER_AGENT}"
            }
            webViewClient = Client()
            webChromeClient = Chrome()
        }
        root.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // Keep the page clear of the status bar, navigation bar, cut-outs and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) web.loadUrl(Config.SITE_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun background(): Int {
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return Color.parseColor(if (night) "#15130F" else "#F3EEE4")
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            site.intercept(request.url)

        /** The app's own pages stay in the WebView; every other link opens in the browser. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (site.isLocal(request.url)) return false
            try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: ActivityNotFoundException) {
            }
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            if (!site.isLocal(android.net.Uri.parse(origin))) return callback.invoke(origin, false, false)
            val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
                callback.invoke(origin, true, false)
            } else {
                geoOrigin = origin
                geoCallback = callback
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    }
}
