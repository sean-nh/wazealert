import Foundation

struct Alert: Identifiable, Equatable {
    let id: String
    let type: String        // "CAMERA", "POLICE", "HAZARD", "ACCIDENT"
    let subtype: String
    let street: String
    let lat: Double
    let lon: Double
    var distanceM: Double = 0
    var dirs: [Int]? = nil
    var limitKmh: Int = 0

    var isCommunity: Bool { id.hasPrefix("srv") }

    var label: String {
        switch type {
        case "POLICE": return "Police"
        case "HAZARD": return "Hazard"
        case "ACCIDENT": return "Accident"
        case "CAMERA": return subtype == "CAMERA_REDLIGHT" ? "Red-light camera" : "Speed camera"
        default: return type.prefix(1).uppercased() + type.dropFirst().lowercased()
        }
    }
}

enum AlertFilter {
    /// Alerts in front of the driver, with the camera-direction rule applied when enabled.
    static func ahead(_ alerts: [Alert], lat: Double, lon: Double, heading: Double?, dirFilter: Bool) -> [Alert] {
        alerts.filter { a in
            Geo.isAhead(heading: heading, bearingTo: Geo.bearing(lat, lon, a.lat, a.lon))
                && (!dirFilter || a.type != "CAMERA" || Geo.dirMatches(heading: heading, dirs: a.dirs))
        }
    }
}
