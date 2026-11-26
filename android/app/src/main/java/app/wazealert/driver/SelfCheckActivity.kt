package app.wazealert.driver

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * "Run this before you drive": every common reason an alert app silently fails, checked in one place,
 * each with a button that fixes it. Also plays a test alert so you hear exactly what a real one sounds like.
 */
class SelfCheckActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private val results = mutableListOf<String>()      // plain text copy for "Share diagnostics"
    private var tts: TextToSpeech? = null
    private var voiceResult = "Not tested yet. Tap \"Play test alert\" and listen."
    private var serverResult: String? = null            // filled in by a background ping

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Pre-drive self-check"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        root.addView(button("Play test alert (beep + voice)") { playTestAlert() })
        root.addView(button("Re-check") { refresh() })
        root.addView(button("Share diagnostics") { shareDiagnostics() })
        setContentView(ScrollView(this).apply { addView(root) })
        Prefs.setBool(this, "selfCheckSeen", true)
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; setOnClickListener { onClick() }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    // ---- the checks ------------------------------------------------------------------------------------

    private enum class S(val mark: String, val color: Int) { OK("✓", Color.rgb(46, 125, 50)), WARN("!", Color.rgb(230, 130, 0)), BAD("✗", Color.rgb(198, 40, 40)) }

    private fun refresh() {
        list.removeAllViews(); results.clear()

        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        row(if (fine) S.OK else S.BAD, "Location permission",
            if (fine) "Allowed (precise)" else if (coarse) "Only approximate location is allowed" else "Not allowed",
            if (fine) null else "Fix" to { openAppSettings() })

        val gpsOn = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))
        row(if (gpsOn) S.OK else S.BAD, "Location services (GPS)", if (gpsOn) "On" else "Off for the whole phone",
            if (gpsOn) null else "Fix" to { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })

        val age = if (AlertState.fixAtMs == 0L) -1L else (SystemClock.elapsedRealtime() - AlertState.fixAtMs) / 1000
        row(if (!gpsOn) S.BAD else if (age in 0..15) S.OK else if (AlertState.running) S.WARN else S.BAD, "GPS fix",
            when {
                !gpsOn -> "No fix: location services are off"
                !AlertState.running -> "Alerts are not running. Open the main screen."
                age < 0 -> "No fix yet. Go outdoors with a clear view of the sky; the first fix can take a minute."
                age > 15 -> "Last fix ${age}s ago (signal lost)"
                else -> "Good, accuracy ${AlertState.accuracyM.toInt()} m"
            })

        row(if (AlertState.running) S.OK else S.BAD, "Alerts service", if (AlertState.running) "Running" else "Not running")

        val cams = CameraDb.size
        row(if (cams > 100_000) S.OK else S.BAD, "Camera database",
            if (cams > 0) "%,d cameras loaded".format(cams) else "Not loaded yet")

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC); val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        row(if (vol == 0) S.BAD else if (vol * 4 < max) S.WARN else S.OK, "Media volume",
            if (vol == 0) "Muted: you will not hear alerts. Raise the media volume." else "$vol of $max" + if (vol * 4 < max) " (low)" else "")

        row(if (voiceResult.startsWith("Played")) S.OK else if (voiceResult.startsWith("Failed")) S.BAD else S.WARN,
            "Voice and beep", voiceResult)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val unrestricted = pm.isIgnoringBatteryOptimizations(packageName)
        row(if (unrestricted) S.OK else S.WARN, "Battery restrictions",
            if (unrestricted) "Unrestricted: Android won't stop alerts to save power"
            else "Some phones stop background apps after a while. Allow unrestricted battery use for long drives.",
            if (unrestricted) null else "Allow" to { requestUnrestrictedBattery() })

        if (Build.VERSION.SDK_INT >= 33) {
            val n = granted(Manifest.permission.POST_NOTIFICATIONS)
            row(if (n) S.OK else S.WARN, "Notifications", if (n) "Allowed" else "Blocked: the \"alerts running\" notification is hidden (alerts still work)",
                if (n) null else "Fix" to { openAppSettings() })
        }

        val base = Prefs.serverUrl(this)
        if (base.isEmpty()) row(S.OK, "Community server", "Not used (reports stay on this phone)")
        else {
            val r = serverResult
            row(if (r == null) S.WARN else if (r == "ok") S.OK else S.BAD, "Community server",
                when (r) { null -> "Checking..."; "ok" -> "Reachable"; else -> "Not reachable: $r" })
            if (r == null) thread {
                val up = CommunityClient.ping(base)
                runOnUiThread { serverResult = if (up) "ok" else "check the address in Settings"; if (!isFinishing) refresh() }
            }
        }

        val bad = results.count { it.startsWith("[FAIL]") }; val warn = results.count { it.startsWith("[WARN]") }
        val summary = when {
            bad > 0 -> "$bad problem(s) to fix before you drive."
            warn > 0 -> "Ready, with $warn thing(s) worth fixing for long drives."
            else -> "Everything looks good. Safe driving."
        }
        list.addView(TextView(this).apply {
            text = summary; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(10))
            setTextColor(if (bad > 0) S.BAD.color else if (warn > 0) S.WARN.color else S.OK.color)
        }, 0)
        results.add(0, summary)
    }

    private fun row(s: S, label: String, detail: String, fix: Pair<String, () -> Unit>? = null) {
        results.add("[${when (s) { S.OK -> "ok"; S.WARN -> "WARN"; S.BAD -> "FAIL" }}] $label: $detail".replace("[ok]", "[ ok ]"))
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(6)) }
        line.addView(TextView(this).apply {
            text = s.mark; textSize = 22f; setTextColor(s.color); setTypeface(typeface, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(TextView(this).apply { text = label; textSize = 16f; setTypeface(typeface, Typeface.BOLD) })
        col.addView(TextView(this).apply { text = detail; textSize = 14f })
        line.addView(col)
        if (fix != null) line.addView(Button(this).apply { text = fix.first; setOnClickListener { fix.second() } })
        list.addView(line)
    }

    // ---- fixes ---------------------------------------------------------------------------------------------

    private fun openAppSettings() =
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))

    private fun requestUnrestrictedBattery() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { openAppSettings() }
        }
    }

    /** Plays the same beep + navigation-guidance speech a real alert uses, so the driver hears it before driving. */
    private fun playTestAlert() {
        val metric = Prefs.metric(this)
        val text = "Speed camera ahead, ${Fmt.dist(805.0, metric, spoken = true)}, limit " +
            Fmt.limit(56, metric).replace("km/h", "kilometers per hour")
        voiceResult = "Starting..."; refresh()
        DiagLog.log("self-check: test alert")
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 90).startTone(ToneGenerator.TONE_PROP_BEEP2, 250) } catch (_: Exception) {}
        tts?.shutdown()
        tts = TextToSpeech(this) { status ->
            runOnUiThread {
                if (status != TextToSpeech.SUCCESS) {
                    voiceResult = "Failed: no speech engine. Install or enable one in Settings > System > Languages & input > Text-to-speech."
                    DiagLog.log("self-check: speech engine FAILED ($status)"); refresh(); return@runOnUiThread
                }
                val t = tts ?: return@runOnUiThread
                t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                val l = Locale.getDefault()
                t.language = if (t.isLanguageAvailable(l) >= 0) l else Locale.US
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { runOnUiThread { voiceResult = "Played. If you heard the beep and the voice, alerts will be audible."; refresh() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { runOnUiThread { voiceResult = "Failed: the speech engine reported an error."; refresh() } }
                })
                voiceResult = "Playing..."
                refresh()
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "selftest")
            }
        }
    }

    // ---- diagnostics -----------------------------------------------------------------------------------------

    private fun shareDiagnostics() {
        val version = try { packageManager.getPackageInfo(packageName, 0).let { "${it.versionName} (${it.versionCode})" } } catch (e: Exception) { "?" }
        val text = buildString {
            appendLine("WazeAlert diagnostics")
            appendLine("App $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Locale ${Locale.getDefault()}, units ${if (Prefs.metric(this@SelfCheckActivity)) "metric" else "imperial"}, mode ${Prefs.mode(this@SelfCheckActivity)}")
            appendLine()
            results.forEach { appendLine(it) }
            appendLine()
            appendLine("Recent events (no locations recorded):")
            append(DiagLog.dump())
        }
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "WazeAlert diagnostics").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
    }
}
