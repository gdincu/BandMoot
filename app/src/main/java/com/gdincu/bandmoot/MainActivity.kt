package com.gdincu.bandmoot

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
import com.gdincu.bandmoot.band.BandConnectorService
import com.gdincu.bandmoot.band.XiaomiProtocol

/**
 * Single-screen setup: notification access -> BLE scan -> auth key -> start link service.
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
                startForegroundService(svc)
                log("Link service started. Start a Komoot ride.")
            }
        }
        layout.addView(status)
        layout.addView(btnListener)
        layout.addView(btnScan)
        layout.addView(authKeyInput)
        layout.addView(btnSaveKey)
        layout.addView(btnStart)
        setContentView(ScrollView(this).apply { addView(layout) })

        // Prefill once so the scan step can be skipped when the band is already known.
        if (prefs().getString(BandConnectorService.PREF_MAC, null).isNullOrBlank()) {
            prefs().edit().putString(BandConnectorService.PREF_MAC, DEFAULT_MAC).apply()
        }

        // Needed on API 33+ for the foreground-service status entry.
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
        const val DEFAULT_MAC = "04:DA:28:1C:36:32"

        /** 32-hex BLE auth key candidate (HMAC check decides: READY = correct). */
        const val DEFAULT_AUTH_KEY_CANDIDATE = "21c22a78e8526957f65b412ff8a64e27"
    }
}
