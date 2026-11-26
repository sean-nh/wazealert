# First drive: checklist

Do this once, parked, before you rely on the app. It takes about five minutes.

## 1. Install

**Android:** on the phone, open the repository's *Releases* page, download the newest `WazeAlert-*-release.apk`, open it and allow
"install unknown apps" for your browser when asked. (Android 8 or newer.)

**iPhone:** `WazeAlert-unsigned.ipa` has to be signed with your Apple ID first (Sideloadly or AltStore from a PC/Mac, or
Xcode). A free Apple ID works but the app expires after 7 days. iOS 16 or newer; turn on Developer Mode
(Settings > Privacy & Security) if the phone asks.

## 2. Allow the right permissions

- **Location: "While using the app", with Precise ON.** Approximate location makes distances wrong.
- **Notifications: Allow** (Android 13+). The app shows a small "running" notification so the system keeps it alive.

## 3. Run the pre-drive self-check

The app offers it the first time; later it is the **Pre-drive self-check** button on the main screen.
Fix every red mark (each has a plain-language message and, where possible, a button):

| Check | What "good" looks like |
|---|---|
| Location permission / services | Allowed (precise) / On |
| GPS fix | "Good, accuracy N m". Needs a clear view of the sky; the first fix can take a minute |
| Camera database | About 123,000 cameras loaded (works with no internet; refreshes itself weekly) |
| Volume | Not muted. Raise it now, not on the highway |
| Voice and beep | Tap **Play test alert** and *listen*: you should hear a beep then "Speed camera ahead, 0.5 miles, limit 35 mph" |
| Battery (Android) | Tap **Allow** so Android does not stop the app during long drives |
| Low Power Mode (iPhone) | Off |

On some Android phones (Samsung, Xiaomi, Huawei, OnePlus) you also need to exempt the app from "sleeping apps" or "autostart"
restrictions: see dontkillmyapp.com for your brand.

## 4. On the road

- Open the app **before** you start driving and wait for "GPS ... mph" to appear in the grey status line.
- Leave it running. The screen can turn off; keep the phone plugged in on long trips (GPS drains the battery).
- Banner colours: amber under 1 km, red under 400 m. Voice speaks about 30 to 70 seconds before you arrive (Settings > How early).
- Alerts only speak while you are moving, and only for things ahead of you.
- **Over the limit:** approaching a camera faster than its posted limit (plus a 5 km/h margin) the banner adds "SLOW DOWN" and the voice adds "you are over the limit".
- **Closer reminder:** if you are still over a camera's limit about 12 seconds before it, the voice says "Slow down, limit N" once. Both spoken warnings can be turned off in Settings > Cameras; the banner stays.
- **Parallel roads:** spoken alerts only fire for cameras on your line of travel (within 45 degrees and 300 m to the side), so a camera on a nearby highway should not be announced. The list still shows everything in front of you.
- **Map button:** shows you, your heading, your speed, nearby cameras and community reports. Map tiles need internet; alerts do not.
- Car Bluetooth or CarPlay/Android Auto audio: the voice is played as navigation guidance, so it should come through the car speakers
  and lower your music. Check this once while parked using **Play test alert**.

## 5. If anything is wrong

Open **Pre-drive self-check > Share diagnostics** and send the text. It contains phone model, versions, each check's result and
the last 300 events (no locations are recorded), which is what is needed to find the problem.

## What to know

- Tested on emulators, simulators and automated tests; **not yet on a real phone while driving**. Report anything odd.
- Cameras come from the wzsabre.rocks database (about 123,000, worldwide, updated weekly). It is incomplete and cameras move.
- Camera "direction" filtering assumes the database's direction field means the direction of traffic the camera watches. If you
  notice missed cameras, switch off Settings > "Ignore cameras that face the other way".
- Community reports need a server address (Settings > Community server). Without one, reports stay on your phone.
- Check the law for camera/speed-trap alerts where you drive, and do not operate the phone while driving.
