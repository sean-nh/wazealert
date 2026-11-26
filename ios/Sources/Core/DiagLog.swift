import Foundation

/// Small in-memory event log (last 300 lines) shown by "Share diagnostics". It never records coordinates,
/// only events (permission changes, GPS lost, speech, errors), so it is safe to send to someone for debugging.
final class DiagLog {
    static let shared = DiagLog()
    private var lines: [String] = []
    private let lock = NSLock()
    private let fmt: DateFormatter = {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"; f.locale = Locale(identifier: "en_US_POSIX"); return f
    }()

    static func log(_ msg: String) { shared.add(msg) }
    static func dump() -> String { shared.snapshot() }

    private func add(_ msg: String) {
        lock.lock(); defer { lock.unlock() }
        print("[WazeAlert] \(msg)")
        lines.append("\(fmt.string(from: Date())) \(msg)")
        if lines.count > 300 { lines.removeFirst(lines.count - 300) }
    }

    private func snapshot() -> String {
        lock.lock(); defer { lock.unlock() }
        return lines.joined(separator: "\n")
    }
}
