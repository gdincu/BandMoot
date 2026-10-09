package com.example.bandmoot.nav

import android.app.Notification
import android.content.Context
import android.os.Build
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Step-2 aid: records the raw shape of Komoot notifications so the parser
 * ([KomootParser]) can be tuned against YOUR Komoot version.
 *
 * Captures every `de.komoot.android` post (not only navigation ones) — the
 * nav vs non-nav comparison is what reveals the reliable gate
 * (MiBandNotify uses channelId ∋ "navigation" + isOngoing; verify it holds).
 *
 * Files land in `<filesDir>/komoot_captures/*.txt` — pull with
 * `adb pull <path>` or read in-app via MainActivity "Show last capture".
 */
object NotificationCapture {

    const val PREF_LAST_CAPTURE = "last_capture"

    fun capture(ctx: Context, sbn: StatusBarNotification): File {
        val notif = sbn.notification
        val extras = notif?.extras
        val sb = StringBuilder()
        sb.appendLine("package=${sbn.packageName}")
        sb.appendLine("postTime=${SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(sbn.postTime))}")
        sb.appendLine("isOngoing=${notif?.isOngoing} isLocalOnly=${notif?.let {
            androidx.core.app.NotificationCompat.getLocalOnly(it)
        }}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            sb.appendLine("channelId=${notif?.channelId}")
        }
        sb.appendLine("category=${notif?.category} flags=${notif?.flags} actions=${notif?.actions?.size}")
        sb.appendLine("--- extras ---")
        if (extras != null) {
            for (key in extras.keySet().sorted()) {
                val v = extras.get(key)
                if (v is android.graphics.Bitmap) {
                    sb.appendLine("$key=<Bitmap ${v.width}x${v.height}>")
                    continue
                }
                val shown = when (v) {
                    null -> "null"
                    is CharSequence -> v.toString().take(300)
                    is Array<*> -> v.joinToString(" | ").take(300)
                    else -> "${v.javaClass.simpleName}=$v".take(200)
                }
                sb.appendLine("$key=$shown")
            }
            sb.appendLine("hasLargeIcon=${extras.containsKey(Notification.EXTRA_LARGE_ICON)}")
            sb.appendLine("TITLE=${extras.getCharSequence(Notification.EXTRA_TITLE)}")
            sb.appendLine("TEXT=${extras.getCharSequence(Notification.EXTRA_TEXT)}")
            sb.appendLine("BIG_TEXT=${extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.take(300)}")
            sb.appendLine("SUB_TEXT=${extras.getCharSequence(Notification.EXTRA_SUB_TEXT)}")
            sb.appendLine("INFO=${extras.getCharSequence(Notification.EXTRA_INFO_TEXT)}")
        } else {
            sb.appendLine("<no extras>")
        }
        val parsed = KomootParser.parse(sbn)
        sb.appendLine("--- parser says ---")
        sb.appendLine("isNav=${KomootParser.isKomootNavigation(sbn)} parsed=$parsed")

        val dir = File(ctx.filesDir, "komoot_captures").apply { mkdirs() }
        val name = "${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date())}" +
            "_ongoing${notif?.isOngoing}.txt"
        val file = File(dir, name)
        file.writeText(sb.toString())
        ctx.getSharedPreferences(BandPrefs.NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_LAST_CAPTURE, file.absolutePath).apply()
        Log.i("KomootCapture", sb.toString())
        return file
    }

    fun lastCapture(ctx: Context): File? {
        val path = ctx.getSharedPreferences(BandPrefs.NAME, Context.MODE_PRIVATE)
            .getString(PREF_LAST_CAPTURE, null) ?: return null
        return File(path).takeIf { it.exists() }
    }
}

/** Shared prefs name + known-device defaults (see MainActivity). */
object BandPrefs {
    const val NAME = "bandmoot"
}
