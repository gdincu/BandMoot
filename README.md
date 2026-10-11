# BandMoot

Komoot turn-by-turn navigation on the Xiaomi Smart Band 8 Active: an Android
notification listener parses Komoot's live-navigation notification and
forwards each turn over the band's encrypted BLE protocol.

## How it works

1. `KomootListenerService` (notification listener, Komoot only) receives every
   `de.komoot.android` post and passes navigation posts on.
2. `KomootParser` accepts live-navigation posts (`channel_live_navigation`,
   ongoing) and extracts turn / title / detail / distance. Non-turn stats
   ticks are ignored, not forwarded.
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
   settings. Verify a turn card appears in the shade.

## Status

Parsing validated against live captures (`SHARP_LEFT` + distance, u-turns,
stats filtering). BLE handshake is a line-faithful port of Gadgetbridge's
Xiaomi stack and has shown auth-step-1 flakes against real hardware; failures
degrade to link `FAILED` with a log instead of crashing.

## Release / deploy

Package: `com.gdincu.bandmoot` (re-install required if you had the old
`com.example.bandmoot` debug build — Android treats it as a different app,
so unpair/re-grant notification access after installing).

- Debug (`bandmoot-debug` artifact): debug-signed, installs directly for
  testing. Not for stores.
- Release (`bandmoot-release` artifact): signed when a keystore is
  configured, otherwise `app-release-unsigned.apk` — sign before deploying.

Sign locally once: create a keystore, put it outside git, and reference it
in `keystore.properties` at the repo root (never committed):

```properties
storeFile=/absolute/path/bandmoot-release.keystore
storePassword=…
keyAlias=bandmoot
keyPassword=…
```

then `gradle :app:assembleRelease`. For CI-signed builds, set the
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`
and `ANDROID_KEY_PASSWORD` repo secrets instead. Bump `versionCode` per
release; keep the AGPL license files in place.

## License

AGPL-3.0-or-later — this app ports Gadgetbridge's AGPL transport code, so the
whole work stays AGPL. See `LICENSE` and `NOTICE.md`. Public repo = source
included; no proprietary forks of this codebase.
