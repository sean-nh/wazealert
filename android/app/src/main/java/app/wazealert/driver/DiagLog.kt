package app.wazealert.driver

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Small in-memory event log (last 300 lines) shown by "Share diagnostics". It never records coordinates,
 * only events (service started, GPS lost, speech started, errors), so it is safe to send to someone for debugging.
 */
object DiagLog {
    private const val TAG = "WazeAlert"
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(msg: String) {
        Log.i(TAG, msg)
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > 300) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}
