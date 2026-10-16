package com.gdincu.bandmoot.nav

import android.content.ComponentName
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.gdincu.bandmoot.band.BandConnectorService

/**
 * Navigation listener: parses turn-by-turn posts from Komoot and Google Maps
 * and forwards them to [BandConnectorService]. Enable via notification access
 * settings.
 *
 * Class name is kept (Komoot-only origins) so existing notification-access
 * grants — which are stored per component name — keep working after the
 * Maps addition.
 */
class KomootListenerService : NotificationListenerService() {

    private var activeNavigationKey: String? = null
    private var activePackage: String? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val instruction = when {
            sbn.packageName == KomootParser.KOMOOT_PACKAGE -> {
                if (!KomootParser.isKomootNavigation(sbn)) return
                KomootParser.parse(sbn)
            }
            GoogleMapsParser.isMapsPackage(sbn.packageName) -> {
                if (!GoogleMapsParser.isMapsNavigation(sbn)) return
                GoogleMapsParser.parse(sbn)
            }
            else -> return
        } ?: return
        activeNavigationKey = sbn.key
        activePackage = sbn.packageName
        forward(instruction)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key != activeNavigationKey) return
        if (sbn.packageName != activePackage) return
        val pkg = activePackage
        activeNavigationKey = null
        activePackage = null
        Log.i(TAG, "Active navigation notification removed ($pkg); clearing band")
        forward(null)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, KomootListenerService::class.java))
    }

    private fun forward(instruction: TurnInstruction?) {
        val intent = Intent(this, BandConnectorService::class.java).apply {
            action = if (instruction == null) {
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
        try {
            startForegroundService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start link service: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "KomootListenerService"
    }
}
