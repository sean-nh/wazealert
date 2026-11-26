import Foundation

/// Alerts the user reported from this phone (police / hazard). Stored locally; they expire after 2 hours.
enum ReportStore {
    private struct Report: Codable { var t: String; var lat: Double; var lon: Double; var ts: TimeInterval }
    private static let key = "reports"
    private static let ttl: TimeInterval = 2 * 3600

    private static func load(_ d: UserDefaults) -> [Report] {
        guard let data = d.data(forKey: key), let r = try? JSONDecoder().decode([Report].self, from: data) else { return [] }
        return r
    }

    static func add(type: String, lat: Double, lon: Double, defaults d: UserDefaults = .standard, now: Date = Date()) {
        var r = load(d)
        r.append(Report(t: type, lat: lat, lon: lon, ts: now.timeIntervalSince1970))
        if let data = try? JSONEncoder().encode(r) { d.set(data, forKey: key) }
    }

    static func active(lat: Double, lon: Double, maxAge: TimeInterval = ttl,
                       defaults d: UserDefaults = .standard, now: Date = Date()) -> [Alert] {
        let all = load(d)
        let keep = all.filter { now.timeIntervalSince1970 - $0.ts <= ttl }
        if keep.count != all.count, let data = try? JSONEncoder().encode(keep) { d.set(data, forKey: key) }
        // kept on disk, just hidden by the user's age filter
        return keep.filter { now.timeIntervalSince1970 - $0.ts <= maxAge }.map {
            Alert(id: "user\(Int($0.ts))", type: $0.t, subtype: "USER", street: "reported by you",
                  lat: $0.lat, lon: $0.lon, distanceM: Geo.distance(lat, lon, $0.lat, $0.lon))
        }
    }
}
