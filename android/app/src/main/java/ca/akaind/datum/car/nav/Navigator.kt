package ca.akaind.datum.car.nav

import ca.akaind.datum.Datum
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.routing.Route
import ca.akaind.datum.routing.RouteProgress
import ca.akaind.datum.routing.RouteService
import ca.akaind.datum.search.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** A position fix, independent of android.location so the logic is easy to test. */
data class Fix(val at: LatLon, val speedMps: Double? = null, val bearing: Double? = null, val accuracyM: Double = 10.0)

/**
 * Turn-by-turn guidance for one trip: tracks progress along the route, speaks each
 * instruction at the right distance, reroutes when the car leaves the route, and
 * notices arrival. UI-free; NavigationScreen draws what it publishes.
 */
class Navigator(
    val destination: Place,
    route: Route,
    private val scope: CoroutineScope,
    private val voice: Voice,
    private val routing: RouteService = Datum.routing,
    private val onUpdate: (State) -> Unit,
) {
    data class State(
        val route: Route,
        val snapshot: RouteProgress.Snapshot? = null,
        val rerouting: Boolean = false,
        val arrived: Boolean = false,
        val error: String? = null,
        /** Bumped each time the route is replaced, so the map knows to redraw it. */
        val routeVersion: Int = 0,
    )

    private var progress = RouteProgress(route)
    private val spoken = HashSet<String>()
    private var rerouteJob: Job? = null
    private var lastRerouteAt = 0L

    var state = State(route)
        private set

    fun start() {
        val first = progress.route.maneuvers.firstOrNull()
        first?.let { say("pre:0", it.verbalPre ?: it.instruction) }
        publish(state)
    }

    fun onFix(fix: Fix) {
        if (state.arrived) return
        val snap = progress.update(fix.at, fix.accuracyM)
        if (snap.offRoute) {
            val version = state.routeVersion
            reroute(fix)
            // Unless the new route already arrived, show the old position as rerouting.
            if (state.routeVersion == version) publish(state.copy(snapshot = snap, rerouting = rerouteJob?.isActive == true))
            return
        }
        guide(snap, fix.speedMps ?: 0.0)
        if (snap.arrived) {
            val last = progress.route.maneuvers.lastOrNull()
            say("pre:${progress.route.maneuvers.lastIndex}", last?.verbalPre ?: "You have arrived at your destination.")
            publish(state.copy(snapshot = snap, arrived = true, rerouting = false))
            return
        }
        publish(state.copy(snapshot = snap, rerouting = rerouteJob?.isActive == true))
    }

    /** Speak the next instruction early enough to act on it: an alert well ahead, then the instruction itself. */
    private fun guide(snap: RouteProgress.Snapshot, speed: Double) {
        val i = snap.stepIndex
        val m = progress.route.maneuvers.getOrNull(i) ?: return
        val preAt = max(if (m.isArrival) 40.0 else 70.0, speed * 9)
        val alertAt = when {
            speed > 22 -> 1200.0
            speed > 12 -> 500.0
            else -> 220.0
        }
        when {
            snap.toStep <= preAt -> {
                spoken += "alert:$i"
                say("pre:$i", m.verbalPre ?: m.instruction)
            }
            snap.toStep <= alertAt && snap.toStep > preAt * 1.8 && m.verbalAlert != null && !m.isArrival ->
                say("alert:$i", m.verbalAlert)
        }
    }

    private fun reroute(fix: Fix) {
        if (rerouteJob?.isActive == true || Datum.now() - lastRerouteAt < 8_000) return
        lastRerouteAt = Datum.now()
        rerouteJob = scope.launch {
            try {
                val r = withContext(Datum.io) {
                    routing.route(fix.at, destination.latLon, progress.route.costing, alternates = 0, heading = fix.bearing)
                }.first()
                progress = RouteProgress(r)
                spoken.clear()
                spoken += "pre:0"
                publish(State(r, rerouting = false, routeVersion = state.routeVersion + 1))
            } catch (e: Exception) {
                publish(state.copy(rerouting = false, error = e.message))
            }
        }
    }

    private fun say(key: String, text: String) {
        if (spoken.add(key)) voice.say(text)
    }

    private fun publish(s: State) {
        state = s
        onUpdate(s)
    }
}
