package com.gdincu.bandmoot.nav

/** Shared turn-guessing + distance parsing for navigation notifications. */
object NavHints {

    // Split on middot/bullet/dash/pipe/colon used by Google: "60 m · Turn left onto …"
    private val titleSplitRe = Regex("""\s*[·•\u2013\u2014|:]+\s+""")

    fun guessTurn(title: String, detail: String): TurnType = guessTurn("$title $detail")

    fun guessTurn(text: String): TurnType {
        val t = text.lowercase()
        // Status overrides keep raw substring checks (no normalization) for "no gps"/"let's go".
        if ("no gps" in t) return TurnType.NO_GPS
        if ("let's go" in t || "lets go" in t) return TurnType.LETS_GO

        // Normalized hyphen form mirrors GlyphMaps/Maneuver.fromMapsString for reliable
        // "sharp-left", "keep-left", "turn-left", etc. matching across locales.
        val s = normalizeForLookup(text)

        return when {
            // Arrival must be specific — "Arrive 21:51" ETA is NOT arrival; caller must
            // strip ETA and only pass maneuver text. We match GlyphMaps: arriving/destination/you-have-arrived.
            "arriving" in s || "destination" in s || "you-have-arrived" in s ||
                s.startsWith("arrive") || "-arrive" in s || "finish" in s -> TurnType.ARRIVE

            "uturn" in s || "u-turn" in s || "make-a-u-turn" in s || "turnaround" in s -> TurnType.UTURN

            // Roundabout variants — map to ROUNDABOUT or specific sharp/slight.
            "roundabout-sharp-left" in s -> TurnType.SHARP_LEFT
            "roundabout-sharp-right" in s -> TurnType.SHARP_RIGHT
            "roundabout-slight-left" in s -> TurnType.KEEP_LEFT
            "roundabout-slight-right" in s -> TurnType.KEEP_RIGHT
            "roundabout-left" in s -> TurnType.LEFT
            "roundabout-right" in s -> TurnType.RIGHT
            "roundabout-straight" in s -> TurnType.STRAIGHT
            "roundabout-uturn" in s || "roundabout-u-turn" in s -> TurnType.UTURN
            "exit-the-roundabout" in s -> TurnType.STRAIGHT
            "roundabout" in s && ("turn-left" in s || "take-the-left" in s) -> TurnType.LEFT
            "roundabout" in s && ("turn-right" in s || "take-the-right" in s) -> TurnType.RIGHT
            "roundabout" in s && ("continue-straight" in s || "go-straight" in s) -> TurnType.STRAIGHT
            "roundabout" in s || "round-about" in s -> TurnType.ROUNDABOUT
            // JCT/exit heuristic for Komoot
            "exit" in s && ("roundabout" in t || "motorway" in t || "highway" in t || "take" in t) -> TurnType.ROUNDABOUT
            ("jct" in s || "junction" in s) && "exit" in s -> TurnType.ROUNDABOUT

            "sharp-left" in s -> TurnType.SHARP_LEFT
            "sharp-right" in s -> TurnType.SHARP_RIGHT

            // Forks — map to KEEP_* so BandConnectorService shows ←/→ (simplified Komoot arrows).
            "fork-left" in s -> TurnType.KEEP_LEFT
            "fork-right" in s -> TurnType.KEEP_RIGHT
            "take-the-exit" in s || "take-exit" in s -> TurnType.KEEP_RIGHT

            // Keep / bear / veer / slight / ramp must beat plain "left-onto".
            "merge-left" in s || "bear-left" in s || "veer-left" in s ||
                "slight-left" in s || "ramp-left" in s || "off-ramp-left" in s ||
                "on-ramp-left" in s || "keep-left" in s || "stay-left" in s ||
                "stay-on-the-left" in s -> TurnType.KEEP_LEFT
            "merge-right" in s || "bear-right" in s || "veer-right" in s ||
                "slight-right" in s || "ramp-right" in s || "off-ramp-right" in s ||
                "on-ramp-right" in s || "keep-right" in s || "stay-right" in s ||
                "stay-on-the-right" in s -> TurnType.KEEP_RIGHT

            "turn-left" in s || "make-a-left" in s || "take-a-left" in s ||
                "take-the-left" in s || "left-onto" in s || "left" in s && "onto" in s -> TurnType.LEFT
            "turn-right" in s || "make-a-right" in s || "take-a-right" in s ||
                "take-the-right" in s || "right-onto" in s || "right" in s && "onto" in s -> TurnType.RIGHT
            // Fallback plain left/right (after all specific left/right variants)
            "left" in t -> TurnType.LEFT
            "right" in t -> TurnType.RIGHT

            "straight" in t || "continue" in t || "follow" in t || "ahead" in t -> TurnType.STRAIGHT
            "merge" in t || "join" in t || "take the ramp" in t || "take exit" in t -> TurnType.STRAIGHT
            "start" in t || "depart" in t || "head" in t -> TurnType.DEPART
            else -> TurnType.BLANK
        }
    }

    /**
     * Google Live Updates: "60 m · Turn left onto ADM Rd" -> "Turn left onto ADM Rd".
     * If the prefix before the first middot/bullet/dash is a distance, strip it.
     */
    fun stripDistancePrefix(title: String): String {
        val parts = titleSplitRe.split(title, limit = 2)
        return if (parts.size == 2 && parseDistanceMeters(parts[0]) != -1L) {
            parts[1].trim()
        } else {
            title.trim()
        }
    }

    fun normalizeForLookup(text: String): String =
        text.lowercase()
            .replace(' ', ' ') // NBSP
            .replace(' ', ' ') // narrow NBSP
            .replace(Regex("[\u2013\u2014\u2212]"), "-")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private val distanceRe =
        Regex("""([0-9]+[.,]?[0-9]*)\s*(kilometres?|kilometers?|km|miles?|mi|metres?|meters?|mtrs?|yards?|yds?|yd|feet|ft|m)\b""", RegexOption.IGNORE_CASE)

    fun parseDistanceMeters(text: String): Long {
        val normalized = text.replace(' ', ' ').replace(' ', ' ').replace(',', '.')
        val m = distanceRe.find(normalized) ?: return -1
        val value = m.groupValues[1].toDoubleOrNull() ?: return -1
        return when (m.groupValues[2].lowercase()) {
            "m", "meter", "meters", "metre", "metres", "mtr", "mtrs" -> value.toLong()
            "km", "kilometer", "kilometers", "kilometre", "kilometres" -> (value * 1000).toLong()
            "ft", "feet" -> (value * 0.3048).toLong()
            "yd", "yds", "yard", "yards" -> (value * 0.9144).toLong()
            "mi", "mile", "miles" -> (value * 1609.34).toLong()
            else -> -1
        }
    }

    /** Distance from the first segment of a split title, e.g. "60 m · …" -> 60. */
    fun parseDistanceFromTitle(title: String): Long {
        val parts = titleSplitRe.split(title, limit = 2)
        if (parts.size == 2) {
            val d = parseDistanceMeters(parts[0])
            if (d != -1L) return d
        }
        return parseDistanceMeters(title)
    }

    fun extractStreetName(phrase: String): String? {
        val markers = listOf(" onto ", " to stay on ", " toward ", " towards ", " on ")
        for (m in markers) {
            val idx = phrase.indexOf(m, ignoreCase = true)
            if (idx >= 0) {
                val name = phrase.substring(idx + m.length).trim()
                if (name.isNotEmpty()) return name
            }
        }
        return null
    }
}
