package com.example.bandmoot.band

import android.os.Build
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Xiaomi encrypted-handshake crypto, ported from Gadgetbridge (AGPL-3.0-or-later):
 * - `service/devices/xiaomi/XiaomiAuthService.java`
 *   (computeAuthStep3Hmac, hmacSHA256, encrypt/decrypt, buildNonceCommand,
 *   handleWatchNonce/AuthDeviceInfo)
 *
 * Key schedule (all from the 16-byte BLE auth key, hex in app prefs):
 * 1. phone sends random 16-byte phoneNonce: Command{type:1, subtype:26}
 * 2. band replies WatchNonce{nonce, hmac}: Command{type:1, subtype:26}
 * 3. HKDF-ish ("miwear-auth", key = HMAC(phoneNonce||watchNonce, secretKey))
 *    -> 64 bytes = decryptionKey[16] | encryptionKey[16] |
 *                   decryptionNonce[4] | encryptionNonce[4]
 * 4. verify HMAC-SHA256(decryptionKey, watchNonce||phoneNonce) == watch hmac
 * 5. reply AuthStep3{hmac(encryptionKey, phoneNonce||watchNonce),
 *    AES-CCM-32(AuthDeviceInfo)}: Command{type:1, subtype:27}
 * 6. band confirms: Command{type:1, subtype:27} -> link is INITIALIZED.
 *
 * Session crypto afterwards: AES-CCM with 32-bit tag,
 * nonce12 = nonce4 || 0x00000000 || indexLE (index 0 reserved for auth,
 * first session index is 1; see XiaomiFrame / XiaomiCharacteristic.reset()).
 */
object XiaomiCrypto {

    const val AUTH_TYPE = 1
    const val CMD_NONCE = 26
    const val CMD_AUTH = 27

    data class SessionKeys(
        val encryptionKey: ByteArray,
        val decryptionKey: ByteArray,
        val encryptionNonce: ByteArray, // 4 bytes
        val decryptionNonce: ByteArray, // 4 bytes
    )

    fun randomNonce(size: Int = 16): ByteArray =
        ByteArray(size).also { SecureRandom().nextBytes(it) }

    fun hexToSecretKey(authKeyHex: String): ByteArray {
        val clean = authKeyHex.trim().removePrefix("0x").removePrefix("0X")
        require(clean.length >= 32 && clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Auth key must be 32 hex chars"
        }
        val out = ByteArray(16)
        for (i in 0 until 16) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun buildNonceCommand(phoneNonce: ByteArray): XiaomiProto.Command =
        XiaomiProto.Command.newBuilder()
            .setType(AUTH_TYPE)
            .setSubtype(CMD_NONCE)
            .setAuth(
                XiaomiProto.Auth.newBuilder()
                    .setPhoneNonce(
                        XiaomiProto.PhoneNonce.newBuilder()
                            .setNonce(com.google.protobuf.ByteString.copyFrom(phoneNonce)),
                    ),
            )
            .build()

    /**
     * Verifies the band's WatchNonce and derives session keys.
     * @return keys + step-3 reply command, or null when the band HMAC mismatches
     * (wrong auth key -> caller must abort, like Gadgetbridge's disconnect).
     */
    fun handleWatchNonce(
        secretKey: ByteArray,
        phoneNonce: ByteArray,
        watchNonce: XiaomiProto.WatchNonce,
    ): Pair<SessionKeys, XiaomiProto.Command>? {
        val watchNonceBytes = watchNonce.nonce.toByteArray()
        val step2 = computeAuthStep3Hmac(secretKey, phoneNonce, watchNonceBytes)

        val keys = SessionKeys(
            decryptionKey = step2.copyOfRange(0, 16),
            encryptionKey = step2.copyOfRange(16, 32),
            decryptionNonce = step2.copyOfRange(32, 36),
            encryptionNonce = step2.copyOfRange(36, 40),
        )

        val confirm = hmacSha256(keys.decryptionKey, watchNonceBytes + phoneNonce)
        if (!confirm.contentEquals(watchNonce.hmac.toByteArray())) {
            return null // wrong auth key
        }

        val deviceInfo = XiaomiProto.AuthDeviceInfo.newBuilder()
            .setUnknown1(0)
            .setPhoneApiLevel(Build.VERSION.SDK_INT.toFloat())
            .setPhoneName(Build.MODEL)
            .setUnknown3(224)
            .setRegion(Locale.getDefault().language.take(2).uppercase(Locale.ROOT))
            .build()

        val encryptedNonces = hmacSha256(keys.encryptionKey, phoneNonce + watchNonceBytes)
        val encryptedDeviceInfo = encrypt(keys.encryptionKey, keys.encryptionNonce, 0, deviceInfo.toByteArray())

        val step3 = XiaomiProto.AuthStep3.newBuilder()
            .setEncryptedNonces(com.google.protobuf.ByteString.copyFrom(encryptedNonces))
            .setEncryptedDeviceInfo(com.google.protobuf.ByteString.copyFrom(encryptedDeviceInfo))
            .build()

        val reply = XiaomiProto.Command.newBuilder()
            .setType(AUTH_TYPE)
            .setSubtype(CMD_AUTH)
            .setAuth(XiaomiProto.Auth.newBuilder().setAuthStep3(step3))
            .build()
        return keys to reply
    }

    /** HKDF-like expansion from Gadgetbridge computeAuthStep3Hmac (64 bytes out). */
    fun computeAuthStep3Hmac(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
        val label = "miwear-auth".toByteArray()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(phoneNonce + watchNonce, "HmacSHA256"))
        val hmacKeyBytes = mac.doFinal(secretKey)
        mac.init(SecretKeySpec(hmacKeyBytes, "HmacSHA256"))

        val output = ByteArray(64)
        var tmp = ByteArray(0)
        var counter: Byte = 1
        var i = 0
        while (i < output.size) {
            mac.update(tmp)
            mac.update(label)
            mac.update(counter)
            tmp = mac.doFinal()
            for (b in tmp) {
                if (i >= output.size) break
                output[i++] = b
            }
            counter++
        }
        return output
    }

    fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }

    fun nonce12(nonce4: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(nonce4)
            .putInt(0)
            .putInt(index)
            .array()

    /** AES-CCM encrypt with 32-bit tag (matches Gadgetbridge encrypt()). */
    fun encrypt(key: ByteArray, nonce4: ByteArray, index: Int, payload: ByteArray): ByteArray {
        val cipher = newCcm(true, key, 32, nonce12(nonce4, index))
        val out = ByteArray(cipher.getOutputSize(payload.size))
        val n = cipher.processBytes(payload, 0, payload.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    /** AES-CCM decrypt with 32-bit tag (matches Gadgetbridge decrypt()). */
    fun decrypt(key: ByteArray, nonce4: ByteArray, index: Int, payload: ByteArray): ByteArray {
        val cipher = newCcm(false, key, 32, nonce12(nonce4, index))
        val out = ByteArray(cipher.getOutputSize(payload.size))
        val n = cipher.processBytes(payload, 0, payload.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    private fun newCcm(forEncrypt: Boolean, key: ByteArray, macBits: Int, nonce: ByteArray): CCMBlockCipher {
        val engine = AESEngine()
        engine.init(forEncrypt, KeyParameter(key))
        return CCMBlockCipher(engine).also {
            it.init(forEncrypt, AEADParameters(KeyParameter(key), macBits, nonce, null))
        }
    }
}
