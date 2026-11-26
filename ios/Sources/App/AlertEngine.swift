import Foundation
import CoreLocation
import AVFoundation
import AudioToolbox
import UIKit

/// Owns GPS, the camera database, alert evaluation and voice. Mirrors the Android AlertService.
final class AlertEngine: NSObject, ObservableObject, CLLocationManagerDelegate, AVSpeechSynthesizerDelegate {
    enum Permission { case unknown, denied, reduced, ok }

    /// What the map screen draws: own position/heading and the alerts inside the alert radius.
    struct MapSnap { let lat: Double; let lon: Double; let heading: Double?; let alerts: [Alert] }
    @Published var mapSnap: MapSnap? = nil

    @Published var status = "Starting..."
    @Published var nearest = "No alerts ahead"
    @Published var nearestM: Double? = nil
    @Published var items: [String] = []
    @Published var permission: Permission = .unknown
    @Published var muted = false
    @Published var paused = false
    /// Id of the nearest alert if it is a community report (enables the "still there / gone" buttons).
    @Published var nearestCommunityId: String? = nil

    private var community: [Alert] = []
    private var communityNote = ""
    private var nextCommunityAt: TimeInterval = 0
    private var riskText = ""
    private var nextRiskAt: TimeInterval = 0

    private let lm = CLLocationManager()
    private let tracker = FixTracker()
    private let synth = AVSpeechSynthesizer()
    private var db: CameraDB?
    private var dbError: String?
    private var timer: Timer?
    private var announced = Set<String>()
    private var lastUpdate: TimeInterval = 0
    private var started = false

    private let speakTypes: Set<String> = ["POLICE", "ACCIDENT", "HAZARD", "CAMERA"]
    private var defaults: UserDefaults { .standard }
    private var lastState = ""
    private var testCompletion: ((Bool) -> Void)?

    // Read-only facts for the self-check screen.
    var cameraCount: Int { db?.count ?? 0 }
    var isStarted: Bool { started }
    var fixAgeSeconds: Double? { tracker.fix.map { Date().timeIntervalSince1970 - $0.at } }
    var fixAccuracy: Double? { tracker.fix?.sample.accuracy }

    /// Logs only when the tracking state changes, so the diagnostics stay short.
    private func note(_ state: String) {
        if state != lastState { lastState = state; DiagLog.log("state: \(state)") }
    }

    /// Plays the same beep + speech a real alert uses so the driver hears it before driving.
    func playTestAlert(done: @escaping (Bool) -> Void) {
        let m = metric
        let text = "Speed camera ahead, \(Fmt.dist(805, metric: m, spoken: true)), limit "
            + Fmt.limit(56, metric: m).replacingOccurrences(of: "km/h", with: "kilometers per hour")
        DiagLog.log("self-check: test alert")
        if AVSpeechSynthesisVoice.speechVoices().isEmpty { done(false); return }
        testCompletion = done
        Beep.play()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.say(text) }
    }

    // MARK: lifecycle

    func start() {
        guard !started else { return }
        started = true
        DiagLog.log("engine started")
        lm.delegate = self
        lm.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        lm.distanceFilter = kCLDistanceFilterNone
        lm.activityType = .automotiveNavigation
        lm.pausesLocationUpdatesAutomatically = false
        lm.allowsBackgroundLocationUpdates = true          // needs UIBackgroundModes=location
        lm.showsBackgroundLocationIndicator = true
        synth.delegate = self
        handleAuthorization()
        loadDatabase()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in self?.update() }
    }

    func setPaused(_ p: Bool) {
        paused = p
        if p { lm.stopUpdatingLocation(); tracker.reset(); status = "Paused"; nearest = "Paused"; nearestM = nil; items = [] }
        else if permission == .ok || permission == .reduced { lm.startUpdatingLocation() }
    }

    /// Community server base URL ("" = off) and the anonymous device id.
    private var serverURL: String {
        let s = (defaults.string(forKey: "serverUrl") ?? "").trimmingCharacters(in: .whitespaces)
        let trimmed = s.hasSuffix("/") ? String(s.dropLast()) : s
        return trimmed.hasPrefix("http") ? trimmed : ""
    }
    private var deviceId: String {
        if let id = defaults.string(forKey: "deviceId"), !id.isEmpty { return id }
        let id = UUID().uuidString
        defaults.set(id, forKey: "deviceId")
        return id
    }

    /// With a community server configured the report is shared (and shows up via the next fetch);
    /// otherwise, or if the server is unreachable, it is kept on this phone. Returns false if there is no GPS fix.
    func report(type: String) -> Bool {
        guard let f = tracker.fix else { return false }
        let lat = f.sample.lat, lon = f.sample.lon, heading = f.heading
        let base = serverURL
        if base.isEmpty { ReportStore.add(type: type, lat: lat, lon: lon); return true }
        let id = deviceId
        Task {
            do { try await CommunityClient.post(base: base, type: type, lat: lat, lon: lon, heading: heading, deviceId: id)
                 await MainActor.run { self.nextCommunityAt = 0 } }
            catch { await MainActor.run { ReportStore.add(type: type, lat: lat, lon: lon) } }
        }
        return true
    }

    func vote(_ v: Int) {
        guard let id = nearestCommunityId, !serverURL.isEmpty else { return }
        let base = serverURL, dev = deviceId
        Task {
            try? await CommunityClient.vote(base: base, alertId: id, deviceId: dev, vote: v)
            await MainActor.run { self.nextCommunityAt = 0 }
        }
    }

    /// Pulls community reports and the risk score in the background; never blocks the 2-second loop.
    private func refreshCommunity(lat: Double, lon: Double, radiusM: Double, maxSeenSec: Int, now: TimeInterval) {
        let base = serverURL
        if base.isEmpty { community = []; communityNote = ""; riskText = ""; return }
        if now >= nextCommunityAt {
            nextCommunityAt = now + 20
            Task {
                do {
                    let list = try await CommunityClient.fetch(base: base, lat: lat, lon: lon, radiusM: radiusM, maxSeenSec: maxSeenSec)
                    await MainActor.run { self.community = list; self.communityNote = " · \(list.count) community" }
                } catch {
                    await MainActor.run {
                        self.community = []; self.communityNote = " · community offline"
                        self.nextCommunityAt = Date().timeIntervalSince1970 + 120
                    }
                }
            }
        }
        if now >= nextRiskAt {
            nextRiskAt = now + 120
            Task {
                let tz = TimeZone.current.secondsFromGMT() / 60
                if let r = try? await CommunityClient.risk(base: base, lat: lat, lon: lon, tzMinutes: tz) {
                    await MainActor.run { self.riskText = " · risk \(r.level) (\(r.score))" }
                } else {
                    await MainActor.run { self.riskText = "" }
                }
            }
        }
    }

    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }

    private func loadDatabase() {
        status = "Loading camera database..."
        Task.detached { [weak self] in
            do {
                let loaded = try await CameraStore.load()
                await MainActor.run { self?.db = loaded; self?.dbError = nil }
                // get a fresher database for next time; never blocks alerts and failure is harmless
                await CameraStore.refreshIfStale()
            } catch {
                DiagLog.log("camera database load FAILED: \(error.localizedDescription)")
                await MainActor.run {
                    self?.dbError = "Camera data failed to load (\(error.localizedDescription)); retrying..."
                }
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                await MainActor.run { self?.loadDatabase() }
            }
        }
    }

    // MARK: permissions

    private func handleAuthorization() {
        DiagLog.log("location authorization status \(lm.authorizationStatus.rawValue), precise: \(lm.accuracyAuthorization == .fullAccuracy)")
        switch lm.authorizationStatus {
        case .notDetermined:
            permission = .unknown
            lm.requestWhenInUseAuthorization()
        case .denied, .restricted:
            permission = .denied
        case .authorizedWhenInUse, .authorizedAlways:
            if lm.accuracyAuthorization == .reducedAccuracy {
                permission = .reduced
                // Ask for precise location for this session; key is in Info.plist.
                lm.requestTemporaryFullAccuracyAuthorization(withPurposeKey: "Precise")
            } else {
                permission = .ok
            }
            if !paused { lm.startUpdatingLocation() }
        @unknown default:
            permission = .denied
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) { handleAuthorization() }

    // MARK: location

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let now = Date().timeIntervalSince1970
        for l in locations where l.horizontalAccuracy >= 0 {
            tracker.accept(GPSSample(lat: l.coordinate.latitude, lon: l.coordinate.longitude,
                                     t: l.timestamp.timeIntervalSince1970, accuracy: l.horizontalAccuracy,
                                     speed: l.speed >= 0 ? l.speed : nil,
                                     course: l.course >= 0 ? l.course : nil), now: now)
        }
        if now - lastUpdate > 1.5 { update() }   // also runs from the timer; this keeps it alive in the background
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        if (error as? CLError)?.code == .denied { permission = .denied }
    }

    // MARK: evaluation

    private var metric: Bool { defaults.bool(forKey: "metric") }

    private func voiceOn(_ type: String) -> Bool {
        switch type {
        case "CAMERA": return defaults.bool(forKey: "voiceCamera")
        case "POLICE": return defaults.bool(forKey: "voicePolice")
        default: return defaults.bool(forKey: "voiceHazard")
        }
    }

    func update() {
        lastUpdate = Date().timeIntervalSince1970
        if paused { return }
        switch permission {
        case .denied:
            status = "Location permission is off for WazeAlert"; nearest = "Location needed"; nearestM = nil; items = []; return
        case .unknown:
            status = "Waiting for location permission..."; return
        default: break
        }
        if let e = dbError, db == nil { status = e; return }
        guard let db = db else { status = "Loading camera database..."; return }
        guard let f = tracker.fix else {
            note("waiting for GPS fix")
            status = "Waiting for GPS fix..."; nearest = "Searching for GPS"; nearestM = nil; items = []; return
        }
        let age = lastUpdate - f.at
        if age > FixTracker.lostAfter(moving: f.heading != nil) {
            note("GPS signal LOST")
            status = "GPS signal lost (\(Int(age))s ago)"; nearest = "GPS signal lost"; nearestM = nil; return
        }
        note("tracking")

        let lat = f.sample.lat, lon = f.sample.lon
        let radiusM = Double(defaults.integer(forKey: "radiusM"))
        let maxAgeMin = defaults.integer(forKey: "maxAgeMin")
        refreshCommunity(lat: lat, lon: lon, radiusM: radiusM, maxSeenSec: maxAgeMin * 60, now: lastUpdate)

        let live = community.map { a -> Alert in
            var c = a; c.distanceM = Geo.distance(lat, lon, a.lat, a.lon); return c
        }
        let mine = ReportStore.active(lat: lat, lon: lon, maxAge: TimeInterval(maxAgeMin * 60))
        let cams = db.near(lat: lat, lon: lon, radiusM: radiusM)
        let all = (live + mine + cams).filter { $0.distanceM <= radiusM }.sorted { $0.distanceM < $1.distanceM }

        let dirFilter = defaults.bool(forKey: "dirFilter")
        let mode = defaults.string(forKey: "mode") ?? "front"
        func camOk(_ a: Alert) -> Bool { !dirFilter || a.type != "CAMERA" || Geo.dirMatches(heading: f.heading, dirs: a.dirs) }
        func bearingTo(_ a: Alert) -> Double { Geo.bearing(lat, lon, a.lat, a.lon) }
        let shown = all.filter { Geo.inMode(mode, heading: f.heading, bearingTo: bearingTo($0), distM: $0.distanceM) && (mode == "radius" || camOk($0)) }
        let ahead = all.filter { Geo.isAhead(heading: f.heading, bearingTo: bearingTo($0)) && camOk($0) }   // what gets spoken

        // re-arm voice for alerts that have left the 4 km zone
        let near4k = Set(all.filter { $0.distanceM < 4000 }.map { $0.id })
        announced = announced.intersection(near4k)

        mapSnap = MapSnap(lat: lat, lon: lon, heading: f.heading, alerts: Array(all.prefix(300)))
        let m = metric
        let moving = f.heading != nil
        let acc = "±\(Int(f.sample.accuracy)) m"
        let motion = moving ? "\(Fmt.speed(f.speed, metric: m)), heading \(Int((f.heading ?? 0).rounded()))°" : "stationary"
        var st = "GPS \(acc) · \(motion) · \(cams.count) cameras"
        if !mine.isEmpty { st += ", \(mine.count) yours" }
        st += communityNote + riskText
        if permission == .reduced { st += " · approximate location!" }
        status = st

        let near = shown.first { speakTypes.contains($0.type) }
        nearest = near.map { "\($0.label) \(Fmt.dist($0.distanceM, metric: m))" } ?? "No alerts ahead"
        nearestM = near?.distanceM
        nearestCommunityId = (near?.isCommunity ?? false) ? near?.id : nil
        items = shown.map {
            "\($0.label) - \(Fmt.dist($0.distanceM, metric: m))  \($0.street)"
                + ($0.limitKmh > 0 ? "  (limit \(Fmt.limit($0.limitKmh, metric: m)))" : "")
        }

        if moving && !muted {
            let radius = min(Geo.warnRadius(speed: f.speed, lead: defaults.integer(forKey: "lead")), radiusM)
            for a in ahead where speakTypes.contains(a.type) && voiceOn(a.type) && a.distanceM < radius {
                if announced.insert(a.id).inserted { speak(a, metric: m) }
            }
        }
    }

    // MARK: voice

    private func speak(_ a: Alert, metric: Bool) {
        var text = "\(a.label) ahead, \(Fmt.dist(a.distanceM, metric: metric, spoken: true))"
        if a.limitKmh > 0 {
            text += ", limit " + Fmt.limit(a.limitKmh, metric: metric).replacingOccurrences(of: "km/h", with: "kilometers per hour")
        }
        DiagLog.log("speak: \(text)")
        if defaults.bool(forKey: "beep") {
            Beep.play()                       // plays through the audio session, so the silent switch does not mute it
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { [weak self] in self?.say(text) }
        } else {
            say(text)
        }
    }

    private func say(_ text: String) {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .voicePrompt, options: [.duckOthers])
        try? session.setActive(true)
        let u = AVSpeechUtterance(string: text)
        u.voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
        synth.speak(u)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        DiagLog.log("speech finished")
        if let done = testCompletion { testCompletion = nil; done(true) }
        if !synthesizer.isSpeaking { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        DiagLog.log("speech CANCELLED")
        if let done = testCompletion { testCompletion = nil; done(false) }
    }
}
