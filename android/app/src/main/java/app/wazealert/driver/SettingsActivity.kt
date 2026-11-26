package app.wazealert.driver

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings & about"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(col) })

        fun header(t: String) = col.addView(TextView(this).apply {
            text = t; textSize = 16f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(18), 0, dp(4))
        })
        fun check(label: String, key: String, def: Boolean) = col.addView(CheckBox(this).apply {
            text = label; isChecked = Prefs.bool(this@SettingsActivity, key, def)
            setOnCheckedChangeListener { _, v -> Prefs.setBool(this@SettingsActivity, key, v) }
        })
        fun choice(opts: List<String>, selected: Int, onPick: (Int) -> Unit) = col.addView(RadioGroup(this).apply {
            opts.forEachIndexed { i, o -> addView(RadioButton(context).apply { text = o; id = 1000 + i; isChecked = i == selected }) }
            setOnCheckedChangeListener { _, id -> onPick(id - 1000) }
        })

        header("Units")
        choice(listOf("Miles / mph", "Kilometers / km/h"), if (Prefs.metric(this)) 1 else 0) {
            Prefs.setBool(this, "metric", it == 1)
        }

        header("Show alerts")
        val modes = listOf("front", "path", "radius")
        choice(listOf("In front of me", "On my path (approximate)", "Within radius (all around)"),
            modes.indexOf(Prefs.mode(this)).coerceAtLeast(0)) { Prefs.setStr(this, "mode", modes[it]) }

        header("Radius")
        val radii = listOf(2000, 5000, 10000)
        choice(listOf("2 km (1.2 mi)", "5 km (3.1 mi)", "10 km (6.2 mi)"),
            radii.indexOf(Prefs.radiusM(this)).coerceAtLeast(1)) { Prefs.setInt(this, "radiusM", radii[it]) }

        header("Hide reports not confirmed within")
        val ages = listOf(30, 60, 120, 240)
        choice(listOf("30 minutes", "1 hour", "2 hours", "4 hours"),
            ages.indexOf(Prefs.maxAgeMin(this)).coerceAtLeast(2)) { Prefs.setInt(this, "maxAgeMin", ages[it]) }

        header("Voice alerts for")
        check("Speed and red-light cameras", "voiceCamera", true)
        check("Police", "voicePolice", true)
        check("Hazards and accidents", "voiceHazard", true)
        check("Beep before the voice", "beep", true)

        header("Cameras")
        check("Ignore cameras that face the other way", "dirFilter", true)

        header("How early to warn")
        val leads = listOf(30, 45, 70)
        choice(listOf("Late (about 30 s ahead)", "Normal (about 45 s)", "Early (about 70 s)"),
            leads.indexOf(Prefs.lead(this)).coerceAtLeast(1)) { Prefs.setInt(this, "lead", leads[it]) }

        header("Community server")
        col.addView(TextView(this).apply {
            textSize = 13f
            text = "Optional. With a server address, \"Report police / hazard\" shares your report anonymously with other " +
                "drivers, you see their reports, and you get a risk score for where you are. Your report includes its " +
                "location and a random anonymous ID. Leave empty to keep reports on this phone only."
        })
        col.addView(EditText(this).apply {
            hint = "https://your-server.example.com"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(Prefs.str(this@SettingsActivity, "serverUrl", ""))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { Prefs.setStr(this@SettingsActivity, "serverUrl", s.toString()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        header("About")
        col.addView(TextView(this).apply {
            textSize = 14f
            text = "Camera data: the wzsabre.rocks camera database (ExCam format, \"World cameras by @schuster\"), " +
                "updated weekly. Speed limits in that data are km/h and are converted for display.\n\n" +
                "Not affiliated with Waze, wzSabre or Valentine Research. Community reports come only from users of " +
                "this app through the server above; reports are unverified and expire after 2-4 hours.\n\n" +
                "This app is an aid, not a guarantee: cameras move, data is incomplete, and GPS can be wrong. " +
                "Radar-detector and camera-alert rules differ by country and state; you are responsible for following " +
                "local law and the posted speed limit. Don't operate the phone while driving."
        })
    }
}
