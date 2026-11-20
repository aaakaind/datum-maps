package ca.akaind.datum.car.nav

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.routing.Route

/**
 * Drives along a route at the speeds Valhalla expects, one fix a second. Runs when
 * the car host asks for auto drive (adb shell dumpsys activity service
 * ca.akaind.datum/.car.DatumCarAppService AUTO_DRIVE), for testing on the Desktop
 * Head Unit without moving.
 */
class DriveSimulator(private val route: Route, private val onFix: (Location) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var along = 0.0
    private val cum = DoubleArray(route.shape.size).also { c ->
        for (i in 1 until route.shape.size) c[i] = c[i - 1] + Geo.distance(route.shape[i - 1], route.shape[i])
    }
    private val tick = object : Runnable {
        override fun run() {
            val speed = speedAt(along)
            along += speed
            val (pos, bearing) = at(along)
            onFix(Location("datum-sim").apply {
                latitude = pos.lat
                longitude = pos.lon
                this.speed = speed.toFloat()
                this.bearing = bearing.toFloat()
                accuracy = 5f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            if (along < cum.last()) handler.postDelayed(this, 1000)
        }
    }

    fun start() = handler.post(tick)

    fun stop() = handler.removeCallbacks(tick)

    private fun speedAt(d: Double): Double {
        val m = route.maneuvers.firstOrNull { cum[it.endShape.coerceAtMost(cum.lastIndex)] > d } ?: return 8.0
        return if (m.timeS > 0 && m.lengthM > 0) (m.lengthM / m.timeS).coerceIn(4.0, 33.0) else 12.0
    }

    private fun at(d: Double): Pair<LatLon, Double> {
        val s = route.shape
        var i = cum.indexOfFirst { it > d } - 1
        if (i < 0) i = s.size - 2
        val segLen = cum[i + 1] - cum[i]
        val t = if (segLen > 0) ((d - cum[i]) / segLen).coerceIn(0.0, 1.0) else 1.0
        return Geo.lerp(s[i], s[i + 1], t) to Geo.bearing(s[i], s[i + 1])
    }
}
