package com.example.bandmoot.nav

import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.bandmoot.band.BandConnectorService

/**
 * Komoot-only listener. Everything else is ignored on purpose (lightweight bike nav).
 *
 * Flow (mirrors MiBandNotify `NotificationService.onNotificationPosted -> p25.run`):
 * 1. [KomootParser.isKomootNavigation] gate (de.komoot.android + navigation channel + ongoing)
 * 2. [KomootParser.parse] title/detail/distance
 * 3. forward to [BandConnectorService] for BLE delivery + vibration
 *
 * Enable via Settings > Apps > Special app access > Notification access.
 */
class KomootListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != KomootParser.KOMOOT_PACKAGE) return
        // Step-2 capture: record EVERY Komoot post (nav or not) for parser tuning.
        runCatching { NotificationCapture.capture(this, sbn) }
        if (!KomootParser.isKomootNavigation(sbn)) return
        val instruction = KomootParser.parse(sbn) ?: return
        forward(instruction, remove = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != KomootParser.KOMOOT_PACKAGE) return
        // Komoot dismisses the nav notification at arrival: clear the band screen.
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
        const val ACTION_LISTENER_STATE = "com.example.bandmoot.LISTENER_STATE"
        const val EXTRA_CONNECTED = "connected"
    }
}
