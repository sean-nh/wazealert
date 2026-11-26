package app.wazealert.driver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File

/** Live map: your position and heading plus the cameras and community reports the app is watching. OpenStreetMap tiles, no API key. */
class MapActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var legend: TextView
    private lateinit var speedView: TextView
    private val arrowIcon by lazy { arrow() }   // built once, not on every 2-second redraw
    private var follow = true
    private var me: Marker? = null
    private val shown = ArrayList<Marker>()
    private val icons = HashMap<Int, BitmapDrawable>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Map"
        Configuration.getInstance().apply {
            userAgentValue = packageName            // OSM tile servers require an identifying user agent
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0
            controller.setZoom(15.0)
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_MOVE) follow = false; false }
        }
        val recenter = Button(this).apply { text = "Follow me"; setOnClickListener { follow = true; AlertState.map.value?.let { draw(it) } } }
        legend = TextView(this).apply {
            text = "Waiting for GPS..."
            setBackgroundColor(Color.argb(200, 255, 255, 255)); setTextColor(Color.BLACK); setPadding(16, 8, 16, 8)
            textSize = 12f
        }
        speedView = TextView(this).apply {
            text = "--"; textSize = 28f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundColor(Color.argb(210, 20, 20, 20)); setTextColor(Color.WHITE); setPadding(24, 8, 24, 8)
        }
        setContentView(FrameLayout(this).apply {
            addView(map)
            addView(legend, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            addView(speedView, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(24, 0, 0, 24) })
            addView(recenter, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 24, 24) })
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { AlertState.map.collect { s -> if (s != null) draw(s) } }
        }
    }

    private fun draw(s: MapSnap) {
        val here = GeoPoint(s.lat, s.lon)
        val metric = Prefs.metric(this)
        val m = me ?: Marker(map).also { it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); it.title = "You"; me = it; map.overlays.add(it) }
        m.icon = arrowIcon
        speedView.text = Fmt.speed(s.speedMps, metric)
        m.rotation = -(s.heading ?: 0.0).toFloat()
        m.isFlat = true
        m.position = here
        map.overlays.removeAll(shown.toSet()); shown.clear()
        for (a in s.alerts) {
            val mk = Marker(map).apply {
                position = GeoPoint(a.lat, a.lon)
                icon = icon(colorOf(a))
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = a.label + " - " + Fmt.dist(a.distanceM, metric)
                snippet = a.street + (if (a.limitKmh > 0) "  (limit ${Fmt.limit(a.limitKmh, metric)})" else "")
            }
            shown.add(mk); map.overlays.add(mk)
        }
        me?.let { map.overlays.remove(it); map.overlays.add(it) }   // keep your arrow on top
        if (follow) map.controller.animateTo(here)
        legend.text = "Purple = speed camera   Red = red-light camera   Blue = police   Orange = hazard   Dark red = accident   (${s.alerts.size} shown)"
        map.invalidate()
    }

    private fun colorOf(a: Alert) = when {
        a.type == "POLICE" -> 0xFF1565C0.toInt()
        a.type == "HAZARD" -> 0xFFEF6C00.toInt()
        a.type == "ACCIDENT" -> 0xFF8B0000.toInt()
        a.subtype == "CAMERA_REDLIGHT" -> 0xFFD32F2F.toInt()
        else -> 0xFF7E57C2.toInt()
    }

    private fun icon(color: Int) = icons.getOrPut(color) {
        val d = resources.displayMetrics.density
        val sz = (22 * d).toInt()
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.WHITE; c.drawCircle(sz / 2f, sz / 2f, sz / 2f, p)
        p.color = color; c.drawCircle(sz / 2f, sz / 2f, sz / 2f - 3 * d, p)
        BitmapDrawable(resources, b)
    }

    private fun arrow(): BitmapDrawable {
        val d = resources.displayMetrics.density
        val sz = (34 * d).toInt()
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply { moveTo(sz / 2f, 2 * d); lineTo(sz - 6 * d, sz - 4 * d); lineTo(sz / 2f, sz - 10 * d); lineTo(6 * d, sz - 4 * d); close() }
        p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = 4 * d; c.drawPath(path, p)
        p.color = 0xFF0D47A1.toInt(); p.style = Paint.Style.FILL; c.drawPath(path, p)
        return BitmapDrawable(resources, b)
    }

    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { map.onPause(); super.onPause() }
}