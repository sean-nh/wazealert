import Foundation

/// Pure helpers (no UIKit/CoreLocation), a straight port of the Android `Geo`/`Fmt`.
enum Geo {
    /// Initial bearing from point 1 to point 2, degrees clockwise from north, 0..<360.
    static func bearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180, dl = (lon2 - lon1) * .pi / 180
        let y = sin(dl) * cos(p2)
        let x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360)
    }

    static func distance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let p1 = lat1 * .pi / 180, p2 = lat2 * .pi / 180
        let a = pow(sin((p2 - p1) / 2), 2) + cos(p1) * cos(p2) * pow(sin((lon2 - lon1) * .pi / 360), 2)
        return 2 * 6371000.0 * asin(min(1, sqrt(a)))
    }

    /// Smallest absolute angle between two headings, 0...180.
    static func angleDiff(_ a: Double, _ b: Double) -> Double {
        abs((a - b + 540).truncatingRemainder(dividingBy: 360) - 180)
    }

    /// No heading (stationary) counts as "ahead" so nothing is hidden.
    static func isAhead(heading: Double?, bearingTo: Double, limit: Double = 70) -> Bool {
        guard let h = heading else { return true }
        return angleDiff(h, bearingTo) < limit
    }

    /// Sideways distance (m) from the line you are driving along to a target `distM` away and `diffDeg` off your heading.
    static func crossTrack(distM: Double, diffDeg: Double) -> Double { abs(distM * sin(diffDeg * .pi / 180)) }

    /// Should the list show this alert in the chosen mode?
    /// "radius": everything in range. "path": ahead and within ~120 m of your line of travel (an approximation of
    /// "on my street", since there is no road data). Anything else ("front"): ahead of you, within 70 degrees.
    static func inMode(_ mode: String, heading: Double?, bearingTo: Double, distM: Double) -> Bool {
        guard let h = heading, mode != "radius" else { return true }
        if mode == "path" {
            let d = angleDiff(h, bearingTo)
            return d < 25 && crossTrack(distM: distM, diffDeg: d) <= 120
        }
        return isAhead(heading: h, bearingTo: bearingTo)
    }

    /// Does a camera that watches traffic travelling in `dirs` apply to a driver heading `heading`?
    /// Missing direction data or a missing heading means "yes": never hide on missing data.
    static func dirMatches(heading: Double?, dirs: [Int]?, tol: Double = 100) -> Bool {
        guard let h = heading, let d = dirs, !d.isEmpty else { return true }
        return d.contains { angleDiff(h, Double($0)) <= tol }
    }

    /// How far ahead (meters) to warn: `lead` seconds of travel, clamped to 800...3000 m.
    static func warnRadius(speed: Double, lead: Int = 45) -> Double {
        min(3000, max(800, speed * Double(lead)))
    }
}

/// Unit formatting. Camera-database limits are km/h (56 = 35 mph, 48 = 30 mph, 120 = 75 mph).
enum Fmt {
    static func dist(_ m: Double, metric: Bool, spoken: Bool = false) -> String {
        if metric && m < 1000 { return "\(Int(m.rounded()))" + (spoken ? " meters" : " m") }
        if metric { return String(format: "%.1f", m / 1000) + (spoken ? " kilometers" : " km") }
        return String(format: "%.1f", m / 1609.34) + (spoken ? " miles" : " mi")
    }

    static func speed(_ mps: Double, metric: Bool) -> String {
        metric ? String(format: "%.0f km/h", mps * 3.6) : String(format: "%.0f mph", mps * 2.23694)
    }

    static func limit(_ kmh: Int, metric: Bool) -> String {
        metric ? "\(kmh) km/h" : "\(Int((Double(kmh) * 0.621371 / 5).rounded()) * 5) mph"
    }
}
