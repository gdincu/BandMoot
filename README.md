# BandMoot

Komoot turn-by-turn navigation on the Xiaomi Smart Band 8 Active: an Android
notification listener parses Komoot's live-navigation notification and
forwards each turn over the band's encrypted BLE protocol.

## How it works

1. `KomootListenerService` (notification listener, Komoot only) captures every
   `de.komoot.android` post to `filesDir/komoot_captures/` for parser tuning.
2. `KomootParser` accepts live-navigation posts (`channel_live_navigation`,
   ongoing) and extracts turn / title / detail / distance. Stats and status
   states (recording timer, "No GPS") are ignored, not forwarded.
3. `BandConnectorService` (foreground service) owns one `XiaomiLink`: BLE
   connect → AES-CCM auth handshake (16-byte BLE auth key) → sends each turn
   once per approach segment (distance-bucketed dedup, no per-second spam).

## Setup

1. Install the app.
2. Grant notification access, then scan for `Xiaomi Band 8 Active …` and save it.
3. Save the 32-hex-char BLE auth key (from Mi Fitness logs / freemyband-style
   tooling; the link's HMAC check reports a wrong key as auth failure).
4. Start the link service.
5. In Komoot, during navigation: speaker icon → **Notifications** mode, and
   allow the `Navigation instructions` channel + Live updates in Android
   settings. Verify a turn card appears in the shade; the app's
   "Show last Komoot capture" shows what the parser saw.

## Status

Parsing validated against live captures (`SHARP_LEFT` + distance, u-turns,
stats filtering). BLE handshake is a line-faithful port of Gadgetbridge's
Xiaomi stack and has shown auth-step-1 flakes against real hardware; failures
degrade to link `FAILED` with a log instead of crashing.

## License

AGPL-3.0-or-later — this app ports Gadgetbridge's AGPL transport code, so the
whole work stays AGPL. See `LICENSE` and `NOTICE.md`. Public repo = source
included; no proprietary forks of this codebase.
