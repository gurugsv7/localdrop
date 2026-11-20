package app.localdrop

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** A visible user-initiated receive. Clipboard publication waits for window focus. */
class ReceiveActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var retry: View
    private var started = false
    private var pending: String? = null
    private var pairNeeded = false
    private var inFlight = false
    private var gallerySummary: String? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!inFlight) {
            started = false
            if (hasWindowFocus()) { started = true; receive() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = Desk.column(this).apply {
            setPadding(dp(24), dp(24), dp(24), dp(16))
            setBackgroundColor(Desk.canvas)
        }
        panel.addView(TextView(this).apply {
            text = "RECEIVE FROM LAPTOP"; textSize = 12f; setTextColor(Desk.orange)
        })
        status = TextView(this).apply {
            text = "Connecting to your laptop…"; textSize = 18f; setTextColor(Desk.paper)
            setPadding(0, dp(18), 0, dp(18))
        }
        panel.addView(status)
        retry = Desk.button(this, "Retry") {
            if (pairNeeded) {
                startActivity(Intent(this, MainActivity::class.java)); finish()
            } else receive()
        }.also { it.visibility = View.GONE; panel.addView(it) }
        panel.addView(Desk.button(this, "Done") { finish() })
        setContentView(panel)
        window.setLayout(dp(320), LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        if (pending != null) finishReceive()
        else if (!started) { started = true; receive() }
    }

    private fun receive() {
        val pairing = Queue.prefs(this).getString("pair", null)
        if (pairing == null) {
            pairNeeded = true
            status.text = "Pair your laptop in LocalDrop first."
            (retry as android.widget.Button).text = "Open LocalDrop"
            retry.visibility = View.VISIBLE
            return
        }
        if (!receiving.compareAndSet(false, true)) {
            status.text = "A receive is already in progress. Try again in a moment."
            retry.visibility = View.VISIBLE
            return
        }
        retry.visibility = View.GONE
        inFlight = true
        status.text = "Receiving from your laptop…"
        val folder = File(filesDir, "inbox").absolutePath
        Thread {
            val result = try { Native.pull(pairing, folder) }
                catch (_: Exception) { "error:unavailable" }
            val exported = try { Gallery.summary(applicationContext, result) }
                finally { receiving.set(false) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    pending = result
                    gallerySummary = exported
                    if (hasWindowFocus()) finishReceive()
                }
            }
        }.start()
    }

    private fun finishReceive() {
        val result = pending ?: return
        pending = null
        inFlight = false
        val summary = runCatching {
            when {
                result.startsWith("error:") -> "Laptop unavailable. Check that both devices are on the same Wi-Fi or hotspot."
                result.isEmpty() -> "Nothing to receive. Copy something on your laptop first."
                else -> {
                    val manifest = JSONObject(result)
                    val count = manifest.getJSONArray("items").length()
                    if (!manifest.isNull("text")) {
                        getSystemService(ClipboardManager::class.java).setPrimaryClip(
                            ClipData.newPlainText("LocalDrop", manifest.getString("text")))
                    }
                    val message = gallerySummary ?: if (count == 0) "Copied to your phone. Ready to paste."
                        else "$count file(s) received. Open them in LocalDrop Preferences."
                    Queue.prefs(this).edit().putString("status", message).apply()
                    Alerts.show(this, "Received from Windows", message)
                    message
                }
            }
        }.getOrElse { "Couldn’t complete the receive. Please try again." }
        status.text = summary
        retry.visibility = View.VISIBLE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    companion object { private val receiving = AtomicBoolean(false) }
}
