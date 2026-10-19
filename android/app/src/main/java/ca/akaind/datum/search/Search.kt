package ca.akaind.datum.search

import ca.akaind.datum.Config
import ca.akaind.datum.geo.LatLon
import ca.akaind.datum.net.Http
import org.json.JSONObject
import java.util.Locale

/** A place someone can search for and drive to. */
data class Place(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    /** Colour group shared with the web app: food, shop, health, civic, rec, transit, serv, place, street, area, water. */
    val group: String = "serv",
) {
    val latLon get() = LatLon(lat, lon)
}

interface SearchService {
    suspend fun search(query: String, near: LatLon?, limit: Int = 8): List<Place>
    suspend fun reverse(at: LatLon): Place?
}

/** Search on Photon (komoot), the same geocoder the web app uses. */
class PhotonSearch(private val base: String = Config.SEARCH) : SearchService {
    override suspend fun search(query: String, near: LatLon?, limit: Int): List<Place> {
        val q = Http.query("q" to query, "lat" to near?.lat, "lon" to near?.lon, "limit" to limit, "lang" to lang())
        return parse(Http.get("$base/api?$q"))
    }

    override suspend fun reverse(at: LatLon): Place? {
        val q = Http.query("lat" to at.lat, "lon" to at.lon, "lang" to lang())
        return parse(Http.get("$base/reverse?$q")).firstOrNull()
    }

    private fun lang() = if (Locale.getDefault().language in setOf("de", "fr")) Locale.getDefault().language else "en"

    companion object {
        fun parse(json: String): List<Place> {
            val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
            val out = ArrayList<Place>()
            val seen = HashSet<String>()
            for (i in 0 until features.length()) {
                val f = features.getJSONObject(i)
                val c = f.getJSONObject("geometry").getJSONArray("coordinates")
                val p = f.getJSONObject("properties")
                fun s(k: String) = p.optString(k).takeIf { it.isNotBlank() }
                val street = listOfNotNull(s("housenumber"), s("street")).joinToString(" ").ifBlank { null }
                val name = s("name") ?: street ?: s("city") ?: continue
                val locality = s("city") ?: s("district") ?: s("county")
                val address = listOfNotNull(street.takeIf { it != name }, locality.takeIf { it != name }, s("state"))
                    .distinct().joinToString(", ")
                val key = "$name|$address"
                if (!seen.add(key)) continue
                out += Place(name, address, c.getDouble(1), c.getDouble(0), group(s("osm_key"), s("osm_value")))
            }
            return out
        }

        /** Same grouping as photonGroup() in index.html. */
        fun group(k: String?, v: String?): String = when {
            k == "place" || k == "boundary" -> "place"
            k == "highway" -> "street"
            k == "natural" || k == "waterway" -> if (v == "water" || k == "waterway") "water" else "area"
            k == "leisure" -> "rec"
            k == "shop" -> "shop"
            k == "healthcare" -> "health"
            k == "railway" || k == "public_transport" || k == "aeroway" -> "transit"
            k == "tourism" -> if (v in setOf("museum", "gallery", "attraction", "artwork", "viewpoint")) "civic" else "serv"
            k == "historic" -> "civic"
            k == "amenity" -> when (v) {
                "restaurant", "cafe", "fast_food", "bar", "pub", "ice_cream", "food_court" -> "food"
                "hospital", "pharmacy", "doctors", "dentist", "clinic", "veterinary" -> "health"
                "school", "college", "university", "library", "townhall", "theatre", "cinema", "arts_centre",
                "place_of_worship", "police", "fire_station", "post_office" -> "civic"
                "fuel", "charging_station", "parking", "bus_station", "ferry_terminal", "bicycle_rental" -> "transit"
                else -> "serv"
            }
            else -> "serv"
        }
    }
}
