package com.example.bandmoot.band

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.bandmoot.MainActivity
import com.example.bandmoot.nav.TurnType

/**
 * Foreground service owning the BLE link to the single paired 8 Active.
 *
 * Scan (name-regex filter) -> connect -> encrypted auth (key from prefs) ->
 * Notification3 write -> dedup.
 *
 * Transport (XiaomiLink/Crypto/Frame + vendored xiaomi.proto) is ported from
 * Gadgetbridge (AGPL-3.0-or-later) -> this app must stay AGPL-compatible
 * if distributed. See XiaomiProtocol.kt.
 */
class BandConnectorService : Service() {

    private var lastKey: String? = null
    private var lastSentAt: Long = 0
    private var link: XiaomiLink? = null
    private var pendingNav: Triple<String, String, Long>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        ensureLink()
    }

    /** (Re)connects when MAC + a valid auth key are stored. */
    private fun ensureLink() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val mac = prefs.getString(PREF_MAC, null)
        val key = prefs.getString(PREF_AUTH_KEY, null)
        if (mac.isNullOrBlank() || !XiaomiProtocol.isValidAuthKey(key ?: "")) {
            updateForeground("BandMoot", "Missing band MAC or auth key — open the app.")
            return
        }
        if (link?.state() == XiaomiLink.State.READY ||
            link?.state() == XiaomiLink.State.CONNECTING ||
            link?.state() == XiaomiLink.State.AUTH
        ) {
            return
        }
        updateForeground("BandMoot", "Connecting to $mac …")
        XiaomiLink(this, mac, key!!.trim()) { msg ->
            updateForeground("BandMoot", msg.take(120))
        }.also {
            link = it
            it.setOnReadyListener {
                pendingNav?.let { (title, detail, _) ->
                    it.sendNav(title, detail)
                    pendingNav = null
                }
            }
            it.connect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureLink()
        when (intent?.action) {
            ACTION_SEND_NAV -> {
                val turn = runCatching {
                    TurnType.valueOf(intent.getStringExtra(EXTRA_TURN) ?: "BLANK")
                }.getOrDefault(TurnType.BLANK)
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
                val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
                val distanceM = intent.getLongExtra(EXTRA_DISTANCE_M, -1)
                onNavInstruction(turn, title, detail, distanceM)
            }
            ACTION_CLEAR_NAV -> onNavCleared()
        }
        return START_STICKY
    }

    private fun onNavInstruction(turn: TurnType, title: String, detail: String, distanceM: Long) {
        // Dedup: Komoot reposts the ongoing notification ~1/sec with a ticking
        // countdown ("30 m" -> "25 m"), so key on turn + distance-stripped text +
        // 50 m distance bucket: one buzz per approach segment instead of per second.
        // (Mirrors NotificationService.L/Q cache + distance filter.)
        val now = System.currentTimeMillis()
        val key = "$turn|${title.withoutDistances()}|${detail.withoutDistances()}|${distanceM / 50}"
        if (key == lastKey && now - lastSentAt < DEDUP_WINDOW_MS) return
        lastKey = key
        lastSentAt = now

        // Arrow glyph per turn (same idea as dy4 symbols).
        val arrow = when (turn) {
            TurnType.LEFT, TurnType.SLIGHT_LEFT -> "← "
            TurnType.RIGHT, TurnType.SLIGHT_RIGHT -> "→ "
            TurnType.SHARP_LEFT -> "↰ "
            TurnType.SHARP_RIGHT -> "↱ "
            TurnType.UTURN -> "↩ "
            TurnType.STRAIGHT, TurnType.DEPART -> "↑ "
            TurnType.KEEP_LEFT -> "⇐ "
            TurnType.KEEP_RIGHT -> "⇒ "
            TurnType.ROUNDABOUT -> "◌ "
            TurnType.ARRIVE -> "⚑ "
            else -> ""
        }
        val bandTitle = (arrow + title).take(120)
        val bandBody = detail.take(512)

        val l = link
        if (l?.state() == XiaomiLink.State.READY) {
            l.sendNav(bandTitle, bandBody)
        } else {
            // Band not linked yet (or reconnecting): remember the latest step and
            // flush it in the onReady callback so the first turn isn't lost.
            pendingNav = Triple(bandTitle, bandBody, distanceM)
        }
        updateForeground("🧭 $title", detail.ifBlank { turn.name })
    }

    private fun onNavCleared() {
        lastKey = null
        pendingNav = null
        link?.clearNav()
        updateForeground("BandMoot", "Waiting for Komoot navigation…")
    }

    override fun onDestroy() {
        link?.disconnect()
        link = null
        super.onDestroy()
    }

    // --- scan helpers (used by MainActivity) ---

    companion object {
        private const val TAG = "BandConnectorService"
        const val ACTION_SEND_NAV = "com.example.bandmoot.SEND_NAV"
        const val ACTION_CLEAR_NAV = "com.example.bandmoot.CLEAR_NAV"
        const val EXTRA_TURN = "turn"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_DISTANCE_M = "distanceM"

        const val DEDUP_WINDOW_MS = 3000L
        const val PREFS = "bandmoot"
        const val PREF_MAC = "mac"
        const val PREF_AUTH_KEY = "authKey"

        fun scanFilter(): ScanFilter = ScanFilter.Builder()
            .setServiceUuid(
                android.os.ParcelUuid(XiaomiProtocol.SERVICE_FE95),
            )
            .build()

        fun scanSettings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        fun isBand8ActiveName(name: String?): Boolean =
            name != null && XiaomiProtocol.DEVICE_NAME_REGEX.matches(name)

        fun bluetoothManager(ctx: Context) =
            ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        const val CHANNEL_ID = "band_link"
        const val NOTIF_ID = 41
    }

    /** Strips distance tokens ("30 m • ", "0.1 km") so countdown reposts dedup. */
    private fun String.withoutDistances(): String =
        replace(
            Regex("[0-9]+[.,]?[0-9]*\\s*(m|km|mi|ft|yd)\\b\\s*[•·\\-–|]?\\s*", RegexOption.IGNORE_CASE),
            "",
        ).trim()

    private fun startAsForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Band link",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BandMoot")
            .setContentText("Waiting for Komoot navigation…")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        // Android 14+ validates FGS type against granted permissions at startForeground().
        // connectedDevice requires FOREGROUND_SERVICE_CONNECTED_DEVICE + BLUETOOTH_CONNECT
        // (or other BT/NFC perms). KomootListenerService can trigger the service before
        // MainActivity ever requested BLUETOOTH_CONNECT, so we must not crash — fall
        // back to a type that doesn't need BT.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasFgsPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE,
                    ) == PackageManager.PERMISSION_GRANTED
                } else true
                val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT,
                    ) == PackageManager.PERMISSION_GRANTED
                } else true
                val useConnectedDevice = hasFgsPermission && hasBtPermission
                if (useConnectedDevice) {
                    startForeground(
                        NOTIF_ID,
                        notif,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                    )
                } else {
                    Log.w(TAG, "FGS connectedDevice not granted (fgs=$hasFgsPermission bt=$hasBtPermission), using fallback")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(
                            NOTIF_ID,
                            notif,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                        )
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                }
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "startForeground with connectedDevice failed, fallback: ${e.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIF_ID, notif)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground also failed", e2)
                mgr.notify(NOTIF_ID, notif)
            }
        }
    }

    private fun updateForeground(title: String, text: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        mgr.notify(NOTIF_ID, notif)
    }
}
