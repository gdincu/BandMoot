package com.gdincu.bandmoot

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for what the user sees.
 *
 * BandConnectorService pushes every foreground-notification update here, and
 * MainActivity renders this buffer — so the in-app log always contains
 * exactly what the notification card shows (plus app-side events logged via
 * [add]).
 *
 * Same-process only (app + service share a process). In-memory ring buffer:
 * history survives activity recreation while the process (and usually the
 * foreground service) is alive.
 */
object LogStore {
    private const val MAX_LINES = 100

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()

    fun add(msg: String) {
        val entry = "• ${timestamp()} $msg"
        val toNotify: List<() -> Unit>
        synchronized(lock) {
            lines.addLast(entry)
            while (lines.size > MAX_LINES) lines.removeFirst()
            toNotify = listeners.toList()
        }
        for (l in toNotify) {
            try {
                l()
            } catch (_: Exception) {
            }
        }
    }

    /** Mirrors one foreground notification (title + text) into the log. */
    fun addNotification(title: String, text: String) {
        val t = title.trim()
        val b = text.trim()
        add(if (b.isEmpty() || b == t) t else "$t — $b")
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun addListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: () -> Unit) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
}
