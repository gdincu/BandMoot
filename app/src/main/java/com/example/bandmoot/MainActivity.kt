package com.example.bandmoot

import android.Manifest
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.bandmoot.band.BandConnectorService
import com.example.bandmoot.band.XiaomiProtocol
import com.example.bandmoot.nav.NotificationCapture

/**
 * Single-screen setup: notification access -> BLE scan (8 Active only) -> auth key ->
 * start link service. No fitness, watchface, or health features on purpose.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var authKeyInput: EditText
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            if (!BandConnectorService.isBand8ActiveName(name)) return
            stopScan()
            val mac = result.device.address
            prefs().edit().putString(BandConnectorService.PREF_MAC, mac).apply()
            log("Found $name ($mac) — saved. Enter auth key, then Start link.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { text = "BandMoot setup" }
        authKeyInput = EditText(this).apply {
            hint = "32-char BLE auth key"
            val saved = prefs().getString(BandConnectorService.PREF_AUTH_KEY, null)
            setText(if (saved.isNullOrBlank()) DEFAULT_AUTH_KEY_CANDIDATE else saved)
        }
        val btnListener = Button(this).apply {
            text = "1. Enable notification access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        val btnScan = Button(this).apply {
            text = "2. Scan for 8 Active"
            setOnClickListener { toggleScan() }
        }
        val btnSaveKey = Button(this).apply {
            text = "3. Save auth key"
            setOnClickListener {
                val key = authKeyInput.text.toString().trim()
                if (!XiaomiProtocol.isValidAuthKey(key)) {
                    toast("Key must be 32 hex chars (or 0x+32)")
                    return@setOnClickListener
                }
                prefs().edit().putString(BandConnectorService.PREF_AUTH_KEY, key).apply()
                log("Auth key saved.")
            }
        }
        val btnStart = Button(this).apply {
            text = "4. Start link service"
            setOnClickListener {
                val svc = Intent(this@MainActivity, BandConnectorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
                log("Link service started. Start a Komoot ride.")
            }
        }
        val captureView = TextView(this).apply {
            text = "No Komoot capture yet."
            setTextIsSelectable(true)
        }
        val btnCapture = Button(this).apply {
            text = "Show last Komoot capture"
            setOnClickListener {
                val file = NotificationCapture.lastCapture(this@MainActivity)
                if (file == null) {
                    toast("Nothing captured yet — start a Komoot ride first")
                    return@setOnClickListener
                }
                captureView.text = "${file.absolutePath}\n\n${file.readText().take(4000)}"
            }
        }
        layout.addView(status)
        layout.addView(btnListener)
        layout.addView(btnScan)
        layout.addView(authKeyInput)
        layout.addView(btnSaveKey)
        layout.addView(btnStart)
        layout.addView(btnCapture)
        layout.addView(captureView)
        setContentView(ScrollView(this).apply { addView(layout) })

        // MAC seen in your Mi Fitness log (04:DA:28:1C:36:32): prefill once so the
        // scan step can be skipped if the band is already known.
        if (prefs().getString(BandConnectorService.PREF_MAC, null).isNullOrBlank()) {
            prefs().edit().putString(BandConnectorService.PREF_MAC, DEFAULT_MAC).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        val mac = prefs().getString(BandConnectorService.PREF_MAC, null)
        val listenerOn = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        )?.contains(packageName) == true
        log("Listener: ${if (listenerOn) "ON" else "OFF"} | Band: ${mac ?: "none"}")
    }

    private fun toggleScan() {
        if (scanning) {
            stopScan()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
                7,
            )
            return
        }
        val scanner = BandConnectorService.bluetoothManager(this).adapter?.bluetoothLeScanner
        if (scanner == null) {
            toast("Bluetooth unavailable")
            return
        }
        scanning = true
        log("Scanning for Xiaomi Band 8 Active… (name + FE95 filter)")
        scanner.startScan(
            listOf(BandConnectorService.scanFilter()),
            BandConnectorService.scanSettings(),
            scanCallback,
        )
    }

    private fun stopScan() {
        scanning = false
        try {
            BandConnectorService.bluetoothManager(this).adapter?.bluetoothLeScanner
                ?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun prefs() = getSharedPreferences(BandConnectorService.PREFS, MODE_PRIVATE)

    private fun log(msg: String) {
        status.text = "${status.text}\n• $msg"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** MAC from your Mi Fitness device log (Xiaomi Smart Band 8 Active). */
        const val DEFAULT_MAC = "04:DA:28:1C:36:32"

        /**
         * Candidate BLE auth key for the 8 Active, from the Mi Fitness
         * `requestBindDeviceList` log: `token`/`encryptKey` =
         * 21c22a78e8526957f65b412ff8a64e27 (32 hex = 16 bytes, the size the
         * Xiaomi encrypted handshake needs). NOT confirmed — the log's real
         * `authKey` field is null for this band (compare: your Band 7 in the
         * same log carries authKey=56364ad0949dab6f79c91bfcd5a6d189).
         * The link's HMAC check is the definitive test: READY = correct key,
         * "Auth FAILED: band HMAC mismatch" = fall back to the freemyband key.
         */
        const val DEFAULT_AUTH_KEY_CANDIDATE = "21c22a78e8526957f65b412ff8a64e27"
    }
}
