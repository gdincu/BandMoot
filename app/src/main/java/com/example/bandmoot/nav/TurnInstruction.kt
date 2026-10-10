package com.example.bandmoot.nav

/** Minimal turn-arrow set, mirroring the decompiled app's `dy4` enum (42 values there).
 *  Keep this small: Komoot bike navigation only needs these. */
enum class TurnType {
    BLANK,
    ARRIVE,
    DEPART,
    LEFT,
    SLIGHT_LEFT,
    SHARP_LEFT,
    RIGHT,
    SLIGHT_RIGHT,
    SHARP_RIGHT,
    STRAIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    ROUNDABOUT,
    UTURN,
    FORK,
    // Komoot live-nav statuses (like NavigatorForMiBand's rerouting) — must notify.
    NO_GPS,
    LETS_GO,
}

/** One parsed Komoot navigation step. */
data class TurnInstruction(
    val turn: TurnType,
    /** e.g. "Turn left onto High St" (EXTRA_TITLE / BIG_TEXT). */
    val title: String,
    /** e.g. "250 m - High St" detail line. */
    val detail: String,
    /** Raw distance in meters when parseable, else -1. */
    val distanceMeters: Long = -1,
)
