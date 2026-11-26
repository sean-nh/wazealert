import SwiftUI

struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("metric") private var metric = defaultMetric
    @AppStorage("voiceCamera") private var voiceCamera = true
    @AppStorage("voicePolice") private var voicePolice = true
    @AppStorage("voiceHazard") private var voiceHazard = true
    @AppStorage("beep") private var beep = true
    @AppStorage("dirFilter") private var dirFilter = true
    @AppStorage("lead") private var lead = 45
    @AppStorage("mode") private var mode = "front"
    @AppStorage("radiusM") private var radiusM = 5000
    @AppStorage("maxAgeMin") private var maxAgeMin = 120
    @AppStorage("serverUrl") private var serverUrl = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("Units") {
                    Picker("Units", selection: $metric) {
                        Text("Miles / mph").tag(false)
                        Text("Kilometers / km/h").tag(true)
                    }.pickerStyle(.segmented)
                }
                Section("Show alerts") {
                    Picker("Mode", selection: $mode) {
                        Text("In front of me").tag("front")
                        Text("On my path (approximate)").tag("path")
                        Text("Within radius (all around)").tag("radius")
                    }.pickerStyle(.inline).labelsHidden()
                }
                Section("Radius") {
                    Picker("Radius", selection: $radiusM) {
                        Text("2 km (1.2 mi)").tag(2000)
                        Text("5 km (3.1 mi)").tag(5000)
                        Text("10 km (6.2 mi)").tag(10000)
                    }.pickerStyle(.segmented)
                }
                Section("Hide reports not confirmed within") {
                    Picker("Age", selection: $maxAgeMin) {
                        Text("30 min").tag(30)
                        Text("1 h").tag(60)
                        Text("2 h").tag(120)
                        Text("4 h").tag(240)
                    }.pickerStyle(.segmented)
                }
                Section("Voice alerts for") {
                    Toggle("Speed and red-light cameras", isOn: $voiceCamera)
                    Toggle("Police", isOn: $voicePolice)
                    Toggle("Hazards and accidents", isOn: $voiceHazard)
                    Toggle("Beep before the voice", isOn: $beep)
                }
                Section("Cameras") {
                    Toggle("Ignore cameras that face the other way", isOn: $dirFilter)
                }
                Section("How early to warn") {
                    Picker("Lead time", selection: $lead) {
                        Text("Late (about 30 s ahead)").tag(30)
                        Text("Normal (about 45 s)").tag(45)
                        Text("Early (about 70 s)").tag(70)
                    }.pickerStyle(.inline).labelsHidden()
                }
                Section("Community server") {
                    TextField("https://your-server.example.com", text: $serverUrl)
                        .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("Optional. With a server address, \"Report police / hazard\" shares your report anonymously with other drivers, you see their reports, and you get a risk score for where you are. Your report includes its location and a random anonymous ID. Leave empty to keep reports on this phone only.")
                        .font(.footnote)
                }
                Section("About") {
                    Text("Camera data: the wzsabre.rocks camera database (ExCam format, \"World cameras by @schuster\"), updated weekly. Speed limits in that data are km/h and are converted for display.")
                    Text("Not affiliated with Waze, wzSabre or Valentine Research. Community reports come only from users of this app through the server above; reports are unverified and expire after 2-4 hours.")
                    Text("This app is an aid, not a guarantee: cameras move, data is incomplete, and GPS can be wrong. Radar-detector and camera-alert rules differ by country and state; you are responsible for following local law and the posted speed limit. Don't operate the phone while driving.")
                }
                .font(.footnote)
            }
            .navigationTitle("Settings & about")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}
