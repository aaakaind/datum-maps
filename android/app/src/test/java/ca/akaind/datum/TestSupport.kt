package ca.akaind.datum

import android.location.Location
import ca.akaind.datum.car.DatumSession
import ca.akaind.datum.car.map.CarMap
import ca.akaind.datum.car.nav.Voice
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.routing.Route
import ca.akaind.datum.routing.RouteService
import ca.akaind.datum.routing.ValhallaRouting
import ca.akaind.datum.search.Place
import ca.akaind.datum.search.SearchService

fun fixture(name: String): String =
    requireNotNull(object {}.javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

fun kanataRoutes(): List<Route> = ValhallaRouting.parse(fixture("valhalla_auto_kanata.json"))
fun ottawaRoutes(): List<Route> = ValhallaRouting.parse(fixture("valhalla_auto_ottawa.json"))

class FakeVoice : Voice {
    val said = mutableListOf<String>()
    override var muted = false
    override fun say(text: String) { if (!muted) said += text }
    override fun shutdown() = Unit
}

class FakeSearch(var results: List<Place> = emptyList()) : SearchService {
    val queries = mutableListOf<String>()
    override suspend fun search(query: String, near: LatLon?, limit: Int): List<Place> { queries += query; return results }
    override suspend fun reverse(at: LatLon): Place? = results.firstOrNull()
}

class FakeRouting(var routes: List<Route>) : RouteService {
    data class Call(val from: LatLon, val to: LatLon, val alternates: Int, val heading: Double?)
    val calls = mutableListOf<Call>()
    var failWith: String? = null
    override suspend fun route(from: LatLon, to: LatLon, costing: String, alternates: Int, heading: Double?): List<Route> {
        calls += Call(from, to, alternates, heading)
        failWith?.let { throw ca.akaind.datum.routing.RoutingException(it) }
        return routes
    }
}

/** Records what the screens ask the map to do, and draws nothing. */
class FakeCarMap : CarMap {
    var routes: Pair<Route?, List<Route>> = null to emptyList()
    var places: List<Place> = emptyList()
    var dest: Place? = null
    var navOn = false
    var pois: List<Place> = emptyList()
    var loc: Location? = null
    override val location: Location? get() = loc
    override fun showRoutes(primary: Route?, alternates: List<Route>, fit: Boolean) { routes = primary to alternates }
    override fun showPlaces(places: List<Place>, fit: Boolean) { this.places = places }
    override fun showDestination(place: Place?) { dest = place }
    override fun setLocation(location: Location) { loc = location }
    override fun setNavigating(navigating: Boolean) { navOn = navigating }
    override fun recenter() = Unit
    override fun zoomBy(delta: Double) = Unit
    override fun setDarkMode(dark: Boolean) = Unit
    override fun locationPermissionChanged() = Unit
    override fun nearbyPois(classes: Set<String>, near: LatLon, limit: Int, callback: (List<Place>) -> Unit) = callback(pois)
}

class TestSession(val fakeMap: FakeCarMap = FakeCarMap(), val fakeVoice: FakeVoice = FakeVoice()) : DatumSession() {
    override fun createMap(): CarMap = fakeMap
    override val voice: Voice get() = fakeVoice
}

fun location(lat: Double, lon: Double, speed: Float? = null, bearing: Float? = null) = Location("test").apply {
    latitude = lat
    longitude = lon
    accuracy = 5f
    time = System.currentTimeMillis()
    speed?.let { this.speed = it }
    bearing?.let { this.bearing = it }
}
