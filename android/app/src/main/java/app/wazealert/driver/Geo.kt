package app.wazealert.driver

import kotlin.math.*

/** Pure helpers (no Android dependencies, so they are unit-testable on the JVM). */
object Geo {
    /** Initial bearing from point 1 to point 2, degrees clockwise from north, 0..360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val a = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(lon2 - lon1) / 2).pow(2)
        return 2 * 6371000.0 * asin(sqrt(a))
    }

    /** Smallest absolute angle between two headings, 0..180. */
    fun angleDiff(a: Double, b: Double): Double = abs(((a - b) + 540.0) % 360.0 - 180.0)

    /** No heading (stationary) counts as "ahead" so nothing is hidden. */
    fun isAhead(heading: Double?, bearingToTarget: Double, limitDeg: Double = 70.0): Boolean =
        heading == null || angleDiff(heading, bearingToTarget) < limitDeg

    /** Sideways distance (m) from the line you are driving along to a target at [distM] and [diffDeg] off your heading. */
    fun crossTrackM(distM: Double, diffDeg: Double): Double = abs(distM * sin(Math.toRadians(diffDeg)))

    /**
     * Should the list show this alert in the chosen mode?
     * "radius": everything in range. "path": ahead and within ~120 m of your line of travel (approximation of
     * "on my street", since there is no road data). Anything else: ahead of you (within 70 degrees).
     */
    fun inMode(mode: String, heading: Double?, bearingTo: Double, distM: Double): Boolean = when {
        mode == "radius" || heading == null -> true
        mode == "path" -> angleDiff(heading, bearingTo).let { it < 25.0 && crossTrackM(distM, it) <= 120.0 }
        else -> isAhead(heading, bearingTo)
    }

    /**
     * Does a camera that watches traffic travelling in [dirs] apply to a driver heading [heading]?
     * Unknown direction data or unknown heading means "yes" (never hide on missing data).
     */
    fun dirMatches(heading: Double?, dirs: IntArray?, tolDeg: Double = 100.0): Boolean =
        heading == null || dirs == null || dirs.isEmpty() || dirs.any { angleDiff(heading, it.toDouble()) <= tolDeg }

    /** How far ahead (meters) to warn: [leadSeconds] of travel, clamped to 800..3000 m. */
    fun warnRadiusM(speedMps: Double, leadSeconds: Int = 45): Double =
        (speedMps * leadSeconds).coerceIn(800.0, 3000.0)
}

/** Unit formatting. Camera-database limits are km/h (56 = 35 mph, 48 = 30 mph, 120 = 75 mph). */
object Fmt {
    fun dist(m: Double, metric: Boolean, spoken: Boolean = false): String = when {
        metric && m < 1000 -> "${m.roundToInt()}" + if (spoken) " meters" else " m"
        metric -> "%.1f".format(m / 1000) + if (spoken) " kilometers" else " km"
        else -> "%.1f".format(m / 1609.34) + if (spoken) " miles" else " mi"
    }

    fun speed(mps: Double, metric: Boolean): String =
        if (metric) "%.0f km/h".format(mps * 3.6) else "%.0f mph".format(mps * 2.23694)

    fun limit(kmh: Int, metric: Boolean): String =
        if (metric) "$kmh km/h" else "${(kmh * 0.621371 / 5).roundToInt() * 5} mph"
}
