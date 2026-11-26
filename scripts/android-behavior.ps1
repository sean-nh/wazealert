# Emulator check of the over-limit voice behaviour (debug build, uses run-as). About 4 minutes.
#  A. driving fast at a camera with a posted limit: first warning says "over the limit", then a closer "Slow down" reminder, each once per camera
#  B. same drive with the setting switched off: neither phrase is spoken, the normal camera warning still is
# Usage: ./scripts/android-behavior.ps1 -Apk android/app/build/outputs/apk/debug/app-debug.apk
param([string]$Apk)
$sdk = "$env:USERPROFILE\android-tools\sdk"; $adb = "$sdk\platform-tools\adb.exe"
$pkg = "app.wazealert.driver"; $fail = 0
function Check($name, $ok, $detail = "") { if ($ok) { "PASS  $name $detail" } else { "FAIL  $name $detail"; $script:fail++ } }
function Fix([double]$lon, [double]$lat = 39.7535) { & $adb emu geo fix $lon $lat | Out-Null }
function Logs { & $adb logcat -d | Out-String }
function Drive([double]$lon0, [int]$steps, [double]$dLon, [double]$lat = 39.7535) { [double]$l = $lon0; for ($i = 0; $i -lt $steps; $i++) { $l += $dLon; Fix $l $lat; Start-Sleep -Milliseconds 1000 }; return $l }
function Fresh([bool]$overLimit) {
  & $adb shell am force-stop $pkg; & $adb shell pm clear $pkg | Out-Null
  $api = [int](& $adb shell getprop ro.build.version.sdk)
  $perms = @("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"); if ($api -ge 33) { $perms += "POST_NOTIFICATIONS" }
  foreach ($perm in $perms) { & $adb shell pm grant $pkg "android.permission.$perm" 2>&1 | Out-Null }
  if (-not $overLimit) {
    $x = Join-Path $env:TEMP "wa-settings.xml"
    [IO.File]::WriteAllText($x, '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><boolean name="overLimit" value="false" /></map>')
    & $adb push $x /data/local/tmp/wa-settings.xml | Out-Null
    & $adb shell "run-as $pkg sh -c 'mkdir -p shared_prefs; cat /data/local/tmp/wa-settings.xml > shared_prefs/settings.xml'" | Out-Null
  }
  & $adb logcat -c 2>$null | Out-Null
}
function Launch { & $adb shell am start -f 0x10008000 -n "$pkg/.MainActivity" | Out-Null }

& $adb install -r $Apk | Out-Null
& $adb shell settings put secure location_mode 3 | Out-Null

# A. fast approach, setting on (default)
Fresh $true; Fix -84.2100; Launch; Start-Sleep 12
$null = Drive -84.2100 55 -0.0003            # ~92 km/h toward the cameras to the west
$t = Logs
$cam = ([regex]::Matches($t, "speak: Speed camera ahead")).Count
$over = ([regex]::Matches($t, "speak: Speed camera ahead[^\r\n]*you are over the limit")).Count
$slow = ([regex]::Matches($t, "speak: Slow down, limit")).Count
"INFO  A: camera warnings $cam, with 'over the limit' $over, 'Slow down' reminders $slow"
Check "A camera warning spoken" ($cam -ge 1)
Check "A 'you are over the limit' spoken for a camera with a limit" ($over -ge 1)
Check "A closer 'Slow down' reminder spoken" ($slow -ge 1)
Check "A at most one reminder per camera" ($slow -le $cam)
Check "A no crash" (-not ($t -match "FATAL EXCEPTION"))

# B. same drive with the setting off
Fresh $false; Fix -84.2100; Launch; Start-Sleep 12
$null = Drive -84.2100 55 -0.0003
$t = Logs
$cam = ([regex]::Matches($t, "speak: Speed camera ahead")).Count
"INFO  B: camera warnings $cam"
Check "B camera warning still spoken" ($cam -ge 1)
Check "B no 'over the limit' / 'Slow down' when switched off" (-not ($t -match "you are over the limit|speak: Slow down"))
Check "B no crash" (-not ($t -match "FATAL EXCEPTION"))

"----"; "failures: $fail"
exit $fail
