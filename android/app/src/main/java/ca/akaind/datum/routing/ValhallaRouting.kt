package ca.akaind.datum.routing

import ca.akaind.datum.Config
import ca.akaind.datum.geo.Geo
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.net.Http
import ca.akaind.datum.net.HttpException
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Turn-by-turn routes from Valhalla, the engine the web app uses. */
class ValhallaRouting(private val url: String = Config.ROUTE) : RouteService {
    override suspend fun route(from: LatLon, to: LatLon, costing: String, alternates: Int, heading: Double?): List<Route> {
        val start = JSONObject().put("lat", from.lat).put("lon", from.lon)
        if (heading != null) start.put("heading", heading.toInt()).put("heading_tolerance", 45)
        val req = JSONObject()
            .put("locations", JSONArray().put(start).put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", costing)
            .put("alternates", alternates)
            .put("directions_options", JSONObject().put("units", "kilometers").put("language", language()))
        val body = try {
            Http.postJson(url, req.toString())
        } catch (e: HttpException) {
            val msg = runCatching { JSONObject(e.body).optString("error") }.getOrNull()
            throw RoutingException(msg?.takeIf { it.isNotBlank() } ?: "The routing server answered ${e.code}")
        }
        return parse(body, costing)
    }

    private fun language() = when (Locale.getDefault().language) {
        "fr" -> "fr-FR"
        "de" -> "de-DE"
        "es" -> "es-ES"
        "it" -> "it-IT"
        else -> "en-US"
    }

    companion object {
        fun parse(json: String, costing: String = "auto"): List<Route> {
            val root = JSONObject(json)
            val routes = mutableListOf(trip(root.getJSONObject("trip"), costing))
            val alts = root.optJSONArray("alternates")
            if (alts != null) for (i in 0 until alts.length()) routes += trip(alts.getJSONObject(i).getJSONObject("trip"), costing)
            return routes
        }

        private fun trip(trip: JSONObject, costing: String): Route {
            val shape = ArrayList<LatLon>()
            val maneuvers = ArrayList<Maneuver>()
            val legs = trip.getJSONArray("legs")
            for (l in 0 until legs.length()) {
                val leg = legs.getJSONObject(l)
                val offset = if (shape.isEmpty()) 0 else shape.size - 1
                val pts = Geo.decodePolyline6(leg.getString("shape"))
                shape += if (shape.isEmpty()) pts else pts.drop(1)
                val ms = leg.getJSONArray("maneuvers")
                for (i in 0 until ms.length()) {
                    val m = ms.getJSONObject(i)
                    val type = m.getInt("type")
                    if (l < legs.length() - 1 && type in 4..6) continue // intermediate arrivals
                    maneuvers += Maneuver(
                        type = type,
                        instruction = m.optString("instruction"),
                        verbalAlert = m.optString("verbal_transition_alert_instruction").ifBlank { null },
                        verbalPre = m.optString("verbal_pre_transition_instruction").ifBlank { null },
                        verbalPost = m.optString("verbal_post_transition_instruction").ifBlank { null },
                        streetNames = m.optJSONArray("street_names")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList(),
                        lengthM = m.optDouble("length", 0.0) * 1000,
                        timeS = m.optDouble("time", 0.0),
                        beginShape = m.getInt("begin_shape_index") + offset,
                        endShape = m.getInt("end_shape_index") + offset,
                        roundaboutExit = m.optInt("roundabout_exit_count", 0),
                        bearingBefore = m.optInt("bearing_before", 0),
                        bearingAfter = m.optInt("bearing_after", 0),
                    )
                }
            }
            val summary = trip.getJSONObject("summary")
            return Route(shape, maneuvers, summary.getDouble("length") * 1000, summary.getDouble("time"), costing)
        }
    }
}
