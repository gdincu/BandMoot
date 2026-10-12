package com.gdincu.bandmoot

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.gdincu.bandmoot.band.BandConnectorService
import com.gdincu.bandmoot.band.XiaomiProtocol

/** Single-screen setup: notification access -> BLE scan -> auth key -> start/stop link. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var authKeyInput: EditText
    private lateinit var authKeyHint: TextView
    private lateinit var pairedContainer: LinearLayout
    private lateinit var btnScan: Button
    private var scanning = false
    private val handler = Handler(Looper.getMainLooper())
    private var scanTimeoutRunnable: Runnable? = null
    private val discovered = mutableMapOf<String, Discovered>()

    private data class Discovered(val name: String, val mac: String, var rssi: Int)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!BandConnectorService.isBand8ActiveName(name)) return
            val mac = result.device.address ?: return
            val norm = BandConnectorService.normalizeMac(mac)
            val existing = discovered[norm]
            if (existing == null) {
                discovered[norm] = Discovered(name, norm, result.rssi)
                log("Found $name ($norm) RSSI ${result.rssi}dBm")
            } else {
                existing.rssi = result.rssi
            }
            if (scanning) btnScan.text = "Scanning… ${discovered.size} found (tap to stop)"
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: $errorCode")
            stopScan(showResults = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { text = "BandMoot setup" }

        val btnListener = Button(this).apply {
            text = "1. Enable notification access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val pairedTitle = TextView(this).apply {
            text = "Paired bands:"
            setPadding(0, 24, 0, 8)
            textSize = 15f
        }
        pairedContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        btnScan = Button(this).apply {
            text = "2. Scan for 8 Active (12s)"
            setOnClickListener { toggleScan() }
        }

        authKeyHint = TextView(this).apply {
            textSize = 12f
            setPadding(0, 16, 0, 4)
        }
        authKeyInput = EditText(this).apply {
            hint = "32-char BLE auth key for active band"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val btnSaveKey = Button(this).apply {
            text = "3. Save auth key for active band"
            setOnClickListener { saveKeyForActiveBand() }
        }

        val btnStart = Button(this).apply {
            text = "4. Start link service (active band)"
            setOnClickListener {
                val svc = Intent(this@MainActivity, BandConnectorService::class.java)
                startForegroundService(svc)
                val active = BandConnectorService.getActiveBand(prefs())?.mac ?: "none"
                log("Link service started for $active. Start a Komoot ride.")
            }
        }
        val btnStop = Button(this).apply {
            text = "5. Stop link service"
            setOnClickListener {
                val svc = Intent(this@MainActivity, BandConnectorService::class.java)
                    .setAction(BandConnectorService.ACTION_STOP)
                try {
                    startForegroundService(svc)
                } catch (_: Exception) {
                    startService(svc)
                }
                log("Link service stop requested.")
            }
        }

        layout.addView(status)
        layout.addView(btnListener)
        layout.addView(pairedTitle)
        layout.addView(pairedContainer)
        layout.addView(btnScan)
        layout.addView(authKeyHint)
        layout.addView(authKeyInput)
        layout.addView(btnSaveKey)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(ScrollView(this).apply { addView(layout) })

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

        refreshBandsUi()
    }

    override fun onResume() {
        super.onResume()
        refreshBandsUi()
        val stopped = prefs().getBoolean(BandConnectorService.PREF_STOPPED, false)
        val listenerOn = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners",
        )?.contains(packageName) == true
        val active = BandConnectorService.getActiveBand(prefs())
        val activeLabel = active?.let { "${it.name ?: "Band"} ${it.mac} key:${if (it.authKey != null) "set" else "missing"}" } ?: "none"
        log("Listener: ${if (listenerOn) "ON" else "OFF"} | Active: $activeLabel | Link: ${if (stopped) "STOPPED" else "active"} | Paired: ${BandConnectorService.getBands(prefs()).size}")
    }

    override fun onDestroy() {
        stopScan(showResults = false)
        super.onDestroy()
    }

    private fun refreshBandsUi() {
        BandConnectorService.ensureDefaultsAndMigration(prefs())
        val bands = BandConnectorService.getBands(prefs())
        val activeMac = BandConnectorService.getActiveMac(prefs())

        pairedContainer.removeAllViews()
        if (bands.isEmpty()) {
            pairedContainer.addView(TextView(this).apply {
                text = "No paired bands — Scan or use default ${BandConnectorService.DEFAULT_MAC}"
                textSize = 12f
                setPadding(8, 8, 8, 8)
            })
        } else {
            for (band in bands) {
                val isActive = BandConnectorService.normalizeMac(band.mac) == activeMac
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 4, 0, 4)
                }
                val select = Button(this).apply {
                    text = if (isActive) "●" else "○"
                    textSize = 14f
                    setOnClickListener {
                        BandConnectorService.setActiveMac(prefs(), band.mac)
                        toast("Active: ${band.mac}")
                        refreshBandsUi()
                    }
                }
                val label = TextView(this).apply {
                    text = "${band.name ?: "Band"}  ${band.mac}"
                    textSize = 12f
                    setPadding(12, 0, 12, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    isSingleLine = false
                }
                val remove = Button(this).apply {
                    text = "✕"
                    textSize = 12f
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Remove band?")
                            .setMessage("${band.mac}\nThis removes its saved key.")
                            .setPositiveButton("Remove") { _, _ ->
                                BandConnectorService.removeBand(prefs(), band.mac)
                                toast("Removed ${band.mac}")
                                refreshBandsUi()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                if (isActive) {
                    label.setPadding(12, 8, 12, 8)
                    row.setBackgroundColor(0x1A000000)
                }
                row.addView(select, LinearLayout.LayoutParams(96, LinearLayout.LayoutParams.WRAP_CONTENT))
                row.addView(label)
                row.addView(remove, LinearLayout.LayoutParams(96, LinearLayout.LayoutParams.WRAP_CONTENT))
                pairedContainer.addView(row)
            }
        }

        val active = BandConnectorService.getActiveBand(prefs())
        authKeyHint.text = if (active != null) {
            "Auth key for active: ${active.name ?: "Band"} ${active.mac}  (32 hex, 0x… allowed)"
        } else {
            "Auth key — no active band"
        }
        val lastActive = authKeyInput.tag as? String
        val inputEmpty = authKeyInput.text.toString().trim().isEmpty()
        if (active != null && (inputEmpty || lastActive != active.mac)) {
            authKeyInput.setText(active.authKey ?: "")
        }
        authKeyInput.tag = active?.mac
        authKeyInput.isEnabled = active != null
    }

    private fun saveKeyForActiveBand() {
        val active = BandConnectorService.getActiveBand(prefs())
        if (active == null) {
            toast("No active band — scan or add one first")
            return
        }
        val key = authKeyInput.text.toString().trim()
        if (!XiaomiProtocol.isValidAuthKey(key)) {
            authKeyInput.error = "32 hex chars (or 0x+32)"
            toast("Key must be 32 hex chars (or 0x+32)")
            return
        }
        authKeyInput.error = null
        val normalized = key.lowercase()
        BandConnectorService.updateBandKey(prefs(), active.mac, normalized)
        toast("Key saved for ${active.mac}")
        log("Auth key saved for ${active.name ?: "Band"} ${active.mac} (${normalized.take(4)}…${normalized.takeLast(4)})")
        refreshBandsUi()
    }

    private fun toggleScan() {
        if (scanning) {
            stopScan(showResults = true)
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
        val btMgr = BandConnectorService.bluetoothManager(this)
        val adapter: BluetoothAdapter? = btMgr.adapter
        if (adapter == null) {
            toast("Bluetooth unavailable")
            return
        }
        if (!adapter.isEnabled) {
            toast("Enable Bluetooth first")
            try {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (_: Exception) {
            }
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            toast("Bluetooth scanner unavailable")
            return
        }
        discovered.clear()
        scanning = true
        btnScan.text = "Scanning… 0 found (tap to stop)"
        log("Scanning for Xiaomi Band 8 Active… (12s)")
        try {
            scanner.startScan(
                null,
                BandConnectorService.scanSettings(),
                scanCallback,
            )
        } catch (e: SecurityException) {
            scanning = false
            btnScan.text = "2. Scan for 8 Active (12s)"
            toast("Bluetooth permission revoked: ${e.message}")
            return
        }
        scanTimeoutRunnable = Runnable { stopScan(showResults = true) }
        handler.postDelayed(scanTimeoutRunnable!!, 12_000L)
    }

    private fun stopScan(showResults: Boolean = true) {
        if (!scanning && discovered.isEmpty()) return
        scanning = false
        scanTimeoutRunnable?.let { handler.removeCallbacks(it) }
        scanTimeoutRunnable = null
        try {
            BandConnectorService.bluetoothManager(this).adapter?.bluetoothLeScanner
                ?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        btnScan.text = "2. Scan for 8 Active (12s)"
        if (!showResults) return

        if (discovered.isEmpty()) {
            log("Scan done — no Band 8 Active found. Move closer / enable BT on band.")
            toast("No Band 8 Active found")
            return
        }
        log("Scan done — ${discovered.size} band(s) found")
        if (discovered.size == 1) {
            val d = discovered.values.first()
            BandConnectorService.upsertBand(prefs(), d.mac, d.name, null)
            BandConnectorService.setActiveMac(prefs(), d.mac)
            log("Saved ${d.name} (${d.mac}) — set as active. Now save its auth key.")
            toast("Found ${d.name} — saved as active")
            refreshBandsUi()
            return
        }
        val sorted = discovered.values.sortedByDescending { it.rssi }
        val items = sorted.map { "${it.name}  ${it.mac}  (${it.rssi}dBm)" }.toTypedArray()
        val macs = sorted.map { it.mac }
        val names = sorted.map { it.name }
        AlertDialog.Builder(this)
            .setTitle("Pick a band to pair")
            .setItems(items) { _, which ->
                val mac = macs[which]
                val name = names[which]
                BandConnectorService.upsertBand(prefs(), mac, name, null)
                BandConnectorService.setActiveMac(prefs(), mac)
                log("Picked $name ($mac) — set as active. Now save its auth key.")
                toast("Paired $name")
                refreshBandsUi()
            }
            .setPositiveButton("Add all") { _, _ ->
                for (d in discovered.values) BandConnectorService.upsertBand(prefs(), d.mac, d.name, null)
                val first = macs.firstOrNull()
                if (first != null) BandConnectorService.setActiveMac(prefs(), first)
                log("Added ${discovered.size} band(s). Active: $first — save its auth key.")
                refreshBandsUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun prefs() = getSharedPreferences(BandConnectorService.PREFS, MODE_PRIVATE)

    private fun log(msg: String) {
        val lines = "${status.text}\n• $msg".lines()
        status.text = lines.takeLast(MAX_STATUS_LINES).joinToString("\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val MAX_STATUS_LINES = 30
    }
}
