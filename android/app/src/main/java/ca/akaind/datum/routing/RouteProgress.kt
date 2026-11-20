package ca.akaind.datum.routing

import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import kotlin.math.max

/**
 * Follows a car along a route: snaps each GPS fix to the route line, works out the
 * next maneuver and the distance and time left, and notices when the car has left
 * the route or arrived.
 */
class RouteProgress(val route: Route) {
    data class Snapshot(
        val snapped: LatLon,
        /** Metres travelled along the route. */
        val along: Double,
        /** Index into route.maneuvers of the next maneuver to perform. */
        val stepIndex: Int,
        /** Metres to that maneuver. */
        val toStep: Double,
        val remainingM: Double,
        val remainingS: Double,
        /** Distance from the route line, metres. */
        val offsetM: Double,
        val offRoute: Boolean,
        val arrived: Boolean,
        /** Bearing of the road at the snapped point. */
        val roadBearing: Double,
    )

    private val shape = route.shape
    private val cum = DoubleArray(shape.size)
    val totalM: Double
    private var seg = 0
    private var offStreak = 0

    init {
        for (i in 1 until shape.size) cum[i] = cum[i - 1] + Geo.distance(shape[i - 1], shape[i])
        totalM = cum.lastOrNull() ?: 0.0
    }

    fun update(p: LatLon, accuracyM: Double = 10.0): Snapshot {
        if (shape.size < 2) return Snapshot(p, 0.0, route.maneuvers.lastIndex, 0.0, 0.0, 0.0, 0.0, false, true, 0.0)

        // Look near where the car was: a little behind, and ahead by 1.5 km or 30
        // segments, so a road that passes close by later in the route does not
        // capture the fix.
        var best = seg
        var bestT = 0.0
        var bestD = Double.MAX_VALUE
        var i = max(0, seg - 3)
        while (i < shape.size - 1 && (cum[i] - cum[seg] < 1500 || i < seg + 30)) {
            val (t, d) = Geo.project(p, shape[i], shape[i + 1])
            if (d < bestD) { best = i; bestT = t; bestD = d }
            i++
        }
        // No close match nearby (a tunnel, a gap in fixes): search the whole route,
        // and jump only to a clearly better match.
        if (bestD > 25) {
            for (j in 0 until shape.size - 1) {
                val (t, d) = Geo.project(p, shape[j], shape[j + 1])
                if (d < 40 && d < bestD - 10) { best = j; bestT = t; bestD = d }
            }
        }

        val offThreshold = max(40.0, accuracyM * 1.5)
        offStreak = if (bestD > offThreshold) offStreak + 1 else 0
        if (offStreak == 0) seg = best

        val along = cum[best] + bestT * (cum[best + 1] - cum[best])
        val ms = route.maneuvers
        val step = (1 until ms.size).firstOrNull { cum[ms[it].beginShape.coerceAtMost(cum.lastIndex)] > along + 0.5 } ?: ms.lastIndex
        val toStep = cum[ms[step].beginShape.coerceAtMost(cum.lastIndex)] - along

        val cur = ms[(step - 1).coerceAtLeast(0)]
        val curLen = cum[cur.endShape.coerceAtMost(cum.lastIndex)] - cum[cur.beginShape.coerceAtMost(cum.lastIndex)]
        val curLeft = if (curLen > 0) (toStep / curLen).coerceIn(0.0, 1.0) else 0.0
        var remainingS = cur.timeS * curLeft
        for (k in step until ms.size) remainingS += ms[k].timeS

        val remainingM = (totalM - along).coerceAtLeast(0.0)
        return Snapshot(
            snapped = Geo.lerp(shape[best], shape[best + 1], bestT),
            along = along,
            stepIndex = step,
            toStep = toStep.coerceAtLeast(0.0),
            remainingM = remainingM,
            remainingS = remainingS,
            offsetM = bestD,
            offRoute = offStreak >= 3,
            arrived = remainingM < 25 && offStreak == 0,
            roadBearing = Geo.bearing(shape[best], shape[best + 1]),
        )
    }
}
