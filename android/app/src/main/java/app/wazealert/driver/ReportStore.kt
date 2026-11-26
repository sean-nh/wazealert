package app.wazealert.driver

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Alerts the user reported from this phone (police / hazard). Stored locally; they expire after 2 hours. */
object ReportStore {
    private const val TTL_MS = 2L * 3600 * 1000
    private fun sp(c: Context) = c.getSharedPreferences("reports", Context.MODE_PRIVATE)

    @Synchronized
    fun add(c: Context, type: String, lat: Double, lon: Double) {
        val arr = load(c)
        arr.put(JSONObject().put("t", type).put("lat", lat).put("lon", lon).put("ts", System.currentTimeMillis()))
        sp(c).edit().putString("list", arr.toString()).apply()
    }

    @Synchronized
    fun active(c: Context, lat: Double, lon: Double, maxAgeMs: Long = TTL_MS): List<Alert> {
        val now = System.currentTimeMillis()
        val arr = load(c)
        val keep = JSONArray()
        val out = ArrayList<Alert>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (now - o.getLong("ts") > TTL_MS) continue
            keep.put(o)
            if (now - o.getLong("ts") > maxAgeMs) continue   // kept on disk, just hidden by the user's age filter
            out.add(Alert("user${o.getLong("ts")}", o.getString("t"), "USER", "reported by you",
                o.getDouble("lat"), o.getDouble("lon"),
                distanceM = Geo.distance(lat, lon, o.getDouble("lat"), o.getDouble("lon"))))
        }
        if (keep.length() != arr.length()) sp(c).edit().putString("list", keep.toString()).apply()
        return out
    }

    private fun load(c: Context) = try { JSONArray(sp(c).getString("list", "[]")) } catch (e: Exception) { JSONArray() }
}
