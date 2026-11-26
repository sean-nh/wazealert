package app.wazealert.driver

import android.content.Context
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speed/red-light camera database published by wzsabre.rocks (ExCam format:
 * XZ-compressed JSON lines {"lat","lon","str","flg","spd","dir"}; "World cameras by @schuster").
 * Index: https://wzsabre.rocks/cameras -> {"_link":{"dataUrl":...,"date":...}}
 */
object CameraDb {
    private const val INDEX = "https://wzsabre.rocks/cameras"
    private const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

    private var lat = DoubleArray(0)
    private var lon = DoubleArray(0)
    private var street = emptyArray<String>()
    private var flag = IntArray(0)
    private var limit = IntArray(0)
    private var dirs = emptyArray<IntArray?>()
    val size get() = lat.size
    val loaded get() = lat.isNotEmpty()

    /** Downloads (if stale) and loads the DB. Blocking; call off the main thread. */
    fun load(ctx: Context) {
        val f = File(ctx.filesDir, "cameras.xz")
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            try {
                val dataUrl = JSONObject(get(INDEX).readBytes().decodeToString())
                    .getJSONObject("_link").getString("dataUrl")
                val tmp = File(ctx.filesDir, "cameras.xz.tmp")
                get(dataUrl).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            } catch (e: Exception) {
                if (!f.exists()) throw e // no cache to fall back on
            }
        }
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        val st = ArrayList<String>(); val fl = ArrayList<Int>(); val sp = ArrayList<Int>()
        val dr = ArrayList<IntArray?>()
        XZInputStream(f.inputStream().buffered()).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (!line.startsWith("{\"lat\"")) return@forEach
                val o = JSONObject(line)
                la.add(o.getDouble("lat")); lo.add(o.getDouble("lon"))
                st.add(if (o.isNull("str")) "" else o.optString("str"))
                fl.add(o.optInt("flg"))
                sp.add(if (o.isNull("spd")) 0 else o.optInt("spd"))
                val d = o.optJSONArray("dir")
                dr.add(if (d == null || d.length() == 0) null else IntArray(d.length()) { d.getInt(it) })
            }
        }
        lat = la.toDoubleArray(); lon = lo.toDoubleArray()
        street = st.toTypedArray(); flag = fl.toIntArray(); limit = sp.toIntArray(); dirs = dr.toTypedArray()
    }

    fun near(lat0: Double, lon0: Double, radiusM: Double): List<Alert> {
        val dLat = radiusM / 111_000.0
        val dLon = dLat / Math.cos(Math.toRadians(lat0)).coerceAtLeast(0.01)
        val out = ArrayList<Alert>()
        for (i in lat.indices) {
            if (Math.abs(lat[i] - lat0) > dLat || Math.abs(lon[i] - lon0) > dLon) continue
            val d = Geo.distance(lat0, lon0, lat[i], lon[i])
            if (d > radiusM) continue
            out.add(Alert("cam$i", "CAMERA", if (flag[i] == 2) "CAMERA_REDLIGHT" else "CAMERA_SPEED",
                street[i], lat[i], lon[i], distanceM = d, dirs = dirs[i], limitKmh = limit[i]))
        }
        return out.sortedBy { it.distanceM }
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 60000
        setRequestProperty("User-Agent", "WazeAlert/1.0")
    }.inputStream
}
