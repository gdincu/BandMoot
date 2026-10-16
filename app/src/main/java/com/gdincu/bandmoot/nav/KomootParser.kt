package com.gdincu.bandmoot.nav

import android.app.Notification
import android.service.notification.StatusBarNotification

/** Komoot live-navigation detection + parsing. */
object KomootParser {

    const val KOMOOT_PACKAGE = "de.komoot.android"

    fun isKomootNavigation(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != KOMOOT_PACKAGE) return false
        val notif = sbn.notification ?: return false
        if (!sbn.isOngoing) return false
        val channelId = notif.channelId ?: return false
        return "navigation" in channelId
    }

    fun parse(sbn: StatusBarNotification): TurnInstruction? {
        val notif = sbn.notification ?: return null
        val extras = notif.extras ?: return null
        val title = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence("android.shortCriticalText"),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT),
        ).firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
        val detail = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
        if (title.isBlank() && detail.isBlank()) return null
        val turn = NavHints.guessTurn(title, detail)
        if (turn == TurnType.BLANK) return null
        return TurnInstruction(
            turn = turn,
            title = title,
            detail = detail,
            distanceMeters = NavHints.parseDistanceMeters("$title $detail"),
        )
    }
}
