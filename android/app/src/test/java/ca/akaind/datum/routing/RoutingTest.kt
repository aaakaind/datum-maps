package ca.akaind.datum.routing

import androidx.car.app.navigation.model.Maneuver as CarManeuver
import ca.akaind.datum.R
import ca.akaind.datum.car.Maneuvers
import ca.akaind.datum.fixture
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.kanataRoutes
import ca.akaind.datum.ottawaRoutes
import ca.akaind.datum.search.PhotonSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    @Test fun parsesValhallaRoutesWithAlternates() {
        val ottawa = ottawaRoutes()
        assertEquals(1, ottawa.size)
        val r = ottawa[0]
        assertEquals(5, r.maneuvers.size)
        assertEquals(3274.0, r.lengthM, 1.0)
        assertEquals(r.maneuvers.last().endShape + 1, r.shape.size)
        assertEquals(listOf("Parliament Road", "chemin Parliament"), r.maneuvers[1].streetNames)
        assertEquals("Turn left onto Parliament Road, chemin Parliament.", r.maneuvers[1].verbalPre)
        assertEquals("Parliament Road", r.via)

        val kanata = kanataRoutes()
        assertEquals(2, kanata.size)
        assertEquals(8, kanata[0].maneuvers.size)
        assertEquals("417", kanata[0].via)
        assertTrue(kanata[1].timeS > kanata[0].timeS)
        // The route really ends where we asked to go.
        assertTrue(Geo.distance(kanata[0].destination, LatLon(45.3087, -75.9139)) < 150)
    }

    @Test fun mapsManeuversForTheCarScreen() {
        val ms = kanataRoutes()[0].maneuvers
        assertEquals(CarManeuver.TYPE_DEPART to R.drawable.ic_mnv_depart, Maneuvers.map(ms[0]))
        assertEquals(CarManeuver.TYPE_TURN_NORMAL_LEFT, Maneuvers.map(ms[1]).first)
        assertEquals(CarManeuver.TYPE_TURN_NORMAL_RIGHT, Maneuvers.map(ms[2]).first)
        // "Turn left to take the 417 West ramp": 91° to the left.
        assertEquals(CarManeuver.TYPE_ON_RAMP_NORMAL_LEFT to R.drawable.ic_mnv_turn_left, Maneuvers.map(ms[3]))
        // "Take exit 139": a gentle exit to the right.
        assertEquals(CarManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT to R.drawable.ic_mnv_slight_right, Maneuvers.map(ms[4]))
        assertEquals(CarManeuver.TYPE_DESTINATION, Maneuvers.map(ms[7]).first)
        assertEquals(CarManeuver.TYPE_DESTINATION_LEFT, Maneuvers.map(ottawaRoutes()[0].maneuvers.last()).first)
        val roundabout = ms[1].copy(type = 26, roundaboutExit = 2)
        assertEquals(CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, Maneuvers.map(roundabout).first)
    }

    @Test fun followsTheRouteToTheEnd() {
        val route = kanataRoutes()[0]
        val p = RouteProgress(route)
        var lastStep = 0
        var lastRemaining = Double.MAX_VALUE
        val steps = mutableListOf<Int>()
        // Drive the route shape, one point in three, as GPS fixes.
        for (i in route.shape.indices step 3) {
            val s = p.update(route.shape[i])
            assertFalse("off route at point $i", s.offRoute)
            assertTrue("step went backwards at $i", s.stepIndex >= lastStep)
            assertTrue(s.remainingM <= lastRemaining + 1)
            assertTrue("snapped ${s.offsetM} m away at $i (step ${s.stepIndex}, along ${s.along})", s.offsetM < 1.0)
            lastStep = s.stepIndex
            lastRemaining = s.remainingM
            steps += s.stepIndex
        }
        val end = p.update(route.shape.last())
        assertTrue(end.arrived)
        assertEquals(route.maneuvers.lastIndex, end.stepIndex)
        // Every maneuver came up as "next" on the way.
        assertEquals((1..route.maneuvers.lastIndex).toSet(), steps.toSet() + end.stepIndex)
    }

    @Test fun distanceToTheNextTurnShrinks() {
        val route = ottawaRoutes()[0]
        val p = RouteProgress(route)
        val start = p.update(route.shape[20])
        assertEquals(2, start.stepIndex) // driving down Parliament Road, Exhibition Way next
        val later = p.update(route.shape[120])
        assertEquals(2, later.stepIndex)
        assertTrue(later.toStep < start.toStep)
        assertEquals(start.remainingM - later.remainingM, later.along - start.along, 1.0)
        assertTrue(start.remainingS > later.remainingS)
    }

    @Test fun noticesLeavingTheRoute() {
        val route = ottawaRoutes()[0]
        val p = RouteProgress(route)
        val on = route.shape[60]
        assertFalse(p.update(on).offRoute)
        // 20 m to the side is GPS noise, not a wrong turn.
        assertFalse(p.update(LatLon(on.lat, on.lon + 0.00025)).offRoute)
        // 300 m away for three fixes in a row is a wrong turn.
        val away = LatLon(on.lat, on.lon + 0.004)
        assertFalse(p.update(away).offRoute)
        assertFalse(p.update(away).offRoute)
        assertTrue(p.update(away).offRoute)
        assertFalse(p.update(on).offRoute)
    }

    @Test fun parsesPhotonResults() {
        val places = PhotonSearch.parse(fixture("photon_lansdowne.json"))
        assertEquals("Lansdowne Park", places[0].name)
        assertTrue(places[0].address.contains("Ottawa"))
        assertEquals(45.3991688, places[0].lat, 1e-7)
        assertEquals("rec", places[1].group)
        assertEquals("street", places.first { it.name == "Lansdowne Road South" }.group)
    }
}
