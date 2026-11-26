package app.wazealert.driver

import android.content.Intent
import android.content.res.Configuration as UiConfig
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The main driving screen: a navigation-style map (rotates to your heading, zooms out as you speed up, dark at night)
 * with one big card for the next alert, your speed, and large report / mute / menu buttons.
 * Map tiles: OpenStreetMap data rendered by CARTO. They need internet (and are cached); alerts do not.
 */
class MapActivity : AppCompatActivity() {
    private lateinit var map: MapView
    private lateinit var card: LinearLayout
    private lateinit var cardTitle: TextView
    private lateinit var cardSub: TextView
    private lateinit var cardIcon: android.widget.ImageView
    private lateinit var limitSign: TextView
    private lateinit var voteRow: LinearLayout
    private lateinit var speedView: TextView
    private lateinit var muteBtn: Button
    private lateinit var recenterBtn: TextView
    private lateinit var compassBtn: TextView

    private var follow = true
    private var lastTouch = 0L
    private var headingUp = true
    private var zoomAdjust = 0.0           // set by the +/- buttons, added to the automatic zoom
    private var lastOrient = 0f
    private var lastState = UiState()
    private var lastSnap: MapSnap? = null
    private var nearestId: String? = null
    private var me: Marker? = null
    private var route: Polyline? = null
    private val shown = ArrayList<Marker>()
    private val icons = HashMap<String, BitmapDrawable>()
    private val night get() = (resources.configuration.uiMode and UiConfig.UI_MODE_NIGHT_MASK) == UiConfig.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Float) = v * resources.displayMetrics.density

    private fun round(color: Int, radiusDp: Int = 28, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radiusDp.toFloat()); if (stroke != 0) setStroke(dp(2), stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        headingUp = Prefs.bool(this, "headingUp", true)
        Configuration.getInstance().apply {
            userAgentValue = packageName            // tile servers require an identifying user agent
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
            tileFileSystemCacheMaxBytes = 400L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 300L * 1024 * 1024
        }
        map = MapView(this).apply {
            setTileSource(tileSource())
            overlayManager.tilesOverlay.loadingBackgroundColor = if (night) 0xFF1B1B1B.toInt() else 0xFFEEEEEE.toInt()
            setMultiTouchControls(true)
            isTilesScaledToDpi = true
            minZoomLevel = 4.0; maxZoomLevel = 19.0
            controller.setZoom(16.0)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_MOVE || e.action == MotionEvent.ACTION_POINTER_DOWN) { follow = false; lastTouch = System.currentTimeMillis(); showRecenter() }
                false
            }
        }

        // ---- alert card (top) ----
        cardIcon = android.widget.ImageView(this)
        cardTitle = TextView(this).apply { textSize = 22f; setTypeface(typeface, Typeface.BOLD) }
        cardSub = TextView(this).apply { textSize = 15f }
        limitSign = TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 24f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.BLACK)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE); setStroke(dp(6), 0xFFD32F2F.toInt()) }
            visibility = View.GONE
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cardTitle); addView(cardSub)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(cardIcon, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, dp(8), 0) })
            addView(limitSign, LinearLayout.LayoutParams(dp(60), dp(60)))
        }
        voteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; visibility = View.GONE
            addView(pill("Still there", 0xFF2E7D32.toInt(), 44) { vote(1) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, dp(8), dp(6), 0) })
            addView(pill("It's gone", 0xFF757575.toInt(), 44) { vote(-1) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(6), dp(8), 0, 0) })
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); elevation = dpf(6f)
            addView(row); addView(voteRow)
            setOnClickListener { if (lastState.gpsOff) startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }

        // ---- speed bubble ----
        speedView = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); elevation = dpf(6f)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xE6212121.toInt()) }
        }

        // ---- side buttons ----
        fun side(label: String, size: Float, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = size; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); elevation = dpf(4f)
            setTextColor(if (night) Color.WHITE else Color.BLACK)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (night) 0xF0303030.toInt() else 0xF0FFFFFF.toInt()) }
            setOnClickListener { onClick() }
        }
        recenterBtn = side("\u25CE", 24f) { follow = true; lastSnap?.let { draw(it) }; recenterBtn.visibility = View.GONE }.apply { visibility = View.GONE }
        compassBtn = side("", 18f) {
            headingUp = !headingUp; Prefs.setBool(this@MapActivity, "headingUp", headingUp); updateCompass()
            if (!headingUp) map.setMapOrientation(0f); lastSnap?.let { draw(it) }
        }
        val zoomIn = side("+", 26f) { if (follow) { zoomAdjust += 0.7; lastSnap?.let { draw(it, true) } } else map.controller.zoomIn() }
        val zoomOut = side("\u2212", 26f) { if (follow) { zoomAdjust -= 0.7; lastSnap?.let { draw(it, true) } } else map.controller.zoomOut() }
        updateCompass()
        val sideCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            for (v in listOf(compassBtn, zoomIn, zoomOut, recenterBtn)) addView(v, LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, dp(5), 0, dp(5)) })
        }

        // ---- bottom buttons ----
        muteBtn = pill(if (AlertState.muted) "Unmute" else "Mute", 0xFF455A64.toInt(), 56) {
            AlertState.muted = !AlertState.muted; muteBtn.text = if (AlertState.muted) "Unmute" else "Mute"
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            fun add(b: Button) = addView(b, LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            add(pill("Police", 0xFF1565C0.toInt(), 56) { report("REPORT_POLICE", "Police") })
            add(pill("Hazard", 0xFFEF6C00.toInt(), 56) { report("REPORT_HAZARD", "Hazard") })
            add(muteBtn)
            add(pill("Menu", 0xFF37474F.toInt(), 56) { menu() })
        }
        val credit = TextView(this).apply {
            text = "\u00A9 OpenStreetMap contributors"; textSize = 9f
            setTextColor(if (night) 0xFFBBBBBB.toInt() else 0xFF555555.toInt()); setPadding(dp(4), 0, dp(4), 0)
        }

        setContentView(FrameLayout(this).apply {
            addView(map)
            addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(10), dp(10), dp(10), 0) })
            addView(sideCol, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, 0, dp(10), 0) })
            addView(speedView, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(84)) })
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(14)) })
            addView(credit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), 0) })
        })
        applyNight()
        styleCard(Color.WHITE, Color.BLACK)
        setSpeed(0.0, false)
        render("Starting...", "")

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AlertState.state.collect { st -> lastState = st; if (lastSnap == null) idleCard(st) } }
                launch { AlertState.map.collect { s -> if (s != null) draw(s) } }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    /** Standard OpenStreetMap tiles (no key needed). Night mode inverts the colours, see applyNight(). */
    private fun tileSource() = org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK

    private fun applyNight() {
        map.overlayManager.tilesOverlay.setColorFilter(if (night) org.osmdroid.views.overlay.TilesOverlay.INVERT_COLORS else null)
    }
    private fun pill(label: String, color: Int, heightDp: Int, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        background = round(color, heightDp / 2); elevation = dpf(4f); minHeight = 0; minimumHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setOnClickListener { onClick() }
    }

    private fun updateCompass() { compassBtn.text = if (headingUp) "\u25B2" else "N" }
    private fun showRecenter() { recenterBtn.visibility = View.VISIBLE }

    private fun report(action: String, what: String) {
        if (!AlertState.running || !AlertState.hasFix) { Toast.makeText(this, "Waiting for a GPS fix", Toast.LENGTH_SHORT).show(); return }
        startService(Intent(this, AlertService::class.java).setAction(action))
        Toast.makeText(this, if (Prefs.serverUrl(this).isEmpty()) "$what marked here (saved on this phone only)" else "$what reported to other drivers",
            Toast.LENGTH_SHORT).show()
    }

    private fun vote(v: Int) {
        val id = nearestId ?: return
        startService(Intent(this, AlertService::class.java).setAction("VOTE").putExtra("id", id).putExtra("vote", v))
        Toast.makeText(this, if (v == 1) "Thanks, confirmed" else "Thanks, marked as gone", Toast.LENGTH_SHORT).show()
    }

    private fun menu() {
        val items = arrayOf("Alert list", "Pre-drive check", "Settings and about", "Stop WazeAlert")
        AlertDialog.Builder(this).setItems(items) { _, i ->
            when (i) {
                0 -> finish()
                1 -> startActivity(Intent(this, SelfCheckActivity::class.java))
                2 -> startActivity(Intent(this, SettingsActivity::class.java))
                3 -> { startService(Intent(this, AlertService::class.java).setAction("STOP")); finishAffinity() }
            }
        }.show()
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun styleCard(bg: Int, fg: Int) {
        card.background = round(bg, 18)
        cardTitle.setTextColor(fg); cardSub.setTextColor(fg)
    }

    private fun setSpeed(mps: Double, over: Boolean) {
        val metric = Prefs.metric(this)
        val n = if (metric) "%.0f".format(mps * 3.6) else "%.0f".format(mps * 2.23694)
        val unit = if (metric) "km/h" else "mph"
        val sp = SpannableString("$n\n$unit")
        sp.setSpan(AbsoluteSizeSpan(30, true), 0, n.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(AbsoluteSizeSpan(12, true), n.length, sp.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        speedView.text = sp
        (speedView.background as GradientDrawable).setColor(if (over) 0xF0C62828.toInt() else 0xE6212121.toInt())
    }

    /** Sets the card text and colours by urgency: red under 400 m, amber under 1 km, otherwise plain. */
    private fun render(title: String, sub: String, distM: Double? = null, icon: BitmapDrawable? = null) {
        when {
            distM != null && distM < 400 -> styleCard(0xFFC62828.toInt(), Color.WHITE)
            distM != null && distM < 1000 -> styleCard(0xFFFFB300.toInt(), Color.BLACK)
            night -> styleCard(0xF0303030.toInt(), Color.WHITE)
            else -> styleCard(0xF8FFFFFF.toInt(), Color.BLACK)
        }
        cardTitle.text = title; cardSub.text = sub
        cardIcon.setImageDrawable(icon?.let { BitmapDrawable(resources, it.bitmap) });   // own copy: the cached drawable is also used by the map marker
         cardIcon.visibility = if (icon == null) View.GONE else View.VISIBLE
    }

    /** Card when nothing is ahead (or no position yet): says what the app is doing in plain words. */
    private fun idleCard(st: UiState, cams: Int = 0) {
        limitSign.visibility = View.GONE; voteRow.visibility = View.GONE; nearestId = null
        val s = st.status
        when {
            st.gpsOff -> render("Location is off", "Tap here to turn it on")
            s.startsWith("GPS signal lost") -> render("GPS signal lost", "Alerts resume when the signal returns")
            s.startsWith("Waiting for GPS") -> render("Finding your location...", "Go outside or near a window if this takes long")
            s.startsWith("Loading camera") -> render("Loading cameras...", "One moment")
            s.startsWith("Camera data failed") -> render("Camera data problem", "Retrying. Check the pre-drive check in Menu")
            s.startsWith("Stopped") -> render("Stopped", "Open the app again to restart")
            else -> render("All clear", "Watching for cameras and alerts ahead" + if (cams > 0) "  \u00B7  $cams nearby" else "")
        }
    }

    private fun limitNumber(kmh: Int, metric: Boolean) = if (metric) kmh else ((kmh * 0.621371) / 5).let { Math.round(it).toInt() * 5 }

    private fun draw(s: MapSnap, forceZoom: Boolean = false) {
        lastSnap = s
        val metric = Prefs.metric(this)
        val here = GeoPoint(s.lat, s.lon)
        if (!follow && System.currentTimeMillis() - lastTouch > 12_000) { follow = true; recenterBtn.visibility = View.GONE }

        // The alert the card talks about: nearest alert ahead that we speak for.
        val dirFilter = Prefs.dirFilter(this)
        val nearest = s.alerts.firstOrNull { a ->
            a.type in AlertService.speakTypes && Geo.isAhead(s.heading, Geo.bearing(s.lat, s.lon, a.lat, a.lon)) &&
                (!dirFilter || a.type != "CAMERA" || Geo.dirMatches(s.heading, a.dirs))
        }
        val over = nearest != null && s.heading != null && nearest.distanceM < 1500 && Geo.overLimit(s.speedMps, nearest.limitKmh)
        setSpeed(s.speedMps, over)

        if (nearest == null) idleCard(lastState, s.alerts.count { it.type == "CAMERA" })
        else {
            nearestId = nearest.id
            val sub = Fmt.dist(nearest.distanceM, metric) + (if (nearest.street.isNotBlank()) "  \u00B7  ${nearest.street}" else "") +
                (if (over) "  \u00B7  SLOW DOWN" else "")
            render(nearest.label, sub, nearest.distanceM, icon(nearest, true))
            if (nearest.limitKmh > 0) { limitSign.text = limitNumber(nearest.limitKmh, metric).toString(); limitSign.visibility = View.VISIBLE }
            else limitSign.visibility = View.GONE
            voteRow.visibility = if (nearest.isCommunity && nearest.distanceM < 2000) View.VISIBLE else View.GONE
        }

        // map: orientation, zoom, centre
        val moving = s.heading != null && s.speedMps >= 2.0
        if (follow) {
            map.setMapCenterOffset(0, if (headingUp && moving) (map.height * 0.18).toInt() else 0)   // keep the road ahead visible
            if (headingUp && moving) {
                val target = (-s.heading!!).toFloat()
                if (abs(((target - lastOrient) + 540f) % 360f - 180f) > 3f) { lastOrient = target; map.setMapOrientation(target) }
            }
        }
        val rot = (s.heading ?: 0.0).toFloat()
        val m = me ?: Marker(map).also { it.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); it.title = "You"; me = it; map.overlays.add(it) }
        m.icon = icon("me", 0, false)
        m.isFlat = true
        m.rotation = -rot
        m.position = here

        map.overlays.removeAll(shown.toSet()); shown.clear()
        route?.let { map.overlays.remove(it) }; route = null
        for (a in s.alerts.asReversed()) {
            val isNearest = a.id == nearest?.id
            shown.add(Marker(map).apply {
                position = GeoPoint(a.lat, a.lon)
                icon = icon(a, isNearest)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = a.label + " - " + Fmt.dist(a.distanceM, metric)
                snippet = a.street + (if (a.limitKmh > 0) "  (limit ${Fmt.limit(a.limitKmh, metric)})" else "")
                setOnMarkerClickListener { mk, _ -> if (mk.isInfoWindowShown) mk.closeInfoWindow() else mk.showInfoWindow(); true }
            })
        }
        shown.forEach { map.overlays.add(it) }
        if (nearest != null && nearest.distanceM < 2500) {
            val line = Polyline(map).apply {
                setPoints(listOf(here, GeoPoint(nearest.lat, nearest.lon)))
                outlinePaint.color = colorOf(nearest); outlinePaint.strokeWidth = dpf(4f)
                outlinePaint.pathEffect = DashPathEffect(floatArrayOf(dpf(10f), dpf(8f)), 0f)
            }
            route = line
            map.overlays.add(0, line)
        }
        me?.let { map.overlays.remove(it); map.overlays.add(it) }   // keep your arrow on top

        if (follow) {
            val z = autoZoom(s.speedMps) + zoomAdjust
            if (forceZoom || abs(map.zoomLevelDouble - z) > 0.25) map.controller.animateTo(here, z, 700L) else map.controller.animateTo(here)
        }
        map.invalidate()
    }

    /** Zoom out as you go faster so there is always roughly the next 30 seconds on screen. */
    private fun autoZoom(mps: Double): Double {
        val kmh = mps * 3.6
        return when {
            kmh < 8 -> 17.0
            kmh < 40 -> 16.4
            kmh < 70 -> 15.7
            kmh < 100 -> 15.0
            else -> 14.4
        } - 0.6   // tiles are drawn dpi-scaled, so web-map zoom numbers read about half a level too tight
    }

    private fun colorOf(a: Alert) = when {
        a.type == "POLICE" -> 0xFF1565C0.toInt()
        a.type == "HAZARD" -> 0xFFEF6C00.toInt()
        a.type == "ACCIDENT" -> 0xFF8B0000.toInt()
        a.subtype == "CAMERA_REDLIGHT" -> 0xFFD32F2F.toInt()
        else -> 0xFF7E57C2.toInt()
    }

    private fun icon(a: Alert, big: Boolean) = icon(when {
        a.type == "POLICE" -> "police"
        a.type == "HAZARD" -> "hazard"
        a.type == "ACCIDENT" -> "accident"
        a.subtype == "CAMERA_REDLIGHT" -> "redlight"
        else -> "camera"
    }, colorOf(a), big)

    /** Glyph markers drawn once per kind and cached: camera, red light, police star, hazard triangle, accident, and your arrow. */
    private fun icon(kind: String, color: Int, big: Boolean): BitmapDrawable = icons.getOrPut("$kind/$big") {
        val sz = dp(if (kind == "me") 40 else if (big) 44 else 30)
        val b = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val h = sz / 2f
        if (kind == "me") {
            val path = Path().apply { moveTo(h, 2f); lineTo(sz - dp(6).toFloat(), sz - dp(5).toFloat()); lineTo(h, sz - dp(12).toFloat()); lineTo(dp(6).toFloat(), sz - dp(5).toFloat()); close() }
            p.color = Color.WHITE; p.style = Paint.Style.STROKE; p.strokeWidth = dpf(5f); p.strokeJoin = Paint.Join.ROUND; c.drawPath(path, p)
            p.color = 0xFF1A73E8.toInt(); p.style = Paint.Style.FILL; c.drawPath(path, p)
            return@getOrPut BitmapDrawable(resources, b)
        }
        // coloured disc with white ring
        p.color = Color.WHITE; c.drawCircle(h, h, h, p)
        p.color = color; c.drawCircle(h, h, h - dpf(2.5f), p)
        p.color = Color.WHITE; p.style = Paint.Style.FILL
        val u = sz / 100f   // glyph units
        when (kind) {
            "camera" -> {
                c.drawRoundRect(h - 28 * u, h - 14 * u, h + 28 * u, h + 20 * u, 6 * u, 6 * u, p)
                c.drawRect(h - 12 * u, h - 24 * u, h + 8 * u, h - 12 * u, p)
                p.color = color; c.drawCircle(h, h + 3 * u, 11 * u, p); p.color = Color.WHITE; c.drawCircle(h, h + 3 * u, 6 * u, p)
            }
            "redlight" -> {
                c.drawRoundRect(h - 14 * u, h - 30 * u, h + 14 * u, h + 30 * u, 8 * u, 8 * u, p)
                p.color = 0xFFD32F2F.toInt(); c.drawCircle(h, h - 17 * u, 7 * u, p)
                p.color = 0xFFFFB300.toInt(); c.drawCircle(h, h, 7 * u, p)
                p.color = 0xFF2E7D32.toInt(); c.drawCircle(h, h + 17 * u, 7 * u, p)
            }
            "police" -> {   // five-point badge star
                val path = Path()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 32 * u else 14 * u
                    val ang = Math.toRadians(-90.0 + i * 36.0)
                    val x = h + (r * cos(ang)).toFloat(); val y = h + (r * sin(ang)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); c.drawPath(path, p)
            }
            "hazard" -> {   // warning triangle with "!"
                val path = Path().apply { moveTo(h, h - 30 * u); lineTo(h + 30 * u, h + 24 * u); lineTo(h - 30 * u, h + 24 * u); close() }
                c.drawPath(path, p)
                p.color = color; c.drawRoundRect(h - 3.5f * u, h - 12 * u, h + 3.5f * u, h + 8 * u, 2 * u, 2 * u, p); c.drawCircle(h, h + 16 * u, 4 * u, p)
            }
            else -> {       // accident: bold "!" in a ring
                p.style = Paint.Style.STROKE; p.strokeWidth = 6 * u; c.drawCircle(h, h, 27 * u, p)
                p.style = Paint.Style.FILL; c.drawRoundRect(h - 4 * u, h - 16 * u, h + 4 * u, h + 6 * u, 2 * u, 2 * u, p); c.drawCircle(h, h + 15 * u, 5 * u, p)
            }
        }
        BitmapDrawable(resources, b)
    }

    override fun onResume() {
        super.onResume(); map.onResume()
        muteBtn.text = if (AlertState.muted) "Unmute" else "Mute"
    }
    override fun onPause() { map.onPause(); super.onPause() }
}
