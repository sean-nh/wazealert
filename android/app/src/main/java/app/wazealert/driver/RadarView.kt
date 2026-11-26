package app.wazealert.driver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Radar-style proximity display: you in the centre, the top is the way you are driving, rings mark distance and
 * alerts show as blips where they really are around you. When something dangerous is close, rings pulse outward from
 * you, faster the closer it is, and the whole scope turns amber then red.
 */
class RadarView(context: Context) : View(context) {
    private val rangeM = 2000.0
    private var alerts: List<Alert> = emptyList()
    private var heading: Double? = null
    private var lat = 0.0
    private var lon = 0.0
    private var threatM: Double? = null          // distance of the nearest alert we speak for, ahead of us
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var running = false

    fun update(snap: MapSnap, threat: Alert?) {
        alerts = snap.alerts; heading = snap.heading; lat = snap.lat; lon = snap.lon
        threatM = threat?.distanceM
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; postInvalidateOnAnimation() }
    override fun onDetachedFromWindow() { running = false; super.onDetachedFromWindow() }

    private fun tone(): Int = when {
        threatM != null && threatM!! < 400 -> 0xFFFF5252.toInt()
        threatM != null && threatM!! < 1000 -> 0xFFFFB300.toInt()
        else -> 0xFF4CAF50.toInt()
    }

    private fun blipColor(a: Alert) = when {
        a.type == "POLICE" -> 0xFF448AFF.toInt()
        a.type == "HAZARD" -> 0xFFFF9100.toInt()
        a.type == "ACCIDENT" -> 0xFFFF5252.toInt()
        a.subtype == "CAMERA_REDLIGHT" -> 0xFFFF5252.toInt()
        else -> 0xFFB388FF.toInt()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2; val cy = h / 2
        val r = min(w, h) / 2 - 3 * d
        val now = SystemClock.uptimeMillis()
        val tone = tone()

        // scope face
        p.style = Paint.Style.FILL; p.shader = null; p.color = 0xD9101820.toInt(); c.drawCircle(cx, cy, r, p)

        // forward wedge (the 90 degrees you are driving into) when we know the heading
        if (heading != null) {
            p.color = (tone and 0x00FFFFFF) or 0x26000000
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -135f, 90f, true, p)
        }

        // rotating sweep
        val sweep = (now % 3200L) / 3200f * 360f
        c.save(); c.rotate(sweep, cx, cy)
        p.shader = SweepGradient(cx, cy, intArrayOf(0x00000000, (tone and 0x00FFFFFF) or 0x55000000), floatArrayOf(0.85f, 1f))
        c.drawCircle(cx, cy, r, p); p.shader = null
        c.restore()

        // distance rings (1/3, 2/3, full range) and cross-hair
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * d; p.color = 0x66FFFFFF
        for (i in 1..3) c.drawCircle(cx, cy, r * i / 3f, p)
        p.color = 0x33FFFFFF; c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p)
        p.strokeWidth = 3f * d; p.color = tone; c.drawCircle(cx, cy, r, p)

        // pulse rings: expand from the centre, faster as the threat gets closer
        val t = threatM
        if (t != null && t < 1500) {
            val period = (500 + 1700 * (t / 1500.0)).toLong()          // 0.5 s when on top of it .. 2.2 s at 1.5 km
            for (k in 0 until 2) {
                val phase = (((now + k * period / 2) % period).toFloat() / period)
                p.strokeWidth = 3f * d; p.color = (tone and 0x00FFFFFF) or ((200 * (1f - phase)).toInt() shl 24)
                c.drawCircle(cx, cy, r * phase, p)
            }
        }

        // alerts as blips
        val hd = heading ?: 0.0
        p.style = Paint.Style.FILL
        for (a in alerts.asReversed()) {
            if (a.distanceM > rangeM) continue
            val rel = Math.toRadians(Geo.bearing(lat, lon, a.lat, a.lon) - hd)
            val rr = r * sqrt((a.distanceM / rangeM).toFloat()).coerceAtMost(1f) * 0.97f
            val x = cx + (rr * sin(rel)).toFloat(); val y = cy - (rr * cos(rel)).toFloat()
            val big = a.type == "POLICE" || a.type == "ACCIDENT"
            val br = (if (big) 7f else 5.5f) * d
            if (a.distanceM == t) { p.color = (blipColor(a) and 0x00FFFFFF) or 0x55000000; c.drawCircle(x, y, br * 2.3f, p) }
            p.color = 0xFFFFFFFF.toInt(); c.drawCircle(x, y, br + 1.5f * d, p)
            p.color = blipColor(a); c.drawCircle(x, y, br, p)
        }

        // you: little arrow in the middle
        val arrow = Path().apply { moveTo(cx, cy - 9 * d); lineTo(cx + 6 * d, cy + 7 * d); lineTo(cx, cy + 3 * d); lineTo(cx - 6 * d, cy + 7 * d); close() }
        p.color = 0xFFFFFFFF.toInt(); p.style = Paint.Style.FILL; c.drawPath(arrow, p)

        if (running) postInvalidateOnAnimation()
    }
}
