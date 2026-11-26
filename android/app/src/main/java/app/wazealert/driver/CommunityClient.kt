package app.wazealert.driver

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the WazeAlert community server (see WazeAlert-Server). All calls block; run off the main thread. */
object CommunityClient {
    data class Risk(val score: Int, val level: String)

    private fun call(method: String, url: String, body: JSONObject? = null): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "WazeAlert/1.0")
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: "{}"
            if (code >= 400) error("HTTP $code")
            return JSONObject(text)
        } finally { c.disconnect() }
    }

    /** Active reports near a point whose last confirmation is no older than [maxSeenSec]. */
    fun fetch(base: String, lat: Double, lon: Double, radiusM: Double, maxSeenSec: Int): List<Alert> {
        val arr = call("GET", "$base/v1/reports?lat=$lat&lon=$lon&radius=${radiusM.toInt()}").getJSONArray("reports")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.getInt("seenSec") <= maxSeenSec }
            .map {
                val mins = it.getInt("seenSec") / 60
                val score = it.getInt("score")
                Alert("srv" + it.getString("id"), it.getString("type"), "COMMUNITY",
                    "community · ${if (mins < 1) "just now" else "$mins min ago"} · $score confirmed",
                    it.getDouble("lat"), it.getDouble("lon"),
                    distanceM = Geo.distance(lat, lon, it.getDouble("lat"), it.getDouble("lon")))
            }
    }

    fun post(base: String, type: String, lat: Double, lon: Double, heading: Double?, deviceId: String) {
        val b = JSONObject().put("type", type).put("lat", lat).put("lon", lon).put("deviceId", deviceId)
        if (heading != null) b.put("heading", heading)
        call("POST", "$base/v1/reports", b)
    }

    fun vote(base: String, alertId: String, deviceId: String, vote: Int) {
        call("POST", "$base/v1/reports/${alertId.removePrefix("srv")}/vote",
            JSONObject().put("deviceId", deviceId).put("vote", vote))
    }

    fun risk(base: String, lat: Double, lon: Double, tzMinutes: Int): Risk {
        val o = call("GET", "$base/v1/risk?lat=$lat&lon=$lon&radius=3000&tz=$tzMinutes")
        return Risk(o.getInt("score"), o.getString("level"))
    }
}
