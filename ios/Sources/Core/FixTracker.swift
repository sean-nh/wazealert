import Foundation

/// One raw GPS reading, independent of CoreLocation so it can be unit-tested.
struct GPSSample {
    var lat: Double
    var lon: Double
    var t: TimeInterval          // time the reading was taken (seconds since 1970)
    var accuracy: Double         // horizontal accuracy in meters
    var speed: Double?           // m/s if the device reports one
    var course: Double?          // degrees if the device reports one
}

/// A fix plus the heading/speed we trust (device-reported, or derived from consecutive fixes).
struct Fix {
    var sample: GPSSample
    var heading: Double?
    var speed: Double
    var at: TimeInterval         // wall-clock time we accepted it
}

final class FixTracker {
    static let maxAccuracy = 100.0   // ignore fixes worse than this (meters)
    static let staleSeconds = 15.0   // ignore readings older than this

    /// How long without a new fix before we call it "signal lost". iOS may deliver few updates to a phone that is
    /// not moving (e.g. at a red light), so a stationary phone gets much longer than a moving one.
    static func lostAfter(moving: Bool) -> Double { moving ? staleSeconds : 120 }

    private(set) var fix: Fix?
    private var prev: GPSSample?

    func accept(_ s: GPSSample, now: TimeInterval) {
        if s.accuracy < 0 || s.accuracy > Self.maxAccuracy { return }
        if now - s.t > Self.staleSeconds { return }

        var heading: Double?
        var speed = 0.0
        var tooSoon = false

        if let sp = s.speed, let c = s.course, sp >= 2, c >= 0 {
            heading = c; speed = sp; prev = s
        } else if let p = prev {
            let dt = s.t - p.t
            let dist = Geo.distance(p.lat, p.lon, s.lat, s.lon)
            if dt < 0.5 {
                tooSoon = true                                    // too soon: keep previous reference
            } else if dist >= 8 && dist / dt >= 2 {               // moving: derive heading from the track
                heading = Geo.bearing(p.lat, p.lon, s.lat, s.lon)
                speed = dist / dt
                prev = s
            } else if dt > 10 {
                prev = s                                          // standing still: refresh reference
            }
        } else {
            prev = s
        }

        if tooSoon, let old = fix, let h = old.heading { heading = h; speed = old.speed }
        fix = Fix(sample: s, heading: heading, speed: speed, at: now)
    }

    func reset() { fix = nil; prev = nil }
}
