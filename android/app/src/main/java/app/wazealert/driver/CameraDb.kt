package app.wazealert.driver

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Speed/red-light camera database published by wzsabre.rocks (ExCam format:
 * XZ-compressed JSON lines {"lat","lon","str","flg","spd","dir"}; "World cameras by @schuster").
 * Index: https://wzsabre.rocks/cameras -> {"_link":{"dataUrl":...,"date":...}}
 *
 * A snapshot is bundled in the app (assets/cameras.xz) so the very first launch works with no connection;
 * a fresher copy is downloaded in the background when the cache is older than a week and used from the next launch.
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

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, "cameras.xz")

    /** Loads the cached database (copying the bundled one on first run). Never needs the network. Blocking. */
    fun load(ctx: Context) {
        val f = cacheFile(ctx)
        if (!f.exists()) {
            ctx.assets.open("cameras.xz").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            f.setLastModified(0)          // bundled snapshot counts as stale, so refresh() will fetch a newer one
            DiagLog.log("camera database: using the copy bundled in the app")
        }
        parse(f)
    }

    /** Downloads a newer database if the cache is older than a week. Takes effect on the next launch. Blocking. */
    fun refresh(ctx: Context): Boolean {
        val f = cacheFile(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return false
        val dataUrl = JSONObject(get(INDEX).readBytes().decodeToString()).getJSONObject("_link").getString("dataUrl")
        val tmp = File(ctx.filesDir, "cameras.xz.tmp")
        get(dataUrl).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        if (tmp.length() < 100_000) { tmp.delete(); error("downloaded camera file is too small") }   // guard against an error page
        f.delete(); tmp.renameTo(f)
        DiagLog.log("camera database: downloaded a newer copy (used from next launch)")
        return true
    }

    private fun parse(f: File) {
        val t0 = System.currentTimeMillis()
        // One streaming pass with primitive arrays: the old per-line JSONObject parse allocated ~6 million
        // objects (about 190 MB of garbage) and made low-memory phones stutter at startup.
        var n = 0
        var cap = 131_072
        var la = DoubleArray(cap); var lo = DoubleArray(cap)
        var fl = IntArray(cap); var sp = IntArray(cap)
        var st = arrayOfNulls<String>(cap); var dr = arrayOfNulls<IntArray>(cap)
        // The file is JSON objects separated by newlines; lenient mode accepts several top-level values.
        JsonReader(XZInputStream(f.inputStream().buffered()).bufferedReader()).use { r ->
            r.isLenient = true
            while (r.peek() != JsonToken.END_DOCUMENT) {
                var clat = Double.NaN; var clon = Double.NaN
                var cstr = ""; var cflg = 0; var cspd = 0; var cdir: IntArray? = null
                r.beginObject()
                while (r.hasNext()) {
                    when (r.nextName()) {
                        "lat" -> clat = r.nextDouble()
                        "lon" -> clon = r.nextDouble()
                        "str" -> if (r.peek() == JsonToken.NULL) r.nextNull() else cstr = r.nextString()
                        "flg" -> if (r.peek() == JsonToken.NULL) r.nextNull() else cflg = r.nextInt()
                        "spd" -> if (r.peek() == JsonToken.NULL) r.nextNull() else cspd = r.nextDouble().toInt()
                        "dir" -> if (r.peek() == JsonToken.NULL) r.nextNull() else {
                            val tmp = ArrayList<Int>(2)
                            r.beginArray(); while (r.hasNext()) tmp.add(r.nextDouble().toInt()); r.endArray()
                            cdir = if (tmp.isEmpty()) null else tmp.toIntArray()
                        }
                        else -> r.skipValue()        // e.g. the {"_meta": ...} header line
                    }
                }
                r.endObject()
                if (clat.isNaN() || clon.isNaN()) continue   // header line or malformed row
                if (n == cap) {
                    cap = cap + cap / 2
                    la = la.copyOf(cap); lo = lo.copyOf(cap); fl = fl.copyOf(cap); sp = sp.copyOf(cap)
                    st = st.copyOf(cap); dr = dr.copyOf(cap)
                }
                la[n] = clat; lo[n] = clon; st[n] = cstr; fl[n] = cflg; sp[n] = cspd; dr[n] = cdir; n++
            }
        }
        lat = la.copyOf(n); lon = lo.copyOf(n); flag = fl.copyOf(n); limit = sp.copyOf(n)
        street = Array(n) { st[it] ?: "" }; dirs = Array(n) { dr[it] }
        val ms = System.currentTimeMillis() - t0
        Log.i("CameraDb", "loaded $n cameras in $ms ms")
        DiagLog.log("camera database loaded: $n cameras in $ms ms")
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
