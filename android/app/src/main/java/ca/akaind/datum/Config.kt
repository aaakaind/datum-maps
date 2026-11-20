package ca.akaind.datum

/**
 * Every external service the native code talks to, in one place. These mirror the
 * CONFIG block in index.html; point both at self-hosted instances (Photon and
 * Valhalla are open source) before carrying real traffic.
 */
object Config {
    const val SEARCH = "https://photon.komoot.io"
    const val ROUTE = "https://valhalla1.openstreetmap.de/route"

    /** The bundled web app, served from the APK's assets on a secure origin. */
    const val SITE_URL = "https://appassets.androidplatform.net/site/index.html"

    const val CAR_STYLE_LIGHT = "asset://car/style-light.json"
    const val CAR_STYLE_DARK = "asset://car/style-dark.json"

    val USER_AGENT = "DATUM-Android/${BuildConfig.VERSION_NAME} (ca.akaind.datum; github.com/aaakaind/datum-maps)"
}
