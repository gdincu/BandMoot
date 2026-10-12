package com.gdincu.bandmoot.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Minimal Xiaomi BLE link for Mi Band 8 Active (nav-only). */
@SuppressLint("MissingPermission")
class XiaomiLink(
    private val context: Context,
    private val mac: String,
    authKeyHex: String,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private val secretKey = XiaomiCrypto.hexToSecretKey(authKeyHex)
    private val io = Executors.newSingleThreadExecutor()
    private val state = AtomicReference(State.IDLE)
    private var gatt: BluetoothGatt? = null
    private var mtu = 247
    private var charWrite: BluetoothGattCharacteristic? = null
    private var keys: XiaomiCrypto.SessionKeys? = null
    private var encIndex = 1
    @Volatile private var lastSentId: Int? = null
    private var onReadyListener: (() -> Unit)? = null

    @Volatile private var ackLatch: CountDownLatch? = null
    @Volatile private var writeLatch: CountDownLatch? = null
    @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS
    @Volatile private var ackResult = -1
    @Volatile private var chunkLatch: CountDownLatch? = null
    @Volatile private var chunkEndLatch: CountDownLatch? = null
    @Volatile private var authNonce: ByteArray? = null
    @Volatile private var connectedLatch = CountDownLatch(1)
    @Volatile private var mtuLatch = CountDownLatch(1)
    @Volatile private var notifyLatch = CountDownLatch(1)
    @Volatile private var pendingResend: ShortArray? = null
    private val reassembler = XiaomiFrame.Reassembler()

    enum class State { IDLE, CONNECTING, AUTH, READY, FAILED }

    fun setOnReadyListener(l: () -> Unit) {
        onReadyListener = l
    }

    fun state(): State = state.get()

    fun connect() {
        io.execute {
            try {
                state.set(State.CONNECTING)
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                val device: BluetoothDevice = adapter.getRemoteDevice(mac)
                log("Connecting to $mac …")
                connectedLatch = CountDownLatch(1)
                gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                if (!connectedLatch.await(15, TimeUnit.SECONDS)) {
                    fail("GATT connect timeout"); return@execute
                }
                log("GATT connected, discovering services…")
                if (!gatt!!.discoverServices()) {
                    fail("discoverServices refused"); return@execute
                }
            } catch (t: Throwable) {
                fail("connect: ${t.message}")
            }
        }
    }

    fun disconnect() {
        io.execute {
            try {
                gatt?.disconnect()
            } catch (_: Exception) {
            }
            try {
                gatt?.close()
            } catch (_: Exception) {
            }
            gatt = null
            state.set(State.IDLE)
        }
    }

    /** Sends/updates the ongoing nav notification. Re-sending the same [id] updates in place. */
    fun sendNav(title: String, body: String, id: Int = NAV_ID) {
        io.execute {
            if (state.get() != State.READY) {
                log("sendNav dropped: link not READY (${state.get()})")
                return@execute
            }
            try {
                val ts = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.ROOT).format(Date())
                val n3 = XiaomiProto.Notification3.newBuilder()
                    .setId(id)
                    .setUnknown4("")
                    .setTimestamp(ts)
                    .setPackage(KOMOOT_PACKAGE)
                    .setAppName("Komoot")
                    .setTitle(title.take(120))
                if (body.isNotEmpty()) n3.body = body.take(512)
                val cmd = XiaomiProto.Command.newBuilder()
                    .setType(NOTIF_TYPE)
                    .setSubtype(CMD_NOTIF_SEND)
                    .setNotification(
                        XiaomiProto.Notification.newBuilder()
                            .setNotification2(
                                XiaomiProto.Notification2.newBuilder().setNotification3(n3),
                            ),
                    )
                    .build()
                writeCommand(cmd.toByteArray(), "send nav $id")
                lastSentId = id
                log("Nav sent: $title")
            } catch (t: Throwable) {
                log("sendNav failed: ${t.message}")
            }
        }
    }

    /** Dismisses the last card actually sent (or [id], if given). */
    fun clearNav(id: Int = lastSentId ?: NAV_ID) {
        io.execute {
            if (state.get() != State.READY) return@execute
            try {
                sendNavDismiss(id)
                if (id == lastSentId) lastSentId = null
            } catch (t: Throwable) {
                log("clearNav failed: ${t.message}")
            }
        }
    }

    private fun setScreenOnOnNotifications() {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(NOTIF_TYPE)
            .setSubtype(CMD_SCREEN_ON_ON_NOTIFICATIONS_SET)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setScreenOnOnNotifications(true),
            )
            .build()
        writeCommand(cmd.toByteArray(), "screen-on-on-notifications=true")
    }

    private fun sendNavDismiss(id: Int) {
        if (state.get() != State.READY) {
            throw IllegalStateException("link not READY (${state.get()})")
        }
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(NOTIF_TYPE)
            .setSubtype(CMD_NOTIF_DISMISS)
            .setNotification(
                XiaomiProto.Notification.newBuilder()
                    .setNotificationDismiss(
                        XiaomiProto.NotificationDismiss.newBuilder()
                            .addNotificationId(
                                XiaomiProto.NotificationId.newBuilder()
                                    .setId(id)
                                    .setPackage(KOMOOT_PACKAGE),
                            ),
                    ),
            )
            .build()
        writeCommand(cmd.toByteArray(), "clear nav $id")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedLatch.countDown()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("GATT disconnected (status $status)")
                if (state.get() != State.IDLE) state.set(State.FAILED)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            io.execute {
                runCatching {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        throw IllegalStateException("service discovery failed: $status")
                    }
                    onServicesReady(g)
                }.onFailure { fail("setup: ${it.message}") }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtu = newMtu
                log("MTU now $newMtu (maxWrite ${XiaomiFrame.maxWriteSize(newMtu)})")
            }
            mtuLatch.countDown()
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            notifyLatch.countDown()
        }

        // Acks are signalled here on the binder thread: `io` may be blocked waiting
        // for them, so queueing behind `io` would deadlock. Heavy work goes to `io`.
        // The packet is decoded once here and the result shared with `io`.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onPacketReceived(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value ?: return
            onPacketReceived(v)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeStatus = status
            writeLatch?.countDown()
        }
    }

    private fun onServicesReady(g: BluetoothGatt) {
        val svc = g.getService(XiaomiProtocol.SERVICE_FE95)
        if (svc == null) {
            fail("FE95 service not found"); return
        }
        val read = svc.getCharacteristic(XiaomiProtocol.CHAR_COMMAND_READ)
        val write = svc.getCharacteristic(XiaomiProtocol.CHAR_COMMAND_WRITE)
        val activity = svc.getCharacteristic(XiaomiProtocol.CHAR_ACTIVITY_DATA)
        if (read == null || write == null) {
            fail("51/52 characteristics missing"); return
        }
        charWrite = write

        mtuLatch = CountDownLatch(1)
        g.requestMtu(512)
        mtuLatch.await(5, TimeUnit.SECONDS)

        for (c in listOfNotNull(write, read, activity)) {
            if (!enableNotify(g, c)) {
                fail("notify enable failed for ${c.uuid}"); return
            }
        }
        runAuth()
    }

    private fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(c, true)) return false
        val cccd = c.getDescriptor(CCCD) ?: return false
        notifyLatch = CountDownLatch(1)
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
        if (!ok) return false
        return notifyLatch.await(5, TimeUnit.SECONDS)
    }

    private fun runAuth() {
        state.set(State.AUTH)
        encIndex = 1
        val phoneNonce = XiaomiCrypto.randomNonce()
        authNonce = phoneNonce
        log("Auth step 1: sending phone nonce…")
        writeCommand(XiaomiCrypto.buildNonceCommand(phoneNonce).toByteArray(), "auth step 1")
    }

    private fun onPacketReceived(value: ByteArray) {
        val msg = XiaomiFrame.decode(value)
        signalProtocolAcks(msg)
        io.execute {
            runCatching { onIncoming(msg, value) }.onFailure { fail("incoming: ${it.message}") }
        }
    }

    private fun signalProtocolAcks(msg: XiaomiFrame.Incoming) {
        when (msg) {
            is XiaomiFrame.Incoming.Ack -> {
                ackResult = msg.result
                ackLatch?.countDown()
            }
            XiaomiFrame.Incoming.ChunkAckStart -> chunkLatch?.countDown()
            XiaomiFrame.Incoming.ChunkAckEnd -> chunkEndLatch?.countDown()
            XiaomiFrame.Incoming.ChunkNack -> {
                chunkEndLatch?.countDown()
                ackLatch?.countDown()
            }
            is XiaomiFrame.Incoming.ChunkResend -> {
                pendingResend = msg.indices
                chunkLatch?.countDown()
            }
            else -> Unit
        }
    }

    private fun onIncoming(msg: XiaomiFrame.Incoming, value: ByteArray) {
        when (msg) {
            is XiaomiFrame.Incoming.Ack,
            XiaomiFrame.Incoming.ChunkAckStart,
            XiaomiFrame.Incoming.ChunkAckEnd,
            XiaomiFrame.Incoming.ChunkNack,
            is XiaomiFrame.Incoming.ChunkResend,
            -> return
            is XiaomiFrame.Incoming.Single -> {
                log("rx single ${if (msg.encrypted) "enc" else "plain"} ${msg.payload.size}B")
                sendRaw(XiaomiFrame.PAYLOAD_ACK, "ack")
                val payload = if (msg.encrypted) {
                    val k = keys ?: return
                    XiaomiCrypto.decrypt(k.decryptionKey, k.decryptionNonce, 0, msg.payload)
                } else {
                    msg.payload
                }
                onCommand(payload)
            }
            is XiaomiFrame.Incoming.ChunkedStart -> {
                log("rx chunked-start enc=${msg.encrypted} count=${msg.count}")
                sendRaw(XiaomiFrame.CHUNK_START_ACK, "chunk start ack")
                reassembler.feed(msg)
            }
            is XiaomiFrame.Incoming.Chunk -> {
                log("rx chunk idx=${msg.index} ${msg.bytes.size}B")
                val full = reassembler.feed(msg) ?: return
                sendRaw(XiaomiFrame.CHUNK_END_ACK, "chunk end ack")
                val k = keys
                val payload = if (k != null) {
                    runCatching {
                        XiaomiCrypto.decrypt(k.decryptionKey, k.decryptionNonce, 0, full)
                    }.getOrElse { full }
                } else {
                    full
                }
                onCommand(payload)
            }
            else -> log("Unknown packet ${value.size}B")
        }
    }

    private fun onCommand(payload: ByteArray) {
        val cmd = runCatching { XiaomiProto.Command.parseFrom(payload) }.getOrElse {
            log("Unparseable command ${payload.size}B"); return
        }
        when (cmd.type) {
            XiaomiCrypto.AUTH_TYPE -> when (cmd.subtype) {
                XiaomiCrypto.CMD_NONCE -> {
                    val phoneNonce = authNonce ?: return
                    log("Auth step 2: watch nonce received, verifying…")
                    val result = XiaomiCrypto.handleWatchNonce(secretKey, phoneNonce, cmd.auth.watchNonce)
                    if (result == null) {
                        fail("Auth FAILED: band HMAC mismatch — auth key is wrong")
                        return
                    }
                    keys = result.first
                    writeCommand(result.second.toByteArray(), "auth step 3")
                }
                XiaomiCrypto.CMD_AUTH -> {
                    state.set(State.READY)
                    log("Authenticated. Link READY.")
                    runCatching { setScreenOnOnNotifications() }
                        .onFailure { log("screen-on-on-notifications failed: ${it.message}") }
                    onReadyListener?.invoke()
                }
                else -> log("Auth subtype ${cmd.subtype} ignored")
            }
            NOTIF_TYPE -> log("Band->phone notification cmd subtype=${cmd.subtype} (ignored)")
            else -> log("Cmd type=${cmd.type} subtype=${cmd.subtype} ignored")
        }
    }

    private fun writeCommand(plain: ByteArray, task: String) {
        val ready = state.get() == State.READY
        val sessionKeys = keys
        val bytes = if (ready && sessionKeys != null) {
            XiaomiCrypto.encrypt(sessionKeys.encryptionKey, sessionKeys.encryptionNonce, encIndex, plain)
        } else {
            plain
        }
        val maxWrite = XiaomiFrame.maxWriteSize(mtu)
        if (!XiaomiFrame.shouldWriteChunked(bytes.size, ready, maxWrite)) {
            ackResult = -1
            ackLatch = CountDownLatch(1)
            val packet = XiaomiFrame.encodeSingle(
                bytes,
                encrypted = ready,
                encIndex = if (ready) encIndex else 0,
            )
            if (ready) encIndex++
            sendRaw(packet, task)
            if (ackLatch!!.await(5, TimeUnit.SECONDS).not() || ackResult != 0) {
                throw IllegalStateException("$task: no ack (result=$ackResult)")
            }
            log("$task: ack ok")
            return
        }
        var payload = bytes
        if (ready) {
            payload = XiaomiFrame.prependEncIndex(payload, encIndex)
            encIndex++
        }
        val count = XiaomiFrame.chunkCount(payload.size, maxWrite)
        chunkLatch = CountDownLatch(1)
        sendRaw(XiaomiFrame.encodeChunkedStart(count, ready), "$task chunked-start")
        if (!chunkLatch!!.await(5, TimeUnit.SECONDS)) {
            throw IllegalStateException("$task: no chunk-start ack")
        }
        val resend = pendingResend
        pendingResend = null
        val indices = if (resend != null) resend.toList() else (1..count).map { it.toShort() }
        chunkEndLatch = CountDownLatch(1)
        for (idx in indices) {
            val from = (idx - 1) * (maxWrite - 2)
            val to = minOf(idx * (maxWrite - 2), payload.size)
            sendRaw(XiaomiFrame.encodeChunk(idx.toInt(), payload.copyOfRange(from, to)), "$task chunk $idx")
        }
        if (!chunkEndLatch!!.await(8, TimeUnit.SECONDS)) {
            throw IllegalStateException("$task: no chunk-end ack")
        }
        log("$task: chunks acked")
    }

    private fun sendRaw(bytes: ByteArray, task: String) {
        val g = gatt ?: throw IllegalStateException("no gatt")
        val ch = charWrite ?: throw IllegalStateException("no write char")
        val writeType = if (ch.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        ch.writeType = writeType
        @Suppress("DEPRECATION")
        ch.value = bytes
        writeStatus = BluetoothGatt.GATT_SUCCESS
        writeLatch = CountDownLatch(1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var rc = 0
            for (attempt in 1..3) {
                rc = g.writeCharacteristic(ch, bytes, writeType)
                if (rc == 0) break
                log("$task: write rc=$rc, retry $attempt/3")
                Thread.sleep(300)
            }
            if (rc != 0) throw IllegalStateException("$task: write rc=$rc")
        } else {
            var ok = false
            for (attempt in 1..3) {
                @Suppress("DEPRECATION")
                ok = g.writeCharacteristic(ch)
                if (ok) break
                log("$task: write refused, retry $attempt/3")
                Thread.sleep(300)
            }
            if (!ok) throw IllegalStateException("$task: write refused")
        }
        writeLatch?.await(5, TimeUnit.SECONDS)
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("$task: ATT write rejected, status=$writeStatus")
        }
    }

    private fun fail(msg: String) {
        Log.w(TAG, msg)
        log(msg)
        state.set(State.FAILED)
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "XiaomiLink"
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val KOMOOT_PACKAGE = "de.komoot.android"
        const val NOTIF_TYPE = 7
        const val CMD_NOTIF_SEND = 0
        const val CMD_NOTIF_DISMISS = 1
        const val CMD_SCREEN_ON_ON_NOTIFICATIONS_SET = 7
        const val NAV_ID = 1001
    }
}
