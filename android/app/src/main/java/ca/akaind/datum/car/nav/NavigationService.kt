package ca.akaind.datum.car.nav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ca.akaind.datum.R

/**
 * Keeps location updates flowing while a trip is under way, as Android requires of
 * navigation apps, with an ongoing "Navigating to …" notification.
 */
class NavigationService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val to = intent?.getStringExtra(EXTRA_TO) ?: getString(R.string.app_name)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW))
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.nav_notification, to))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOnlyAlertOnce(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(ID, n)
        } catch (e: Exception) {
            // Not allowed from the background on some Android versions. The car host's
            // binding keeps the app running while projected, so guidance continues.
            Log.w("DATUM", "Could not start the navigation foreground service", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "navigation"
        private const val ID = 7
        private const val EXTRA_TO = "to"

        fun start(context: Context, to: String) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, NavigationService::class.java).putExtra(EXTRA_TO, to)) }
                .onFailure { Log.w("DATUM", "Navigation service not started", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
