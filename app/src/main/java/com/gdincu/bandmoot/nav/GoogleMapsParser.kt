package com.gdincu.bandmoot.nav

import android.app.Notification
import android.os.Parcel
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.widget.RemoteViews

/**
 * Google Maps turn-by-turn detection + parsing.
 *
 * Like-for-like with [KomootParser]: produces a [TurnInstruction] with
 * direction + title + detail + distance. Non-navigation Maps posts (location
 * sharing, offline maps, generic promos) yield null and are ignored.
 *
 * Maps renders its nav card via RemoteViews (direction arrow is an image, not
 * text), so on top of the standard extras we also scrape visible strings out
 * of contentView/bigContentView via the reflection trick (tag == 2
 * ReflectionAction / setText). Everything is best-effort and null-safe: if no
 * direction-like text is found the post is not navigation.
 */
object GoogleMapsParser {

    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    fun isMapsNavigation(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != GOOGLE_MAPS_PACKAGE) return false
        val notif = sbn.notification ?: return false
        // Canonical case: Maps marks the live nav notification as CATEGORY_NAVIGATION.
        if (notif.category == Notification.CATEGORY_NAVIGATION) return true
        // Fallback across Maps versions: any ongoing Maps post is a candidate;
        // parse() still has to find direction-like text before anything forwards.
        if (sbn.isOngoing) {
            val channelId = notif.channelId.orEmpty().lowercase()
            if ("navig" in channelId) return true
            return true
        }
        return false
    }

    fun parse(sbn: StatusBarNotification): TurnInstruction? {
        val notif = sbn.notification ?: return null
        val texts = notificationTexts(notif)
        if (texts.isEmpty()) return null
        val combined = texts.joinToString(" ")
        val turn = NavHints.guessTurn(combined)
        if (turn == TurnType.BLANK) return null
        val title = texts.firstOrNull { NavHints.guessTurn(it) != TurnType.BLANK }
            ?: texts.first()
        val detail = texts.filterNot { it == title }.distinct().joinToString(" • ")
        return TurnInstruction(
            turn = turn,
            title = title,
            detail = detail,
            distanceMeters = NavHints.parseDistanceMeters(combined),
        )
    }

    private fun notificationTexts(notif: Notification): List<String> {
        val out = ArrayList<String>()
        val extras = notif.extras
        if (extras != null) {
            fun addSeq(seq: CharSequence?) {
                val s = seq?.toString()?.trim().orEmpty()
                if (s.isNotEmpty() && s !in out) out.add(s)
            }
            addSeq(extras.getCharSequence(Notification.EXTRA_TITLE))
            addSeq(extras.getCharSequence(Notification.EXTRA_TITLE_BIG))
            addSeq(extras.getCharSequence(Notification.EXTRA_TEXT))
            addSeq(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            addSeq(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
            addSeq(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
            addSeq(extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
            try {
                val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                lines?.forEach { addSeq(it) }
            } catch (_: Exception) {
            }
        }
        for (t in remoteViewsTexts(notif.contentView)) {
            if (t !in out) out.add(t)
        }
        for (t in remoteViewsTexts(notif.bigContentView)) {
            if (t !in out) out.add(t)
        }
        return out.filter { it.isNotBlank() && !isJunk(it) }
    }

    /** Drop package-noise strings that are never navigation content. */
    private fun isJunk(s: String): Boolean {
        val t = s.lowercase()
        return t == "google maps" ||
            t == "maps" ||
            t == "navigation" ||
            t == "exit navigation" ||
            t == "close navigation"
    }

    /**
     * Best-effort scrape of setText() strings from a RemoteViews layout.
     * Mirrors the well-known mActions ReflectionAction parcel walkthrough.
     */
    private fun remoteViewsTexts(views: RemoteViews?): List<String> {
        if (views == null) return emptyList()
        val out = ArrayList<String>()
        try {
            val field = RemoteViews::class.java.getDeclaredField("mActions")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val actions = field.get(views) as? ArrayList<Parcelable> ?: return emptyList()
            for (action in actions) {
                val parcel: Parcel = Parcel.obtain()
                try {
                    action.writeToParcel(parcel, 0)
                    parcel.setDataPosition(0)
                    val tag = parcel.readInt()
                    if (tag != 2) continue // 2 == ReflectionAction
                    parcel.readInt() // viewId
                    val methodName = parcel.readString() ?: continue
                    if (methodName != "setText") continue
                    parcel.readInt() // parameter type tag
                    val text = try {
                        TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel)?.toString()?.trim()
                    } catch (_: Exception) {
                        null
                    } ?: continue
                    if (text.isNotEmpty() && text !in out) out.add(text)
                } catch (_: Exception) {
                    // One malformed action must not kill the whole scrape.
                } finally {
                    parcel.recycle()
                }
            }
        } catch (_: Exception) {
            return out
        }
        return out
    }
}
