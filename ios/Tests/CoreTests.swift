import XCTest
@testable import WazeAlert

/// Mirrors the Android GeoTest, plus checks for the Swift-only pieces (XZ decoding, fix tracking, reports).
final class CoreTests: XCTestCase {

    func testBearingCardinal() {
        XCTAssertEqual(Geo.bearing(0, 0, 1, 0), 0, accuracy: 0.5)
        XCTAssertEqual(Geo.bearing(0, 0, 0, 1), 90, accuracy: 0.5)
        XCTAssertEqual(Geo.bearing(1, 0, 0, 0), 180, accuracy: 0.5)
        XCTAssertEqual(Geo.bearing(0, 1, 0, 0), 270, accuracy: 0.5)
    }

    func testAngleDiffWraps() {
        XCTAssertEqual(Geo.angleDiff(350, 10), 20, accuracy: 1e-9)
        XCTAssertEqual(Geo.angleDiff(0, 180), 180, accuracy: 1e-9)
    }

    func testAheadFilter() {
        XCTAssertTrue(Geo.isAhead(heading: 270, bearingTo: 270))
        XCTAssertTrue(Geo.isAhead(heading: 270, bearingTo: 300))
        XCTAssertFalse(Geo.isAhead(heading: 270, bearingTo: 90))
        XCTAssertFalse(Geo.isAhead(heading: 270, bearingTo: 0))
        XCTAssertTrue(Geo.isAhead(heading: 5, bearingTo: 350))
        XCTAssertTrue(Geo.isAhead(heading: nil, bearingTo: 90))
    }

    func testCameraDirection() {
        XCTAssertTrue(Geo.dirMatches(heading: 270, dirs: [282]))
        XCTAssertFalse(Geo.dirMatches(heading: 270, dirs: [102]))
        XCTAssertTrue(Geo.dirMatches(heading: 270, dirs: [102, 282]))
        XCTAssertTrue(Geo.dirMatches(heading: 270, dirs: nil))
        XCTAssertTrue(Geo.dirMatches(heading: nil, dirs: [102]))
        XCTAssertTrue(Geo.dirMatches(heading: 350, dirs: [10]))
    }

    func testAlertModes() {
        XCTAssertTrue(Geo.inMode("front", heading: 270, bearingTo: 270, distM: 1000))
        XCTAssertTrue(Geo.inMode("front", heading: 270, bearingTo: 300, distM: 1000))
        XCTAssertFalse(Geo.inMode("front", heading: 270, bearingTo: 90, distM: 1000))
        XCTAssertTrue(Geo.inMode("path", heading: 270, bearingTo: 270, distM: 1000))
        XCTAssertTrue(Geo.inMode("path", heading: 270, bearingTo: 276, distM: 1000))    // ~105 m sideways
        XCTAssertFalse(Geo.inMode("path", heading: 270, bearingTo: 280, distM: 1000))   // ~174 m sideways
        XCTAssertTrue(Geo.inMode("path", heading: 270, bearingTo: 290, distM: 200))     // ~68 m sideways
        XCTAssertFalse(Geo.inMode("path", heading: 270, bearingTo: 300, distM: 200))    // outside the 25 deg cone
        XCTAssertTrue(Geo.inMode("radius", heading: 270, bearingTo: 90, distM: 1000))
        XCTAssertTrue(Geo.inMode("path", heading: nil, bearingTo: 90, distM: 1000))
        XCTAssertEqual(Geo.crossTrack(distM: 1000, diffDeg: 90), 1000, accuracy: 1e-6)
    }

    // Same JSON shape the server (WazeAlert-Server) returns.
    func testParsesCommunityReports() throws {
        let json = """
        {"reports":[
          {"id":"5aca009333f3","type":"POLICE","lat":39.7535,"lon":-84.2235,"heading":270,"ageSec":71,"seenSec":6,"score":2},
          {"id":"aaaaaaaaaaaa","type":"HAZARD","lat":39.76,"lon":-84.22,"heading":null,"ageSec":9000,"seenSec":8000,"score":1}
        ]}
        """.data(using: .utf8)!
        let all = try CommunityClient.parseReports(json, lat: 39.7535, lon: -84.2230, maxSeenSec: 7200)
        XCTAssertEqual(all.count, 1)                              // the hazard was last confirmed >2 h ago
        XCTAssertEqual(all[0].id, "srv5aca009333f3")
        XCTAssertTrue(all[0].isCommunity)
        XCTAssertEqual(all[0].label, "Police")
        XCTAssertEqual(all[0].distanceM, 43, accuracy: 3)
        XCTAssertTrue(all[0].street.contains("2 confirmed"))
    }

    func testWarnRadius() {
        XCTAssertEqual(Geo.warnRadius(speed: 5), 800, accuracy: 1e-9)
        XCTAssertEqual(Geo.warnRadius(speed: 30), 1350, accuracy: 1e-9)
        XCTAssertEqual(Geo.warnRadius(speed: 100), 3000, accuracy: 1e-9)
        XCTAssertEqual(Geo.warnRadius(speed: 30, lead: 70), 2100, accuracy: 1e-9)
    }

    func testUnits() {
        XCTAssertEqual(Fmt.limit(56, metric: false), "35 mph")
        XCTAssertEqual(Fmt.limit(48, metric: false), "30 mph")
        XCTAssertEqual(Fmt.limit(120, metric: false), "75 mph")
        XCTAssertEqual(Fmt.limit(56, metric: true), "56 km/h")
        XCTAssertEqual(Fmt.dist(805, metric: false), "0.5 mi")
        XCTAssertEqual(Fmt.dist(400, metric: true), "400 m")
        XCTAssertEqual(Fmt.dist(1500, metric: true), "1.5 km")
        XCTAssertEqual(Fmt.speed(20.1168, metric: false), "45 mph")
    }

    // The fixture is the first 40 lines of the real wzsabre database, XZ-compressed.
    func testDecodesRealXZFixture() throws {
        let url = try XCTUnwrap(Bundle(for: CoreTests.self).url(forResource: "sample", withExtension: "xz"))
        let raw = try CameraDB.decompress(try Data(contentsOf: url))
        let db = CameraDB.parse(raw)
        XCTAssertEqual(db.count, 39)                                  // 40 lines minus the _meta line
        let near = db.near(lat: 39.75353475, lon: -84.22806708333334, radiusM: 50)
        let c = try XCTUnwrap(near.first)
        XCTAssertEqual(c.street, "Roosevelt Elementary")
        XCTAssertEqual(c.limitKmh, 56)
        XCTAssertEqual(c.dirs ?? [], [282])
        XCTAssertEqual(c.label, "Speed camera")
    }

    func testFixTrackerDerivesHeadingAndSpeed() {
        let tr = FixTracker()
        let stepLon = 20.0 / (111_320.0 * cos(39.7535 * .pi / 180))   // ~20 m per second, heading west
        var lon = -84.21
        for i in 0..<5 {
            let t = 1000.0 + Double(i)
            tr.accept(GPSSample(lat: 39.7535, lon: lon, t: t, accuracy: 5, speed: nil, course: nil), now: t)
            lon -= stepLon
        }
        let f = tr.fix
        XCTAssertEqual(f?.heading ?? -1, 270, accuracy: 1.0)
        XCTAssertEqual(f?.speed ?? 0, 20, accuracy: 1.0)
    }

    func testFixTrackerRejectsBadReadings() {
        let tr = FixTracker()
        tr.accept(GPSSample(lat: 1, lon: 1, t: 1000, accuracy: 150, speed: nil, course: nil), now: 1000)  // inaccurate
        XCTAssertNil(tr.fix)
        tr.accept(GPSSample(lat: 1, lon: 1, t: 1000, accuracy: 5, speed: nil, course: nil), now: 1020)    // stale
        XCTAssertNil(tr.fix)
        tr.accept(GPSSample(lat: 1, lon: 1, t: 1000, accuracy: 5, speed: nil, course: nil), now: 1001)    // fine
        XCTAssertNotNil(tr.fix)
        XCTAssertNil(tr.fix?.heading)                                                                      // stationary
    }

    // The camera snapshot bundled in the app must decode and parse, so the first launch works with no connection.
    func testBundledCameraDatabaseLoads() throws {
        let url = try XCTUnwrap(Bundle(for: AlertEngine.self).url(forResource: "cameras", withExtension: "xz"),
                                "cameras.xz is not bundled in the app")
        let db = CameraDB.parse(try CameraDB.decompress(try Data(contentsOf: url)))
        XCTAssertGreaterThan(db.count, 100_000)
        let near = db.near(lat: 39.75353475, lon: -84.22806708333334, radiusM: 50)
        XCTAssertEqual(near.first?.street, "Roosevelt Elementary")
    }

    // The beep is generated in memory; check it is a well-formed WAV (RIFF header, 16-bit mono, 0.25 s).
    func testBeepIsAValidWav() {
        let d = Beep.wavData
        XCTAssertEqual(String(data: d.prefix(4), encoding: .ascii), "RIFF")
        XCTAssertEqual(String(data: d.subdata(in: 8..<12), encoding: .ascii), "WAVE")
        XCTAssertEqual(d.count, 44 + 11025 * 2)
    }

    func testDiagnosticLogKeepsRecentLines() {
        for i in 0..<350 { DiagLog.log("line \(i)") }
        let dump = DiagLog.dump()
        XCTAssertTrue(dump.contains("line 349"))
        XCTAssertFalse(dump.contains("line 10\n"))
        XCTAssertLessThanOrEqual(dump.split(separator: "\n").count, 300)
    }

    func testSignalLostThresholds() {
        XCTAssertEqual(FixTracker.lostAfter(moving: true), 15)
        XCTAssertGreaterThan(FixTracker.lostAfter(moving: false), 60)   // a car at a red light must not trigger it
    }

    func testDeviceHeadingIsPreferred() {
        let tr = FixTracker()
        tr.accept(GPSSample(lat: 1, lon: 1, t: 1000, accuracy: 5, speed: 15, course: 90), now: 1000)
        XCTAssertEqual(tr.fix?.heading, 90)
        XCTAssertEqual(tr.fix?.speed, 15)
    }

    func testReportsExpireAfterTwoHours() throws {
        let d = try XCTUnwrap(UserDefaults(suiteName: "wazealert.tests"))
        d.removePersistentDomain(forName: "wazealert.tests")
        let t0 = Date(timeIntervalSince1970: 1_000_000)
        ReportStore.add(type: "POLICE", lat: 10, lon: 10, defaults: d, now: t0)
        XCTAssertEqual(ReportStore.active(lat: 10, lon: 10, defaults: d, now: t0.addingTimeInterval(3600)).count, 1)
        XCTAssertEqual(ReportStore.active(lat: 10, lon: 10, defaults: d, now: t0.addingTimeInterval(7300)).count, 0)
    }
}
