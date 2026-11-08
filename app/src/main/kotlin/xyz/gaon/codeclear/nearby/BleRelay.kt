package xyz.gaon.codeclear.nearby

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import xyz.gaon.codeclear.toLowerHex

/**
 * Anonymous one-hop transport. All platform calls run behind current permission
 * checks on one worker; revocation, close, and late callbacks cannot publish another receipt.
 */
@SuppressLint("MissingPermission")
class BleRelay(
    private val context: Context,
    private val sender: Boolean,
    private val known: (String) -> Boolean,
    private val received: (NearbyOriginal) -> Unit,
    private val status: (NearbyStatus) -> Unit,
    private val diagnostic: (String, Int) -> Unit = { _, _ -> }
) : NearbyRadio {
    private data class Export(val bytes: ByteArray, val expires: Long)
    private val thread = HandlerThread("nearby-radio").apply { start() }
    private val worker = Handler(thread.looper)
    private val closed = AtomicBoolean()
    private val queued = AtomicInteger()
    private val scanPending = AtomicBoolean()
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter
    private val exports = linkedMapOf<Long, Export>()
    private val cooldown = linkedMapOf<String, Long>()
    private val attempts = ArrayDeque<Long>()
    private val random = SecureRandom()
    private var token = ByteArray(8)
    private var tokenHex = token.toLowerHex()
    private var scanning = false
    private var scanFailures = 0
    private var diagnosticSightings = 0
    private var advertising = false
    private var server: BluetoothGattServer? = null
    private val serverClose = DeferredGattClose {
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        worker.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
    private var serverReady = false
    private var peer: BluetoothDevice? = null
    private var peerSession = 0L
    private var snapshot = emptyList<Export>()
    private var serverMtu = 23
    private var request: RelayBlockRequest? = null
    private var requests = 0
    private var client: BluetoothGatt? = null
    private var clientMtu = 23
    private var discovering = false
    private var entries = emptyList<RelayEntry>()
    private var cursor = 0
    private var assembly: RelayAssembly? = null
    private var control: BluetoothGattCharacteristic? = null
    private var data: BluetoothGattCharacteristic? = null
    private var receiveStatus = NearbyStatus.RECEIVE_ONLY

    override fun start() = post {
        val current = NearbyAccess.status(context, sender)
        if (current !in setOf(NearbyStatus.LISTENING, NearbyStatus.RECEIVE_ONLY)) {
            status(current)
            return@post
        }
        receiveStatus = current
        if (sender && adapter.isMultipleAdvertisementSupported) openServer()
        startScanning()
        expire()
    }

    private fun startScanning() {
        if (scanning || closed.get()) return
        val available = NearbyAccess.status(context, sender)
        if (available !in setOf(NearbyStatus.LISTENING, NearbyStatus.RECEIVE_ONLY)) {
            status(available)
            return
        }
        val delay = SCAN_STARTS.reserveDelay(SystemClock.elapsedRealtime())
        if (delay > 0) {
            status(NearbyStatus.COOLDOWN)
            later(delay, ::startScanning)
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: error("BLE scanning unavailable")
        val filter = ScanFilter.Builder().setServiceData(
            SERVICE,
            byteArrayOf(1),
            byteArrayOf(-1)
        ).build()
        scanner.startScan(
            listOf(filter),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
            scan
        )
        scanning = true
        diagnostic("scan-ready", 0)
        status(if (scanning) receiveStatus else NearbyStatus.COOLDOWN)
    }

    /** Only newly captured, reviewed local records reach this boundary; no history is read here. */
    override fun publish(
        id: Long,
        title: String,
        body: String,
        capturedAt: Long,
        expiresAt: Long,
        occurrence: RelayOccurrence
    ) = post {
        if (!sender || id in exports) return@post
        val now = System.currentTimeMillis()
        if (capturedAt < now - RelayProtocol.LIFETIME || capturedAt > now + 300000) return@post
        val bytes = try {
            RelayProtocol.encode(title, body, capturedAt, occurrence)
        } catch (_: Exception) {
            return@post
        }
        val elapsed = SystemClock.elapsedRealtime()
        val deadline = minOf(
            expiresAt,
            elapsed +
                (capturedAt + RelayProtocol.LIFETIME - now).coerceIn(0, RelayProtocol.LIFETIME)
        )
        if (deadline <= elapsed) return@post
        exports[id] = Export(bytes, deadline)
        while (exports.size > 8) exports.remove(exports.keys.first())
        advertise()
    }

    private fun tryPost(action: () -> Unit): Boolean {
        if (closed.get()) return false
        if (queued.incrementAndGet() > 64) {
            queued.decrementAndGet()
            return false
        }
        val submitted = worker.post {
            queued.decrementAndGet()
            if (!closed.get()) {
                try {
                    if (!NearbyAccess.granted(context, sender)) {
                        status(NearbyStatus.PERMISSION_REQUIRED)
                        close()
                    } else {
                        action()
                    }
                } catch (_: SecurityException) {
                    diagnostic("permission", 0)
                    status(NearbyStatus.PERMISSION_REQUIRED)
                    close()
                } catch (_: RuntimeException) {
                    diagnostic("platform", 0)
                    status(NearbyStatus.ERROR)
                    close()
                }
            }
        }
        if (!submitted) queued.decrementAndGet()
        return submitted
    }

    private fun post(action: () -> Unit) {
        tryPost(action)
    }

    private fun later(delay: Long, action: () -> Unit) {
        worker.postDelayed({ post(action) }, delay)
    }

    private fun expire() {
        val now = SystemClock.elapsedRealtime()
        if (exports.entries.removeAll { it.value.expires <= now }) advertise()
        later(15000, ::expire)
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val signal = result.scanRecord?.getServiceData(SERVICE) ?: return
            if (signal.size != 8 || signal[0].toInt() != 1 ||
                !scanPending.compareAndSet(false, true)
            ) {
                return
            }
            val submitted = tryPost {
                scanPending.set(false)
                if (diagnosticSightings < 4) diagnostic("discovery", ++diagnosticSightings)
                if (client != null || peer != null) return@tryPost
                val key = signal.toLowerHex()
                val now = SystemClock.elapsedRealtime()
                cooldown.entries.removeAll { it.value <= now }
                while (attempts.firstOrNull()?.let { it <= now - 60000 } ==
                    true
                ) {
                    attempts.removeFirst()
                }
                if (key in cooldown || attempts.size >= 4) return@tryPost
                // Deterministic direction avoids two advertising installations connecting at once.
                if (advertising &&
                    key >= tokenHex
                ) {
                    return@tryPost
                }
                cooldown[key] = now + 30000
                if (cooldown.size > 64) cooldown.remove(cooldown.keys.first())
                attempts.addLast(now)
                later(random.nextInt(1200).toLong()) {
                    if (client == null && peer == null) connect(result.device)
                }
            }
            if (!submitted) scanPending.set(false)
        }
        override fun onScanFailed(errorCode: Int) = post {
            diagnostic("scan", errorCode)
            runCatching { adapter.bluetoothLeScanner?.stopScan(this) }
            scanning = false
            if (++scanFailures <= 2 && (
                    errorCode == SCAN_RESTART_LIMIT ||
                        errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ||
                        errorCode == SCAN_FAILED_INTERNAL_ERROR
                    )
            ) {
                status(NearbyStatus.COOLDOWN)
                later(31000, ::startScanning)
            } else {
                status(NearbyStatus.ERROR)
                close()
            }
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = post {
            diagnostic("advertise-ready", 0)
        }
        override fun onStartFailure(errorCode: Int) = post {
            diagnostic("advertise", errorCode)
            advertising = false
            receiveStatus = NearbyStatus.RECEIVE_ONLY
            status(if (scanning) receiveStatus else NearbyStatus.COOLDOWN)
        }
    }

    private fun advertise() {
        if (closed.get()) return
        peer?.let { server?.cancelConnection(it) }
        peer = null
        snapshot = emptyList()
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        if (advertising) advertiser.stopAdvertising(advertiseCallback)
        advertising = false
        if (exports.isEmpty() || !serverReady) return
        token = byteArrayOf(1, 0) + ByteArray(6).also(random::nextBytes)
        tokenHex = token.toLowerHex()
        // Fresh exports overlap brief low-power listening windows without raising continuous scan duty.
        advertiser.startAdvertising(
            AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(
                    AdvertiseSettings.ADVERTISE_TX_POWER_LOW
                ).setConnectable(true).build(),
            AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false)
                .addServiceData(SERVICE, token).build(),
            advertiseCallback
        )
        advertising = true
    }

    private fun openServer() {
        val service = BluetoothGattService(SERVICE.uuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        for (uuid in listOf(INVENTORY, DATA)) {
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    uuid,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
        }
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                CONTROL,
                BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )
        server = manager.openGattServer(context, serving)
        serverClose.registrationStarted()
        val accepted = try {
            server?.addService(service) == true
        } catch (error: RuntimeException) {
            serverClose.registrationFinished()
            throw error
        }
        if (!accepted) {
            serverClose.registrationFinished()
            receiveStatus = NearbyStatus.RECEIVE_ONLY
        }
    }

    private val serving = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(code: Int, service: BluetoothGattService) {
            // This callback must survive close(): unregistering while addService is pending
            // can leave attribute handles routed to a dead server on the tested firmware.
            worker.post {
                serverClose.registrationFinished()
                post {
                    if (code != BluetoothGatt.GATT_SUCCESS) diagnostic("server", code)
                    serverReady = code == BluetoothGatt.GATT_SUCCESS
                    if (serverReady) diagnostic("server-ready", 0)
                    if (serverReady && exports.isNotEmpty()) advertise()
                    if (!serverReady) {
                        receiveStatus = NearbyStatus.RECEIVE_ONLY
                        status(if (scanning) receiveStatus else NearbyStatus.COOLDOWN)
                    }
                }
            }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, code: Int, state: Int) =
            post {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    val now = SystemClock.elapsedRealtime()
                    while (attempts.firstOrNull()?.let { it <= now - 60000 } ==
                        true
                    ) {
                        attempts.removeFirst()
                    }
                    if (peer != null || client != null || exports.isEmpty() || attempts.size >= 4) {
                        server?.cancelConnection(device)
                    } else {
                        attempts.addLast(now)
                        peer = device
                        snapshot = exports.values.toList()
                        serverMtu = 23
                        requests = 0
                        request = null
                        val session = ++peerSession
                        later(30000) {
                            if (peer == device && peerSession == session) disconnectPeer()
                        }
                    }
                } else if (peer == device) {
                    peer = null
                    snapshot = emptyList()
                }
            }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = post {
            if (peer == device) serverMtu = mtu.coerceIn(23, 517)
        }
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            id: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) = post {
            if (peer != device || ++requests > 8192 || snapshot.any {
                    it.expires <= SystemClock.elapsedRealtime()
                }
            ) {
                respond(device, id, BluetoothGatt.GATT_FAILURE, offset, byteArrayOf())
                disconnectPeer()
                return@post
            }
            val value = runCatching {
                when (characteristic.uuid) {
                    INVENTORY -> {
                        val list = RelayFrames.inventory(
                            snapshot.map {
                                RelayEntry(RelayProtocol.root(it.bytes), it.bytes.size)
                            }
                        )
                        require(offset in 0..list.size)
                        list.copyOfRange(offset, minOf(list.size, offset + serverMtu - 1))
                    }

                    DATA -> {
                        require(offset == 0)
                        val block = requireNotNull(request)
                        RelayFrames.block(block, snapshot[block.index].bytes, serverMtu)
                    }

                    else -> error("Unexpected characteristic")
                }
            }.getOrNull()
            respond(
                device,
                id,
                if (value ==
                    null
                ) {
                    BluetoothGatt.GATT_FAILURE
                } else {
                    BluetoothGatt.GATT_SUCCESS
                },
                offset,
                value ?: byteArrayOf()
            )
        }
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            id: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            val copy = value.take(8).toByteArray()
            post {
                val accepted = runCatching {
                    require(peer == device && !preparedWrite && responseNeeded && offset == 0)
                    require(characteristic.uuid == CONTROL && ++requests <= 8192)
                    val block = RelayFrames.request(copy)
                    require(
                        block.index in snapshot.indices &&
                            block.offset < snapshot[block.index].bytes.size
                    )
                    request = block
                }.isSuccess
                if (responseNeeded) {
                    respond(
                        device,
                        id,
                        if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE,
                        0,
                        byteArrayOf()
                    )
                }
            }
        }
    }

    private fun respond(
        device: BluetoothDevice,
        id: Int,
        code: Int,
        offset: Int,
        value: ByteArray
    ) {
        if (!closed.get() && NearbyAccess.granted(context, sender)) {
            server?.sendResponse(device, id, code, offset, value)
        }
    }
    private fun disconnectPeer() {
        peerSession++
        peer?.let { server?.cancelConnection(it) }
        peer = null
        snapshot = emptyList()
        request = null
    }

    // This transport-selecting overload remains available across the Android 11+ baseline.
    @Suppress("DEPRECATION")
    private fun connect(device: BluetoothDevice) {
        discovering = false
        clientMtu = 23
        entries = emptyList()
        assembly = null
        control = null
        data = null
        client = device.connectGatt(context, false, receiving, BluetoothDevice.TRANSPORT_LE)
        val connection = client
        if (connection == null) return
        status(NearbyStatus.TRANSFERRING)
        later(30000) {
            if (client === connection) {
                diagnostic("timeout", 0)
                disconnectClient()
            }
        }
    }

    private fun discover(gatt: BluetoothGatt) {
        if (!discovering && client === gatt) {
            discovering = true
            if (!gatt.discoverServices()) disconnectClient()
        }
    }

    private val receiving = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, code: Int, state: Int) = post {
            if (client !== gatt) return@post
            if (code != BluetoothGatt.GATT_SUCCESS) diagnostic("connect", code)
            if (code != BluetoothGatt.GATT_SUCCESS ||
                state == BluetoothProfile.STATE_DISCONNECTED
            ) {
                disconnectClient()
            } else if (state == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(247)) discover(gatt)
                later(2000) { discover(gatt) }
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, code: Int) = post {
            if (client === gatt) {
                if (code == BluetoothGatt.GATT_SUCCESS) clientMtu = mtu.coerceIn(23, 517)
                discover(gatt)
            }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, code: Int) = post {
            if (client !== gatt) return@post
            if (code != BluetoothGatt.GATT_SUCCESS) diagnostic("services", code)
            val service = gatt.getService(SERVICE.uuid)
            control = service?.getCharacteristic(CONTROL)
            data = service?.getCharacteristic(DATA)
            val inventory = service?.getCharacteristic(INVENTORY)
            if (code != BluetoothGatt.GATT_SUCCESS || inventory == null || control == null ||
                data == null ||
                !gatt.readCharacteristic(inventory)
            ) {
                disconnectClient()
            }
        }
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            code: Int
        ) = read(gatt, characteristic.uuid, value, code)

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            code: Int
        ) {
            if (Build.VERSION.SDK_INT <
                33
            ) {
                read(gatt, characteristic.uuid, characteristic.value, code)
            }
        }
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            code: Int
        ) = post {
            if (client === gatt && (
                    code != BluetoothGatt.GATT_SUCCESS ||
                        characteristic.uuid != CONTROL ||
                        data?.let(gatt::readCharacteristic) != true
                    )
            ) {
                disconnectClient()
            }
        }
    }

    private fun read(gatt: BluetoothGatt, uuid: UUID, value: ByteArray, code: Int) {
        val bytes = value.take(513).toByteArray()
        post {
            if (client !== gatt) return@post
            if (code != BluetoothGatt.GATT_SUCCESS) {
                diagnostic("read", code)
                disconnectClient()
                return@post
            }
            val valid = runCatching {
                if (uuid == INVENTORY) {
                    entries = RelayFrames.inventory(bytes)
                    cursor = 0
                    next(gatt)
                } else {
                    require(uuid == DATA)
                    val pending = requireNotNull(assembly)
                    pending.append(bytes)
                    if (pending.complete) {
                        val original = pending.finish(System.currentTimeMillis())
                        if (!closed.get()) received(original)
                        cursor++
                        assembly = null
                        next(gatt)
                    } else {
                        fetch(gatt)
                    }
                }
            }.isSuccess
            if (!valid) {
                diagnostic("payload", 0)
                disconnectClient()
            }
        }
    }

    private fun next(gatt: BluetoothGatt) {
        while (cursor < entries.size && known(entries[cursor].root)) cursor++
        if (cursor == entries.size) {
            disconnectClient()
        } else {
            assembly = RelayAssembly(entries[cursor], cursor)
            fetch(gatt)
        }
    }

    @Suppress("DEPRECATION")
    private fun fetch(gatt: BluetoothGatt) {
        val characteristic = requireNotNull(control)
        val bytes = RelayFrames.request(
            cursor,
            requireNotNull(assembly).offset,
            minOf(
                248,
                clientMtu - 11
            )
        )
        val submitted = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(
                characteristic,
                bytes,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = bytes
            gatt.writeCharacteristic(characteristic)
        }
        if (!submitted) disconnectClient()
    }

    private fun disconnectClient() {
        val previous = client
        client = null
        assembly = null
        entries = emptyList()
        runCatching { previous?.disconnect() }
        runCatching { previous?.close() }
        if (!closed.get()) status(if (scanning) receiveStatus else NearbyStatus.COOLDOWN)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.post {
            runCatching { if (scanning) adapter.bluetoothLeScanner?.stopScan(scan) }
            runCatching {
                if (advertising) adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
            }
            disconnectClient()
            exports.clear()
            snapshot = emptyList()
            cooldown.clear()
            serverClose.close()
            if (!serverClose.released) {
                worker.postDelayed({
                    if (!serverClose.released) diagnostic("server-close-timeout", 0)
                    serverClose.expire()
                }, 5000)
            }
        }
    }

    companion object {
        private val SCAN_STARTS = ScanStartBudget()

        // The platform publishes scan throttling as error 6; older SDKs also report the integer.
        private const val SCAN_RESTART_LIMIT = 6
        val SERVICE = ParcelUuid(UUID.fromString("7f6b9c22-d68a-4718-a954-b7790dc81350"))
        private val INVENTORY = UUID.fromString("7f6b9c23-d68a-4718-a954-b7790dc81350")
        private val CONTROL = UUID.fromString("7f6b9c24-d68a-4718-a954-b7790dc81350")
        private val DATA = UUID.fromString("7f6b9c25-d68a-4718-a954-b7790dc81350")
    }
}
