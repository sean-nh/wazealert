package app.wazealert.driver

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.TimeZone

data class UiState(
    val status: String = "Starting...",
    val nearest: String = "No alerts ahead",
    val nearestM: Double? = null,
    /** Id of the nearest alert if it is a community report (enables the "still there / gone" buttons). */
    val nearestCommunityId: String? = null,
    val items: List<String> = emptyList(),
    val gpsOff: Boolean = false,
)

/** What the map screen draws: own position/heading and the alerts within the alert radius. */
data class MapSnap(val lat: Double, val lon: Double, val heading: Double?, val alerts: List<Alert>)

object AlertState {
    val state = MutableStateFlow(UiState())
    val map = MutableStateFlow<MapSnap?>(null)
    @Volatile var muted = false
    @Volatile var running = false
    @Volatile var hasFix = false
    /** Accuracy (m) and uptime-millis of the latest accepted GPS fix, for the self-check screen. */
    @Volatile var accuracyM = -1f
    @Volatile var fixAtMs = 0L
}

/** A GPS fix plus the heading/speed we trust (device-reported, or derived from consecutive fixes). */
private data class Fix(val loc: Location, val heading: Double?, val speedMps: Double, val at: Long)

class AlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var fused: FusedLocationProviderClient? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsFailed = false
    private var tone: ToneGenerator? = null
    private var callback: LocationCallback? = null

    @Volatile private var fix: Fix? = null
    private var prev: Location? = null

    private val announced = mutableSetOf<String>()
    @Volatile private var community: List<Alert> = emptyList()
    private var communityNote = ""
    @Volatile private var nextCommunityAt = 0L
    private var riskText = ""
    private var nextRiskAt = 0L

    companion object {
        private const val TAG = "WazeAlert"
        const val MAX_ACCURACY_M = 100f   // ignore fixes worse than this
        const val STALE_S = 15            // no usable fix for this long => "GPS signal lost"
        val speakTypes = setOf("POLICE", "ACCIDENT", "HAZARD", "CAMERA")
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> { stopSelf(); return START_NOT_STICKY }
            "REPORT_POLICE", "REPORT_HAZARD" -> {
                if (AlertState.running) report(if (intent.action == "REPORT_POLICE") "POLICE" else "HAZARD")
                return START_STICKY
            }
            "VOTE" -> {
                val id = intent.getStringExtra("id"); val v = intent.getIntExtra("vote", 0)
                if (AlertState.running && id != null && (v == 1 || v == -1)) sendVote(id, v)
                return START_STICKY
            }
        }
        if (AlertState.running) return START_STICKY

        // A sticky restart can happen after the user revoked location; bail out instead of crashing.
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return START_NOT_STICKY
        }
        // ServiceCompat: the 3-argument startForeground only exists on Android 10+, this works back to Android 8.
        try { ServiceCompat.startForeground(this, 1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) }
        catch (e: Exception) { stopSelf(); return START_NOT_STICKY }
        AlertState.running = true
        DiagLog.log("service started")

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                val l = Locale.getDefault()
                tts?.language = if ((tts?.isLanguageAvailable(l) ?: -1) >= 0) l else Locale.US
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { DiagLog.log("tts start $utteranceId") }
                    override fun onDone(utteranceId: String?) { DiagLog.log("tts done $utteranceId") }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { DiagLog.log("tts ERROR $utteranceId") }
                })
                ttsReady = true
                DiagLog.log("speech engine ready")
            } else {
                ttsFailed = true          // no speech engine installed/enabled: say so instead of staying silent
                DiagLog.log("speech engine FAILED to start (status $status)")
            }
        }
        tone = try { ToneGenerator(AudioManager.STREAM_MUSIC, 90) } catch (e: Exception) { null }
        fused = LocationServices.getFusedLocationProviderClient(this)
        startLocation()
        scope.launch { loop() }
        return START_STICKY
    }

    // ---- reports and votes -------------------------------------------------------------------------------

    /** With a community server configured the report is shared (and appears via the next fetch); otherwise it is kept on this phone. */
    private fun report(type: String) {
        val f = fix ?: return
        val base = Prefs.serverUrl(this)
        val lat = f.loc.latitude; val lon = f.loc.longitude
        if (base.isEmpty()) { ReportStore.add(this, type, lat, lon); return }
        scope.launch(Dispatchers.IO) {
            try {
                CommunityClient.post(base, type, lat, lon, f.heading, Prefs.deviceId(this@AlertService))
                nextCommunityAt = 0   // refresh the list right away
            } catch (e: Exception) {
                ReportStore.add(this@AlertService, type, lat, lon)   // offline: keep it locally rather than lose it
            }
        }
    }

    private fun sendVote(id: String, v: Int) {
        val base = Prefs.serverUrl(this); if (base.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try { CommunityClient.vote(base, id, Prefs.deviceId(this@AlertService), v); nextCommunityAt = 0 } catch (_: Exception) {}
        }
    }

    // ---- notification and GPS ----------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val ch = "alerts"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "Alerts", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AlertService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("WazeAlert running")
            .setContentText("Watching for cameras and alerts ahead")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open).setOngoing(true)
            .addAction(0, "Stop", stop).build()
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) { r.locations.forEach(::accept) }
        }
        callback = cb
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setMinUpdateIntervalMillis(500)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()
        fused?.requestLocationUpdates(req, cb, mainLooper)
    }

    /** Filters a raw fix and works out heading/speed. */
    private fun accept(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return
        val ageS = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1e9
        if (ageS > STALE_S) return

        var heading: Double? = null
        var speed = 0.0
        var tooSoon = false
        if (loc.hasSpeed() && loc.hasBearing() && loc.speed >= 2f) {
            heading = loc.bearing.toDouble(); speed = loc.speed.toDouble(); prev = loc
        } else {
            val p = prev
            if (p == null) prev = loc
            else {
                val dt = (loc.elapsedRealtimeNanos - p.elapsedRealtimeNanos) / 1e9
                val dist = Geo.distance(p.latitude, p.longitude, loc.latitude, loc.longitude)
                when {
                    dt < 0.5 -> tooSoon = true                       // too soon, keep previous reference
                    !Geo.plausibleSpeed(dist / dt) -> {              // position jump (GPS glitch, tunnel exit): re-anchor, do not invent a speed/heading
                        DiagLog.log("GPS jump of ${dist.toInt()} m ignored")
                        prev = loc
                    }
                    dist >= 8.0 && dist / dt >= 2.0 -> {             // moving: derive heading from the track
                        heading = Geo.bearing(p.latitude, p.longitude, loc.latitude, loc.longitude)
                        speed = dist / dt; prev = loc
                    }
                    dt > 10 -> prev = loc                            // standing still: refresh reference
                }
            }
        }
        val old = fix
        if (tooSoon && old?.heading != null) { heading = old.heading; speed = old.speedMps }
        if (fix == null) DiagLog.log("first GPS fix accepted (accuracy ${if (loc.hasAccuracy()) loc.accuracy.toInt() else -1} m)")
        fix = Fix(loc, heading, speed, SystemClock.elapsedRealtime())
        AlertState.hasFix = true
        AlertState.accuracyM = if (loc.hasAccuracy()) loc.accuracy else -1f
        AlertState.fixAtMs = SystemClock.elapsedRealtime()
    }

    // ---- main loop ---------------------------------------------------------------------------------------

    private suspend fun loop() {
        publish(UiState(status = "Loading camera database..."))
        while (scope.isActive) {
            if (!CameraDb.loaded) {
                try { CameraDb.load(applicationContext) }
                catch (e: Exception) {
                    DiagLog.log("camera database load FAILED: ${e.message}")
                    publish(UiState(status = "Camera data failed to load (${e.message}); retrying..."))
                    delay(30_000); continue
                }
                // Get a fresher database for next time; never blocks alerts and failure is harmless.
                scope.launch(Dispatchers.IO) {
                    try { CameraDb.refresh(applicationContext) }
                    catch (e: Exception) { DiagLog.log("camera database refresh skipped: ${e.message}") }
                }
            }
            try { update() } catch (e: Exception) { publish(UiState(status = "Error: ${e.message}")) }
            delay(2000)
        }
    }

    private fun publish(s: UiState) { AlertState.state.value = s }

    private fun gpsEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))

    private fun voiceOn(type: String) = when (type) {
        "CAMERA" -> Prefs.voiceCamera(this)
        "POLICE" -> Prefs.voicePolice(this)
        else -> Prefs.voiceHazard(this)
    }

    /** Pulls community reports and the risk score in the background; never blocks the 2-second loop. */
    private fun refreshCommunity(lat: Double, lon: Double, radiusM: Double, maxSeenSec: Int) {
        val base = Prefs.serverUrl(this)
        val now = System.currentTimeMillis()
        if (base.isEmpty()) { community = emptyList(); communityNote = ""; riskText = ""; return }
        if (now >= nextCommunityAt) {
            nextCommunityAt = now + 20_000
            scope.launch(Dispatchers.IO) {
                try {
                    community = CommunityClient.fetch(base, lat, lon, radiusM, maxSeenSec)
                    communityNote = " · ${community.size} community"
                } catch (e: Exception) {
                    if (communityNote != " · community offline") DiagLog.log("community server unreachable: ${e.message}")
                    community = emptyList(); communityNote = " · community offline"
                    nextCommunityAt = System.currentTimeMillis() + 120_000
                }
            }
        }
        if (now >= nextRiskAt) {
            nextRiskAt = now + 120_000
            scope.launch(Dispatchers.IO) {
                riskText = try {
                    val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
                    val r = CommunityClient.risk(base, lat, lon, tz)
                    " · risk ${r.level} (${r.score})"
                } catch (e: Exception) { "" }
            }
        }
    }

    private var lastState = ""
    /** Logs only when the tracking state changes, so the diagnostics stay short. */
    private fun note(state: String) { if (state != lastState) { lastState = state; DiagLog.log("state: $state") } }

    private fun update() {
        if (!gpsEnabled()) {
            note("location services OFF")
            publish(UiState(status = "Location services are off", nearest = "GPS off", gpsOff = true)); return
        }
        val f = fix
            ?: run { note("waiting for GPS fix"); publish(UiState(status = "Waiting for GPS fix...", nearest = "Searching for GPS")); return }
        val ageS = (SystemClock.elapsedRealtime() - f.at) / 1000
        if (ageS > STALE_S) {
            note("GPS signal LOST")
            publish(UiState(status = "GPS signal lost (${ageS}s ago)", nearest = "GPS signal lost")); return
        }
        note("tracking")
        val loc = f.loc
        val lat = loc.latitude; val lon = loc.longitude
        val metric = Prefs.metric(this)
        val radiusM = Prefs.radiusM(this).toDouble()
        val maxAgeMin = Prefs.maxAgeMin(this)

        refreshCommunity(lat, lon, radiusM, maxAgeMin * 60)
        val live = community.map { it.copy(distanceM = Geo.distance(lat, lon, it.lat, it.lon)) }
        val mine = ReportStore.active(this, lat, lon, maxAgeMin * 60_000L)
        val cams = CameraDb.near(lat, lon, radiusM)
        val all = (live + mine + cams).filter { it.distanceM <= radiusM }.sortedBy { it.distanceM }

        val dirFilter = Prefs.dirFilter(this)
        val mode = Prefs.mode(this)
        fun bearingTo(a: Alert) = Geo.bearing(lat, lon, a.lat, a.lon)
        fun camOk(a: Alert) = !dirFilter || a.type != "CAMERA" || Geo.dirMatches(f.heading, a.dirs)
        val shown = all.filter { Geo.inMode(mode, f.heading, bearingTo(it), it.distanceM) && (mode == "radius" || camOk(it)) }
        val ahead = all.filter { Geo.isAhead(f.heading, bearingTo(it)) && camOk(it) }   // what gets spoken

        // re-arm voice for alerts that have left the 4 km zone
        announced.retainAll(all.filter { it.distanceM < 4000 }.map { it.id }.toSet())

        AlertState.map.value = MapSnap(lat, lon, f.heading, all.take(300))
        val moving = f.heading != null
        val acc = if (loc.hasAccuracy()) "±${loc.accuracy.toInt()} m" else "?"
        val near = shown.firstOrNull { it.type in speakTypes }
        publish(UiState(
            status = "GPS $acc · " +
                (if (moving) "${Fmt.speed(f.speedMps, metric)}, heading ${"%.0f".format(f.heading)}°" else "stationary") +
                " · ${cams.size} cameras" + (if (mine.isNotEmpty()) ", ${mine.size} yours" else "") +
                communityNote + riskText + (if (ttsFailed) " · voice unavailable (no speech engine)" else ""),
            nearest = near?.let {
                "${it.label} ${Fmt.dist(it.distanceM, metric)}" +
                    (if (moving && it.distanceM < 1500 && Geo.overLimit(f.speedMps, it.limitKmh)) " - SLOW DOWN (limit ${Fmt.limit(it.limitKmh, metric)})" else "")
            } ?: "No alerts ahead",
            nearestM = near?.distanceM,
            nearestCommunityId = near?.takeIf { it.isCommunity }?.id,
            items = shown.map {
                "${it.label} - ${Fmt.dist(it.distanceM, metric)}  ${it.street}" +
                    (if (it.limitKmh > 0) "  (limit ${Fmt.limit(it.limitKmh, metric)})" else "")
            },
        ))

        if (moving && !AlertState.muted && ttsReady) {
            val radius = minOf(Geo.warnRadiusM(f.speedMps, Prefs.lead(this)), radiusM)
            ahead.filter { it.type in speakTypes && voiceOn(it.type) && it.distanceM < radius && announced.add(it.id) }
                .forEach { speak(it, metric, f.speedMps) }
        }
    }

    private fun speak(a: Alert, metric: Boolean, speedMps: Double = 0.0) {
        val text = "${a.label} ahead, ${Fmt.dist(a.distanceM, metric, spoken = true)}" +
            (if (a.limitKmh > 0) ", limit ${Fmt.limit(a.limitKmh, metric).replace("km/h", "kilometers per hour")}" else "") +
            (if (Geo.overLimit(speedMps, a.limitKmh)) ", you are over the limit" else "")
        DiagLog.log("speak: $text")
        if (Prefs.beep(this)) {
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            scope.launch { delay(400); tts?.speak(text, TextToSpeech.QUEUE_ADD, null, a.id) }
        } else tts?.speak(text, TextToSpeech.QUEUE_ADD, null, a.id)
    }

    override fun onDestroy() {
        DiagLog.log("service stopped")
        AlertState.running = false
        AlertState.hasFix = false
        callback?.let { fused?.removeLocationUpdates(it) }
        scope.cancel()
        tts?.shutdown()
        tone?.release()
        publish(UiState(status = "Stopped"))
        super.onDestroy()
    }
}
