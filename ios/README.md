# WazeAlert for iPhone

SwiftUI port of the Android app: speed/red-light camera alerts from the wzsabre.rocks camera database,
heading-aware ("ahead of you") filtering, camera-direction filtering, voice + beep, a colour-coded banner,
local "Report police / hazard" buttons, units/voice/lead-time settings, and background operation.

Also like Android: alert modes (in front / on my path / within radius), a radius and report-age setting, and
optional shared community reports with "Still there / Gone" votes and a risk score (needs the server in
`../WazeAlert-Server`; enter its HTTPS address under Settings > Community server).

Differences from Android: "Pause/Resume" replaces "Stop alerts" (iOS keeps apps alive in the background only
while location updates run).

## Status: written, NOT yet compiled

This was written on a Windows PC, which cannot run Xcode. The pure logic (distance, bearing, ahead filter,
camera direction, unit conversion, fix tracking, XZ camera database) is covered by `Tests/CoreTests.swift`,
which mirrors the Android unit tests that pass. The first compile on a Mac may need small fixes.

## Build it (pick one)

**A. On a Mac with Xcode 15+**
```
brew install xcodegen
xcodegen generate
open WazeAlert.xcodeproj
```
In Xcode: select the WazeAlert target > Signing & Capabilities > choose your Team, plug in the iPhone, press Run.
Run the tests with Cmd+U.

**B. Without a Mac (GitHub Actions)**
Push this folder to a GitHub repository. `.github/workflows/ios.yml` builds and runs the tests on a cloud Mac and
uploads an *unsigned* `.ipa`. An unsigned IPA cannot be installed as-is; it has to be signed
(Xcode, or a re-signing tool such as AltStore/Sideloadly with your Apple ID).

## Installing on a real iPhone

- A free Apple ID works, but the app expires after 7 days and must be re-installed.
- A paid Apple Developer account ($99/year) gives 1-year installs and TestFlight/App Store distribution.
- iPhone: Settings > Privacy & Security > Developer Mode must be on.

## First run

Allow Location ("While Using the App", leave Precise Location on). The app then keeps running with the screen
off (blue status-bar indicator) because it declares the `location` and `audio` background modes.
