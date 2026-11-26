# WazeAlert

Camera and community-report alerts for drivers, on Android and iPhone.

| Folder | What |
|---|---|
| `android/` | Kotlin app (builds and tested; signed release build supported locally) |
| `ios/` | SwiftUI port, generated with XcodeGen. Built and tested by GitHub Actions on a macOS runner |
| `server/` | Optional community-reports server (Node, no dependencies) |

Camera data comes from the wzsabre.rocks camera database. See each folder's README or build files.
Signing keys are never committed: `android/release.keystore` and `android/keystore.properties` stay local.
