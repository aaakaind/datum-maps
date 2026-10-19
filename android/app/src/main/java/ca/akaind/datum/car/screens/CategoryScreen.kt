package ca.akaind.datum.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.ItemList
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ca.akaind.datum.Datum
import ca.akaind.datum.R
import ca.akaind.datum.car.DatumSession
import ca.akaind.datum.car.Templates
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.search.Place
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Nearest places of one kind. Read first from the map tiles already on the phone
 * (nothing sent anywhere), as the web app does; Photon is the fallback.
 */
class CategoryScreen(ctx: CarContext, private val session: DatumSession, private val category: Category) : Screen(ctx) {
    private var places: List<Place>? = null
    private var waitingForLocation = false
    private val onFirstFix: (android.location.Location) -> Unit = { load() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = load()
            override fun onResume(owner: LifecycleOwner) {
                session.map.showRoutes(null)
                session.map.showDestination(null)
                places?.let { session.map.showPlaces(it.take(6)) }
            }
            override fun onDestroy(owner: LifecycleOwner) { session.removeLocationListener(onFirstFix) }
        })
    }

    private fun load() {
        val loc = session.location
        if (loc == null) {
            if (!waitingForLocation) session.addLocationListener(onFirstFix)
            waitingForLocation = true
            return
        }
        if (waitingForLocation) session.removeLocationListener(onFirstFix)
        waitingForLocation = false
        val near = LatLon(loc.latitude, loc.longitude)
        session.map.nearbyPois(category.classes, near) { found ->
            if (found.isNotEmpty()) return@nearbyPois show(found)
            lifecycleScope.launch {
                val fallback = runCatching {
                    withContext(Datum.io) { Datum.search.search(category.query, near, 10) }
                        .filter { Geo.distance(near, it.latLon) < 25_000 }
                        .sortedBy { Geo.distance(near, it.latLon) }
                }.getOrDefault(emptyList())
                show(fallback)
            }
        }
    }

    private fun show(found: List<Place>) {
        places = found
        session.map.showPlaces(found.take(6))
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val list = places?.let { found ->
            val from = session.location?.let { LatLon(it.latitude, it.longitude) }
            ItemList.Builder().apply {
                found.take(6).forEachIndexed { i, p -> addItem(placeRow(carContext, p, i, from) { screenManager.push(RoutePreviewScreen(carContext, session, p)) }) }
                if (found.isEmpty()) setNoItemsMessage(carContext.getString(R.string.car_none_nearby, category.label.lowercase()))
            }.build()
        }
        return Templates.placeList(carContext, category.label, list, Templates.mapStrip(carContext, session.map::recenter, session.map::zoomBy)) { }
    }
}
