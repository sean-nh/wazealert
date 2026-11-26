import XCTest
import CoreLocation

/// Taps through the real app in the simulator: permission prompt, settings sheet, report button.
final class WazeAlertUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testSettingsAndReportFlow() throws {
        let app = XCUIApplication()

        // Accept the location permission prompt whenever it appears.
        addUIInterruptionMonitor(withDescription: "Location permission") { alert in
            for label in ["Allow While Using App", "Allow Once", "Allow"] where alert.buttons[label].exists {
                alert.buttons[label].tap(); return true
            }
            return false
        }
        app.launch()
        XCTAssertTrue(app.navigationBars["WazeAlert"].waitForExistence(timeout: 15), "main screen did not appear")
        app.tap()   // lets the interruption monitor deal with the permission alert

        if #available(iOS 16.4, *) {
            XCUIDevice.shared.location = XCUILocation(location: CLLocation(latitude: 39.7535, longitude: -84.2230))
        }

        // Main screen controls exist
        for name in ["Mute voice", "Pause", "Report police", "Report hazard"] {
            XCTAssertTrue(app.buttons[name].waitForExistence(timeout: 5), "missing button \(name)")
        }

        // Mute toggles its own label
        app.buttons["Mute voice"].tap()
        XCTAssertTrue(app.buttons["Unmute voice"].waitForExistence(timeout: 3))
        app.buttons["Unmute voice"].tap()

        // Pause / Resume
        app.buttons["Pause"].tap()
        XCTAssertTrue(app.buttons["Resume"].waitForExistence(timeout: 3))
        app.buttons["Resume"].tap()

        // Report police: either reported (fix available) or told to wait for GPS. Never silent.
        app.buttons["Report police"].tap()
        let toast = NSPredicate(format: "label CONTAINS 'reported' OR label CONTAINS 'Waiting for a GPS fix'")
        XCTAssertTrue(app.staticTexts.matching(toast).firstMatch.waitForExistence(timeout: 5), "no feedback after Report police")

        // Settings sheet
        app.buttons["Settings and about"].tap()
        XCTAssertTrue(app.navigationBars["Settings & about"].waitForExistence(timeout: 5), "settings did not open")
        func hasSection(_ header: String) -> Bool {
            app.staticTexts[header].exists || app.staticTexts[header.uppercased()].exists
        }
        // The form is a lazy list: rows below the fold are not in the accessibility tree until scrolled to.
        for header in ["Units", "Show alerts"] { XCTAssertTrue(hasSection(header), "missing section \(header)") }
        app.buttons["Kilometers / km/h"].tap()
        XCTAssertTrue(app.buttons["Kilometers / km/h"].isSelected)
        app.buttons["Miles / mph"].tap()                         // put it back
        XCTAssertTrue(app.buttons["Miles / mph"].isSelected)
        for _ in 0..<4 where !hasSection("About") { app.swipeUp() }
        for header in ["Community server", "About"] { XCTAssertTrue(hasSection(header), "missing section \(header) after scrolling") }
        for _ in 0..<4 { app.swipeDown() }
        app.buttons["Done"].tap()
        XCTAssertTrue(app.navigationBars["WazeAlert"].waitForExistence(timeout: 5), "did not return to the main screen")

        let shot = XCTAttachment(screenshot: app.screenshot()); shot.lifetime = .keepAlways; add(shot)
    }
}
