package ca.akaind.datum

import android.Manifest
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.location.Location
import android.media.ImageReader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import ca.akaind.datum.car.map.SurfaceMap
import ca.akaind.datum.routing.ValhallaRouting
import ca.akaind.datum.search.Place
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs on a device or emulator. Saves screenshots to the app's external files
 * folder (Android/data/ca.akaind.datum/files/shots) for review.
 */
@RunWith(AndroidJUnit4::class)
class DeviceTests {
    @get:Rule
    val location: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val shots = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun save(bmp: Bitmap, name: String) = File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun onMain(block: () -> Unit) = inst.runOnMainSync(block)

    // ------------------------------------------------------------------ phone app

    private fun findWebView(v: View): WebView? = when (v) {
        is WebView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findWebView(v.getChildAt(it)) }
        else -> null
    }

    private fun js(web: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var out = ""
        onMain { web.evaluateJavascript(script) { out = it; latch.countDown() } }
        latch.await(30, TimeUnit.SECONDS)
        return out
    }

    private fun waitJs(web: WebView, condition: String, timeoutS: Int): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutS * 1000L
        while (SystemClock.elapsedRealtime() < end) {
            if (js(web, "(function(){try{return !!($condition)}catch(e){return false}})()") == "true") return true
            SystemClock.sleep(1000)
        }
        return false
    }

    @Test fun phoneAppRunsTheBundledWebApp() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var web: WebView? = null
            scenario.onActivity { web = findWebView(it.window.decorView) }
            val w = requireNotNull(web)
            // Opens offline: the page, MapLibre, d3, the fonts and the world map all come from the APK.
            assertTrue("web app booted", waitJs(w, "window.__datum && window.__datum.worldReady", 240))
            assertTrue("fonts loaded", waitJs(w, "document.fonts.check('700 20px Jost')", 60))
            assertTrue(js(w, "location.origin").contains("appassets.androidplatform.net"))
            SystemClock.sleep(3000)
            save(inst.uiAutomation.takeScreenshot(), "phone-world")

            // Then the street map, which needs the network for tiles.
            js(w, "window.__datum.hideWorld(); window.__datum.map.jumpTo({center:[-75.6995,45.4236], zoom:15.2}); 1")
            val tiles = waitJs(w, "window.__datum.map.loaded() && window.__datum.map.areTilesLoaded()", 300)
            SystemClock.sleep(4000)
            save(inst.uiAutomation.takeScreenshot(), if (tiles) "phone-map" else "phone-map-no-network")
        }
    }

    // ------------------------------------------------------------------ car map

    private fun renderCar(name: String, width: Int, height: Int, dpi: Int, setup: (SurfaceMap) -> Unit) {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val map = SurfaceMap(ctx)
        onMain {
            map.attach(reader.surface, width, height, dpi)
            setup(map)
        }
        val styled = waitFor(120) { map.isReady }
        assertTrue("car map style loaded", styled)
        val idle = CountDownLatch(1)
        onMain { setup(map); map.onIdle { idle.countDown() } }
        idle.await(180, TimeUnit.SECONDS)
        SystemClock.sleep(2500)
        val img = requireNotNull(reader.acquireLatestImage()) { "no frame" }
        val plane = img.planes[0]
        val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        img.close()
        save(Bitmap.createBitmap(bmp, 0, 0, width, height), name)
        onMain { map.detach() }
        reader.close()
    }

    private fun waitFor(seconds: Int, cond: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + seconds * 1000L
        while (SystemClock.elapsedRealtime() < end) {
            var ok = false
            onMain { ok = cond() }
            if (ok) return true
            SystemClock.sleep(500)
        }
        return false
    }

    private val routes by lazy {
        ValhallaRouting.parse(inst.context.assets.open("valhalla_auto_kanata.json").bufferedReader().use { it.readText() })
    }
    private val kanata = Place("Kanata Centrum", "Kanata, Ottawa", 45.3087, -75.9139, "shop")

    @Test fun carMapRoutePreview() = renderCar("car-route-preview", 1280, 720, 160) { m ->
        m.setDarkMode(false)
        m.setVisibleArea(Rect(430, 70, 1180, 700))
        m.showDestination(kanata)
        m.showRoutes(routes[0], routes.drop(1), fit = true)
    }

    @Test fun carMapNavigatingAtNight() = renderCar("car-navigating-dark", 1280, 720, 160) { m ->
        m.setDarkMode(true)
        m.setVisibleArea(Rect(430, 70, 1180, 700))
        m.showDestination(kanata)
        m.showRoutes(routes[0], emptyList(), fit = false)
        val p = routes[0].shape[110]
        val q = routes[0].shape[111]
        m.setLocation(Location("test").apply {
            latitude = p.lat; longitude = p.lon; accuracy = 5f; speed = 14f
            bearing = ca.akaind.datum.geo.Geo.bearing(p, q).toFloat()
            time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        m.setNavigating(true)
    }
}
