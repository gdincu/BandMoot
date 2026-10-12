package com.gdincu.bandmoot.band

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
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gdincu.bandmoot.MainActivity
import com.gdincu.bandmoot.nav.TurnType
import java.util.Locale

/** Foreground service owning the BLE link to the paired 8 Active. */
class BandConnectorService : Service() {

    private var lastKey: String? = null
    private var lastSentAt: Long = 0
    private var lastTurn: TurnType? = null
    private var lastDistanceM: Long = -1
    private var lastTitleStripped: String? = null
    private var lastDetailStripped: String? = null
    private var link: XiaomiLink? = null
    private var pendingNav: Pair<String, String>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate() {
        super.onCreate()
        if (isStoppedByUser()) {
            startAsForeground("BandMoot", "Link stopped — tap Start in the app to reconnect.")
            return
        }
        startAsForeground()
        ensureLink()
    }

    private fun isStoppedByUser(): Boolean = prefs().getBoolean(PREF_STOPPED, false)

    private fun setStoppedByUser(stopped: Boolean) {
        prefs().edit().putBoolean(PREF_STOPPED, stopped).apply()
    }

    private fun ensureLink() {
        if (isStoppedByUser()) {
            updateForeground("BandMoot", "Link stopped — tap Start in the app to reconnect.")
            return
        }
        val mac = prefs().getString(PREF_MAC, null)
        val key = prefs().getString(PREF_AUTH_KEY, null).orEmpty().trim()
        if (mac.isNullOrBlank() || !XiaomiProtocol.isValidAuthKey(key)) {
            updateForeground("BandMoot", "Missing band MAC or auth key — open the app.")
            return
        }
        val s = link?.state()
        if (s == XiaomiLink.State.READY || s == XiaomiLink.State.CONNECTING || s == XiaomiLink.State.AUTH) return
        updateForeground("BandMoot", "Connecting to $mac …")
        XiaomiLink(this, mac, key) { msg ->
            updateForeground("BandMoot", msg.take(120))
        }.also {
            link = it
            it.setOnReadyListener {
                pendingNav?.let { (title, body) ->
                    it.sendNav(title, body)
                    pendingNav = null
                }
            }
            it.connect()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val action = intent?.action) {
            ACTION_STOP -> {
                stopLinkAndSelf()
                return START_NOT_STICKY
            }
            ACTION_SEND_NAV, ACTION_CLEAR_NAV -> {
                if (isStoppedByUser()) {
                    Log.i(TAG, "Ignoring $action: link stopped by user")
                    stopSelf()
                    return START_NOT_STICKY
                }
                ensureLink()
                val i = intent ?: return START_STICKY
                if (action == ACTION_SEND_NAV) {
                    val turn = runCatching {
                        TurnType.valueOf(i.getStringExtra(EXTRA_TURN) ?: "BLANK")
                    }.getOrDefault(TurnType.BLANK)
                    onNavInstruction(
                        turn,
                        i.getStringExtra(EXTRA_TITLE).orEmpty(),
                        i.getStringExtra(EXTRA_DETAIL).orEmpty(),
                        i.getLongExtra(EXTRA_DISTANCE_M, -1),
                    )
                } else {
                    onNavCleared()
                }
                return START_STICKY
            }
            else -> {
                // Explicit user start (MainActivity, no action): clear the stopped flag.
                if (isStoppedByUser()) setStoppedByUser(false)
                ensureLink()
                return START_STICKY
            }
        }
    }

    private fun onNavInstruction(turn: TurnType, title: String, detail: String, distanceM: Long) {
        // Only buzz on turn change, status, threshold hit/cross, reroute or text change.
        val now = System.currentTimeMillis()
        val titleStripped = title.withoutDistances()
        val detailStripped = detail.withoutDistances()
        val isStatus = turn == TurnType.NO_GPS || turn == TurnType.LETS_GO
        val shouldSend = when {
            lastTurn == null -> true
            isStatus ->
                !(turn == lastTurn && titleStripped == lastTitleStripped && now - lastSentAt < STATUS_DEDUP_WINDOW_MS)
            turn != lastTurn -> true
            distanceM == -1L || lastDistanceM == -1L -> {
                val key = "$turn|$titleStripped|$detailStripped|$distanceM"
                !(key == lastKey && now - lastSentAt < DEDUP_WINDOW_MS)
            }
            distanceM == lastDistanceM && titleStripped == lastTitleStripped && detailStripped == lastDetailStripped -> false
            distanceM > lastDistanceM -> true
            titleStripped != lastTitleStripped || detailStripped != lastDetailStripped -> true
            distanceM in DISTANCE_THRESHOLDS_M -> true
            crossedThreshold(lastDistanceM, distanceM) -> true
            else -> false
        }
        if (!shouldSend) {
            Log.d(TAG, "Suppressed tick: turn=$turn dist=${distanceM}m last=${lastDistanceM}m")
            return
        }

        lastKey = "$turn|$titleStripped|$detailStripped|$distanceM"
        lastTurn = turn
        lastDistanceM = distanceM
        lastTitleStripped = titleStripped
        lastDetailStripped = detailStripped
        lastSentAt = now

        val arrow = when (turn) {
            TurnType.LEFT, TurnType.SHARP_LEFT, TurnType.KEEP_LEFT -> "← "
            TurnType.RIGHT, TurnType.SHARP_RIGHT, TurnType.KEEP_RIGHT -> "→ "
            TurnType.STRAIGHT, TurnType.DEPART, TurnType.LETS_GO -> "↑ "
            else -> ""
        }
        // Title = arrow + distance, body = street; statuses keep their text as title.
        // Body must not be empty or the band drops the card.
        val distanceLabel = formatDistanceLabel(distanceM)
        val bandTitle = if (isStatus || distanceLabel == null) {
            (arrow + title).take(120)
        } else {
            (arrow + distanceLabel).take(120)
        }
        val bandBody = streetLabel(detail, title).take(512)

        val l = link
        if (l?.state() == XiaomiLink.State.READY) {
            l.sendNav(bandTitle, bandBody)
        } else {
            pendingNav = bandTitle to bandBody
        }
        updateForeground("🧭 $title", detail.ifBlank { turn.name })
    }

    private fun onNavCleared() {
        resetNavState()
        link?.clearNav()
        updateForeground("BandMoot", "Waiting for Komoot navigation…")
    }

    private fun resetNavState() {
        lastKey = null
        lastTurn = null
        lastDistanceM = -1
        lastTitleStripped = null
        lastDetailStripped = null
        pendingNav = null
    }

    /** User-initiated stop: dismiss the band card, tear down BLE, remove FGS notification. */
    private fun stopLinkAndSelf() {
        Log.i(TAG, "Stop requested: tearing down link")
        setStoppedByUser(true)
        resetNavState()
        link?.clearNav()
        link?.disconnect()
        link = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        link?.disconnect()
        link = null
        try {
            getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BandConnectorService"
        const val ACTION_SEND_NAV = "com.gdincu.bandmoot.SEND_NAV"
        const val ACTION_CLEAR_NAV = "com.gdincu.bandmoot.CLEAR_NAV"
        const val ACTION_STOP = "com.gdincu.bandmoot.STOP"
        const val EXTRA_TURN = "turn"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DETAIL = "detail"
        const val EXTRA_DISTANCE_M = "distanceM"

        private const val DEDUP_WINDOW_MS = 3000L
        private const val STATUS_DEDUP_WINDOW_MS = 8000L
        private val DISTANCE_THRESHOLDS_M = listOf(0L, 20L, 50L, 100L, 200L, 500L, 1000L, 2000L, 5000L)
        private val STREET_TRIM_CHARS = charArrayOf(' ', '•', '·', '-', '–', '|', ',')
        private val DISTANCE_STRIP_RE =
            Regex("[0-9]+[.,]?[0-9]*\\s*(m|km|mi|ft|yd)\\b\\s*[•·\\-–|]?\\s*", RegexOption.IGNORE_CASE)
        const val PREFS = "bandmoot"
        const val PREF_MAC = "mac"
        const val PREF_AUTH_KEY = "authKey"
        const val PREF_STOPPED = "stopped"

        fun scanFilter(): ScanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(XiaomiProtocol.SERVICE_FE95))
            .build()

        fun scanSettings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        fun isBand8ActiveName(name: String?): Boolean =
            name != null && XiaomiProtocol.DEVICE_NAME_REGEX.matches(name)

        fun bluetoothManager(ctx: Context) =
            ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        private const val CHANNEL_ID = "band_link"
        private const val NOTIF_ID = 41
    }

    private fun formatDistanceLabel(distanceM: Long): String? = when {
        distanceM < 0 -> null
        distanceM < 1000 -> "${distanceM}m"
        else -> "%.1f".format(Locale.ROOT, distanceM / 1000.0).removeSuffix(".0") + "km"
    }

    private fun streetLabel(detail: String, title: String): String {
        val fromDetail = detail.withoutDistances().trim(*STREET_TRIM_CHARS)
        if (fromDetail.isNotEmpty()) return fromDetail
        return title.withoutDistances().trim(*STREET_TRIM_CHARS)
    }

    private fun String.withoutDistances(): String =
        DISTANCE_STRIP_RE.replace(this, "").trim()

    private fun crossedThreshold(prev: Long, cur: Long): Boolean {
        if (prev <= cur) return false
        return DISTANCE_THRESHOLDS_M.any { it in (cur + 1) until prev }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, BandConnectorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .build()
    }

    private fun startAsForeground(
        title: String = "BandMoot",
        text: String = "Waiting for Komoot navigation…",
    ) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Band link", NotificationManager.IMPORTANCE_LOW),
        )
        val notif = buildNotification(title, text)
        // connectedDevice needs BT permissions which may not be granted yet; fall back instead of crashing.
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
                if (hasFgsPermission && hasBtPermission) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    Log.w(TAG, "FGS connectedDevice not granted, using fallback")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(title, text))
    }
}
