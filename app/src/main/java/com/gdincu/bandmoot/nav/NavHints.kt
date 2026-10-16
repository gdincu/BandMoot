package com.gdincu.bandmoot.nav

/** Shared turn-guessing + distance parsing for navigation notifications. */
object NavHints {

    fun guessTurn(title: String, detail: String): TurnType = guessTurn("$title $detail")

    fun guessTurn(text: String): TurnType {
        val t = text.lowercase()
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
            "exit" in t && ("roundabout" in t || "motorway" in t || "highway" in t || "take" in t) -> TurnType.ROUNDABOUT
            "left" in t -> TurnType.LEFT
            "right" in t -> TurnType.RIGHT
            "straight" in t || "continue" in t || "follow" in t || "ahead" in t -> TurnType.STRAIGHT
            "merge" in t || "join" in t || "take the ramp" in t || "take exit" in t -> TurnType.STRAIGHT
            "start" in t || "depart" in t || "head" in t -> TurnType.DEPART
            else -> TurnType.BLANK
        }
    }

    private val distanceRe =
        Regex("""([0-9]+[.,]?[0-9]*)\s*(m|km|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)

    fun parseDistanceMeters(text: String): Long {
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
