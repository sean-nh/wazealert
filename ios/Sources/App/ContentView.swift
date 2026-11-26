import SwiftUI

struct ContentView: View {
    @EnvironmentObject var engine: AlertEngine
    @State private var showSettings = false
    @State private var showSelfCheck = false
    @State private var showMap = false
    @State private var showNudge = false
    @AppStorage("selfCheckSeen") private var selfCheckSeen = false
    @State private var toast: String?

    private var bannerColor: Color {
        guard let m = engine.nearestM else { return .clear }
        if m < 400 { return Color(red: 0.78, green: 0.16, blue: 0.16) }   // red
        if m < 1000 { return Color(red: 1.0, green: 0.70, blue: 0.0) }    // amber
        return .clear
    }
    private var bannerText: Color {
        guard let m = engine.nearestM else { return .primary }
        if m < 400 { return .white }
        if m < 1000 { return .black }
        return .primary
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                Text(engine.status).font(.footnote).foregroundColor(.secondary)

                Text(engine.nearest)
                    .font(.system(size: 30, weight: .bold))
                    .minimumScaleFactor(0.6)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(bannerColor)
                    .foregroundColor(bannerText)

                if engine.permission == .denied || engine.permission == .reduced {
                    Text(engine.permission == .denied
                         ? "Location permission was denied. WazeAlert can't work without it."
                         : "Only approximate location is allowed. Turn on Precise Location so alerts are accurate.")
                        .font(.callout)
                    Button("Open Settings") { engine.openSettings() }.buttonStyle(.borderedProminent)
                }

                HStack {
                    Button(engine.muted ? "Unmute voice" : "Mute voice") { engine.muted.toggle() }
                    Button(engine.paused ? "Resume" : "Pause") { engine.setPaused(!engine.paused) }
                }.buttonStyle(.bordered).frame(maxWidth: .infinity)

                HStack {
                    Button("Report police") { report("POLICE", "Police") }
                    Button("Report hazard") { report("HAZARD", "Hazard") }
                }.buttonStyle(.bordered).frame(maxWidth: .infinity)

                if engine.nearestCommunityId != nil {
                    HStack {
                        Button("Still there") { engine.vote(1); toast = "Thanks, confirmed" }
                        Button("Gone") { engine.vote(-1); toast = "Thanks, marked as gone" }
                    }.buttonStyle(.bordered).frame(maxWidth: .infinity)
                }

                Button("Map") { showMap = true }.buttonStyle(.bordered).frame(maxWidth: .infinity)

                Button("Pre-drive self-check") { showSelfCheck = true }.buttonStyle(.bordered).frame(maxWidth: .infinity)

                if let toast { Text(toast).font(.footnote).foregroundColor(.secondary) }

                List(Array(engine.items.enumerated()), id: \.offset) { Text($0.element) }.listStyle(.plain)
            }
            .padding()
            .navigationTitle("WazeAlert")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button { showSettings = true } label: { Image(systemName: "gearshape") }
                        .accessibilityLabel("Settings and about")
                }
            }
            .sheet(isPresented: $showSettings) { SettingsView() }
            .sheet(isPresented: $showMap) { MapScreen().environmentObject(engine) }
            .sheet(isPresented: $showSelfCheck) { SelfCheckView().environmentObject(engine) }
            // once, after location is allowed: offer the self-check so there are no surprises on the road
            .onChange(of: engine.permission) { p in
                if (p == .ok || p == .reduced) && !selfCheckSeen { showNudge = true }
            }
            .alert("Check everything works?", isPresented: $showNudge) {
                Button("Run self-check") { showSelfCheck = true }
                Button("Later", role: .cancel) {}
            } message: {
                Text("The pre-drive self-check tests location, volume and voice, and plays a test alert, so there are no surprises on the road.")
            }
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true   // keep the display awake while visible
            engine.start()
        }
        .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func report(_ type: String, _ what: String) {
        let shared = !((UserDefaults.standard.string(forKey: "serverUrl") ?? "").isEmpty)
        toast = !engine.report(type: type) ? "Waiting for a GPS fix"
            : shared ? "\(what) reported to the community"
            : "\(what) reported here (saved on this phone only; add a community server in Settings to share)"
    }
}
