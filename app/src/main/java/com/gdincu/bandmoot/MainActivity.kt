package com.gdincu.bandmoot

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.gdincu.bandmoot.band.BandConnectorService
import com.gdincu.bandmoot.band.XiaomiProtocol

/** MAC + AuthKey + Connect/Disconnect with collapsible logs. */
class MainActivity : Activity() {

    private lateinit var macInput: EditText
    private lateinit var authKeyInput: EditText
    private lateinit var logView: TextView
    private lateinit var logContainer: LinearLayout
    private var logsExpanded = false
    private val logListener: () -> Unit = { runOnUiThread { renderLogs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BandConnectorService.ensureDefaultsAndMigration(prefs())
        val active = BandConnectorService.getActiveBand(prefs())

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val macLabel = TextView(this).apply {
            text = "MAC:"
            textSize = 14f
            setPadding(0, 24, 0, 4)
        }
        macInput = EditText(this).apply {
            hint = "04:DA:28:1C:36:32"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 16f
            setPadding(24, 28, 24, 28)
            setText(active?.mac ?: BandConnectorService.DEFAULT_MAC)
        }

        val keyLabel = TextView(this).apply {
            text = "AuthKey:"
            textSize = 14f
            setPadding(0, 16, 0, 4)
        }
        authKeyInput = EditText(this).apply {
            hint = "32 hex chars (or 0x+32)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            textSize = 16f
            setPadding(24, 28, 24, 28)
            setText(active?.authKey ?: BandConnectorService.DEFAULT_AUTH_KEY_CANDIDATE)
        }

        val btnConnect = Button(this).apply {
            text = "Connect"
            textSize = 18f
            minimumHeight = 140
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setOnClickListener { connect() }
        }

        val btnDisconnect = Button(this).apply {
            text = "Disconnect"
            textSize = 18f
            minimumHeight = 140
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#F44336"))
            setOnClickListener { disconnect() }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        }
        btnRow.addView(btnConnect, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 16 })
        btnRow.addView(btnDisconnect, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 16 })

        val btnToggleLogs = Button(this).apply {
            text = "Show logs ▾"
            textSize = 13f
            setPadding(0, 16, 0, 16)
        }

        logView = TextView(this).apply {
            text = ""
            textSize = 12f
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }
        val logScroll = ScrollView(this).apply {
            addView(logView)
        }
        logContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 8, 0, 0)
            addView(logScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 400))
        }

        btnToggleLogs.setOnClickListener {
            logsExpanded = !logsExpanded
            logContainer.visibility = if (logsExpanded) View.VISIBLE else View.GONE
            btnToggleLogs.text = if (logsExpanded) "Hide logs ▴" else "Show logs ▾"
        }

        layout.addView(macLabel)
        layout.addView(macInput)
        layout.addView(keyLabel)
        layout.addView(authKeyInput)
        layout.addView(btnRow)
        layout.addView(btnToggleLogs)
        layout.addView(logContainer)

        setContentView(ScrollView(this).apply { addView(layout) })

        // 1) POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                8,
            )
        }

        // 2) Nearby devices (BLE scan/connect) — need both SCAN and CONNECT on S+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needsScan = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            val needsConnect = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            if (needsScan || needsConnect) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ),
                    7,
                )
            }
        }

        // Bluetooth enabled check
        if (!isBluetoothEnabled()) {
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
            }
        }

        // 3) Notification listener
        if (!isNotificationListenerEnabled()) {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun connect() {
        // Ensure Nearby-devices permission before BLE connect (otherwise XiaomiLink fails with BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needsScan = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
            val needsConnect = ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            if (needsScan || needsConnect) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT),
                    7,
                )
                toast("Nearby devices permission required — allow and tap Connect again")
                log("Nearby devices permission missing — requested")
                return
            }
        }

        val macRaw = macInput.text.toString().trim()
        val keyRaw = authKeyInput.text.toString().trim()

        if (!isValidMac(macRaw)) {
            macInput.error = "Use AA:BB:CC:DD:EE:FF"
            toast("Invalid MAC address")
            return
        }
        macInput.error = null

        if (!XiaomiProtocol.isValidAuthKey(keyRaw)) {
            authKeyInput.error = "32 hex chars (or 0x+32)"
            toast("Key must be 32 hex chars (or 0x+32)")
            return
        }
        authKeyInput.error = null

        val mac = BandConnectorService.normalizeMac(macRaw)
        val key = keyRaw.trim()

        BandConnectorService.upsertBand(prefs(), mac, null, key)
        BandConnectorService.setActiveMac(prefs(), mac)
        prefs().edit().putString(BandConnectorService.PREF_AUTH_KEY, key).apply()
        log("Auth key saved for $mac.")

        val svc = Intent(this, BandConnectorService::class.java)
        startForegroundService(svc)
        log("Link service started for $mac. Start a Komoot ride.")
    }

    private fun disconnect() {
        val svc = Intent(this, BandConnectorService::class.java)
            .setAction(BandConnectorService.ACTION_STOP)
        try {
            startForegroundService(svc)
        } catch (_: Exception) {
            startService(svc)
        }
        log("Disconnect requested.")
        toast("Disconnect requested")
    }

    private fun isValidMac(mac: String): Boolean =
        mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))

    private fun isNotificationListenerEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(packageName) == true

    private fun isBluetoothEnabled(): Boolean =
        try {
            BandConnectorService.bluetoothManager(this).adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }

    private fun prefs() = getSharedPreferences(BandConnectorService.PREFS, MODE_PRIVATE)

    private fun log(msg: String) {
        LogStore.add(msg)
        renderLogs()
    }

    private fun renderLogs() {
        if (::logView.isInitialized) {
            logView.text = LogStore.snapshot().joinToString("\n")
        }
    }

    override fun onResume() {
        super.onResume()
        renderLogs()
        LogStore.addListener(logListener)
    }

    override fun onPause() {
        LogStore.removeListener(logListener)
        super.onPause()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
