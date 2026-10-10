package com.example.bandmoot.nav

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.bandmoot.band.BandConnectorService

/**
 * Komoot-only listener. Everything else is ignored on purpose (lightweight bike nav).
 *
 * Flow (mirrors `NotificationService.onNotificationPosted -> p25.run`):
 * 1. [KomootParser.isKomootNavigation] gate (de.komoot.android + navigation channel + ongoing)
 * 2. [KomootParser.parse] title/detail/distance
 * 3. forward to [BandConnectorService] for BLE delivery + vibration
 *
 * Enable via Settings > Apps > Special app access > Notification access.
 */
class KomootListenerService : NotificationListenerService() {

    private var activeNavigationKey: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KomootParser.KOMOOT_PACKAGE) return
        if (!KomootParser.isKomootNavigation(sbn)) return
        activeNavigationKey = sbn.key
        val instruction = KomootParser.parse(sbn) ?: return
        forward(instruction, remove = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != KomootParser.KOMOOT_PACKAGE) return
        if (sbn.key != activeNavigationKey) {
            Log.d(TAG, "Ignoring removed non-active Komoot notification key=${sbn.key}")
            return
        }
        activeNavigationKey = null
        // Komoot dismisses the nav notification at arrival: clear the band screen.
        Log.i(TAG, "Active Komoot navigation notification removed; clearing band")
        forward(null, remove = true)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sendBroadcast(Intent(ACTION_LISTENER_STATE).putExtra(EXTRA_CONNECTED, true))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        sendBroadcast(Intent(ACTION_LISTENER_STATE).putExtra(EXTRA_CONNECTED, false))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(android.content.ComponentName(this, KomootListenerService::class.java))
        }
    }

    private fun forward(instruction: TurnInstruction?, remove: Boolean) {
        val intent = Intent(this, BandConnectorService::class.java).apply {
            action = if (remove) {
                BandConnectorService.ACTION_CLEAR_NAV
            } else {
                BandConnectorService.ACTION_SEND_NAV
            }
            instruction?.let {
                putExtra(BandConnectorService.EXTRA_TURN, it.turn.name)
                putExtra(BandConnectorService.EXTRA_TITLE, it.title)
                putExtra(BandConnectorService.EXTRA_DETAIL, it.detail)
                putExtra(BandConnectorService.EXTRA_DISTANCE_M, it.distanceMeters)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    companion object {
        private const val TAG = "KomootListenerService"
        const val ACTION_LISTENER_STATE = "com.example.bandmoot.LISTENER_STATE"
        const val EXTRA_CONNECTED = "connected"
    }
}
