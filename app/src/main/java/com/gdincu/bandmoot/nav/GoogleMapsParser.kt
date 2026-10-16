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
 * Maps (Live Updates / ProgressStyle) structure verified live (Android 14+):
 *   android.title             = "60 m · Turn left onto ADM Rd"  (distance + middot + maneuver)
 *   android.shortCriticalText = "60 m"                          (authoritative distance)
 *   android.text / subText    = "Arrive 21:51" / "15 min · 2.1 km · 21:51" (ETA footer, never maneuver)
 *   android.largeIcon         = directional arrow bitmap (not text — rendered as ImageView)
 *
 * Like-for-like with [KomootParser]: produces a [TurnInstruction] with
 * direction + title + detail + distance. Non-navigation Maps posts (location
 * sharing, offline maps, generic promos) yield null and are ignored.
 *
 * On older Maps versions the extras miss the ProgressStyle fields and the card
 * is rendered via RemoteViews, so we keep the reflection scrape (tag == 2
 * ReflectionAction / setText) as a fallback.
 */
object GoogleMapsParser {

    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    const val MAPS_LITE_PACKAGE = "com.google.android.apps.mapslite"

    fun isMapsPackage(pkg: String?): Boolean =
        pkg == GOOGLE_MAPS_PACKAGE || pkg == MAPS_LITE_PACKAGE
    private val NAV_HINTS = listOf(
        "turn ", "keep ", "head ", "exit", "arrive", "arriving",
        "roundabout", "continue ", "merge", "destination", "u-turn",
        "uturn", "fork", "ramp", "onto ",
    )

    fun isMapsNavigation(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != GOOGLE_MAPS_PACKAGE && sbn.packageName != MAPS_LITE_PACKAGE) return false
        val notif = sbn.notification ?: return false
        // Canonical: Maps marks the live nav notification as CATEGORY_NAVIGATION.
        if (notif.category == Notification.CATEGORY_NAVIGATION || notif.category == "navigation") return true
        val extras = notif.extras
        val template = extras?.getString("android.template").orEmpty()
        val ongoing = sbn.isOngoing || (notif.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val shortCritical = extras?.getCharSequence("android.shortCriticalText")?.toString()?.trim().orEmpty()
        // Android 16 Live Updates use ProgressStyle + ongoing.
        if (template.contains("ProgressStyle") && ongoing) return true
        // Distance-bearing ongoing tick is nav (shortCritical is exclusive to nav).
        if (ongoing && shortCritical.isNotBlank() && NavHints.parseDistanceMeters(shortCritical) != -1L) return true
        // Legacy fallback: ongoing + channel hint, but still requires parse() to find maneuver.
        if (ongoing) {
            val channelId = notif.channelId.orEmpty().lowercase()
            if ("navig" in channelId) return true
            // Only allow ongoing nav if it already looks like a maneuver; otherwise ignore
            // location-sharing / offline-map ongoing notifications.
            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val blob = "$title $text".lowercase()
            if (NAV_HINTS.any { it in blob }) return true
            return false
        }
        return false
    }

    fun parse(sbn: StatusBarNotification): TurnInstruction? {
        val notif = sbn.notification ?: return null
        // Fast path: structured extras (Android 16 Live Updates). This is the user-described
        // "title = meters · Turn left … / body = Arrive 21:51" format.
        parseStructured(notif)?.let { return it }
        // Fallback: scrape all visible strings (older Maps / RemoteViews).
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

    /**
     * Structured parse for Live Updates:
     * title = "60 m · Turn left onto Hauptstr.", shortCritical = "60 m", subText = ETA.
     * Arrow is an Icon bitmap (not text) — mapped via textual maneuver -> simplified TurnType.
     */
    private fun parseStructured(notif: Notification): TurnInstruction? {
        val extras = notif.extras ?: return null
        val titleRaw = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val textRaw = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val subTextRaw = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        val shortCritical = extras.getCharSequence("android.shortCriticalText")?.toString()?.trim()
            ?: extras.getString("android.shortCriticalText")?.trim()

        // No title at all -> can't do structured parse, fall through to scrape.
        if (titleRaw.isBlank() && textRaw.isBlank()) return null

        // Unified headline: title is authoritative; text is fallback for rare variants.
        val headline = titleRaw.ifBlank { textRaw }

        // Drop non-nav heads: rerouting / starting / post-trip survey (GlyphMaps parity).
        val headlineLower = headline.lowercase()
        if (headlineLower.startsWith("rerouting") ||
            (headlineLower.contains("rerouting") && headline.length < 24)
        ) return null
        if (headlineLower.startsWith("starting navigation")) return null
        if (headlineLower.startsWith("how was")) return null
        // ETA-only bodies like "Arrive 21:51" are never maneuvers.
        if (isEtaOnly(headline)) return null

        // Distance: shortCritical is authoritative (GlyphMaps resolveDistance), then prefix of title.
        val distanceM = resolveDistance(shortCritical, headline, textRaw, subTextRaw)

        // Maneuver text: strip the leading distance prefix ("60 m · ") so only
        // "Turn left onto …" is fed to guessTurn. ETA / subText is never included.
        var maneuverText = NavHints.stripDistancePrefix(headline)
        if (maneuverText.isBlank() && textRaw.isNotBlank() && !isEtaOnly(textRaw)) {
            maneuverText = NavHints.stripDistancePrefix(textRaw)
        }
        if (maneuverText.isBlank() || isEtaOnly(maneuverText)) return null

        val turn = NavHints.guessTurn(maneuverText)
        if (turn == TurnType.BLANK) return null

        // Street for band body: extract after "onto"/"toward"/"on", else whole maneuver.
        val street = NavHints.extractStreetName(maneuverText)
        val detail = street?.takeIf { it.isNotBlank() } ?: maneuverText
        // Title forwarded to BandConnectorService is maneuver text (arrow+distance handled there).
        return TurnInstruction(
            turn = turn,
            title = maneuverText,
            detail = detail,
            distanceMeters = distanceM,
        )
    }

    private fun resolveDistance(shortCritical: String?, title: String, text: String, subText: String): Long {
        shortCritical?.let {
            val d = NavHints.parseDistanceMeters(it)
            if (d != -1L) return d
        }
        val fromTitle = NavHints.parseDistanceFromTitle(title)
        if (fromTitle != -1L) return fromTitle
        // Last resort: parse any distance in text/subText (but not ETA time).
        listOf(text, subText).forEach { t ->
            if (t.isNotBlank() && !isEtaOnly(t)) {
                val d = NavHints.parseDistanceMeters(t)
                if (d != -1L) return d
            }
        }
        return -1
    }

    private fun isEtaOnly(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty()) return true
        // "Arrive 21:51", "Arrive at 21:51", "ETA 21:51", "21:51" — pure arrival footer.
        // Must not match "Turn left onto Arrive St" (contains arrive but also maneuver).
        val lower = t.lowercase()
        // If it contains a maneuver keyword like turn/keep/roundabout/onto, it's not ETA-only.
        if (NAV_HINTS.any { it.trim() in lower && it.trim() != "arrive" && it.trim() != "arriving" }) return false
        if (Regex("""\barrive\b.*\d{1,2}:\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(t)) return true
        if (Regex("""^\s*\d{1,2}:\d{2}\s*$""").matches(t)) return true
        if (t.equals("arriving", ignoreCase = true)) return false // arriving is a maneuver (ARRIVE)
        // Heuristic: very short "Arrive ..." without turn keywords is ETA
        if (lower.startsWith("arrive") && t.length < 20 && "turn" !in lower && "onto" !in lower && "toward" !in lower) return true
        return false
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

    /** Drop package-noise / ETA / transient heads that are never the maneuver. */
    private fun isJunk(s: String): Boolean {
        val t = s.lowercase().trim()
        if (t == "google maps" || t == "maps" || t == "navigation" ||
            t == "exit navigation" || t == "close navigation"
        ) return true
        if (t.startsWith("rerouting")) return true
        if (t.startsWith("starting navigation")) return true
        if (t.startsWith("how was")) return true
        // Pure ETA footers like "Arrive 21:51" must not pollute maneuver detection.
        if (isEtaOnly(s)) return true
        return false
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
