package ca.akaind.datum.car

import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.net.Uri
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import ca.akaind.datum.car.map.CarMap
import ca.akaind.datum.car.map.CarMapRenderer
import ca.akaind.datum.car.nav.LocationSource
import ca.akaind.datum.car.nav.Voice
import ca.akaind.datum.car.nav.VoiceGuide
import ca.akaind.datum.car.screens.HomeScreen
import ca.akaind.datum.car.screens.PermissionScreen
import ca.akaind.datum.car.screens.RoutePreviewScreen
import ca.akaind.datum.car.screens.SearchScreen
import ca.akaind.datum.search.Place
import java.util.concurrent.CopyOnWriteArraySet

/** One connection to a car: owns the car map, location updates and the voice. */
open class DatumSession : Session() {
    lateinit var map: CarMap
        protected set
    private var renderer: CarMapRenderer? = null
    private var locations: LocationSource? = null
    private val listeners = CopyOnWriteArraySet<(Location) -> Unit>()
    private var voiceGuide: Voice? = null
    val recents by lazy { Recents(carContext) }

    open val voice: Voice
        get() = voiceGuide ?: VoiceGuide(carContext).also { voiceGuide = it }

    val location: Location? get() = map.location ?: locations?.last()

    fun hasLocationPermission() = LocationSource(carContext).hasPermission()

    /** Builds the car map. Tests override this to draw nowhere. */
    protected open fun createMap(): CarMap = CarMapRenderer(carContext).also { it.start(); renderer = it }

    override fun onCreateScreen(intent: Intent): Screen {
        map = createMap()
        locations = LocationSource(carContext)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = startLocation()
            override fun onStop(owner: LifecycleOwner) { locations?.stop() }
            override fun onDestroy(owner: LifecycleOwner) {
                renderer?.stop()
                voiceGuide?.shutdown()
            }
        })
        val home = HomeScreen(carContext, this)
        val screens = carContext.getCarService(ScreenManager::class.java)
        if (!hasLocationPermission()) {
            screens.push(home)
            return PermissionScreen(carContext, this)
        }
        val target = navigationTarget(intent) ?: return home
        screens.push(home)
        return target
    }

    override fun onNewIntent(intent: Intent) {
        val target = navigationTarget(intent) ?: return
        carContext.getCarService(ScreenManager::class.java).push(target)
    }

    override fun onCarConfigurationChanged(newConfiguration: Configuration) {
        map.setDarkMode(carContext.isDarkMode)
    }

    fun onLocationPermissionGranted() {
        startLocation()
        map.locationPermissionChanged()
    }

    fun addLocationListener(l: (Location) -> Unit) = listeners.add(l)

    fun removeLocationListener(l: (Location) -> Unit) = listeners.remove(l)

    private fun startLocation() {
        locations?.start { loc ->
            map.setLocation(loc)
            listeners.forEach { it(loc) }
        }
    }

    /**
     * "Navigate to…" from Assistant or another app arrives as a geo: URI, either
     * coordinates (geo:45.42,-75.69) or a query (geo:0,0?q=Lansdowne+Park).
     */
    private fun navigationTarget(intent: Intent): Screen? {
        if (intent.action != CarContext.ACTION_NAVIGATE) return null
        val target = parseGeo(intent.data) ?: return null
        return when (target) {
            is GeoTarget.Point -> RoutePreviewScreen(carContext, this, target.place)
            is GeoTarget.Query -> SearchScreen(carContext, this, initialQuery = target.text)
        }
    }

    sealed interface GeoTarget {
        data class Point(val place: Place) : GeoTarget
        data class Query(val text: String) : GeoTarget
    }

    companion object {
        fun parseGeo(uri: Uri?): GeoTarget? {
            if (uri == null || uri.scheme != "geo") return null
            val ssp = uri.schemeSpecificPart ?: return null
            val coords = ssp.substringBefore('?').split(',')
            val lat = coords.getOrNull(0)?.toDoubleOrNull()
            val lon = coords.getOrNull(1)?.toDoubleOrNull()
            val q = ssp.substringAfter('?', "").split('&').firstOrNull { it.startsWith("q=") }?.substring(2)
                ?.let { Uri.decode(it.replace('+', ' ')) }?.trim()
            // geo:0,0?q=lat,lon(Label)
            val qm = q?.let { Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""").find(it) }
            if (qm != null) {
                val label = qm.groupValues[3].ifBlank { "Dropped pin" }
                return GeoTarget.Point(Place(label, "", qm.groupValues[1].toDouble(), qm.groupValues[2].toDouble(), "place"))
            }
            if (!q.isNullOrBlank()) return GeoTarget.Query(q)
            if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) {
                return GeoTarget.Point(Place("Dropped pin", "%.5f, %.5f".format(lat, lon), lat, lon, "place"))
            }
            return null
        }
    }
}
