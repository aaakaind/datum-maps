package ca.akaind.datum

import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.geo.Units
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.roundToLong

class GeoTest {
    private fun encode(points: List<LatLon>): String {
        val sb = StringBuilder()
        var plat = 0L
        var plon = 0L
        fun put(v: Long) {
            var x = if (v < 0) (v shl 1).inv() else v shl 1
            while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }
            sb.append((x + 63).toInt().toChar())
        }
        for (p in points) {
            val lat = (p.lat * 1e6).roundToLong()
            val lon = (p.lon * 1e6).roundToLong()
            put(lat - plat); put(lon - plon)
            plat = lat; plon = lon
        }
        return sb.toString()
    }

    @After fun metric() { Units.imperial = false }

    @Test fun decodesPrecision6Polylines() {
        val pts = listOf(LatLon(45.423630, -75.700900), LatLon(45.398310, -75.683620), LatLon(-33.9249, 18.4241), LatLon(0.0, 0.0))
        val back = Geo.decodePolyline6(encode(pts))
        assertEquals(pts.size, back.size)
        pts.zip(back).forEach { (a, b) ->
            assertEquals(a.lat, b.lat, 1e-6)
            assertEquals(a.lon, b.lon, 1e-6)
        }
    }

    @Test fun distanceAndBearing() {
        val ottawa = LatLon(45.4215, -75.6972)
        val montreal = LatLon(45.5017, -73.5673)
        assertEquals(166_500.0, Geo.distance(ottawa, montreal), 1500.0)
        assertEquals(87.0, Geo.bearing(ottawa, montreal), 2.0)
    }

    @Test fun turnAngles() {
        assertEquals(90.0, Geo.turnAngle(0.0, 90.0), 1e-9)
        assertEquals(-90.0, Geo.turnAngle(10.0, 280.0), 1e-9)
        assertEquals(-91.0, Geo.turnAngle(239.0, 148.0), 1e-9)
        assertEquals(20.0, Geo.turnAngle(350.0, 10.0), 1e-9)
    }

    @Test fun projectsOntoSegments() {
        val a = LatLon(45.0, -75.0)
        val b = LatLon(45.0, -74.99)
        val (t, d) = Geo.project(LatLon(45.0001, -74.995), a, b)
        assertEquals(0.5, t, 0.01)
        assertEquals(11.06, d, 0.2)
        assertEquals(0.0, Geo.project(LatLon(45.0, -75.01), a, b).first, 1e-9)
    }

    @Test fun metricRounding() {
        Units.imperial = false
        assertEquals("40 m", Units.distance(37.0))
        assertEquals("400 m", Units.distance(420.0))
        assertEquals("1.2 km", Units.distance(1234.0))
        assertEquals("23 km", Units.distance(23_387.0))
        assertEquals("22 min", Units.duration(1349.0))
        assertEquals("1 h 05 min", Units.duration(3900.0))
    }

    @Test fun imperialRounding() {
        Units.imperial = true
        assertEquals("350 ft", Units.distance(100.0))
        assertEquals("3.1 mi", Units.distance(5000.0))
        assertEquals("15 mi", Units.distance(24_000.0))
    }
}
