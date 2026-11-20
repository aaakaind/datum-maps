package ca.akaind.datum.car.nav

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Location from the platform LocationManager (GPS and network, or the fused
 * provider on Android 12+). No Google Play services dependency.
 */
class LocationSource(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null
    private var lastGpsAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(): List<String> {
        val all = lm?.allProviders ?: return emptyList()
        if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in all) return listOf(LocationManager.FUSED_PROVIDER)
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
    }

    @SuppressLint("MissingPermission")
    fun start(onLocation: (Location) -> Unit) {
        stop()
        val lm = lm ?: return
        if (!hasPermission()) return
        val l = LocationListener { loc ->
            // With GPS and network both on, ignore network fixes while GPS is fresh.
            if (loc.provider == LocationManager.GPS_PROVIDER) lastGpsAt = loc.time
            else if (loc.provider == LocationManager.NETWORK_PROVIDER && loc.time - lastGpsAt < 10_000) return@LocationListener
            onLocation(loc)
        }
        listener = l
        for (p in providers()) runCatching { lm.requestLocationUpdates(p, 1000L, 0f, l, Looper.getMainLooper()) }
        last()?.let(onLocation)
    }

    @SuppressLint("MissingPermission")
    fun last(): Location? {
        val lm = lm ?: return null
        if (!hasPermission()) return null
        return (lm.allProviders).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    fun stop() {
        listener?.let { lm?.removeUpdates(it) }
        listener = null
    }
}
