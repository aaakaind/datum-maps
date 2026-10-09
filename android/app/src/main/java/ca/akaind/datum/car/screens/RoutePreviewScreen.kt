package ca.akaind.datum.car.screens

import android.location.Location
import android.text.SpannableString
import android.text.Spanned
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ca.akaind.datum.Datum
import ca.akaind.datum.R
import ca.akaind.datum.car.CarUi
import ca.akaind.datum.car.DatumSession
import ca.akaind.datum.car.Templates
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.routing.Route
import ca.akaind.datum.search.Place
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Up to three driving routes to a place, drawn on the map, and Go. */
class RoutePreviewScreen(ctx: CarContext, private val session: DatumSession, private val place: Place) : Screen(ctx) {
    private var routes: List<Route>? = null
    private var selected = 0
    private var error: String? = null
    private var waitingForLocation = false
    private val onFirstFix: (Location) -> Unit = { load() }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) = load()
            override fun onResume(owner: LifecycleOwner) = draw(fit = true)
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
        error = null
        lifecycleScope.launch {
            try {
                routes = withContext(Datum.io) {
                    Datum.routing.route(LatLon(loc.latitude, loc.longitude), place.latLon, "auto", alternates = 2,
                        heading = loc.takeIf { it.hasBearing() && it.hasSpeed() && it.speed > 3 }?.bearing?.toDouble())
                }
                selected = 0
            } catch (e: Exception) {
                error = e.message ?: carContext.getString(R.string.car_route_failed)
            }
            draw(fit = true)
            invalidate()
        }
    }

    private fun draw(fit: Boolean) {
        session.map.showPlaces(emptyList(), fit = false)
        session.map.showDestination(place)
        val rs = routes ?: return
        val chosen = rs.getOrNull(selected) ?: return
        session.map.showRoutes(chosen, rs.filterIndexed { i, _ -> i != selected }, fit)
    }

    override fun onGetTemplate(): Template {
        error?.let { msg ->
            val retry = Action.Builder().setTitle(carContext.getString(R.string.car_retry)).setOnClickListener { load() }.build()
            return Templates.message(carContext, place.name, carContext.getString(R.string.car_no_route, msg), retry)
        }
        val rs = routes
        val items = rs?.let {
            ItemList.Builder().apply {
                it.forEachIndexed { i, r ->
                    val title = SpannableString(" ")
                    title.setSpan(DurationSpan.create(r.timeS.toLong()), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    val text = SpannableString(" " + (r.via?.let { v -> " · " + carContext.getString(R.string.car_via, v) } ?: ""))
                    text.setSpan(DistanceSpan.create(CarUi.distance(r.lengthM)), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                    addItem(Row.Builder().setTitle(title).addText(text).build())
                }
                setSelectedIndex(selected)
                setOnSelectedListener { i ->
                    selected = i
                    draw(fit = false)
                }
            }.build()
        }
        return Templates.routePreview(carContext, place.name, items, ::start, Templates.mapStrip(carContext, session.map::recenter, session.map::zoomBy)) { }
    }

    private fun start() {
        val route = routes?.getOrNull(selected) ?: return
        session.recents.add(place)
        // Start from the root, so the navigation screen does not count against the
        // host's limit on how many screens deep a task can go.
        screenManager.popToRoot()
        screenManager.push(NavigationScreen(carContext, session, place, route))
    }
}
