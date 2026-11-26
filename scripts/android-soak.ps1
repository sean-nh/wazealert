# Long soak test on an emulator with a DEBUG build: drives back and forth for N minutes and samples memory every 5 minutes.
# Each sample first forces a garbage collection (SIGUSR1), so the numbers show real growth, not collector lag.
# Usage: ./scripts/android-soak.ps1 -Apk android/app/build/outputs/apk/debug/app-debug.apk -Minutes 60
# Exit code 1 if the app died or memory (after GC) grew by more than -MaxGrowthMb between minute 10 and the end.
param([string]$Apk, [int]$Minutes = 60, [int]$MaxGrowthMb = 25)
$sdk = "$env:USERPROFILE\android-tools\sdk"; $adb = "$sdk\platform-tools\adb.exe"
$pkg = "app.wazealert.driver"
function Fix([double]$lon, [double]$lat = 39.7535) { & $adb emu geo fix $lon $lat | Out-Null }
function Num($text, $pattern) { if ($text -match $pattern) { [int]$Matches[1] } else { -1 } }

& $adb install -r $Apk | Out-Null
& $adb shell settings put secure location_mode 3 | Out-Null
& $adb shell am force-stop $pkg; & $adb shell pm clear $pkg | Out-Null
$api = [int](& $adb shell getprop ro.build.version.sdk)
$perms = @("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION"); if ($api -ge 33) { $perms += "POST_NOTIFICATIONS" }
foreach ($perm in $perms) { & $adb shell pm grant $pkg "android.permission.$perm" 2>&1 | Out-Null }
& $adb logcat -c 2>$null | Out-Null
Fix -84.2100; & $adb shell am start -f 0x10008000 -n "$pkg/.MainActivity" | Out-Null; Start-Sleep 15

$start = Get-Date; $next = 0; $rows = @(); [double]$lon = -84.2100; [double]$dir = 0.0003
"minute,pss_kb,java_kb,native_kb,threads,fds"
while (((Get-Date) - $start).TotalMinutes -lt $Minutes) {
  $min = [int]((Get-Date) - $start).TotalMinutes
  if ($min -ge $next) {
    $next += 5
    $pid_ = (& $adb shell pidof $pkg).Trim()
    if (-not $pid_) { "APP DIED at minute $min"; $rows += $null; break }
    & $adb shell run-as $pkg kill -10 $pid_ | Out-Null; Start-Sleep 3
    $m = (& $adb shell dumpsys meminfo $pkg) | Out-String
    $pss = Num $m 'TOTAL PSS:\s+(\d+)'; if ($pss -lt 0) { $pss = Num $m 'TOTAL\s+(\d+)' }
    $row = [pscustomobject]@{ minute = $min; pss = $pss; java = (Num $m 'Java Heap:\s+(\d+)'); native = (Num $m 'Native Heap:\s+(\d+)')
      threads = [int](& $adb shell "ls /proc/$pid_/task | wc -l"); fds = [int](& $adb shell "run-as $pkg ls /proc/$pid_/fd | wc -l") }
    $rows += $row
    "$($row.minute),$($row.pss),$($row.java),$($row.native),$($row.threads),$($row.fds)"
  }
  $lon += $dir; if ($lon -gt -84.2100 + 0.036 -or $lon -lt -84.2100 - 0.036) { $dir = -$dir }   # turn around every ~3 km
  Fix $lon; Start-Sleep -Milliseconds 1000
}
$log = (& $adb logcat -d | Out-String)
$crash = $log -match "FATAL EXCEPTION|ANR in"
$rows = $rows | Where-Object { $_ }
$alive = [bool](& $adb shell pidof $pkg)
$base = $rows | Where-Object { $_.minute -ge 10 } | Select-Object -First 1
$last = $rows | Select-Object -Last 1
$growthMb = if ($base -and $last) { [math]::Round(($last.pss - $base.pss) / 1024, 1) } else { 0 }
"----"; "alive=$alive crash=$crash growth_after_minute_10=${growthMb}MB threads $($base.threads)->$($last.threads) fds $($base.fds)->$($last.fds)"
if (-not $alive -or $crash -or $growthMb -gt $MaxGrowthMb) { "SOAK FAILED"; exit 1 } else { "SOAK PASSED"; exit 0 }
