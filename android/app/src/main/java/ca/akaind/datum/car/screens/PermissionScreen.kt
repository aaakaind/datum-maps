package ca.akaind.datum.car.screens

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ParkedOnlyOnClickListener
import androidx.car.app.model.Template
import ca.akaind.datum.R
import ca.akaind.datum.car.CarUi
import ca.akaind.datum.car.DatumSession
import ca.akaind.datum.car.Templates

/** Asks for location on the phone; the car screen cannot show the system dialog itself. */
class PermissionScreen(ctx: CarContext, private val session: DatumSession) : Screen(ctx) {
    private var denied = false

    override fun onGetTemplate(): Template {
        val allow = Action.Builder()
            .setTitle(carContext.getString(R.string.car_allow))
            .setBackgroundColor(CarUi.ACCENT)
            .setOnClickListener(ParkedOnlyOnClickListener.create { request() })
            .build()
        val text = carContext.getString(if (denied) R.string.car_location_denied else R.string.car_location_why)
        return Templates.message(carContext, carContext.getString(R.string.car_location_title), text, allow, back = false)
    }

    private fun request() {
        carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) { granted, _ ->
            if (granted.isNotEmpty()) {
                session.onLocationPermissionGranted()
                screenManager.pop()
            } else {
                denied = true
                invalidate()
            }
        }
    }
}
