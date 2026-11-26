package app.wazealert.driver

import android.content.Context
import java.util.Locale
import java.util.UUID

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun bool(c: Context, k: String, d: Boolean) = sp(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = sp(c).edit().putBoolean(k, v).apply()
    fun int(c: Context, k: String, d: Int) = sp(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = sp(c).edit().putInt(k, v).apply()
    fun str(c: Context, k: String, d: String) = sp(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = sp(c).edit().putString(k, v).apply()

    // Miles by default only in the few countries that use them.
    fun metric(c: Context) = bool(c, "metric", Locale.getDefault().country !in setOf("US", "LR", "MM"))
    fun voiceCamera(c: Context) = bool(c, "voiceCamera", true)
    fun voicePolice(c: Context) = bool(c, "voicePolice", true)
    fun voiceHazard(c: Context) = bool(c, "voiceHazard", true)
    fun beep(c: Context) = bool(c, "beep", true)
    fun dirFilter(c: Context) = bool(c, "dirFilter", true)
    /** Warning lead time in seconds: 30 / 45 / 70. */
    fun lead(c: Context) = int(c, "lead", 45)

    /** Which alerts the list shows: "front" (ahead of me), "path" (on my road, approx.), "radius" (all around). */
    fun mode(c: Context) = str(c, "mode", "front")
    /** Alert radius in meters: 2000 / 5000 / 10000. */
    fun radiusM(c: Context) = int(c, "radiusM", 5000)
    /** Hide community/own reports whose last confirmation is older than this many minutes. */
    fun maxAgeMin(c: Context) = int(c, "maxAgeMin", 120)

    /** Community server base URL, no trailing slash; empty = community features off. */
    fun serverUrl(c: Context): String = str(c, "serverUrl", "").trim().trimEnd('/').takeIf { it.startsWith("http") } ?: ""

    /** Random anonymous ID, created once; the server salts and hashes it. */
    fun deviceId(c: Context): String {
        var id = str(c, "deviceId", "")
        if (id.isEmpty()) { id = UUID.randomUUID().toString(); setStr(c, "deviceId", id) }
        return id
    }
}
