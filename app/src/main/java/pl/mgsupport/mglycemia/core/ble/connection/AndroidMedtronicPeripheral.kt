/*
 * Android BluetoothLeAdvertiser + BluetoothGattServer implementation of [MedtronicPeripheral].
 *
 * GlycemicGPT code (GPL-3.0). Structurally modelled on OpenMinimed's JavaPumpConnector
 * BlePeripheralDevice (GPL-3.0, used with permission): advertise as a "Mobile …" device, stand up a
 * read-only GATT server (SAKE + Device Information), and forward GATT-server events to a
 * [PeripheralListener]. All UUIDs and the advertising contract come from B1's [MedtronicProtocol].
 *
 * This is thin Android glue with no unit-test surface (the Android BLE stack is not mockable in
 * local unit tests); it is exercised over-the-air against a real pump in 48.A2 / Milestone F. The
 * testable connection logic lives in [MedtronicBleConnectionManager] / [SakeHandshakeDriver].
 */
package pl.mgsupport.mglycemia.core.ble.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import pl.mgsupport.mglycemia.core.ble.protocol.MedtronicProtocol
import pl.mgsupport.mglycemia.core.ble.protocol.PduFramer
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Real-device [MedtronicPeripheral]. Construct with the application [Context]; permissions
 * (`BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` on API 31+, `ACCESS_FINE_LOCATION` below) are
 * declared in the module manifest and granted by the host app before use.
 */
@SuppressLint("MissingPermission")
class AndroidMedtronicPeripheral(context: Context) : MedtronicPeripheral {

    private val appContext = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter get() = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    // Written when the server is opened (caller/worker thread) and read from sendSakeNotification on
    // the worker thread + the GATT-server callbacks on the binder thread, so they need a memory
    // barrier for the cross-thread reads.
    @Volatile
    private var gattServer: BluetoothGattServer? = null

    @Volatile
    private var sakeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var listener: PeripheralListener? = null

    @Volatile
    private var peer: BluetoothDevice? = null

    /** Receives OS bond transitions so pairing failures are visible and actionable in the log. */
    private var bondReceiver: BroadcastReceiver? = null

    /**
     * Forwards Bluetooth adapter on/off so the connection manager can pause and resume instead of
     * tearing the session down. Invoked on the main thread; the manager hops onto its worker.
     */
    @Volatile
    var onAdapterStateChanged: ((Boolean) -> Unit)? = null

    private var adapterReceiver: BroadcastReceiver? = null

    @Volatile
    private var currentMode: AdvertisingMode = AdvertisingMode.FIRST_PAIR

    @Volatile
    private var currentName: String = MedtronicBleConnectionManager.DEFAULT_LOCAL_NAME

    // Advertising parameters held until the GATT services finish registering (see start()). Set on
    // the worker thread and consumed from onServiceAdded on the binder thread.
    @Volatile
    private var pendingAdvertise: Pair<AdvertisingMode, String>? = null

    // Services are added one at a time: addService -> onServiceAdded -> addNextService (the Android
    // GATT server only accepts a new service once the previous add has completed). The queue is
    // populated on the worker thread but drained from onServiceAdded on the binder thread, so it must
    // be thread-safe.
    private val pendingServices = ConcurrentLinkedQueue<BluetoothGattService>()

    /** Static phone/OS facts are logged once per process; only the live half repeats. */
    @Volatile
    private var staticDiagnosticsLogged = false

    /** Monotonic timestamp of the current GATT link-up; 0 when no link is up. */
    @Volatile
    private var linkUpElapsedRealtime = 0L

    /** The GATT table cannot change while the process lives, so it is dumped once. */
    @Volatile
    private var gattMapLogged = false

    private val diagTimeFormat = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    override fun isSupported(): Boolean {
        val ad = adapter ?: return false
        return ad.isEnabled && ad.isMultipleAdvertisementSupported && ad.bluetoothLeAdvertiser != null
    }

    override fun start(mode: AdvertisingMode, localName: String, listener: PeripheralListener) {
        this.listener = listener
        currentMode = mode
        currentName = localName
        registerBondReceiver()
        registerAdapterReceiver()
        emitDiagnostics(listener)
        listener.onDiagnostic("adapter name=${adapter?.name ?: "?"}, advertising as $localName")
        if (gattServer == null) {
            // Defer advertising until the SAKE + Device Info services are registered (service adds are
            // async via onServiceAdded): otherwise a fast pump could connect and discover an empty
            // GATT table before the services land.
            pendingAdvertise = mode to localName
            openGattServer()
        } else {
            startAdvertising(mode, localName)
        }
    }

    override fun advertise(mode: AdvertisingMode, localName: String) {
        currentMode = mode
        currentName = localName
        stopAdvertising()
        startAdvertising(mode, localName)
    }

    override fun stopAdvertising() {
        val ad = advertiser ?: return
        val cb = advertiseCallback ?: return
        try {
            ad.stopAdvertising(cb)
        } catch (e: SecurityException) {
            Timber.w(e, "stopAdvertising failed")
        } finally {
            advertiseCallback = null
        }
    }

    override fun sendSakeNotification(payload: ByteArray): Boolean {
        val server = gattServer
        val characteristic = sakeCharacteristic
        val target = peer
        if (server == null || characteristic == null || target == null) {
            diag("Cannot notify SAKE: server/characteristic/peer not ready")
            return false
        }
        return try {
            val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(target, characteristic, false, payload) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = payload
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(target, characteristic, false)
            }
            diag("sake notify len=${payload.size} success=$success value=${hex(payload)}")
            success
        } catch (e: SecurityException) {
            diag("sake notify failed: SecurityException")
            false
        }
    }

    override fun connectedDevice(): BluetoothDevice? = peer

    override fun removeBond(address: String): Boolean {
        val device = adapter?.getRemoteDevice(address) ?: return false
        if (device.bondState != BluetoothDevice.BOND_BONDED) return false
        return try {
            // BluetoothDevice.removeBond() is a hidden API; reflection is the standard workaround.
            val method = device.javaClass.getMethod("removeBond")
            (method.invoke(device) as? Boolean) ?: false
        } catch (e: Exception) {
            // Do not log the MAC at WARN -- WARN+ is Sentry-eligible and the address is a device id.
            Timber.w(e, "removeBond failed")
            false
        }
    }

    override fun stop() {
        stopAdvertising()
        unregisterBondReceiver()
        unregisterAdapterReceiver()
        peer = null
        sakeCharacteristic = null
        pendingAdvertise = null
        pendingServices.clear()
        gattServer?.let {
            try {
                it.close()
            } catch (e: SecurityException) {
                Timber.w(e, "closing GATT server failed")
            }
        }
        gattServer = null
    }

    // -- Diagnostics --------------------------------------------------------

    /**
     * Emit the phone/BLE environment snapshot so a failed pairing can be diagnosed from the app log
     * alone. The static half (model, Android build, SoC, ABIs) is logged once per process; the live
     * half (adapter flags, permissions, Doze, memory, clock) on every session start.
     */
    private fun emitDiagnostics(listener: PeripheralListener) {
        val lines =
            try {
                if (staticDiagnosticsLogged) {
                    DeviceDiagnostics.liveFacts(appContext)
                } else {
                    staticDiagnosticsLogged = true
                    DeviceDiagnostics.report(appContext)
                }
            } catch (e: RuntimeException) {
                listOf("device diagnostics unavailable: ${e::class.simpleName}")
            }
        lines.forEach { listener.onDiagnostic(it) }
    }

    // -- Advertising --------------------------------------------------------

    private fun startAdvertising(mode: AdvertisingMode, localName: String) {
        val ad = adapter?.bluetoothLeAdvertiser
        if (ad == null) {
            Timber.e("BluetoothLeAdvertiser unavailable")
            listener?.onAdvertiseFailed(ADVERTISER_UNAVAILABLE)
            return
        }
        advertiser = ad

        // Reconnect uses a low-latency (short) interval because the paired pump scans infrequently
        // and misses long intervals (Sec. 7); first pairing uses the balanced interval.
        val advertiseMode = when (mode) {
            AdvertisingMode.RECONNECT -> AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
            AdvertisingMode.FIRST_PAIR -> AdvertiseSettings.ADVERTISE_MODE_BALANCED
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advertiseMode)
            .setConnectable(true)
            .setTimeout(0) // advertise until explicitly stopped
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        // The "Mobile …" name is carried in the manufacturer data (company 0x01F9), mirroring the
        // proven JavaPumpConnector advertisement; the GAP device name is intentionally excluded to
        // keep the 31-byte advertisement within budget. Pump-side name matching is confirmed live in
        // 48.A2 (Sec. 3).
        val serviceUuid = ParcelUuid(MedtronicProtocol.sigUuid(mode.serviceUuid16))
        val data = AdvertiseData.Builder()
            .addManufacturerData(MedtronicProtocol.COMPANY_ID, MedtronicProtocol.manufacturerData(localName))
            .addServiceUuid(serviceUuid)
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(true)
            .build()

        diag(
            "advertise mode=$mode service=$serviceUuid " +
                "company=0x${Integer.toHexString(MedtronicProtocol.COMPANY_ID)} " +
                "mfr=${hex(MedtronicProtocol.manufacturerData(localName))}",
        )

        val callback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                Timber.d("Advertising started: %s", settingsInEffect)
                listener?.onAdvertiseStarted(mode)
            }

            override fun onStartFailure(errorCode: Int) {
                Timber.e("Advertising failed: errorCode=%d", errorCode)
                listener?.onAdvertiseFailed(errorCode)
            }
        }
        advertiseCallback = callback
        try {
            ad.startAdvertising(settings, data, callback)
        } catch (e: SecurityException) {
            Timber.e(e, "startAdvertising failed")
            advertiseCallback = null
            listener?.onAdvertiseFailed(ADVERTISER_UNAVAILABLE)
        }
    }

    // -- GATT server --------------------------------------------------------

    private fun openGattServer() {
        val manager = bluetoothManager ?: run {
            Timber.e("BluetoothManager unavailable; cannot open GATT server")
            failPendingAdvertise()
            return
        }
        try {
            val server = manager.openGattServer(appContext, gattServerCallback) ?: run {
                Timber.e("openGattServer returned null")
                failPendingAdvertise()
                return
            }
            gattServer = server
            pendingServices.clear()
            pendingServices.add(createDeviceInfoService())
            pendingServices.add(createSakeService())
            addNextService()
        } catch (e: SecurityException) {
            // Missing BLUETOOTH_CONNECT: surface it instead of leaving the manager hung in CONNECTING.
            Timber.e(e, "Failed to open GATT server")
            failPendingAdvertise()
        }
    }

    private fun failPendingAdvertise() {
        pendingAdvertise = null
        listener?.onAdvertiseFailed(GATT_SERVER_UNAVAILABLE)
    }

    private fun addNextService() {
        val server = gattServer ?: return
        val next = pendingServices.poll()
        if (next == null) {
            // All services registered -- now it is safe to advertise (see start()).
            logGattMap()
            pendingAdvertise?.let { (mode, name) ->
                pendingAdvertise = null
                startAdvertising(mode, name)
            }
            return
        }
        try {
            val added = server.addService(next)
            if (!added) {
                // addService returning false means no onServiceAdded callback will ever arrive, so
                // advertising would otherwise never start and the UI would sit in CONNECTING.
                Timber.e("addService returned false for %s", next.uuid)
                failPendingAdvertise()
            }
        } catch (e: SecurityException) {
            Timber.e(e, "addService failed for %s", next.uuid)
            failPendingAdvertise()
        }
    }

    /**
     * Dumps the exact GATT table we expose. A pump that connects and drops before subscribing is
     * usually rejecting one of these properties/permissions, and without the dump the log cannot
     * be compared against the reference implementation (JavaPumpConnector).
     */
    private fun logGattMap() {
        if (gattMapLogged) return
        gattMapLogged = true
        val server = gattServer ?: return
        for (service in server.services) {
            diag("gatt service ${service.uuid} type=${service.type}")
            for (characteristic in service.characteristics) {
                diag(
                    "gatt char ${characteristic.uuid} " +
                        "props=0x${Integer.toHexString(characteristic.properties)} " +
                        "perms=0x${Integer.toHexString(characteristic.permissions)}",
                )
                for (descriptor in characteristic.descriptors) {
                    diag(
                        "gatt desc ${descriptor.uuid} " +
                            "perms=0x${Integer.toHexString(descriptor.permissions)}",
                    )
                }
            }
        }
    }

    private fun createSakeService(): BluetoothGattService {
        val service = BluetoothGattService(
            MedtronicProtocol.SAKE_SERVICE_FIRST_PAIR_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        // SAKE characteristic: NOTIFY (phone -> pump) + WRITE (pump -> phone). Read-only data path;
        // no control/calibration characteristic is ever exposed. The UUID is on the Medtronic vendor
        // base (same 0xFE82 short code as the SIG-based service) to match JavaPumpConnector, which
        // paired on real hardware; the SIG-based form shipped before left the pump unable to find the
        // characteristic, so the handshake never began (issue #844). Live re-confirmation on a real
        // 780G is still pending (DESK).
        val sake = BluetoothGattCharacteristic(
            MedtronicProtocol.SAKE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        sake.addDescriptor(
            BluetoothGattDescriptor(
                MedtronicProtocol.CCCD_UUID,
                // Wymagamy szyfrowania, żeby pompa zainicjowała parowanie SMP przed subskrypcją.
                // Bez tego telefon bez bondu (Galaxy S24) nigdy nie dostaje zapisu CCCD, a pompa
                // odchodzi około 7 s po odczycie deskryptora.
                BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED or
                    BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED,
            ),
        )
        service.addCharacteristic(sake)
        sakeCharacteristic = sake
        return service
    }

    private fun createDeviceInfoService(): BluetoothGattService {
        val service = BluetoothGattService(
            MedtronicProtocol.DEVICE_INFO_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        fun readChar(uuid: java.util.UUID, value: ByteArray): BluetoothGattCharacteristic =
            BluetoothGattCharacteristic(
                uuid,
                BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ,
            ).apply { @Suppress("DEPRECATION") setValue(value) }

        // The pump reads this table right after connecting and drops the link when the values look
        // wrong, so these mirror JavaPumpConnector's BlePeripheralDevice byte for byte (manifest,
        // model, HW/FW/SW revisions) instead of placeholders. A bare "0" firmware was observed to
        // end the connection a few seconds after the GATT handshake.
        val ascii = Charsets.US_ASCII
        service.addCharacteristic(
            readChar(
                MedtronicProtocol.MANUFACTURER_NAME_UUID,
                DIS_MANUFACTURER.toByteArray(ascii)
            )
        )
        service.addCharacteristic(readChar(MedtronicProtocol.MODEL_NUMBER_UUID, DIS_MODEL.toByteArray(ascii)))
        service.addCharacteristic(readChar(MedtronicProtocol.SERIAL_NUMBER_UUID, currentName.toByteArray(ascii)))
        service.addCharacteristic(readChar(MedtronicProtocol.HARDWARE_REVISION_UUID, DIS_HARDWARE.toByteArray(ascii)))
        service.addCharacteristic(readChar(MedtronicProtocol.FIRMWARE_REVISION_UUID, DIS_FIRMWARE.toByteArray(ascii)))
        service.addCharacteristic(readChar(MedtronicProtocol.SOFTWARE_REVISION_UUID, DIS_SOFTWARE.toByteArray(ascii)))
        service.addCharacteristic(readChar(MedtronicProtocol.SYSTEM_ID_UUID, ByteArray(8)))
        // PnP ID is a fixed 7-byte structure (vendor id source, vendor id, product id, product
        // version). Returning a single byte makes the pump see a malformed Device Information table
        // and drop the link right after reading 2A50.
        service.addCharacteristic(readChar(MedtronicProtocol.PNP_ID_UUID, ByteArray(7)))
        service.addCharacteristic(readChar(MedtronicProtocol.REGULATORY_CERT_UUID, ByteArray(0)))
        return service
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    peer = device
                    linkUpElapsedRealtime = SystemClock.elapsedRealtime()
                    val bond =
                        when (device.bondState) {
                            BluetoothDevice.BOND_BONDED -> "bonded"
                            BluetoothDevice.BOND_BONDING -> "bonding"
                            else -> "none"
                        }
                    // A fresh link starts with no subscription; never reuse a stale CCCD value from a
                    // previous pump session (it would tell the pump notifications are already enabled).
                    sakeCccdValue = byteArrayOf(0x00, 0x00)
                    listener?.onDiagnostic("GATT link up, status=${gattStatusLabel(status)}, bond=$bond")
                    listener?.onPumpConnected(device.address)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    peer = null
                    sakeCccdValue = byteArrayOf(0x00, 0x00)
                    diag("GATT link down, status=${gattStatusLabel(status)}")
                    linkUpElapsedRealtime = 0L
                    listener?.onPumpDisconnected(status)
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            listener?.onDiagnostic("service added ${short(service.uuid)} status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addNextService()
            } else {
                // Without this the manager would hang in CONNECTING -- service registration is the one
                // BLE step with no timeout in this layer.
                Timber.e("Failed to add service %s (status=%d)", service.uuid, status)
                failPendingAdvertise()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            // The pump reads the serial number during pairing and compares it with the advertised
            // "Mobile …" name. Keep it live so a rotated identity is reflected without rebuilding
            // the GATT server.
            val stored =
                if (characteristic.uuid == MedtronicProtocol.SERIAL_NUMBER_UUID) {
                    currentName.toByteArray(Charsets.US_ASCII)
                } else {
                    characteristic.value ?: ByteArray(0)
                }
            diag("read ${short(characteristic.uuid)} len=${stored.size} offset=$offset")
            @Suppress("DEPRECATION")
            respondWithStoredBlob(device, requestId, offset, stored)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            diag(
                "write ${short(characteristic.uuid)} len=${value.size} offset=$offset value=${hex(value)}",
            )
            // Complete the ATT request first. The SAKE driver posts the next notification, and some
            // stacks drop the link if a notification overtakes the write response during bonding.
            if (responseNeeded) {
                sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            if (characteristic.uuid == MedtronicProtocol.SAKE_CHARACTERISTIC_UUID) {
                listener?.onSakeWrite(value)
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            // The CCCD read has to reflect the subscription state. Answering 0x0000 on initial read
            // makes the pump believe notifications were never enabled and it drops the link right after
            // this read (seen on Galaxy S24 and Redmi Note 13 Pro: read 2902, then link down).
            val isSakeCccd = descriptor.uuid == MedtronicProtocol.CCCD_UUID &&
                descriptor.characteristic?.uuid == MedtronicProtocol.SAKE_CHARACTERISTIC_UUID
            // Report the real subscription state, exactly like JavaPumpConnector. A fake 0x0100 makes
            // the pump believe it is already subscribed, so it never writes the CCCD -- and Android
            // then refuses to transmit our notifications (no subscription registered), which is the
            // deadlock seen on Galaxy S24 and Redmi: pump reads 2902, waits, then drops the link.
            val reply = if (isSakeCccd) {
                sakeCccdValue.copyOf(2)
            } else {
                descriptor.value ?: byteArrayOf(0x00, 0x00)
            }
            @Suppress("DEPRECATION")
            descriptor.value = reply
            diag(
                "descriptor read ${short(descriptor.uuid)} on ${descriptor.characteristic?.uuid?.let(::short) ?: "?"} " +
                    "offset=$offset len=${reply.size} reply=${hex(reply)}",
            )
            @Suppress("DEPRECATION")
            respondWithStoredBlob(device, requestId, offset, reply)

            if (isSakeCccd && reply.size >= 2 && (reply[0].toInt() and 0x01) != 0) {
                // Reachable only after the pump really wrote the CCCD; the driver ignores duplicates.
                listener?.onSakeSubscribed()
            } else if (isSakeCccd) {
                // If the pump reads 0x0000 it must write 0x0100 to subscribe. Log it when it doesn't,
                // so the next report distinguishes "pump never subscribed" from "stack dropped our
                // notification".
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    if (peer != null && sakeCccdValue.contentEquals(byteArrayOf(0x00, 0x00))) {
                        diag("pump read CCCD but did not subscribe within 2s")
                    }
                }, 2_000L)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            diag(
                "descriptor write ${short(descriptor.uuid)} on ${descriptor.characteristic?.uuid?.let(::short) ?: "?"} " +
                    "len=${value.size} offset=$offset value=${hex(value)}",
            )
            @Suppress("DEPRECATION")
            descriptor.value = value
            // Respond before starting the SAKE wake-up: the pump must see the subscription accepted
            // before it receives the first notification.
            if (responseNeeded) {
                sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
            val isSakeCccd = descriptor.uuid == MedtronicProtocol.CCCD_UUID &&
                descriptor.characteristic?.uuid == MedtronicProtocol.SAKE_CHARACTERISTIC_UUID
            if (isSakeCccd && value.size >= 2) {
                sakeCccdValue = value.copyOf(2)
                when {
                    (value[0].toInt() and 0x01) != 0 -> {
                        Timber.d("Pump subscribed to SAKE notifications")
                        listener?.onSakeSubscribed()
                    }

                    value[0].toInt() == 0 && value[1].toInt() == 0 -> {
                        Timber.d("Pump unsubscribed from SAKE notifications")
                        listener?.onSakeUnsubscribed()
                    }

                    else -> Timber.d("Unexpected SAKE CCCD value %02x%02x; ignoring", value[1], value[0])
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            // We never request a larger MTU and keep every PDU <= 20 bytes regardless of what the
            // central negotiates (Sec. 6). This is observational only.
            listener?.onDiagnostic("mtu=$mtu")
            Timber.d("Central negotiated MTU=%d (ignored; PDUs stay <= %d bytes)", mtu, PduFramer.MAX_PDU_SIZE)
        }
    }

    private var sakeCccdValue = byteArrayOf(0x00, 0x00)

    private fun registerBondReceiver() {
        if (bondReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val device =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    if (device == null) return
                    // Only the pump this session is talking to matters; ignore unrelated devices.
                    val current = peer
                    if (current != null && current.address != device.address) return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val reason = intent.getIntExtra(EXTRA_BOND_REASON, 0)
                    val failed = state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING
                    listener?.onDiagnostic(
                        "bond state ${bondLabel(previous)} -> ${bondLabel(state)} reason=$reason" +
                            if (failed) " (pairing failed)" else "",
                    )
                    listener?.onBondStateChanged(state, previous, reason)
                }
            }
        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            bondReceiver = receiver
        } catch (e: SecurityException) {
            Timber.w(e, "Cannot register bond-state receiver")
        }
    }

    private fun unregisterBondReceiver() {
        val receiver = bondReceiver ?: return
        bondReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Timber.d(e, "Bond receiver was already unregistered")
        }
    }

    /** Whether the radio exists but is currently off (vs. truly unsupported hardware). */
    fun isRadioOff(): Boolean {
        val ad = adapter ?: return false
        return !ad.isEnabled
    }

    /** Register the adapter on/off watcher without starting advertising (for a cold start with BT off). */
    fun watchAdapterState() {
        registerAdapterReceiver()
    }

    private fun registerAdapterReceiver() {
        if (adapterReceiver != null) return
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_OFF -> {
                            Timber.w("Bluetooth adapter off; pausing peripheral")
                            listener?.onDiagnostic("adapter state off")
                            onAdapterStateChanged?.invoke(false)
                        }

                        BluetoothAdapter.STATE_ON -> {
                            Timber.d("Bluetooth adapter on; resuming peripheral")
                            listener?.onDiagnostic("adapter state on")
                            onAdapterStateChanged?.invoke(true)
                        }
                    }
                }
            }
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            adapterReceiver = receiver
        } catch (e: SecurityException) {
            Timber.w(e, "Cannot register adapter-state receiver")
        }
    }

    private fun unregisterAdapterReceiver() {
        val receiver = adapterReceiver ?: return
        adapterReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Timber.d(e, "Adapter receiver was already unregistered")
        }
    }

    private fun bondLabel(state: Int): String =
        when (state) {
            BluetoothDevice.BOND_BONDED -> "bonded"
            BluetoothDevice.BOND_BONDING -> "bonding"
            BluetoothDevice.BOND_NONE -> "none"
            else -> "unknown($state)"
        }

    private fun short(uuid: java.util.UUID): String = uuid.toString().substring(4, 8)

    private fun sendResponse(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray) {
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            Timber.w(e, "sendResponse failed")
            listener?.onDiagnostic("sendResponse failed: SecurityException")
        }
    }

    /**
     * Statusy GATT rozwinięte na słowa: w logu użytkownika `status=133` nic nie mówi, a
     * `status=133 (gatt-error)` od razu kieruje na właściwą ścieżkę diagnostyki.
     */
    /**
     * Diagnostic line with the time since link-up. Without it a log cannot tell an immediate
     * protocol rejection (few ms) from the pump waiting for something and timing out (seconds).
     */
    private fun diag(line: String) {
        val since = linkUpElapsedRealtime
        val rel =
            if (since == 0L) ""
            else "t=+${SystemClock.elapsedRealtime() - since}ms "
        val local =
            try {
                java.time.LocalTime.now().format(diagTimeFormat)
            } catch (e: RuntimeException) {
                ""
            }
        listener?.onDiagnostic("$local $rel$line")
    }

    private fun gattStatusLabel(status: Int): String =
        when (status) {
            BluetoothGatt.GATT_SUCCESS -> "0 (success)"
            8 -> "8 (timeout)"
            19 -> "19 (remote-user-terminated)"
            22 -> "22 (local-host-terminated)"
            133 -> "133 (gatt-error)"
            257 -> "257 (gatt-failure)"
            else -> status.toString()
        }

    private fun hex(value: ByteArray): String =
        if (value.isEmpty()) {
            "-"
        } else {
            value.joinToString(" ") { "%02x".format(it) }
        }

    /** Reply to a characteristic or descriptor read with [stored], honoring the central's [offset]. */
    private fun respondWithStoredBlob(device: BluetoothDevice, requestId: Int, offset: Int, stored: ByteArray) {
        // The ATT offset is an unsigned 16-bit field, so a negative value can't arrive from the wire;
        // the `offset < 0` guard is defensive so copyOfRange() below can never throw.
        if (offset < 0 || offset > stored.size) {
            sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, ByteArray(0))
            return
        }
        sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, stored.copyOfRange(offset, stored.size))
    }

    private companion object {
        /** `BluetoothDevice.EXTRA_REASON` is hidden; the broadcast extra name is stable. */
        const val EXTRA_BOND_REASON = "android.bluetooth.device.extra.REASON"

        /** Synthetic AdvertiseCallback error code for "no advertiser available" (not a platform code). */
        const val ADVERTISER_UNAVAILABLE = -1

        /** Synthetic error code for "GATT server could not be opened" (e.g. missing permission). */
        const val GATT_SERVER_UNAVAILABLE = -2

        // Device Information Service strings the pump reads after connecting. These are the values
        // JavaPumpConnector's BlePeripheralDevice uses; the pump tolerates them, while an empty
        // revision string made it drop the link during pairing.
        const val DIS_MANUFACTURER = "Test Manufacturer"
        const val DIS_MODEL = "Test Model Number"
        const val DIS_HARDWARE = "HW-1.0"
        const val DIS_FIRMWARE = "14587043"
        const val DIS_SOFTWARE = "2.9.0 f1093d1"
    }
}
