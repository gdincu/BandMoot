# BandMoot

Komoot and Google Maps turn-by-turn navigation on the Xiaomi Smart Band 8
Active: an Android notification listener parses live-navigation notifications
and forwards each turn over the band's encrypted BLE protocol.

<img width="270" height="240" alt="image" src="https://github.com/user-attachments/assets/f8cf4e7d-183c-4867-9de0-69e076fc72c6" />


## How it works

1. `KomootListenerService` (notification listener, Komoot + Google Maps)
   receives navigation posts and passes them on. The service name is kept
   from the Komoot-only days so existing notification-access grants survive
   the Maps addition.
2. `KomootParser` / `GoogleMapsParser` accept live-navigation posts (Komoot:
   `channel_live_navigation`, ongoing; Maps: `CATEGORY_NAVIGATION` /
   ongoing) and extract turn / title / detail / distance via the shared
   `NavHints`. Non-turn ticks and non-navigation posts (location sharing,
   offline maps, promos) are ignored, not forwarded. Maps renders its card
   via RemoteViews with the arrow as an image, so the parser scrapes visible
   text out of the standard extras plus the RemoteViews `setText` strings.
3. `BandConnectorService` (foreground service) owns one `XiaomiLink`: BLE
   connect → AES-CCM auth handshake (16-byte BLE auth key) → sends each turn
   once per approach segment (distance-bucketed dedup, no per-second spam).

## Setup

1. Install the app provide all required permissions.
2. Enter valid **MAC** (`AA:BB:CC:DD:EE:FF`) and **AuthKey**
   (`32 hex chars`, `0x`-prefix allowed) values.
3. Tap **Connect**.
4. In Komoot during navigation: speaker icon → **Notifications** mode, and
   allow the `Navigation instructions` channel + Live updates in Android
   settings. In Google Maps: just start driving/walking navigation — no
   in-app toggle needed. Verify a turn card appears in the shade.
5. Stop anytime via **Disconnect** or the **Stop** action
   on the link notification.

## Status

Parsing validated against live captures (`LEFT` + distance, u-turns,
stats filtering). BLE handshake is a line-faithful port of Gadgetbridge's
Xiaomi stack and has shown auth-step-1 flakes against real hardware; failures
degrade to link `FAILED` with a log instead of crashing.

## License

AGPL-3.0-or-later — this app ports Gadgetbridge's AGPL transport code, so the
whole work stays AGPL. See `LICENSE` and `NOTICE.md`. Public repo = source
included; no proprietary forks of this codebase.
