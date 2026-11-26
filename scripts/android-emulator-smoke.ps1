<#
  Smoke test for the Android app on a running emulator (or device with mock GPS via `adb emu geo fix`).

  What it checks: install, runtime permissions, foreground service, camera database loads, a simulated drive
  produces a spoken alert (speech engine starts AND finishes), no crash, no ANR.
  Exit code 0 = pass, 1 = a check failed.

  Usage (PowerShell):
    ./scripts/android-emulator-smoke.ps1 -Adb "$env:USERPROFILE\android-tools\sdk\platform-tools\adb.exe" `
        -Apk android/app/build/outputs/apk/debug/app-debug.apk
#>
param(
    [string]$Adb = "adb",
    [string]$Apk = "android/app/build/outputs/apk/debug/app-debug.apk",
    [string]$Package = "app.wazealert.driver"
)
$ErrorActionPreference = "Stop"
$failed = 0
function Check($name, $ok, $detail = "") {
    if ($ok) { Write-Host ("PASS  {0} {1}" -f $name, $detail) } else { Write-Host ("FAIL  {0} {1}" -f $name, $detail); $script:failed++ }
}

& $Adb install -r $Apk | Out-Null
& $Adb shell pm clear $Package | Out-Null
foreach ($p in "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS") {
    & $Adb shell pm grant $Package "android.permission.$p" 2>$null | Out-Null
}
& $Adb logcat -c 2>$null | Out-Null
& $Adb emu geo fix -84.2160 39.7535 | Out-Null          # start about 1.1 km east of a known camera (Dayton, OH)
& $Adb shell am start -n "$Package/.MainActivity" | Out-Null
Start-Sleep 30                                           # first run downloads the camera database

$svc = (& $Adb shell dumpsys activity services $Package | Select-String "isForeground=true").Count -gt 0
Check "foreground service running" $svc

$lon = -84.2160
for ($i = 0; $i -lt 24; $i++) {                          # drive west at ~15 m/s
    $lon -= 0.000200
    & $Adb emu geo fix $lon 39.7535 | Out-Null
    Start-Sleep -Milliseconds 1100
}
Start-Sleep 3

$log = (& $Adb logcat -d -s WazeAlert:I CameraDb:I) -join "`n"
$loaded = [regex]::Match($log, "loaded (\d+) cameras in (\d+) ms")
Check "camera database loaded" ($loaded.Success -and [int]$loaded.Groups[1].Value -gt 100000) $loaded.Value
Check "voice alert spoken" ($log -match "speak: .*camera ahead")
Check "speech engine started and finished" (($log -match "tts start") -and ($log -match "tts done"))

$crash = (& $Adb logcat -d -b crash 2>&1 | Select-String "Process: $Package").Count
Check "no crash" ($crash -eq 0)
$anr = (& $Adb logcat -d -b all 2>&1 | Select-String "ANR in $Package").Count
Check "no ANR" ($anr -eq 0)

if ($failed -gt 0) { Write-Host "$failed check(s) failed"; exit 1 }
Write-Host "all checks passed"; exit 0
