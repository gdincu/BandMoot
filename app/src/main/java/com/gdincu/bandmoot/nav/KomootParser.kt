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
        val turn = guessTurn(title, detail)
        if (turn == TurnType.BLANK) return null
        return TurnInstruction(
            turn = turn,
            title = title,
            detail = detail,
            distanceMeters = parseDistanceMeters("$title $detail"),
        )
    }

    private fun guessTurn(title: String, detail: String): TurnType {
        val t = "$title $detail".lowercase()
        return when {
            "no gps" in t -> TurnType.NO_GPS
            "let's go" in t || "lets go" in t -> TurnType.LETS_GO
            "arriv" in t || "finish" in t || "destination" in t -> TurnType.ARRIVE
            "u-turn" in t || "u turn" in t || "turnaround" in t -> TurnType.UTURN
            "sharp left" in t -> TurnType.SHARP_LEFT
            "sharp right" in t -> TurnType.SHARP_RIGHT
            "slight left" in t || "keep left" in t -> TurnType.KEEP_LEFT
            "slight right" in t || "keep right" in t -> TurnType.KEEP_RIGHT
            "roundabout" in t || "round-about" in t -> TurnType.ROUNDABOUT
            "left" in t -> TurnType.LEFT
            "right" in t -> TurnType.RIGHT
            "straight" in t || "continue" in t || "follow" in t || "ahead" in t -> TurnType.STRAIGHT
            "start" in t || "depart" in t || "head" in t -> TurnType.DEPART
            else -> TurnType.BLANK
        }
    }

    private val distanceRe =
        Regex("""([0-9]+[.,]?[0-9]*)\s*(m|km|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)

    private fun parseDistanceMeters(text: String): Long {
        val m = distanceRe.find(text) ?: return -1
        val value = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return -1
        return when (m.groupValues[2].lowercase()) {
            "m" -> value.toLong()
            "km" -> (value * 1000).toLong()
            "mi" -> (value * 1609.34).toLong()
            "ft" -> (value * 0.3048).toLong()
            "yd" -> (value * 0.9144).toLong()
            else -> -1
        }
    }
}
