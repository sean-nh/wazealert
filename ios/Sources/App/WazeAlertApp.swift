import SwiftUI

/// Miles by default except where the locale is metric. Used for both the registered default and @AppStorage.
let defaultMetric = Locale.current.usesMetricSystem && Locale.current.region?.identifier != "GB"

@main
struct WazeAlertApp: App {
    @StateObject private var engine = AlertEngine()

    init() {
        UserDefaults.standard.register(defaults: [
            "metric": defaultMetric, "voiceCamera": true, "voicePolice": true, "voiceHazard": true,
            "beep": true, "dirFilter": true, "lead": 45,
            "mode": "front", "radiusM": 5000, "maxAgeMin": 120, "serverUrl": "",
        ])
    }

    var body: some Scene {
        WindowGroup {
            ContentView().environmentObject(engine)
        }
    }
}
