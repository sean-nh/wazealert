package app.wazealert.driver

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var nearestView: TextView
    private lateinit var listView: ListView
    private lateinit var fixButton: Button
    private var needsAppSettings = false
    private var asked = false
    private var communityId: String? = null
    private var nudged = false

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkAndStart() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // keep display awake while visible
        statusView = findViewById(R.id.status)
        nearestView = findViewById(R.id.nearest)
        listView = findViewById(R.id.list)
        fixButton = findViewById(R.id.fix)

        findViewById<Button>(R.id.toggle).setOnClickListener {
            AlertState.muted = !AlertState.muted
            (it as Button).text = if (AlertState.muted) "Unmute voice" else "Mute voice"
        }
        findViewById<Button>(R.id.stop).setOnClickListener { sendToService("STOP") }
        findViewById<Button>(R.id.reportPolice).setOnClickListener { report("REPORT_POLICE", "Police") }
        findViewById<Button>(R.id.reportHazard).setOnClickListener { report("REPORT_HAZARD", "Hazard") }
        findViewById<Button>(R.id.voteUp).setOnClickListener { vote(1) }
        findViewById<Button>(R.id.voteDown).setOnClickListener { vote(-1) }
        findViewById<Button>(R.id.selfcheck).setOnClickListener {
            startActivity(Intent(this, SelfCheckActivity::class.java))
        }
        findViewById<Button>(R.id.settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        fixButton.setOnClickListener {
            if (needsAppSettings)
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            else startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlertState.state.collect { s ->
                    if (hasFine()) {
                        statusView.text = s.status
                        nearestView.text = s.nearest
                        banner(s.nearestM)
                        communityId = s.nearestCommunityId
                        findViewById<View>(R.id.voteRow).visibility = if (communityId != null) View.VISIBLE else View.GONE
                        listView.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, s.items)
                        needsAppSettings = false
                        fixButton.visibility = if (s.gpsOff) View.VISIBLE else View.GONE
                        fixButton.text = "Open location settings"
                    }
                }
            }
        }
    }

    /** Red under 400 m, amber under 1 km, otherwise plain; works in light and dark themes. */
    private fun banner(m: Double?) {
        when {
            m != null && m < 400 -> { nearestView.setBackgroundColor(Color.rgb(198, 40, 40)); nearestView.setTextColor(Color.WHITE) }
            m != null && m < 1000 -> { nearestView.setBackgroundColor(Color.rgb(255, 179, 0)); nearestView.setTextColor(Color.BLACK) }
            else -> {
                nearestView.setBackgroundColor(Color.TRANSPARENT)
                nearestView.setTextColor(statusView.textColors)
            }
        }
    }

    private fun sendToService(action: String) =
        startService(Intent(this, AlertService::class.java).setAction(action))

    private fun report(action: String, what: String) {
        if (!AlertState.running || !AlertState.hasFix) {
            Toast.makeText(this, "Waiting for a GPS fix", Toast.LENGTH_SHORT).show(); return
        }
        sendToService(action)
        val msg = if (Prefs.serverUrl(this).isNotEmpty()) "$what reported to the community"
                  else "$what reported here (saved on this phone only; add a community server in Settings to share)"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun vote(v: Int) {
        val id = communityId ?: return
        startService(Intent(this, AlertService::class.java).setAction("VOTE").putExtra("id", id).putExtra("vote", v))
        Toast.makeText(this, if (v == 1) "Thanks, confirmed" else "Thanks, marked as gone", Toast.LENGTH_SHORT).show()
    }

    // Re-check every time we come back (e.g. after the user fixed a permission in system settings).
    override fun onResume() {
        super.onResume()
        checkAndStart()
    }

    private fun hasFine() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun checkAndStart() {
        if (hasFine()) {
            // Notification permission only affects the "running" notification, so it is requested but not required.
            if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) && !asked) {
                asked = true
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)); return
            }
            ContextCompat.startForegroundService(this, Intent(this, AlertService::class.java))
            if (!Prefs.bool(this, "selfCheckSeen", false) && !nudged) {
                nudged = true
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Check everything works?")
                    .setMessage("The pre-drive self-check tests location, volume, voice and battery settings, and plays a test alert, so there are no surprises on the road.")
                    .setPositiveButton("Run self-check") { _, _ -> startActivity(Intent(this, SelfCheckActivity::class.java)) }
                    .setNegativeButton("Later", null)
                    .show()
            }
            return
        }
        if (!asked) {
            asked = true
            statusView.text = "Location access is needed to find cameras and alerts near you."
            val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            permLauncher.launch(perms.toTypedArray())
            return
        }
        // Denied, or only "approximate" location was granted: we need precise GPS.
        needsAppSettings = true
        nearestView.text = "Location permission needed"
        banner(null)
        statusView.text = if (granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            "Only approximate location is allowed. Turn on \"Use precise location\" so alerts are accurate."
        else "Location permission was denied. WazeAlert can't work without it."
        listView.adapter = null
        fixButton.text = "Open app settings"
        fixButton.visibility = View.VISIBLE
    }
}
