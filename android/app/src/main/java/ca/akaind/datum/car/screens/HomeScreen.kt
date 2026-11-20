package ca.akaind.datum.car.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ca.akaind.datum.R
import ca.akaind.datum.car.CarUi
import ca.akaind.datum.car.DatumSession
import ca.akaind.datum.car.Templates

/** The map, following the car, with Search. */
class HomeScreen(ctx: CarContext, private val session: DatumSession) : Screen(ctx) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                session.map.showRoutes(null)
                session.map.showPlaces(emptyList(), fit = false)
                session.map.showDestination(null)
                session.map.setNavigating(false)
            }
        })
    }

    override fun onGetTemplate(): Template {
        val search = Action.Builder()
            .setTitle(carContext.getString(R.string.car_search))
            .setIcon(CarUi.icon(carContext, R.drawable.ic_car_search))
            .setOnClickListener { screenManager.push(SearchScreen(carContext, session)) }
            .build()
        val b = NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder().addAction(search).build())
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            b.setMapActionStrip(Templates.mapStrip(carContext, session.map::recenter, session.map::zoomBy))
            b.setPanModeListener { }
        }
        return b.build()
    }
}
