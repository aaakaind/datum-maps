package ca.akaind.datum.car.screens

import android.location.Location
import android.os.SystemClock
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Trip
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import ca.akaind.datum.Datum
import ca.akaind.datum.R
import ca.akaind.datum.car.CarUi
import ca.akaind.datum.car.DatumSession
import ca.akaind.datum.car.Templates
import ca.akaind.datum.car.nav.DriveSimulator
import ca.akaind.datum.car.nav.Fix
import ca.akaind.datum.car.nav.NavigationService
import ca.akaind.datum.car.nav.Navigator
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.geo.Units
import ca.akaind.datum.routing.Route
import ca.akaind.datum.search.Place

/** Turn-by-turn guidance on the car screen, and on the instrument cluster or head-up display where the car has one. */
class NavigationScreen(
    ctx: CarContext,
    private val session: DatumSession,
    private val place: Place,
    route: Route,
) : Screen(ctx) {
    private val navManager: NavigationManager = ctx.getCarService(NavigationManager::class.java)
    private val navigator = Navigator(place, route, lifecycleScope, session.voice) { onState(it) }
    private var simulator: DriveSimulator? = null
    private var shownKey = ""
    private var shownAt = 0L
    private var shownPhase = 0
    private var drawnRouteVersion = -1
    private val onLocation: (Location) -> Unit = { navigator.onFix(it.toFix()) }

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
                    override fun onStopNavigation() = screenManager.popToRoot()
                    override fun onAutoDriveEnabled() = simulate()
                })
                navManager.navigationStarted()
                NavigationService.start(carContext, place.name)
                session.addLocationListener(onLocation)
                navigator.start()
            }

            override fun onResume(owner: LifecycleOwner) {
                drawnRouteVersion = -1
                draw()
                session.map.setNavigating(true)
            }

            override fun onDestroy(owner: LifecycleOwner) {
                session.removeLocationListener(onLocation)
                simulator?.stop()
                navManager.navigationEnded()
                navManager.clearNavigationManagerCallback()
                NavigationService.stop(carContext)
                session.map.setNavigating(false)
            }
        })
    }

    private fun simulate() {
        if (simulator != null) return
        session.removeLocationListener(onLocation)
        simulator = DriveSimulator(navigator.state.route) { loc ->
            session.map.setLocation(loc)
            navigator.onFix(loc.toFix())
        }.also { it.start() }
    }

    private fun Location.toFix() = Fix(
        LatLon(latitude, longitude),
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        bearing = if (hasBearing()) bearing.toDouble() else null,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else 20.0,
    )

    private fun draw() {
        val s = navigator.state
        if (s.routeVersion == drawnRouteVersion) return
        drawnRouteVersion = s.routeVersion
        session.map.showPlaces(emptyList(), fit = false)
        session.map.showDestination(place)
        session.map.showRoutes(s.route, emptyList(), fit = false)
    }

    private fun onState(s: Navigator.State) {
        draw()
        updateTrip(s)
        // Redraw the card only when what it shows changes, at most once a second.
        val snap = s.snapshot
        val key = listOf(snap?.stepIndex, snap?.toStep?.let { Units.distance(it) }, snap?.remainingS?.let { (it / 60).toInt() },
            s.rerouting, s.arrived, s.routeVersion).joinToString("|")
        val phase = if (s.arrived) 2 else if (s.rerouting) 1 else 0
        val now = SystemClock.elapsedRealtime()
        if (key != shownKey && (now - shownAt >= 1000 || phase != shownPhase)) {
            shownKey = key
            shownPhase = phase
            shownAt = now
            invalidate()
        }
    }

    private fun updateTrip(s: Navigator.State) {
        val snap = s.snapshot ?: return
        if (s.arrived) return
        val m = s.route.maneuvers.getOrNull(snap.stepIndex) ?: return
        val stepEta = CarUi.estimate(snap.toStep, (snap.toStep / (s.route.lengthM / s.route.timeS.coerceAtLeast(1.0))), Datum.now())
        val trip = Trip.Builder()
            .addStep(CarUi.step(carContext, m), stepEta)
            .addDestination(Destination.Builder().setName(place.name).setAddress(place.address.ifBlank { " " }).build(),
                CarUi.estimate(snap.remainingM, snap.remainingS, Datum.now()))
            .setLoading(s.rerouting)
        s.route.maneuvers.getOrNull(snap.stepIndex - 1)?.streetNames?.firstOrNull()?.let { trip.setCurrentRoad(it) }
        runCatching { navManager.updateTrip(trip.build()) }
    }

    override fun onGetTemplate(): Template {
        val s = navigator.state
        val snap = s.snapshot
        val voice = session.voice
        val mute = CarUi.action(carContext, if (voice.muted) R.drawable.ic_car_volume_off else R.drawable.ic_car_volume_on) {
            voice.muted = !voice.muted
            invalidate()
        }
        val exit = Action.Builder()
            .setTitle(carContext.getString(if (s.arrived) R.string.car_done else R.string.car_exit))
            .setOnClickListener { screenManager.popToRoot() }
            .build()
        val b = NavigationTemplate.Builder().setActionStrip(ActionStrip.Builder().addAction(exit).addAction(mute).build())
        when {
            s.arrived -> b.setNavigationInfo(
                MessageInfo.Builder(carContext.getString(R.string.car_arrived)).setText(place.name)
                    .setImage(CarUi.icon(carContext, R.drawable.ic_mnv_destination, tint = null)).build()
            )
            s.rerouting || snap == null -> b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            else -> {
                val ms = s.route.maneuvers
                val step = ms[snap.stepIndex]
                val info = RoutingInfo.Builder().setCurrentStep(CarUi.step(carContext, step), CarUi.distance(snap.toStep))
                // Show what comes right after, when it comes soon after.
                ms.getOrNull(snap.stepIndex + 1)?.takeIf { step.lengthM < 400 && !step.isArrival }?.let { info.setNextStep(CarUi.step(carContext, it)) }
                b.setNavigationInfo(info.build())
                b.setDestinationTravelEstimate(CarUi.estimate(snap.remainingM, snap.remainingS, Datum.now()))
            }
        }
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            b.setMapActionStrip(Templates.mapStrip(carContext, session.map::recenter, session.map::zoomBy))
            b.setPanModeListener { }
        }
        return b.build()
    }
}
