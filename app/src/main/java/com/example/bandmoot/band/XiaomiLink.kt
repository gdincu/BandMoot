package com.example.bandmoot.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import com.example.bandmoot.R
import com.google.protobuf.ByteString
import nodomain.freeyourgadget.gadgetbridge.proto.xiaomi.XiaomiProto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal Xiaomi BLE link for Mi Band 8 Active (nav-only).
 *
 * Ported call-flow from Gadgetbridge (AGPL-3.0-or-later):
 * - `XiaomiBleSupport.initializeDevice` (find FE95 service, MTU 512 request,
 *   notify on, then auth handshake because the set `isEncrypted`)
 * - `XiaomiAuthService` (plaintext auth packets until CMD_AUTH confirms)
 * - `XiaomiNotificationService.onNotification/onDeleteNotification`
 *   (Command type 7, subtypes 0/1), its icon query/request (7/16, 7/15) and
 *   `XiaomiDataUploadService` (type 50 icon upload on characteristic 0x55)
 *
 * Plain Android BluetoothGatt, no Gadgetbridge framework. Single-threaded op
 * queue; every blocking step has a timeout so a stuck band can't hang the service.
 *
 * UNTESTED AGAINST HARDWARE: framing/crypto are line-faithful ports, but the
 * first live run must be done with logcat open (see Step3 notes in reply).
 */
@SuppressLint("MissingPermission") // caller guards BLUETOOTH_CONNECT/SCAN (MainActivity + manifest)
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
    private var charUpload: BluetoothGattCharacteristic? = null // 0x55: icon-upload parts and their acks
    private var keys: XiaomiCrypto.SessionKeys? = null
    private var encIndex = 1 // 0 is reserved for the auth phase (Characteristic.reset)
    @Volatile private var lastSentId: Int? = null
    private var onReadyListener: (() -> Unit)? = null
    private var pendingIcon: XiaomiNotificationIcon.Upload? = null // waiting for the band's DataUploadAck

    // pending single-write ack / chunked handshake latches
    @Volatile private var ackLatch: CountDownLatch? = null
    @Volatile private var writeLatch: CountDownLatch? = null // one outstanding GATT write
    @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS // ATT result of last write
    @Volatile private var ackResult = -1
    @Volatile private var chunkLatch: CountDownLatch? = null // chunked-start acked
    @Volatile private var chunkEndLatch: CountDownLatch? = null // all chunks acked
    @Volatile private var authNonce: ByteArray? = null
    @Volatile private var authReplyLatch: CountDownLatch? = null
    @Volatile private var authConfirmed = false
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
                val device: BluetoothDevice =
                    XiaomiUuidsHelper.adapter(context).getRemoteDevice(mac)
                log("Connecting to $mac …")
                connectedLatch = CountDownLatch(1)
                gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(context, false, gattCallback)
                }
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

    /**
     * Sends/updates the ongoing nav notification. Safe to call before READY (drops + logs).
     *
     * Re-sending the same [id] updates the card in place. We deliberately do NOT
     * dismiss before sending (Gadgetbridge's XiaomiNotificationService only dismisses
     * when the phone-side notification is removed): the dismiss raced the re-add, and
     * same-id re-adds right after a dismiss were ignored by the band, so the card was
     * never visible.
     */
    fun sendNav(title: String, body: String, id: Int = NAV_ID, key: String? = null) {
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
                if (key != null) {
                    n3.key = key
                    n3.openOnPhone = false
                }
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
                log("Nav sent: $title (key=${key != null})")
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

    /**
     * Band-side "screen on on notifications" setting (Gadgetbridge
     * PREF_SCREEN_ON_ON_NOTIFICATIONS): wakes the band screen whenever a
     * notification arrives. Unsupported firmware just NACKs, caller tolerates it.
     */
    private fun setScreenOnOnNotifications(enabled: Boolean) {
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(NOTIF_TYPE)
            .setSubtype(CMD_SCREEN_ON_ON_NOTIFICATIONS_SET)
            .setNotification(
                XiaomiProto.Notification.newBuilder().setScreenOnOnNotifications(enabled),
            )
            .build()
        writeCommand(cmd.toByteArray(), "screen-on-on-notifications=$enabled")
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

    // ---------- internals ----------

    @Volatile private var connectedLatch = CountDownLatch(1)

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

        // Protocol-ack latches are signalled HERE on the binder thread, not on
        // `io`: `io` is blocked inside writeCommand awaiting these latches
        // during auth, so queueing the signal behind `io` deadlocks and always
        // times out ("setup: auth step 1: no ack (result=-1)"). Only the heavy
        // decrypt/dispatch goes to `io`.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            signalProtocolAcks(value)
            if (characteristic.uuid == XiaomiProtocol.CHAR_DATA_UPLOAD) {
                log("rx 0x55 ${hex(value)}") // upload acks only, never commands
                return
            }
            io.execute {
                runCatching { onIncomingRouted(value) }.onFailure { fail("incoming: ${it.message}") }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val v = characteristic.value ?: return
            signalProtocolAcks(v)
            if (characteristic.uuid == XiaomiProtocol.CHAR_DATA_UPLOAD) {
                log("rx 0x55 ${hex(v)}")
                return
            }
            io.execute {
                runCatching { onIncomingRouted(v) }
                    .onFailure { fail("incoming: ${it.message}") }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            // ATT-level ack: unblocks sendRaw so only one GATT op is ever in flight.
            writeStatus = status
            writeLatch?.countDown()
        }
    }

    @Volatile private var mtuLatch = CountDownLatch(1)
    @Volatile private var notifyLatch = CountDownLatch(1)

    private fun onServicesReady(g: BluetoothGatt) {
        val svc = g.getService(XiaomiProtocol.SERVICE_FE95)
        if (svc == null) {
            fail("FE95 service not found"); return
        }
        val read = svc.getCharacteristic(XiaomiProtocol.CHAR_COMMAND_READ)
        val write = svc.getCharacteristic(XiaomiProtocol.CHAR_COMMAND_WRITE)
        val activity = svc.getCharacteristic(XiaomiProtocol.CHAR_ACTIVITY_DATA)
        val upload = svc.getCharacteristic(XiaomiProtocol.CHAR_DATA_UPLOAD)
        if (read == null || write == null) {
            fail("51/52 characteristics missing"); return
        }
        charWrite = write
        charUpload = upload

        mtuLatch = CountDownLatch(1)
        g.requestMtu(512)
        mtuLatch.await(5, TimeUnit.SECONDS) // proceed with 247 default on timeout

        for (c in listOf(write, read, activity).filterNotNull()) {
            if (!enableNotify(g, c)) {
                fail("notify enable failed for ${c.uuid}"); return
            }
        }
        // Icon-upload acks come back on 0x55. Optional: a failure only disables icon uploads.
        if (upload != null && !enableNotify(g, upload)) {
            log("Icon upload channel: notify failed, notification icons disabled")
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
        authConfirmed = false
        log("Auth step 1: sending phone nonce…")
        writeCommand(XiaomiCrypto.buildNonceCommand(phoneNonce).toByteArray(), "auth step 1")
        // step 2+3 continue in onIncoming when the WatchNonce arrives
    }

    /**
     * Fast path on the BLE binder thread: unblock writeCommand immediately.
     * Must never do GATT writes or touch `io` here.
     */
    private fun signalProtocolAcks(value: ByteArray) {
        when (val msg = XiaomiFrame.decode(value)) {
            is XiaomiFrame.Incoming.Ack -> {
                ackResult = msg.result
                ackLatch?.countDown()
            }
            XiaomiFrame.Incoming.ChunkAckStart -> chunkLatch?.countDown()
            XiaomiFrame.Incoming.ChunkAckEnd -> chunkEndLatch?.countDown()
            XiaomiFrame.Incoming.ChunkNack -> {
                ackResult = CHUNK_NACK
                chunkEndLatch?.countDown()
                ackLatch?.countDown()
            }
            is XiaomiFrame.Incoming.ChunkResend -> {
                pendingResend = msg.indices
                chunkLatch?.countDown() // wake sender to resend listed chunks
            }
            else -> Unit // Single/ChunkedStart/Chunk handled on `io`
        }
    }

    /** Heavy path on `io`: ack incoming payloads, decrypt, dispatch commands. */
    private fun onIncomingRouted(value: ByteArray) {
        when (val msg = XiaomiFrame.decode(value)) {
            is XiaomiFrame.Incoming.Ack -> {
                log("rx ack result=${msg.result}")
                return // latch already signalled on the binder thread
            }
            XiaomiFrame.Incoming.ChunkAckStart -> {
                log("rx chunk-start-ack")
                return
            }
            XiaomiFrame.Incoming.ChunkAckEnd -> {
                log("rx chunk-end-ack")
                return
            }
            XiaomiFrame.Incoming.ChunkNack,
            is XiaomiFrame.Incoming.ChunkResend,
            is XiaomiFrame.Incoming.MissingChunks,
            -> {
                if (msg is XiaomiFrame.Incoming.MissingChunks) sendMissing(msg.indices)
                return
            }
            else -> onIncoming(value)
        }
    }

    private fun onIncoming(value: ByteArray) {
        when (val msg = XiaomiFrame.decode(value)) {
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
            is XiaomiFrame.Incoming.Ack -> {
                log("rx ack result=${msg.result}")
                ackResult = msg.result
                ackLatch?.countDown()
            }
            XiaomiFrame.Incoming.ChunkAckStart -> {
                log("rx chunk-start-ack")
                chunkLatch?.countDown()
            }
            XiaomiFrame.Incoming.ChunkAckEnd -> {
                log("rx chunk-end-ack")
                chunkEndLatch?.countDown()
            }
            XiaomiFrame.Incoming.ChunkNack -> {
                chunkEndLatch?.countDown()
                ackLatch?.countDown()
            }
            is XiaomiFrame.Incoming.ChunkResend -> {
                pendingResend = msg.indices
                chunkLatch?.countDown() // wake sender to resend listed chunks
            }
            is XiaomiFrame.Incoming.MissingChunks -> sendMissing(msg.indices)
            is XiaomiFrame.Incoming.Unknown -> log("Unknown packet ${value.size}B")
        }
    }

    @Volatile private var pendingResend: ShortArray? = null

    private fun onCommand(payload: ByteArray) {
        val cmd = runCatching { XiaomiProto.Command.parseFrom(payload) }.getOrElse {
            log("Unparseable command ${payload.size}B"); return
        }
        log("rx cmd type=${cmd.type} subtype=${cmd.subtype} ${payload.size}B")
        when (cmd.type) {
            XiaomiCrypto.AUTH_TYPE -> when (cmd.subtype) {
                XiaomiCrypto.CMD_NONCE -> { // WatchNonce
                    val phoneNonce = authNonce ?: return
                    log("Auth step 2: watch nonce received, verifying…")
                    val result = XiaomiCrypto.handleWatchNonce(secretKey, phoneNonce, cmd.auth.watchNonce)
                    if (result == null) {
                        fail("Auth FAILED: band HMAC mismatch — auth key is wrong")
                        return
                    }
                    keys = result.first
                    authReplyLatch = CountDownLatch(1)
                    writeCommand(result.second.toByteArray(), "auth step 3")
                }
                XiaomiCrypto.CMD_AUTH -> {
                    authConfirmed = true
                    authReplyLatch?.countDown()
                    state.set(State.READY)
                    log("Authenticated. Link READY.")
                    // Wake the band screen whenever a notification (nav card) arrives.
                    // Band-side persistent setting, as in Gadgetbridge's
                    // PREF_SCREEN_ON_ON_NOTIFICATIONS; must not kill the link if it fails.
                    runCatching { setScreenOnOnNotifications(true) }
                        .onFailure { log("screen-on-on-notifications failed: ${it.message}") }
                    onReadyListener?.invoke()
                }
                else -> log("Auth subtype ${cmd.subtype} ignored")
            }
            NOTIF_TYPE -> onNotificationCommand(cmd)
            DATA_UPLOAD_TYPE -> onDataUploadCommand(cmd)
            else -> log("Cmd type=${cmd.type} subtype=${cmd.subtype} ignored")
        }
    }

    // ---------- notification icon: the band asks for the Komoot icon, we upload it ----------

    /** Band->phone notification commands: icon query (16) and icon request (15). */
    private fun onNotificationCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CMD_NOTIF_ICON_QUERY -> guardIcon("icon query") {
                onIconQuery(cmd.notification.notificationIconQuery.getPackage())
            }
            CMD_NOTIF_ICON_REQUEST -> {
                if (cmd.notification.hasNotificationIconRequest()) {
                    guardIcon("icon request") { onIconRequest(cmd.notification.notificationIconRequest) }
                }
            }
            else -> log("Band->phone notification cmd subtype=${cmd.subtype} (ignored)")
        }
    }

    /**
     * Band asks whether we have an icon for [pkg] (it may send a truncated name). Komoot is the
     * only app we notify for, so answer for any name that prefixes it, echoing the name as sent.
     */
    private fun onIconQuery(pkg: String) {
        if (pkg.isEmpty() || !KOMOOT_PACKAGE.startsWith(pkg)) {
            log("Icon query for '$pkg' ignored")
            return
        }
        log("Icon query for $pkg: replying")
        val reply = XiaomiProto.Command.newBuilder()
            .setType(NOTIF_TYPE)
            .setSubtype(CMD_NOTIF_ICON_REQUEST) // phone->band reply reuses subtype 15
            .setNotification(
                XiaomiProto.Notification.newBuilder().setNotificationIconReply(
                    XiaomiProto.NotificationIconPackage.newBuilder().setPackage(pkg),
                ),
            )
            .build()
        writeCommand(reply.toByteArray(), "icon reply")
    }

    /** Band wants the icon at [req].size px in [req].pixelFormat: encode it and start the upload. */
    private fun onIconRequest(req: XiaomiProto.NotificationIconRequest) {
        if (req.status != 0) {
            log("Icon request status=${req.status} ignored")
            return
        }
        val upload = komootIcon(req.size, req.pixelFormat)
        if (upload == null) {
            log("Cannot encode Komoot icon (size=${req.size}, format=${req.pixelFormat})")
            return
        }
        log("Uploading Komoot icon ${req.size}px, format ${req.pixelFormat}, ${upload.size} B")
        pendingIcon = upload
        val cmd = XiaomiProto.Command.newBuilder()
            .setType(DATA_UPLOAD_TYPE)
            .setSubtype(CMD_UPLOAD_START)
            .setDataUpload(
                XiaomiProto.DataUpload.newBuilder().setDataUploadRequest(
                    XiaomiProto.DataUploadRequest.newBuilder()
                        .setType(XiaomiNotificationIcon.TYPE_NOTIFICATION_ICON)
                        .setMd5Sum(ByteString.copyFrom(upload.md5))
                        .setSize(upload.size),
                ),
            )
            .build()
        writeCommand(cmd.toByteArray(), "icon upload request")
    }

    /** Data-upload service (command type 22): we only expect the DataUploadAck (subtype 0). */
    private fun onDataUploadCommand(cmd: XiaomiProto.Command) {
        if (cmd.subtype != CMD_UPLOAD_START || !cmd.dataUpload.hasDataUploadAck()) {
            log("Data upload cmd subtype=${cmd.subtype} ignored")
            return
        }
        guardIcon("icon upload") { onDataUploadAck(cmd.dataUpload.dataUploadAck) }
    }

    /** Band accepted the request: send the body as parts on 0x55; the band acks each part. */
    private fun onDataUploadAck(ack: XiaomiProto.DataUploadAck) {
        val upload = pendingIcon
        pendingIcon = null
        if (upload == null) {
            log("Data upload ack without a pending icon; ignored")
            return
        }
        if (ack.unknown2 != 0 || ack.resumePosition != 0) {
            log("Icon upload refused by band (unknown2=${ack.unknown2}, resume=${ack.resumePosition})")
            return
        }
        val chunkSize = if (ack.hasChunkSize()) ack.chunkSize else XiaomiNotificationIcon.DEFAULT_CHUNK_SIZE
        val parts = XiaomiNotificationIcon.parts(upload.body, chunkSize)
        parts.forEachIndexed { i, part -> writeDataPart(part, "icon part ${i + 1}/${parts.size}") }
        log("Notification icon uploaded (${parts.size} parts)")
    }

    /** The bundled Komoot logo, scaled to [size] px and encoded in [pixelFormat]; null if unsupported. */
    private fun komootIcon(size: Int, pixelFormat: Int): XiaomiNotificationIcon.Upload? {
        if (size !in MIN_ICON_PX..MAX_ICON_PX) return null
        val src = BitmapFactory.decodeResource(context.resources, R.drawable.ic_komoot)
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val argb = IntArray(size * size)
        scaled.getPixels(argb, 0, size, 0, 0, size, size)
        return XiaomiNotificationIcon.prepare(argb, pixelFormat)
    }

    /** Icon work must never fail the link: fail() would drop the band connection. */
    private inline fun guardIcon(task: String, block: () -> Unit) {
        if (state.get() != State.READY) {
            log("$task skipped: link not READY")
            return
        }
        try {
            block()
        } catch (t: Throwable) {
            log("$task failed: ${t.message}")
        }
    }

    /** Encrypts (once READY) and sends one Command on the command channel, single or chunked. */
    private fun writeCommand(plain: ByteArray, task: String) {
        writeMessage(plain, task, charWrite ?: throw IllegalStateException("no write char"), dataChannel = false)
    }

    /** One icon-upload part on the data-upload characteristic (0x55). */
    private fun writeDataPart(part: ByteArray, task: String) {
        writeMessage(part, task, charUpload ?: throw IllegalStateException("no upload char"), dataChannel = true)
    }

    /**
     * One framed, encrypted message on [ch]; blocks until the band acks it.
     * - command channel: each message takes the next nonce index, which is sent in the frame;
     * - data-upload channel: always nonce index 0 and no index prefix, as Gadgetbridge's
     *   XiaomiCharacteristic does with incrementNonce=false.
     */
    private fun writeMessage(plain: ByteArray, task: String, ch: BluetoothGattCharacteristic, dataChannel: Boolean) {
        val ready = state.get() == State.READY
        val sessionKeys = keys
        val nonce = if (dataChannel) 0 else encIndex
        val bytes = if (ready && sessionKeys != null) {
            XiaomiCrypto.encrypt(sessionKeys.encryptionKey, sessionKeys.encryptionNonce, nonce, plain)
        } else {
            plain // auth phase goes out in plaintext framing (Characteristic.sendNext)
        }
        val maxWrite = XiaomiFrame.maxWriteSize(mtu)
        if (!XiaomiFrame.shouldWriteChunked(bytes.size, ready, maxWrite)) {
            ackResult = -1
            ackLatch = CountDownLatch(1)
            val packet = XiaomiFrame.encodeSingle(
                bytes,
                encrypted = ready,
                encIndex = if (ready) nonce else 0,
            )
            if (ready && !dataChannel) encIndex++
            sendRaw(packet, task, ch)
            if (ackLatch!!.await(5, TimeUnit.SECONDS).not() || ackResult != 0) {
                throw IllegalStateException("$task: no ack (result=$ackResult)")
            }
            log("$task: ack ok")
            return
        }
        // chunked
        var payload = bytes
        if (ready && !dataChannel) {
            payload = XiaomiFrame.prependEncIndex(payload, encIndex)
            encIndex++
        }
        val count = XiaomiFrame.chunkCount(payload.size, maxWrite)
        ackResult = -1
        chunkLatch = CountDownLatch(1)
        sendRaw(XiaomiFrame.encodeChunkedStart(count, ready), "$task chunked-start", ch)
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
            sendRaw(XiaomiFrame.encodeChunk(idx.toInt(), payload.copyOfRange(from, to)), "$task chunk $idx", ch)
        }
        if (!chunkEndLatch!!.await(8, TimeUnit.SECONDS)) {
            throw IllegalStateException("$task: no chunk-end ack")
        }
        if (ackResult == CHUNK_NACK) throw IllegalStateException("$task: band NACKed the chunks")
        log("$task: chunks acked")
    }

    private fun sendMissing(indices: ShortArray) {
        // Band missed some of OUR chunks only during sends we track; simplest
        // correct behavior for nav payloads: the chunkEndLatch path already covers
        // resends via ChunkResend. Log and continue.
        log("Band reports missing chunks ${indices.toList()} (see resend path)")
    }

    private fun sendRaw(bytes: ByteArray, task: String, target: BluetoothGattCharacteristic? = charWrite) {
        val g = gatt ?: throw IllegalStateException("no gatt")
        val ch = target ?: throw IllegalStateException("no write char")
        // 0x0052 exposes write-without-response only: write-with-response gets
        // ATT status 3 (observed). Pick per characteristic properties so other
        // firmwares with plain WRITE keep working.
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
        // Android allows a single outstanding GATT op: arm the ATT latch first,
        // then retry busy rejections (rc 201/202) instead of crashing on them.
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
        // Wait for onCharacteristicWrite so the next op never collides with this one.
        // The ATT status is decisive: 0 = band accepted the bytes, 3 = write not
        // permitted, 5 = insufficient authentication (band demands BLE bonding),
        // 133 = link loss. Anything nonzero means no protocol reply can follow.
        writeLatch?.await(5, TimeUnit.SECONDS)
        if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
            throw IllegalStateException("$task: ATT write rejected, status=$writeStatus")
        }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }

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
        const val CMD_NOTIF_ICON_REQUEST = 15 // band->phone request; the phone's reply reuses subtype 15
        const val CMD_NOTIF_ICON_QUERY = 16
        const val DATA_UPLOAD_TYPE = 22 // command type of the data-upload service
        const val CMD_UPLOAD_START = 0 // DataUploadRequest (phone) and DataUploadAck (band)
        const val CHUNK_NACK = -2 // ackResult sentinel: band NACKed a chunked message
        const val MIN_ICON_PX = 8
        const val MAX_ICON_PX = 256
        const val NAV_ID = 1001
    }
}

private object XiaomiUuidsHelper {
    fun adapter(context: Context) =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
}
