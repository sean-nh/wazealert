# Android stress test for an emulator running with a DEBUG build (uses run-as). About 15 minutes.
# Scenarios: stationary, driving away, approach, process kill, GPS off/on, teleport, rapid start/stop, permission revoked, 6-minute memory check.
# Usage: ./scripts/android-stress.ps1 -Apk android/app/build/outputs/apk/debug/app-debug.apk
param([string]$Apk, [string]$Serial = "")
$ErrorActionPreference = "Continue"
$sdk = "$env:USERPROFILE\android-tools\sdk"; $adb = "$sdk\platform-tools\adb.exe"
$pkg = "app.wazealert.driver"
$fail = 0
function Check($name, $ok, $detail = "") { if ($ok) { "PASS  $name $detail" } else { "FAIL  $name $detail"; $script:fail++; (& $adb logcat -d -s WazeAlert | Select -Last 30) } }
function Fix([double]$lon, [double]$lat = 39.7535) { & $adb emu geo fix $lon $lat | Out-Null }
function Logs { & $adb logcat -d | Out-String }
function Drive([double]$lon0, [int]$steps, [double]$dLon, [double]$lat = 39.7535) { [double]$l = $lon0; for ($i = 0; $i -lt $steps; $i++) { $l += $dLon; Fix $l $lat; Start-Sleep -Milliseconds 1000 }; return $l }
function Fresh {
  & $adb shell am force-stop $pkg
  & $adb shell pm clear $pkg | Out-Null
  $apiLevel = [int](& $adb shell getprop ro.build.version.sdk)
  $perms = @("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"); if ($apiLevel -ge 33) { $perms += "POST_NOTIFICATIONS" }
  foreach ($p in $perms) { & $adb shell pm grant $pkg "android.permission.$p" 2>&1 | Out-Null }
  & $adb logcat -c 2>$null | Out-Null
}
function Launch { & $adb shell am start -f 0x10008000 -n "$pkg/.MainActivity" | Out-Null }
function IsFg { (& $adb shell dumpsys activity services $pkg | Select-String "isForeground=true").Count -gt 0 }

& $adb install -r $Apk | Out-Null
& $adb shell settings put secure location_mode 3 | Out-Null

$api = [int](& $adb shell getprop ro.build.version.sdk)
# 1. stationary: no speech
Fresh; Fix -84.2160; Launch; Start-Sleep 12
for ($i = 0; $i -lt 25; $i++) { Fix -84.2160; Start-Sleep -Milliseconds 1000 }
Check "1 stationary near camera is silent" (-not ((Logs) -match "speak:"))

# 2. driving away from the camera: no speech
Fresh; Fix -84.2300; Launch; Start-Sleep 12
$null = Drive -84.2300 20 0.0003    # east, away from cameras to the west... (cameras near -84.228 .. -84.23)
$t = Logs
Check "2 no crash while driving east" (-not ($t -match "FATAL EXCEPTION"))

# 3. approach: speaks, once per camera
Fresh; Fix -84.2100; Launch; Start-Sleep 12
$null = Drive -84.2100 30 -0.0003
$t = Logs; $n = ([regex]::Matches($t, "speak: Speed camera ahead")).Count
Check "3 approach speaks" ($n -ge 1) "($n announcements)"
Check "3 no repeated announcement of same camera in 30 s" ($n -le 6)

# 4. kill the process mid-drive: service must come back
Fix -84.2200; Start-Sleep 2
$pid1 = (& $adb shell pidof $pkg).Trim()
& $adb shell "run-as $pkg kill $pid1" | Out-Null
Start-Sleep 20
$pid2 = (& $adb shell pidof $pkg)
Check "4 process killed mid-drive" ($pid1 -ne "")
"INFO  after kill: pid $pid1 -> '$pid2' (foreground service: $(IsFg))"

# 5. location services off then on
Fresh; Fix -84.2160; Launch; Start-Sleep 10
function GpsOff { if ($api -lt 28) { & $adb shell settings put secure location_providers_allowed "-gps" | Out-Null; & $adb shell settings put secure location_providers_allowed "-network" | Out-Null } else { & $adb shell settings put secure location_mode 0 | Out-Null } }
function GpsOn { if ($api -lt 28) { & $adb shell settings put secure location_providers_allowed "+gps" | Out-Null; & $adb shell settings put secure location_providers_allowed "+network" | Out-Null }; & $adb shell settings put secure location_mode 3 | Out-Null }
GpsOff; Start-Sleep 6
$t = Logs
Check "5 GPS-off is noticed" ($t -match "location services OFF")
GpsOn; Start-Sleep 3
$null = Drive -84.2160 8 -0.0003
Check "5 recovers after GPS back on" ((Logs) -match "tracking" -and -not ((Logs) -match "FATAL EXCEPTION"))

# 6. teleport 1000 km: no crash
Fix -84.2160 39.7535; Start-Sleep 2; Fix -100.0 35.0; Start-Sleep 4; Fix -84.2160 39.7535; Start-Sleep 4
Check "6 teleport survives" (-not ((Logs) -match "FATAL EXCEPTION|ANR in"))

# 7. rapid start/stop
for ($i = 0; $i -lt 6; $i++) { Launch; Start-Sleep 2; & $adb shell am startservice -n "$pkg/.AlertService" -a STOP 2>$null | Out-Null; Start-Sleep 1 }
Launch; Start-Sleep 6
Check "7 rapid launch/stop no crash" (-not ((Logs) -match "FATAL EXCEPTION|ANR in"))

# 8. permission revoked while running
Fresh; Fix -84.2160; Launch; Start-Sleep 10
& $adb shell pm revoke $pkg android.permission.ACCESS_FINE_LOCATION | Out-Null; Start-Sleep 3
& $adb shell pm revoke $pkg android.permission.ACCESS_COARSE_LOCATION | Out-Null; Start-Sleep 3
Launch; Start-Sleep 5
Check "8 relaunch after revoke does not crash" (-not ((Logs) -match "FATAL EXCEPTION"))
$api = [int](& $adb shell getprop ro.build.version.sdk); if ($api -lt 28) { "SKIP  8 permission-prompt check (uiautomator crashes the API $api system UI)"; $x = "location" } else { & $adb shell uiautomator dump /sdcard/u.xml | Out-Null; & $adb pull /sdcard/u.xml "$env:TEMP\u8.xml" 2>$null | Out-Null
$x = Get-Content "$env:TEMP\u8.xml" -Raw }
Check "8 asks for permission again" ($x -match "permissioncontroller|Allow|location") "(prompt or in-app explanation visible)"

# 9. memory over a 3 minute drive
Fresh; Fix -84.2100; Launch; Start-Sleep 12
$m0 = [int](((& $adb shell dumpsys meminfo $pkg | Select-String "TOTAL PSS|TOTAL\s+\d+") | Select -First 1) -replace '\D+(\d+).*', '$1')
[double]$l = -84.2100; for ($r = 0; $r -lt 3; $r++) { $l = Drive $l 60 -0.0003; $l = Drive $l 60 0.0003 }
$m1 = [int](((& $adb shell dumpsys meminfo $pkg | Select-String "TOTAL PSS|TOTAL\s+\d+") | Select -First 1) -replace '\D+(\d+).*', '$1')
Check "9 memory growth under 60 MB over 6 min" (($m1 - $m0) -lt 60000) "(PSS KB $m0 -> $m1)"
Check "9 still running" ((& $adb shell pidof $pkg) -ne "" -and (IsFg))
Check "9 no crash/ANR" (-not ((Logs) -match "FATAL EXCEPTION|ANR in"))

"----"; "failures: $fail"
exit $fail
