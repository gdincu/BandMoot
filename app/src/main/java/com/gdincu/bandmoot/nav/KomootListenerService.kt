package com.gdincu.bandmoot.nav

import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.gdincu.bandmoot.band.BandConnectorService

/**
 * Komoot-only listener: parses navigation posts and forwards them to
 * [BandConnectorService]. Enable via notification access settings.
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
        if (sbn.key != activeNavigationKey) return
        activeNavigationKey = null
        Log.i(TAG, "Active Komoot navigation notification removed; clearing band")
        forward(null, remove = true)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, KomootListenerService::class.java))
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
        startForegroundService(intent)
    }

    companion object {
        private const val TAG = "KomootListenerService"
    }
}
