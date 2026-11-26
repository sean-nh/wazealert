import Foundation
import SWCompression

/// One line of the ExCam file: {"lat","lon","str","flg","spd","dir"}.
private struct CamRow: Decodable {
    let lat: Double
    let lon: Double
    let str: String?
    let flg: Int?
    let spd: Double?
    let dir: [Double]?
}

/// Camera database published by wzsabre.rocks ("World cameras by @schuster"):
/// an XZ-compressed file of JSON lines, preceded by a {"_meta": ...} line.
final class CameraDB {
    private var lat: [Double] = []
    private var lon: [Double] = []
    private var street: [String] = []
    private var flag: [Int] = []
    private var limit: [Int] = []
    private var dirs: [[Int]?] = []

    var count: Int { lat.count }

    static func parse(_ data: Data) -> CameraDB {
        let db = CameraDB()
        let decoder = JSONDecoder()
        for line in data.split(separator: 0x0A, omittingEmptySubsequences: true) {
            guard let r = try? decoder.decode(CamRow.self, from: Data(line)) else { continue } // also skips the meta line
            db.lat.append(r.lat); db.lon.append(r.lon)
            db.street.append(r.str ?? "")
            db.flag.append(r.flg ?? 0)
            db.limit.append(Int(r.spd ?? 0))
            if let d = r.dir, !d.isEmpty { db.dirs.append(d.map { Int($0) }) } else { db.dirs.append(nil) }
        }
        return db
    }

    static func decompress(_ xz: Data) throws -> Data {
        try XZArchive.unarchive(archive: xz)
    }

    func near(lat lat0: Double, lon lon0: Double, radiusM: Double) -> [Alert] {
        let dLat = radiusM / 111_000.0
        let dLon = dLat / max(0.01, cos(lat0 * .pi / 180))
        var out: [Alert] = []
        for i in 0..<lat.count {
            if abs(lat[i] - lat0) > dLat || abs(lon[i] - lon0) > dLon { continue }
            let d = Geo.distance(lat0, lon0, lat[i], lon[i])
            if d > radiusM { continue }
            out.append(Alert(id: "cam\(i)", type: "CAMERA",
                             subtype: flag[i] == 2 ? "CAMERA_REDLIGHT" : "CAMERA_SPEED",
                             street: street[i], lat: lat[i], lon: lon[i],
                             distanceM: d, dirs: dirs[i], limitKmh: limit[i]))
        }
        return out.sorted { $0.distanceM < $1.distanceM }
    }
}

enum CameraStore {
    private static let indexURL = URL(string: "https://wzsabre.rocks/cameras")!
    private static let maxAge: TimeInterval = 7 * 24 * 3600

    private struct Index: Decodable {
        struct Link: Decodable { let dataUrl: String }
        let _link: Link
    }

    /// Downloads (if the cache is older than a week) and loads the database. Call off the main thread.
    static func load() async throws -> CameraDB {
        let fm = FileManager.default
        let dir = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("cameras.xz")

        let modified = (try? fm.attributesOfItem(atPath: file.path))?[.modificationDate] as? Date
        let stale = modified.map { Date().timeIntervalSince($0) > maxAge } ?? true
        if stale {
            do {
                let (idx, _) = try await URLSession.shared.data(from: indexURL)
                let link = try JSONDecoder().decode(Index.self, from: idx)._link.dataUrl
                guard let dataURL = URL(string: link) else { throw URLError(.badURL) }
                let (bytes, _) = try await URLSession.shared.data(from: dataURL)
                try bytes.write(to: file, options: .atomic)
            } catch {
                if !fm.fileExists(atPath: file.path) { throw error }   // no cache to fall back on
            }
        }
        let compressed = try Data(contentsOf: file)
        return CameraDB.parse(try CameraDB.decompress(compressed))
    }
}
