# Third-party notices

## Gadgetbridge (Freeyourgadget/Gadgetbridge) — AGPL-3.0-or-later

The BLE transport is a clean-room-behavior port of Gadgetbridge's Xiaomi
stack (call flow, framing and crypto; no Gadgetbridge framework code):

- `band/XiaomiProtocol.kt` — BLE identity/UUIDs, from `MiBand8ActiveCoordinator`
  and `XiaomiUuids` (service FE95, chars 0051/0052/0053/0055).
- `band/XiaomiCrypto.kt` — AES-CCM handshake/HKDF schedule, from
  `XiaomiAuthService` (phone nonce → watch nonce → step-3 reply).
- `band/XiaomiFrame.kt` — packet framing/chunking, from
  `XiaomiCharacteristic` (single/chunked writes, ACK scheme, reassembly).
- `band/XiaomiLink.kt` — link lifecycle, from `XiaomiBleSupport`
  (MTU request, notify enable, auth-then-encrypted-notify flow) and the
  notification icon query/request handling of `XiaomiNotificationService`.
- `band/XiaomiNotificationIcon.kt` — notification icon upload (pixel formats,
  upload body and parts), from `XiaomiBitmapUtils` and `XiaomiDataUploadService`.
- `app/src/main/proto/xiaomi.proto` — vendored from Gadgetbridge
  (`nodomain.freeyourgadget.gadgetbridge.proto.xiaomi`, `XiaomiProto`).

Because of this lineage the combined work is licensed AGPL-3.0-or-later
(see `LICENSE`). Per-file KDoc headers above each ported file carry the same
attribution. Upstream: https://codeberg.org/Freeyourgadget/Gadgetbridge

## Komoot BLE Connect spec (komoot/BLEConnect) — Apache-2.0

Reference only: the public direction-arrow table and distance-rounding rule
informed `TurnType`/parsing design. No code copied.
Upstream: https://github.com/luissancar/BLEConnect

## Libraries (dependencies, not shipped as source)

- androidx.core / appcompat, com.google.android.material — Apache-2.0
- protobuf-javalite + protoc — BSD-3-Clause
- Bouncy Castle bcprov-jdk18on — MIT-style BC licence
- Gradle, Android Gradle Plugin, Kotlin — Apache-2.0
