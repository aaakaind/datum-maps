package ca.akaind.datum.car

import android.content.Context
import ca.akaind.datum.search.Place
import org.json.JSONArray
import org.json.JSONObject

/** The last few destinations, kept only on this phone. */
class Recents(context: Context) {
    private val prefs = context.getSharedPreferences("datum", Context.MODE_PRIVATE)

    fun all(): List<Place> = runCatching {
        val a = JSONArray(prefs.getString(KEY, "[]"))
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Place(o.getString("n"), o.optString("a"), o.getDouble("lat"), o.getDouble("lon"), o.optString("g", "serv"))
        }
    }.getOrDefault(emptyList())

    fun add(p: Place) {
        val list = (listOf(p) + all().filterNot { it.name == p.name && it.address == p.address }).take(MAX)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("a", it.address).put("lat", it.lat).put("lon", it.lon).put("g", it.group)) }
        prefs.edit().putString(KEY, a.toString()).apply()
    }

    companion object {
        private const val KEY = "recent_destinations"
        private const val MAX = 6
    }
}
