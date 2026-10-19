package ca.akaind.datum.car

import ca.akaind.datum.Datum
import ca.akaind.datum.FakeRouting
import ca.akaind.datum.FakeVoice
import ca.akaind.datum.car.nav.Fix
import ca.akaind.datum.car.nav.Navigator
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.kanataRoutes
import ca.akaind.datum.ottawaRoutes
import ca.akaind.datum.search.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigatorTest {
    private val lansdowne = Place("Lansdowne", "Ottawa", 45.39831, -75.68362)

    @Before fun setUp() {
        Datum.io = Dispatchers.Unconfined
        var t = 1_000_000L
        Datum.now = { t += 10_000; t }
    }

    private fun drive(nav: Navigator, shape: List<LatLon>, speed: Double, every: Int = 2) {
        for (i in shape.indices step every) {
            val bearing = if (i + 1 < shape.size) Geo.bearing(shape[i], shape[i + 1]) else null
            nav.onFix(Fix(shape[i], speed, bearing))
        }
        nav.onFix(Fix(shape.last(), 0.0))
    }

    @Test fun speaksEachInstructionOnceInOrderAndArrives() {
        val route = ottawaRoutes()[0]
        val voice = FakeVoice()
        val states = mutableListOf<Navigator.State>()
        val nav = Navigator(lansdowne, route, TestScope(UnconfinedTestDispatcher()), voice, FakeRouting(emptyList())) { states += it }
        nav.start()
        drive(nav, route.shape, speed = 11.0)

        assertEquals("Drive south.", voice.said.first())
        val pres = route.maneuvers.drop(1).map { it.verbalPre!! }
        // Each instruction is spoken exactly once, in route order.
        assertEquals(pres, voice.said.filter { it in pres })
        assertEquals(voice.said.size, voice.said.toSet().size)
        assertTrue(states.last().arrived)
        assertEquals("Your destination is on the left.", voice.said.last())
    }

    @Test fun alertsWellBeforeAHighwayExit() {
        val route = kanataRoutes()[0]
        val voice = FakeVoice()
        val nav = Navigator(lansdowne, route, TestScope(UnconfinedTestDispatcher()), voice, FakeRouting(emptyList())) { }
        nav.start()
        drive(nav, route.shape, speed = 27.0, every = 1)
        val exit = route.maneuvers[4]
        val alert = voice.said.indexOf(exit.verbalAlert)
        val pre = voice.said.indexOf(exit.verbalPre)
        assertTrue("alert for the exit was spoken", alert >= 0)
        assertTrue(alert < pre)
    }

    @Test fun reroutesFromWhereTheCarIsHeading() {
        val route = ottawaRoutes()[0]
        val detour = kanataRoutes()[0]
        val routing = FakeRouting(listOf(detour))
        val states = mutableListOf<Navigator.State>()
        val scope = TestScope(StandardTestDispatcher())
        val nav = Navigator(lansdowne, route, scope, FakeVoice(), routing) { states += it }
        nav.start()
        val on = route.shape[60]
        nav.onFix(Fix(on, 12.0, 180.0))
        val away = LatLon(on.lat, on.lon + 0.004)
        repeat(3) { nav.onFix(Fix(away, 12.0, 95.0)) }
        assertTrue(nav.state.rerouting)
        scope.testScheduler.advanceUntilIdle()

        assertEquals(1, routing.calls.size)
        assertEquals(away, routing.calls[0].from)
        assertEquals(lansdowne.latLon, routing.calls[0].to)
        assertEquals(95.0, routing.calls[0].heading!!, 1e-9)
        assertTrue(states.any { it.rerouting })
        assertEquals(detour, nav.state.route)
        assertEquals(1, nav.state.routeVersion)
        assertTrue(!nav.state.rerouting)
    }
}
