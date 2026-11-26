import SwiftUI
import AVFoundation
import CoreLocation
import UIKit

/// "Run this before you drive": every common reason an alert app silently fails, checked in one place,
/// each with a plain-language message. Also plays a test alert so you hear what a real one sounds like.
struct SelfCheckView: View {
    @EnvironmentObject var engine: AlertEngine
    @Environment(\.dismiss) private var dismiss
    @AppStorage("serverUrl") private var serverUrl = ""
    @AppStorage("selfCheckSeen") private var selfCheckSeen = false

    @State private var voiceResult = "Not tested yet. Tap \"Play test alert\" and listen."
    @State private var serverResult: String? = nil
    @State private var now = Date()                      // re-evaluates the checks every 2 s
    private let ticker = Timer.publish(every: 2, on: .main, in: .common).autoconnect()

    enum Level { case ok, warn, bad
        var mark: String { self == .ok ? "✓" : self == .warn ? "!" : "✗" }
        var color: Color { self == .ok ? .green : self == .warn ? .orange : .red }
    }
    struct Check: Identifiable { let id = UUID(); let level: Level; let title: String; let detail: String; let fix: (() -> Void)? }

    // MARK: the checks

    private var checks: [Check] {
        _ = now
        var out: [Check] = []
        let openSettings: () -> Void = { engine.openSettings() }

        switch engine.permission {
        case .ok: out.append(Check(level: .ok, title: "Location permission", detail: "Allowed (precise)", fix: nil))
        case .reduced: out.append(Check(level: .bad, title: "Location permission", detail: "Only approximate location is allowed. Turn on Precise Location.", fix: openSettings))
        case .denied: out.append(Check(level: .bad, title: "Location permission", detail: "Not allowed", fix: openSettings))
        case .unknown: out.append(Check(level: .warn, title: "Location permission", detail: "Waiting for your answer to the permission prompt", fix: nil))
        }

        let age = engine.fixAgeSeconds
        let moving = false
        _ = moving
        if let a = age {
            let lost = a > 120
            out.append(Check(level: lost ? .bad : (a > 15 ? .warn : .ok), title: "GPS fix",
                             detail: lost ? "Last fix \(Int(a))s ago (signal lost)"
                                          : "Good, accuracy \(Int(engine.fixAccuracy ?? 0)) m", fix: nil))
        } else {
            out.append(Check(level: .warn, title: "GPS fix",
                             detail: "No fix yet. Go outdoors with a clear view of the sky; the first fix can take a minute.", fix: nil))
        }

        out.append(Check(level: engine.isStarted ? .ok : .bad, title: "Alerts engine", detail: engine.isStarted ? "Running" : "Not running. Open the main screen.", fix: nil))

        let cams = engine.cameraCount
        out.append(Check(level: cams > 100_000 ? .ok : .warn, title: "Camera database",
                         detail: cams > 0 ? "\(cams.formatted()) cameras loaded" : "Loading... (the first launch takes a few seconds)", fix: nil))

        let vol = AVAudioSession.sharedInstance().outputVolume
        out.append(Check(level: vol == 0 ? .bad : (vol < 0.25 ? .warn : .ok), title: "Volume",
                         detail: vol == 0 ? "Muted: you will not hear alerts. Raise the volume with the side buttons."
                                          : "\(Int(vol * 100))%" + (vol < 0.25 ? " (low)" : ""), fix: nil))

        out.append(Check(level: voiceResult.hasPrefix("Played") ? .ok : (voiceResult.hasPrefix("Failed") ? .bad : .warn),
                         title: "Voice and beep", detail: voiceResult, fix: nil))

        if ProcessInfo.processInfo.isLowPowerModeEnabled {
            out.append(Check(level: .warn, title: "Low Power Mode",
                             detail: "On. iPhone may reduce location updates to save battery; turn it off for long drives.", fix: nil))
        } else {
            out.append(Check(level: .ok, title: "Low Power Mode", detail: "Off", fix: nil))
        }

        let base = serverUrl.trimmingCharacters(in: .whitespaces)
        if base.isEmpty {
            out.append(Check(level: .ok, title: "Community server", detail: "Not used (reports stay on this phone)", fix: nil))
        } else if let r = serverResult {
            out.append(Check(level: r == "ok" ? .ok : .bad, title: "Community server", detail: r == "ok" ? "Reachable" : "Not reachable: \(r)", fix: nil))
        } else {
            out.append(Check(level: .warn, title: "Community server", detail: "Checking...", fix: nil))
        }
        return out
    }

    private var summary: (String, Color) {
        let c = checks
        let bad = c.filter { $0.level == .bad }.count, warn = c.filter { $0.level == .warn }.count
        if bad > 0 { return ("\(bad) problem(s) to fix before you drive.", .red) }
        if warn > 0 { return ("Ready, with \(warn) thing(s) worth fixing for long drives.", .orange) }
        return ("Everything looks good. Safe driving.", .green)
    }

    private var diagnosticsText: String {
        let info = Bundle.main.infoDictionary
        let version = "\(info?["CFBundleShortVersionString"] as? String ?? "?") (\(info?["CFBundleVersion"] as? String ?? "?"))"
        var s = "WazeAlert diagnostics\n"
        s += "App \(version), iOS \(UIDevice.current.systemVersion), \(UIDevice.current.model)\n"
        s += "Locale \(Locale.current.identifier)\n\n\(summary.0)\n"
        for c in checks { s += "[\(c.level == .ok ? " ok " : c.level == .warn ? "WARN" : "FAIL")] \(c.title): \(c.detail)\n" }
        s += "\nRecent events (no locations recorded):\n" + DiagLog.dump()
        return s
    }

    // MARK: view

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(summary.0).font(.headline).foregroundColor(summary.1)
                        .accessibilityIdentifier("selfcheck-summary")
                }
                Section {
                    ForEach(checks) { c in
                        HStack(alignment: .top, spacing: 12) {
                            Text(c.level.mark).font(.title2.bold()).foregroundColor(c.level.color).frame(width: 28)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(c.title).font(.headline)
                                Text(c.detail).font(.subheadline).foregroundColor(.secondary)
                            }
                            Spacer()
                            if let fix = c.fix { Button("Fix", action: fix).buttonStyle(.bordered) }
                        }
                    }
                }
                Section {
                    Button("Play test alert (beep + voice)") { playTest() }
                    ShareLink("Share diagnostics", item: diagnosticsText)
                }
            }
            .navigationTitle("Pre-drive self-check")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .onReceive(ticker) { now = $0 }
        .onAppear { selfCheckSeen = true; pingServer() }
    }

    private func playTest() {
        voiceResult = "Playing..."
        engine.playTestAlert { ok in
            voiceResult = ok ? "Played. If you heard the beep and the voice, alerts will be audible."
                             : "Failed: no speech voices are installed. Add one in Settings > Accessibility > Spoken Content > Voices."
        }
    }

    private func pingServer() {
        let base = serverUrl.trimmingCharacters(in: .whitespaces)
        guard base.hasPrefix("http"), let url = URL(string: base.hasSuffix("/") ? String(base.dropLast()) + "/healthz" : base + "/healthz") else { return }
        var req = URLRequest(url: url, timeoutInterval: 5)
        req.httpMethod = "GET"
        URLSession.shared.dataTask(with: req) { _, resp, _ in
            let ok = (resp as? HTTPURLResponse)?.statusCode == 200
            DispatchQueue.main.async { serverResult = ok ? "ok" : "check the address in Settings" }
        }.resume()
    }
}
