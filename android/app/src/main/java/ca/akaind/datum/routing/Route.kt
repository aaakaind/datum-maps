package ca.akaind.datum.routing

import ca.akaind.datum.geo.LatLon

/** One instruction of a route, as Valhalla describes it. */
data class Maneuver(
    /** Valhalla maneuver type (1 start … 38 merge left). */
    val type: Int,
    val instruction: String,
    val verbalAlert: String?,
    val verbalPre: String?,
    val verbalPost: String?,
    val streetNames: List<String>,
    val lengthM: Double,
    val timeS: Double,
    val beginShape: Int,
    val endShape: Int,
    val roundaboutExit: Int = 0,
    val bearingBefore: Int = 0,
    val bearingAfter: Int = 0,
) {
    val isArrival get() = type in 4..6
}

data class Route(
    val shape: List<LatLon>,
    val maneuvers: List<Maneuver>,
    val lengthM: Double,
    val timeS: Double,
    val costing: String = "auto",
) {
    /** The road the route spends longest on, for "via …" labels. */
    val via: String? get() = maneuvers.filter { it.streetNames.isNotEmpty() }.maxByOrNull { it.lengthM }?.streetNames?.first()
    val destination: LatLon get() = shape.last()
}

class RoutingException(message: String) : Exception(message)

interface RouteService {
    /**
     * Routes from [from] to [to]. [heading] (degrees) asks for a route that starts in
     * the direction of travel, used when rerouting a moving car. The first route is
     * the recommended one; up to [alternates] more may follow.
     */
    suspend fun route(from: LatLon, to: LatLon, costing: String = "auto", alternates: Int = 2, heading: Double? = null): List<Route>
}
