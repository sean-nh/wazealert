import Foundation

/// Talks to the WazeAlert community server (see WazeAlert-Server).
enum CommunityClient {
    struct Risk { let score: Int; let level: String }

    private struct ReportsResponse: Decodable {
        struct Item: Decodable {
            let id: String; let type: String; let lat: Double; let lon: Double
            let ageSec: Int; let seenSec: Int; let score: Int
        }
        let reports: [Item]
    }
    private struct RiskResponse: Decodable { let score: Int; let level: String }

    /// Turns the server's JSON into alerts; reports whose last confirmation is older than `maxSeenSec` are dropped.
    static func parseReports(_ data: Data, lat: Double, lon: Double, maxSeenSec: Int) throws -> [Alert] {
        try JSONDecoder().decode(ReportsResponse.self, from: data).reports
            .filter { $0.seenSec <= maxSeenSec }
            .map { r in
                let mins = r.seenSec / 60
                return Alert(id: "srv" + r.id, type: r.type, subtype: "COMMUNITY",
                             street: "community · \(mins < 1 ? "just now" : "\(mins) min ago") · \(r.score) confirmed",
                             lat: r.lat, lon: r.lon, distanceM: Geo.distance(lat, lon, r.lat, r.lon))
            }
    }

    private static func request(_ method: String, _ url: String, body: [String: Any]? = nil) async throws -> Data {
        guard let u = URL(string: url) else { throw URLError(.badURL) }
        var req = URLRequest(url: u, timeoutInterval: 8)
        req.httpMethod = method
        if let body = body {
            req.httpBody = try JSONSerialization.data(withJSONObject: body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, resp) = try await URLSession.shared.data(for: req)
        if let h = resp as? HTTPURLResponse, h.statusCode >= 400 { throw URLError(.badServerResponse) }
        return data
    }

    static func fetch(base: String, lat: Double, lon: Double, radiusM: Double, maxSeenSec: Int) async throws -> [Alert] {
        let data = try await request("GET", "\(base)/v1/reports?lat=\(lat)&lon=\(lon)&radius=\(Int(radiusM))")
        return try parseReports(data, lat: lat, lon: lon, maxSeenSec: maxSeenSec)
    }

    static func post(base: String, type: String, lat: Double, lon: Double, heading: Double?, deviceId: String) async throws {
        var b: [String: Any] = ["type": type, "lat": lat, "lon": lon, "deviceId": deviceId]
        if let h = heading { b["heading"] = h }
        _ = try await request("POST", "\(base)/v1/reports", body: b)
    }

    static func vote(base: String, alertId: String, deviceId: String, vote: Int) async throws {
        let id = alertId.hasPrefix("srv") ? String(alertId.dropFirst(3)) : alertId
        _ = try await request("POST", "\(base)/v1/reports/\(id)/vote", body: ["deviceId": deviceId, "vote": vote])
    }

    static func risk(base: String, lat: Double, lon: Double, tzMinutes: Int) async throws -> Risk {
        let data = try await request("GET", "\(base)/v1/risk?lat=\(lat)&lon=\(lon)&radius=3000&tz=\(tzMinutes)")
        let r = try JSONDecoder().decode(RiskResponse.self, from: data)
        return Risk(score: r.score, level: r.level)
    }
}
