package dev.datacap

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var limitInput: EditText
    private lateinit var stateLabel: TextView
    private lateinit var toggleButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val padding = (24 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            gravity = Gravity.CENTER_VERTICAL
        }

        layout.addView(TextView(this).apply {
            text = "Download limit"
            textSize = 26f
        })
        layout.addView(TextView(this).apply {
            text = "Set an aggregate limit for other apps in bits per second (bps). Uploads are not limited."
            textSize = 16f
            setPadding(0, padding / 3, 0, padding / 2)
        })

        limitInput = EditText(this).apply {
            hint = "For example: 1000000"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText(getPreferences(MODE_PRIVATE).getLong(PREF_LIMIT, DEFAULT_LIMIT).toString())
        }
        layout.addView(limitInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        stateLabel = TextView(this).apply {
            textSize = 16f
            setPadding(0, padding / 2, 0, padding / 2)
        }
        layout.addView(stateLabel)

        toggleButton = Button(this)
        layout.addView(toggleButton)
        toggleButton.setOnClickListener { onToggle() }

        setContentView(layout)
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    @Deprecated("Deprecated in Android framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) {
            val limit = saveLimit() ?: return
            startLimiter(limit)
        }
    }

    private fun onToggle() {
        if (LimiterVpnService.isRunning(this)) {
            startService(Intent(this, LimiterVpnService::class.java).setAction(LimiterVpnService.ACTION_STOP))
            stateLabel.text = "Stopping VPN..."
            toggleButton.text = "Stopping..."
            toggleButton.isEnabled = false
            toggleButton.postDelayed({
                refreshState()
                toggleButton.isEnabled = true
            }, 1_000L)
            return
        }

        val limit = saveLimit() ?: return
        val permissionIntent = VpnService.prepare(this)
        if (permissionIntent != null) {
            startActivityForResult(permissionIntent, VPN_REQUEST)
        } else {
            startLimiter(limit)
        }
    }

    private fun saveLimit(): Long? {
        val limit = limitInput.text.toString().trim().toLongOrNull()
        if (limit == null || limit <= 0L) {
            limitInput.error = "Enter a positive whole number of bits per second"
            return null
        }
        getPreferences(MODE_PRIVATE).edit().putLong(PREF_LIMIT, limit).apply()
        return limit
    }

    private fun startLimiter(limit: Long) {
        val action = if (LimiterVpnService.isRunning(this)) {
            LimiterVpnService.ACTION_UPDATE_LIMIT
        } else {
            LimiterVpnService.ACTION_START
        }
        val intent = Intent(this, LimiterVpnService::class.java)
            .setAction(action)
            .putExtra(LimiterVpnService.EXTRA_LIMIT_BPS, limit)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        stateLabel.text = "Starting VPN..."
        toggleButton.text = "Starting..."
        toggleButton.isEnabled = false
        toggleButton.postDelayed({
            refreshState()
            toggleButton.isEnabled = true
        }, 1_000L)
    }

    private fun refreshState() {
        val running = LimiterVpnService.isRunning(this)
        stateLabel.text = if (running) "VPN active" else "VPN inactive"
        toggleButton.text = if (running) "Stop limiting" else "Start limiting"
    }

    companion object {
        private const val VPN_REQUEST = 1001
        private const val PREF_LIMIT = "limit_bps"
        private const val DEFAULT_LIMIT = 1_000_000L
    }
}
