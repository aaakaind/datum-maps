package ca.akaind.datum.car.screens

import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import ca.akaind.datum.Datum
import ca.akaind.datum.R
import ca.akaind.datum.car.CarUi
import ca.akaind.datum.car.DatumSession
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.search.Place
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Places to drive to: things nearby on the map, and anything Photon can find. */
data class Category(
    val label: String,
    @DrawableRes val icon: Int,
    /** OpenMapTiles POI classes, read from the map tiles. */
    val classes: Set<String>,
    /** Fallback text search when the tiles have none nearby. */
    val query: String,
)

val CATEGORIES = listOf(
    Category("Gas stations", R.drawable.ic_car_fuel, setOf("fuel"), "gas station"),
    Category("EV charging", R.drawable.ic_car_ev, setOf("charging_station"), "charging station"),
    Category("Parking", R.drawable.ic_car_parking, setOf("parking"), "parking"),
    Category("Food", R.drawable.ic_car_food, setOf("restaurant", "fast_food"), "restaurant"),
    Category("Coffee", R.drawable.ic_car_coffee, setOf("cafe"), "coffee"),
)

class SearchScreen(ctx: CarContext, private val session: DatumSession, initialQuery: String? = null) : Screen(ctx) {
    private var query = initialQuery.orEmpty()
    private var results: List<Place>? = null
    private var searching = false
    private var error: String? = null
    private var job: Job? = null

    init {
        if (!initialQuery.isNullOrBlank()) run(initialQuery, debounce = false)
    }

    private fun near(): LatLon? = session.location?.let { LatLon(it.latitude, it.longitude) }

    private fun run(text: String, debounce: Boolean) {
        job?.cancel()
        query = text
        if (text.isBlank()) {
            results = null
            searching = false
            invalidate()
            return
        }
        job = lifecycleScope.launch {
            if (debounce) delay(450)
            searching = true
            error = null
            invalidate()
            try {
                val found = withContext(Datum.io) { Datum.search.search(text, near()) }
                results = found
            } catch (e: Exception) {
                results = emptyList()
                error = carContext.getString(R.string.car_search_failed)
            }
            searching = false
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val cb = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = run(searchText, debounce = true)
            override fun onSearchSubmitted(searchText: String) = run(searchText, debounce = false)
        }
        val b = SearchTemplate.Builder(cb)
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.car_search_hint))
            .setInitialSearchText(query)
            .setShowKeyboardByDefault(false)
        val res = results
        if (searching && res == null) return b.setLoading(true).build()

        val list = ItemList.Builder()
        if (res == null) {
            for (c in CATEGORIES) {
                list.addItem(Row.Builder().setTitle(c.label).setImage(CarUi.icon(carContext, c.icon)).setBrowsable(true)
                    .setOnClickListener { screenManager.push(CategoryScreen(carContext, session, c)) }.build())
            }
            for (p in session.recents.all().take(3)) {
                list.addItem(Row.Builder().setTitle(p.name).apply { if (p.address.isNotBlank()) addText(p.address) }
                    .setImage(CarUi.icon(carContext, R.drawable.ic_car_recent))
                    .setOnClickListener { screenManager.push(RoutePreviewScreen(carContext, session, p)) }.build())
            }
        } else {
            val from = near()
            res.take(6).forEachIndexed { i, p ->
                list.addItem(placeRow(carContext, p, i, from) { screenManager.push(RoutePreviewScreen(carContext, session, p)) })
            }
            if (res.isEmpty()) list.setNoItemsMessage(error ?: carContext.getString(R.string.car_no_results, query))
        }
        return b.setItemList(list.build()).build()
    }
}
