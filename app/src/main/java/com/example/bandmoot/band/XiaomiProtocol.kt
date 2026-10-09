package com.example.bandmoot.band

import java.util.UUID

/**
 * Xiaomi BLE identity for Mi Band 8 Active.
 *
 * Sources (facts, not copied code):
 * - Gadgetbridge `devices/xiaomi/miband8active/MiBand8ActiveCoordinator.java`:
 *   BLE name `^Xiaomi( Smart)? Band 8 Active [A-Z0-9]{4}$`, DeviceType MIBAND8ACTIVE,
 *   handled by `service/devices/xiaomi/XiaomiSupport` (new Xiaomi stack, NOT Huami milili).
 * - Gadgetbridge `service/devices/xiaomi/XiaomiUuids.java` (encrypted set shared with
 *   Mi Band 8): service FE95 + chars 0051/0052/0053/0055, AES-CCM handshake in
 *   `XiaomiAuthService` using the 32-hex-char BLE auth key (get key via Mi Fitness
 *   sniff / freemyband-style tool; Gadgetbridge prompts REQUIRE_KEY).
 *
 * Full encrypted-protobuf transport (XiaomiProto.Command, chunking in
 * `XiaomiCharacteristic`, MTU 247/512) is Gadgetbridge AGPL code: do NOT copy it
 * into a proprietary app. Either keep this app AGPL-compatible or reimplement the
 * handshake from the protocol description in `XiaomiAuthService.java`.
 */
object XiaomiProtocol {
    val SERVICE_FE95: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    val CHAR_COMMAND_READ: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
    val CHAR_COMMAND_WRITE: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")
    val CHAR_ACTIVITY_DATA: UUID = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb")
    val CHAR_DATA_UPLOAD: UUID = UUID.fromString("00000055-0000-1000-8000-00805f9b34fb")

    val DEVICE_NAME_REGEX = Regex("^Xiaomi( Smart)? Band 8 Active [A-Z0-9]{4}$")

    /** 32 hex chars (or 0x-prefixed); validated like Gadgetbridge `validateAuthKey`. */
    fun isValidAuthKey(key: String): Boolean {
        val k = key.trim()
        return k.matches(Regex("^[0-9a-fA-F]{32}$")) ||
            k.matches(Regex("^0x[0-9a-fA-F]{32}$"))
    }
}
