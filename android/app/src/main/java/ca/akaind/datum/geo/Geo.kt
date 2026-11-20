package ca.akaind.datum.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

object Geo {
    private const val R_EARTH = 6371008.8
    private const val RAD = PI / 180

    /** Great-circle distance in metres. */
    fun distance(a: LatLon, b: LatLon): Double {
        val t1 = a.lat * RAD
        val t2 = b.lat * RAD
        val dt = t2 - t1
        val dl = (b.lon - a.lon) * RAD
        val h = sin(dt / 2).let { it * it } + cos(t1) * cos(t2) * sin(dl / 2).let { it * it }
        return 2 * R_EARTH * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing from a to b, degrees clockwise from north, 0 until 360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val t1 = a.lat * RAD
        val t2 = b.lat * RAD
        val dl = (b.lon - a.lon) * RAD
        val y = sin(dl) * cos(t2)
        val x = cos(t1) * sin(t2) - sin(t1) * cos(t2) * cos(dl)
        return (atan2(y, x) / RAD + 360) % 360
    }

    /** Signed smallest difference b - a between two bearings, -180 until 180. */
    fun turnAngle(before: Double, after: Double): Double = ((after - before + 540) % 360) - 180

    /** Decodes a Valhalla polyline (precision 6). */
    fun decodePolyline6(s: String): List<LatLon> {
        val out = ArrayList<LatLon>(s.length / 4)
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < s.length) {
            for (which in 0..1) {
                var shift = 0
                var result = 0L
                var b: Int
                do {
                    b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (which == 0) lat += d else lon += d
            }
            out += LatLon(lat / 1e6, lon / 1e6)
        }
        return out
    }

    /**
     * Projects p onto the segment a-b on a local flat approximation, which is
     * accurate to centimetres over the few hundred metres a route segment spans.
     * Returns the fraction along the segment (0..1) and the distance in metres.
     */
    fun project(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        val kx = cos(p.lat * RAD) * 111_320.0
        val ky = 110_574.0
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val bx = (b.lon - p.lon) * kx
        val by = (b.lat - p.lat) * ky
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return t to sqrt(cx * cx + cy * cy)
    }

    fun lerp(a: LatLon, b: LatLon, t: Double) = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}
