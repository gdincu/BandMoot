package com.example.bandmoot.nav

import android.app.Notification
import android.os.Build
import android.service.notification.StatusBarNotification

/**
 * Komoot detection + parsing.
 *
 * Reverse-engineered from MiBandNotify (`com.mc.xiaomi1`):
 * - gate in `defpackage/sq0.java`: package `de.komoot.android`
 *   (`KomootActivity`, `GMapsActivity`/`OSMActivity` siblings stripped for this app)
 * - gate in `defpackage/ni3.java`: SDK 26+, `channelId.contains("navigation")`,
 *   `notification.isOngoing == true`
 * - fields in `ni3.f`: `EXTRA_TITLE`, `EXTRA_BIG_TEXT`/`EXTRA_TEXT`,
 *   `EXTRA_SHORT_CRITICAL_TEXT` + `notification.largeIcon` tint-filtered for
 *   `#4F850D`, resized to 105x105 and matched against Komoot drawables
 *   (`defpackage/dy4.java` 42-value turn enum, `vy2` similarity).
 *
 * TODO(bike-nav): capture 2-3 live Komoot notifications (title/bigText/largeIcon
 * bitmap) during a test ride and tighten [from]/[matchIcon]; start with text-only
 * forwarding so turns are readable even before icon mapping exists.
 */
object KomootParser {

    const val KOMOOT_PACKAGE = "de.komoot.android"

    fun isKomootNavigation(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != KOMOOT_PACKAGE) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val notif = sbn.notification ?: return false
        if (!notif.isOngoing) return false
        val channelId = notif.channelId ?: return false
        return "navigation" in channelId
    }

    fun parse(sbn: StatusBarNotification): TurnInstruction? {
        val notif = sbn.notification ?: return null
        val extras = notif.extras ?: return null
        val title = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_SHORT_CRITICAL_TEXT),
        ).firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
        val detail = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
        ).firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
        if (title.isBlank() && detail.isBlank()) return null
        return TurnInstruction(
            turn = guessTurn(title, detail),
            title = title,
            detail = detail,
            distanceMeters = parseDistanceMeters("$title $detail"),
        )
    }

    /** Direction bitmap hook; icon matching is a TODO (needs a Context to resolve
     *  the largeIcon, then downscale to 105x105 like MiBandNotify's dy4.b()). */
    fun directionBitmap(sbn: StatusBarNotification): android.graphics.Bitmap? {
        // TODO(bike-nav): implement with Icon.loadDrawable(context) + capture mapping.
        return null
    }

    private fun guessTurn(title: String, detail: String): TurnType {
        val t = "$title $detail".lowercase()
        return when {
            "arriv" in t || "finish" in t || "destination" in t -> TurnType.ARRIVE
            "u-turn" in t || "u turn" in t || "turnaround" in t -> TurnType.UTURN
            "sharp left" in t -> TurnType.SHARP_LEFT
            "sharp right" in t -> TurnType.SHARP_RIGHT
            "slight left" in t || "keep left" in t -> TurnType.KEEP_LEFT
            "slight right" in t || "keep right" in t -> TurnType.KEEP_RIGHT
            "roundabout" in t || "round-about" in t -> TurnType.ROUNDABOUT
            "left" in t || "l " in " $t" || "↰" in t || "←" in t -> TurnType.LEFT
            "right" in t || "↱" in t || "→" in t -> TurnType.RIGHT
            "straight" in t || "continue" in t || "follow" in t || "↑" in t -> TurnType.STRAIGHT
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
