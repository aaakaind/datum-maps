package ca.akaind.datum.car.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import ca.akaind.datum.Config
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.routing.Route
import ca.akaind.datum.search.Place
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Point
import kotlin.math.ln
import kotlin.math.max

/**
 * DATUM's map drawn into any Surface: a VirtualDisplay on that surface hosts a
 * Presentation holding a MapLibre MapView, using the same style as the web app.
 * Android Auto hands us the car display's surface; tests hand us an ImageReader's.
 */
class SurfaceMap(private val context: Context) : CarMap {
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var attribution: TextView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private var width = 0
    private var height = 0
    private var visible = Rect()
    private var dark = false
    private var navigating = false
    private var following = true

    // What the screens asked for, re-applied whenever the style (re)loads.
    private var primary: Route? = null
    private var alternates: List<Route> = emptyList()
    private var places: List<Place> = emptyList()
    private var dest: Place? = null
    private var pendingFit: (() -> Unit)? = null

    override var location: Location? = null
        private set

    val isReady get() = style != null

    fun attach(surface: Surface, width: Int, height: Int, dpi: Int) {
        detach()
        MapLibre.getInstance(context.applicationContext)
        this.width = width
        this.height = height
        if (visible.isEmpty) visible = Rect(0, 0, width, height)
        val dm = context.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay("DATUM car map", width, height, dpi, surface, 0)
        val p = Presentation(context, vd.display)
        val ctx = p.context
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .attributionEnabled(false).logoEnabled(false).compassEnabled(false)
            .rotateGesturesEnabled(false).tiltGesturesEnabled(false)
        val mv = MapView(ctx, options)
        val att = TextView(ctx).apply {
            text = "© OpenMapTiles © OpenStreetMap"
            textSize = 9f
            setPadding(10, 3, 10, 3)
        }
        val root = FrameLayout(ctx)
        root.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(att, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        p.setContentView(root)
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        p.show()
        display = vd
        presentation = p
        mapView = mv
        attribution = att
        placeAttribution()
        mv.getMapAsync { m ->
            map = m
            m.setMinZoomPreference(2.0)
            m.setMaxZoomPreference(19.0)
            loadStyle()
        }
    }

    fun detach() {
        mapView?.run { onPause(); onStop(); onDestroy() }
        presentation?.dismiss()
        display?.release()
        mapView = null
        presentation = null
        display = null
        map = null
        style = null
        attribution = null
    }

    fun setVisibleArea(rect: Rect) {
        visible = Rect(rect)
        placeAttribution()
        applyCamera(animate = false)
    }

    /** Pan by a drag of (dx, dy) pixels, as the car's touchpad or touchscreen reports it. */
    fun scroll(dx: Float, dy: Float) {
        val m = map ?: return
        stopFollowing()
        val c = PointF(width / 2f + dx, height / 2f + dy)
        m.moveCamera(CameraUpdateFactory.newLatLng(m.projection.fromScreenLocation(c)))
    }

    fun scale(focusX: Float, focusY: Float, factor: Float) {
        val m = map ?: return
        val delta = ln(factor.toDouble()) / ln(2.0)
        if (focusX < 0 || focusY < 0) m.easeCamera(CameraUpdateFactory.zoomBy(delta), 250)
        else m.easeCamera(CameraUpdateFactory.zoomBy(delta, android.graphics.Point(focusX.toInt(), focusY.toInt())), 250)
    }

    /** Runs [block] once the map has finished drawing everything it has to. */
    fun onIdle(block: () -> Unit) {
        val mv = mapView ?: return
        var done = false
        mv.addOnDidBecomeIdleListener(object : MapView.OnDidBecomeIdleListener {
            override fun onDidBecomeIdle() {
                if (done) return
                done = true
                mv.post { mv.removeOnDidBecomeIdleListener(this) }
                block()
            }
        })
    }

    // ------------------------------------------------------------------ CarMap

    override fun showRoutes(primary: Route?, alternates: List<Route>, fit: Boolean) {
        this.primary = primary
        this.alternates = alternates
        applyRoutes()
        if (fit && primary != null) fitTo((alternates + primary).flatMap { it.shape } + listOfNotNull(location?.let { LatLon(it.latitude, it.longitude) }))
    }

    override fun showPlaces(places: List<Place>, fit: Boolean) {
        this.places = places
        applyPlaces()
        if (fit && places.isNotEmpty()) fitTo(places.map { it.latLon } + listOfNotNull(location?.let { LatLon(it.latitude, it.longitude) }))
    }

    override fun showDestination(place: Place?) {
        dest = place
        applyDest()
    }

    @SuppressLint("MissingPermission")
    override fun setLocation(location: Location) {
        val first = this.location == null
        this.location = location
        val lc = map?.takeIf { style != null }?.locationComponent ?: return
        if (lc.isLocationComponentActivated) lc.forceLocationUpdate(location)
        if (first && following) applyCamera(animate = false)
    }

    override fun setNavigating(navigating: Boolean) {
        this.navigating = navigating
        following = true
        applyCamera(animate = true)
    }

    override fun recenter() {
        following = true
        applyCamera(animate = true)
    }

    override fun zoomBy(delta: Double) {
        map?.easeCamera(CameraUpdateFactory.zoomBy(delta), 250)
    }

    override fun setDarkMode(dark: Boolean) {
        if (this.dark == dark && style != null) return
        this.dark = dark
        if (map != null) loadStyle()
    }

    override fun locationPermissionChanged() {
        style?.let { enableLocation(it) }
        applyCamera(animate = false)
    }

    override fun nearbyPois(classes: Set<String>, near: LatLon, limit: Int, callback: (List<Place>) -> Unit) {
        val found = queryPois(classes, near, limit)
        val m = map
        if (found.isNotEmpty() || m == null || m.cameraPosition.zoom >= 13.5) return callback(found)
        // POIs are only in the tiles from zoom 14: look closer, wait for those tiles, ask again.
        stopFollowing()
        m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(near.lat, near.lon), 14.5))
        onIdle { callback(queryPois(classes, near, limit)) }
    }

    private fun queryPois(classes: Set<String>, near: LatLon, limit: Int): List<Place> {
        val src = style?.getSourceAs<VectorSource>("omt") ?: return emptyList()
        val filter = JSONArray().put("all")
            .put(JSONArray().put("has").put("name"))
            .put(JSONArray().put("in").put(JSONArray().put("get").put("class")).put(JSONArray().put("literal").put(JSONArray(classes.toList()))))
        val features = runCatching { src.querySourceFeatures(arrayOf("poi"), Expression.raw(filter.toString())) }.getOrDefault(emptyList())
        val out = ArrayList<Place>()
        for (f in features) {
            val pt = f.geometry() as? Point ?: continue
            val name = (f.getStringProperty("name:en") ?: f.getStringProperty("name_en") ?: f.getStringProperty("name"))?.takeIf { it.isNotBlank() } ?: continue
            val p = Place(name, f.getStringProperty("class")?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "", pt.latitude(), pt.longitude(), "transit")
            if (out.none { it.name == p.name && Geo.distance(it.latLon, p.latLon) < 80 }) out += p
        }
        return out.sortedBy { Geo.distance(near, it.latLon) }.take(limit)
    }

    // ------------------------------------------------------------------ drawing

    private fun loadStyle() {
        val m = map ?: return
        style = null
        m.setStyle(Style.Builder().fromUri(if (dark) Config.CAR_STYLE_DARK else Config.CAR_STYLE_LIGHT)) { s ->
            style = s
            attribution?.setTextColor(if (dark) Color.parseColor("#B8B0A2") else Color.parseColor("#4A453D"))
            attribution?.setBackgroundColor(if (dark) Color.parseColor("#B31A1813") else Color.parseColor("#B3F3EEE4"))
            enableLocation(s)
            applyRoutes()
            applyPlaces()
            applyDest()
            pendingFit?.invoke() ?: applyCamera(animate = false)
            pendingFit = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(s: Style) {
        val m = map ?: return
        val ctx = presentation?.context ?: return
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val opts = LocationComponentOptions.builder(ctx)
            .foregroundTintColor(Color.parseColor("#C8432B"))
            .bearingTintColor(Color.parseColor("#C8432B"))
            .accuracyAlpha(0.12f)
            .trackingGesturesManagement(false)
            .build()
        val lc = m.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.Builder(ctx, s).useDefaultLocationEngine(false).locationComponentOptions(opts).build()
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        location?.let { lc.forceLocationUpdate(it) }
    }

    private fun applyRoutes() {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>("route")?.setGeoJson(lines(listOfNotNull(primary)))
        s.getSourceAs<GeoJsonSource>("route-alt")?.setGeoJson(lines(alternates))
    }

    private fun applyPlaces() {
        val s = style ?: return
        val fc = JSONArray()
        places.forEachIndexed { i, p ->
            fc.put(point(p.lat, p.lon, JSONObject().put("n", "${i + 1}").put("g", p.group)))
        }
        s.getSourceAs<GeoJsonSource>("hits")?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", fc).toString())
    }

    private fun applyDest() {
        val s = style ?: return
        val fc = JSONArray()
        dest?.let { fc.put(point(it.lat, it.lon, JSONObject().put("n", it.name))) }
        s.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(JSONObject().put("type", "FeatureCollection").put("features", fc).toString())
    }

    private fun applyCamera(animate: Boolean) {
        val m = map ?: return
        val lc = m.locationComponent
        if (style == null || !lc.isLocationComponentActivated) {
            if (following) location?.let { m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 15.0)) }
            return
        }
        if (!following) {
            lc.cameraMode = CameraMode.NONE
            return
        }
        val pad = padding()
        if (navigating) {
            // Car in the lower third of the visible area, looking ahead.
            val h = visible.height().toDouble()
            lc.cameraMode = CameraMode.TRACKING_GPS
            lc.paddingWhileTracking(doubleArrayOf(pad[0], pad[1] + h * 0.45, pad[2], pad[3]), if (animate) 600 else 0)
            lc.zoomWhileTracking(16.5, if (animate) 600 else 0)
            lc.tiltWhileTracking(45.0, if (animate) 600 else 0)
        } else {
            lc.cameraMode = CameraMode.TRACKING
            lc.paddingWhileTracking(pad, if (animate) 400 else 0)
            lc.tiltWhileTracking(0.0, if (animate) 400 else 0)
            if (m.cameraPosition.zoom < 12) lc.zoomWhileTracking(15.0, if (animate) 600 else 0)
        }
    }

    private fun stopFollowing() {
        following = false
        map?.takeIf { it.locationComponent.isLocationComponentActivated }?.locationComponent?.cameraMode = CameraMode.NONE
    }

    private fun fitTo(points: List<LatLon>) {
        val m = map
        if (m == null || style == null) {
            pendingFit = { fitTo(points) }
            return
        }
        if (points.isEmpty()) return
        stopFollowing()
        val pad = padding()
        val margin = 48
        if (points.size == 1 || points.all { Geo.distance(it, points[0]) < 50 }) {
            m.easeCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 15.0), 500)
            return
        }
        val b = LatLngBounds.Builder()
        points.forEach { b.include(LatLng(it.lat, it.lon)) }
        m.easeCamera(
            CameraUpdateFactory.newLatLngBounds(b.build(), pad[0].toInt() + margin, pad[1].toInt() + margin, pad[2].toInt() + margin, pad[3].toInt() + margin),
            500,
        )
    }

    /** Insets of the visible area: the part of the car screen not covered by the host's cards and buttons. */
    private fun padding(): DoubleArray {
        val v = if (visible.isEmpty) Rect(0, 0, width, height) else visible
        return doubleArrayOf(v.left.toDouble(), v.top.toDouble(), max(0, width - v.right).toDouble(), max(0, height - v.bottom).toDouble())
    }

    private fun placeAttribution() {
        val att = attribution ?: return
        val lp = att.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = visible.left + 6
        lp.bottomMargin = max(0, height - visible.bottom) + 4
        att.layoutParams = lp
    }

    private fun lines(routes: List<Route>): String {
        val fc = JSONArray()
        for (r in routes) {
            val coords = JSONArray()
            r.shape.forEach { coords.put(JSONArray().put(it.lon).put(it.lat)) }
            fc.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
                .put("geometry", JSONObject().put("type", "LineString").put("coordinates", coords)))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", fc).toString()
    }

    private fun point(lat: Double, lon: Double, props: JSONObject) = JSONObject().put("type", "Feature").put("properties", props)
        .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lon).put(lat)))
}
