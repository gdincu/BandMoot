package com.gdincu.bandmoot.nav

/** Turn types produced by [KomootParser]. */
enum class TurnType {
    BLANK,
    ARRIVE,
    DEPART,
    LEFT,
    SHARP_LEFT,
    RIGHT,
    SHARP_RIGHT,
    STRAIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    ROUNDABOUT,
    UTURN,
    NO_GPS,
    LETS_GO,
}

/** One parsed Komoot navigation step. */
data class TurnInstruction(
    val turn: TurnType,
    val title: String,
    val detail: String,
    val distanceMeters: Long = -1,
)
