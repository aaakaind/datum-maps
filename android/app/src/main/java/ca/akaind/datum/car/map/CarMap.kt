package ca.akaind.datum.car.map

import android.location.Location
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.routing.Route
import ca.akaind.datum.search.Place

/** What the car screens ask of the map drawn on the car's display. */
interface CarMap {
    /** Draws [primary] over [alternates]; [fit] frames them all in the visible area. */
    fun showRoutes(primary: Route?, alternates: List<Route> = emptyList(), fit: Boolean = true)

    /** Numbered result markers; [fit] frames them with the car's position. */
    fun showPlaces(places: List<Place>, fit: Boolean = true)

    fun showDestination(place: Place?)

    fun setLocation(location: Location)

    /** Driving view: follows the car, heading up, tilted, with the car low on screen. */
    fun setNavigating(navigating: Boolean)

    fun recenter()

    fun zoomBy(delta: Double)

    fun setDarkMode(dark: Boolean)

    /** Location permission was just granted: show the car's position. */
    fun locationPermissionChanged()

    /** Named points of interest of the given OpenMapTiles classes, from the map's own tiles, nearest first. */
    fun nearbyPois(classes: Set<String>, near: LatLon, limit: Int = 12, callback: (List<Place>) -> Unit)

    val location: Location?
}
