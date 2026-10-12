package com.gdincu.bandmoot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gdincu.bandmoot.band.BandConnectorService
import com.gdincu.bandmoot.band.XiaomiProtocol

/** Minimal setup: MAC + AuthKey + Connect (persists for next launch). */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var macInput: EditText
    private lateinit var authKeyInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BandConnectorService.ensureDefaultsAndMigration(prefs())
        val active = BandConnectorService.getActiveBand(prefs())

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        status = TextView(this).apply { text = "BandMoot" }

        val macLabel = TextView(this).apply {
            text = "MAC:"
            setPadding(0, 24, 0, 4)
        }
        macInput = EditText(this).apply {
            hint = "04:DA:28:1C:36:32"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(active?.mac ?: BandConnectorService.DEFAULT_MAC)
        }

        val keyLabel = TextView(this).apply {
            text = "AuthKey:"
            setPadding(0, 16, 0, 4)
        }
        authKeyInput = EditText(this).apply {
            hint = "32 hex chars (or 0x+32)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(active?.authKey ?: BandConnectorService.DEFAULT_AUTH_KEY_CANDIDATE)
        }

        val btnConnect = Button(this).apply {
            text = "Connect"
            setOnClickListener { connect() }
        }

        layout.addView(status)
        layout.addView(macLabel)
        layout.addView(macInput)
        layout.addView(keyLabel)
        layout.addView(authKeyInput)
        layout.addView(btnConnect)
        setContentView(ScrollView(this).apply { addView(layout) })

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun connect() {
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
        val key = keyRaw.lowercase().trim()

        BandConnectorService.upsertBand(prefs(), mac, null, key)
        BandConnectorService.setActiveMac(prefs(), mac)
        prefs().edit().putString(BandConnectorService.PREF_AUTH_KEY, key).apply()

        log("Saved $mac — connecting…")
        val svc = Intent(this, BandConnectorService::class.java)
        startForegroundService(svc)
        log("Connect sent for $mac. Check notification for status.")
    }

    private fun isValidMac(mac: String): Boolean =
        mac.matches(Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$"))

    private fun prefs() = getSharedPreferences(BandConnectorService.PREFS, MODE_PRIVATE)

    private fun refreshStatus() {
        val active = BandConnectorService.getActiveBand(prefs())
        val label = active?.let { "${it.mac} key:${if (it.authKey != null) "set" else "missing"}" } ?: "none"
        status.text = "BandMoot — Active: $label"
    }

    private fun log(msg: String) {
        status.text = "${status.text}\n• $msg"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
