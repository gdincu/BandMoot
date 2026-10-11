package com.gdincu.bandmoot.band

import java.util.UUID

/** Xiaomi BLE identity for Mi Band 8 Active (service FE95). */
object XiaomiProtocol {
    val SERVICE_FE95: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
    val CHAR_COMMAND_READ: UUID = UUID.fromString("00000051-0000-1000-8000-00805f9b34fb")
    val CHAR_COMMAND_WRITE: UUID = UUID.fromString("00000052-0000-1000-8000-00805f9b34fb")
    val CHAR_ACTIVITY_DATA: UUID = UUID.fromString("00000053-0000-1000-8000-00805f9b34fb")

    val DEVICE_NAME_REGEX = Regex("^Xiaomi( Smart)? Band 8 Active [A-Z0-9]{4}$")

    /** 32 hex chars, optionally 0x-prefixed. */
    fun isValidAuthKey(key: String): Boolean {
        val k = key.trim()
        return k.matches(Regex("^[0-9a-fA-F]{32}$")) ||
            k.matches(Regex("^0x[0-9a-fA-F]{32}$"))
    }
}
