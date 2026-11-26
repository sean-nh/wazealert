import SwiftUI
import MapKit

/// Live map: your position and heading plus the cameras and community reports the app is watching.
struct MapScreen: View {
    @EnvironmentObject var engine: AlertEngine
    @Environment(\.dismiss) private var dismiss
    @State private var region = MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: 39.75, longitude: -84.2),
                                                   span: MKCoordinateSpan(latitudeDelta: 0.02, longitudeDelta: 0.02))
    @State private var follow = true
    @State private var selected: Alert?

    private struct Pin: Identifiable {
        let id: String
        let coordinate: CLLocationCoordinate2D
        let alert: Alert?          // nil = you
    }

    private var pins: [Pin] {
        guard let s = engine.mapSnap else { return [] }
        var out = s.alerts.map { Pin(id: $0.id, coordinate: CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon), alert: $0) }
        out.append(Pin(id: "me", coordinate: CLLocationCoordinate2D(latitude: s.lat, longitude: s.lon), alert: nil))
        return out
    }

    private func color(_ a: Alert) -> Color {
        switch a.type {
        case "POLICE": return Color(red: 0.08, green: 0.40, blue: 0.75)
        case "HAZARD": return Color(red: 0.94, green: 0.42, blue: 0.0)
        case "ACCIDENT": return Color(red: 0.55, green: 0.0, blue: 0.0)
        default: return a.subtype == "CAMERA_REDLIGHT" ? Color(red: 0.83, green: 0.18, blue: 0.18)
                                                      : Color(red: 0.49, green: 0.34, blue: 0.76)
        }
    }

    private func detail(_ a: Alert) -> String {
        let m = UserDefaults.standard.bool(forKey: "metric")
        return "\(a.label) - \(Fmt.dist(a.distanceM, metric: m))  \(a.street)"
            + (a.limitKmh > 0 ? "  (limit \(Fmt.limit(a.limitKmh, metric: m)))" : "")
    }

    var body: some View {
        NavigationStack {
            ZStack(alignment: .bottom) {
                Map(coordinateRegion: $region, interactionModes: .all, showsUserLocation: false, annotationItems: pins) { pin in
                    MapAnnotation(coordinate: pin.coordinate) {
                        if let a = pin.alert {
                            Circle().fill(color(a)).frame(width: 18, height: 18)
                                .overlay(Circle().stroke(Color.white, lineWidth: 3))
                                .onTapGesture { selected = a }
                        } else {
                            Image(systemName: "location.north.fill").font(.title)
                                .foregroundColor(Color(red: 0.05, green: 0.28, blue: 0.63))
                                .rotationEffect(.degrees(engine.mapSnap?.heading ?? 0))
                        }
                    }
                }
                .ignoresSafeArea(edges: .bottom)
                VStack(spacing: 6) {
                    if let a = selected { Text(detail(a)).font(.callout).padding(8).background(.thinMaterial).cornerRadius(8) }
                    Text("Purple speed camera, red red-light camera, blue police, orange hazard, dark red accident")
                        .font(.caption2).padding(6).background(.thinMaterial).cornerRadius(6)
                    Toggle("Follow me", isOn: $follow).padding(8).background(.thinMaterial).cornerRadius(8)
                        .padding(.horizontal)
                }.padding(.bottom, 8)
            }
            .navigationTitle("Map")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { recenter() }
            .onChange(of: engine.mapSnap?.lat) { _ in if follow { recenter() } }
            .onChange(of: engine.mapSnap?.lon) { _ in if follow { recenter() } }
        }
    }

    private func recenter() {
        guard let s = engine.mapSnap else { return }
        region.center = CLLocationCoordinate2D(latitude: s.lat, longitude: s.lon)
    }
}