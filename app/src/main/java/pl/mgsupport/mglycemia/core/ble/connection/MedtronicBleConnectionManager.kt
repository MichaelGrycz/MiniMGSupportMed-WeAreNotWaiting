/*
 * Phone-as-peripheral BLE connection manager for the Medtronic MiniMed 700-series read-only driver.
 *
 * GlycemicGPT code (GPL-3.0). This is the inverted-topology connection core that has no Tandem
 * equivalent to clone: the phone advertises as a "Mobile …" peripheral and the pump connects to it
 * as the central (medtronic-ble-reverse-engineering.md Sec. 3). It owns the connection lifecycle,
 * drives the SAKE handshake (Sec. 4) to a live encrypted session, and auto-reconnects to an
 * already-paired pump. The Android BLE plumbing it sits on top of is modelled on OpenMinimed's
 * JavaPumpConnector (GPL-3.0, used with permission).
 *
 * Scope (Milestone B2): connection + authentication + reconnect only. The data readers (CGM/IOB/
 * history) and the MedtronicDevicePlugin / Hilt registration that consume this manager land in
 * Milestone C. Over-the-air validation against a real pump rides with 48.A2 / Milestone F -- nothing
 * here is claimed live-verified.
 */
package pl.mgsupport.mglycemia.core.ble.connection

import android.bluetooth.BluetoothDevice
import android.content.Context
import pl.mgsupport.mglycemia.core.ble.sake.MedtronicSakeSession
import pl.mgsupport.mglycemia.core.domain.model.ConnectionState
import pl.mgsupport.mglycemia.core.domain.model.DiscoveredDevice
import pl.mgsupport.mglycemia.core.domain.pump.PumpCredentialProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import org.openminimed.sake.Constants
import org.openminimed.sake.KeyDatabase
import timber.log.Timber

/**
 * A connection fault surfaced alongside [MedtronicBleConnectionManager.connectionState] to explain
 * *why* a connection is not progressing -- in particular the single-peer condition (the pump only
 * talks to one phone at a time, so it must be removed from the official app first; Sec. 7).
 */
enum class MedtronicConnectionFault {
    /** This device cannot advertise as a BLE peripheral, so it can never pair (Sec. 3). */
    PERIPHERAL_UNSUPPORTED,

    /** The BLE advertiser rejected the advertisement (e.g. too many advertisers active). */
    ADVERTISE_FAILED,

    /** The pump connected but the SAKE handshake did not complete within the timeout. */
    HANDSHAKE_TIMEOUT,

    /** The SAKE handshake ran but authentication failed (CMAC/permit verification). */
    AUTH_FAILED,

    /**
     * The OS-level BLE bond entered BOND_BONDING and then fell back to BOND_NONE. The pump dropped
     * the link before it subscribed to SAKE, so the user must confirm the system pairing prompt (or
     * remove a stale pump entry from Bluetooth settings) and try again.
     */
    PAIRING_FAILED,

    /**
     * First-pair advertising has been running with no pump connecting -- the pump is likely still
     * bound to another phone (e.g. the official Medtronic app) and must be removed from it first.
     * Working assumption from desk research; exact live behavior is confirmed in 48.A2 (Sec. 7).
     */
    BOUND_ELSEWHERE_SUSPECTED,

    /**
     * No pump connected at all within the first-pair window. Distinct from
     * [BOUND_ELSEWHERE_SUSPECTED]: here nothing arrived, so the advice is about the pump's pairing
     * screen and its single mobile-device slot, not about a link that came and went.
     */
    PAIRING_TIMEOUT,
}

/**
 * Selects the advertising mode for a connection attempt. Already-paired pumps reconnect via the
 * `0xFE81` service ([AdvertisingMode.RECONNECT]); everything else is a first pairing via `0xFE82`.
 * Pure logic, separated out so the reconnect-service selection (AC5) is directly unit-testable.
 */
internal fun advertisingModeFor(paired: Boolean, forceFirstPair: Boolean): AdvertisingMode =
    if (paired && !forceFirstPair) AdvertisingMode.RECONNECT else AdvertisingMode.FIRST_PAIR

/**
 * Manages the phone-as-peripheral BLE connection to a Medtronic 700-series pump.
 *
 * Responsibilities:
 *  - Advertise as a "Mobile …" peripheral + stand up the read-only GATT server (delegated to
 *    [MedtronicPeripheral]).
 *  - Drive the SAKE handshake on a worker thread (delegated to [SakeHandshakeDriver]).
 *  - Expose the connection lifecycle as [connectionState] and the reason for a stall as [fault].
 *  - Auto-reconnect to an already-paired pump and persist pairing via [PumpCredentialProvider].
 *
 * Inject the collaborators for tests (the BLE layer is mocked); on-device, use the [Context]
 * secondary constructor which wires the real Android peripheral + a HandlerThread worker.
 */
class MedtronicBleConnectionManager(
    private val peripheral: MedtronicPeripheral,
    private val credentialStore: PumpCredentialProvider,
    private val worker: SerialWorker,
    private val scope: CoroutineScope,
    private val keyDatabase: KeyDatabase = Constants.KEYDB_PUMP_EXTRACTED,
    localName: String = DEFAULT_LOCAL_NAME,
    private val handshakeTimeoutMs: Long = DEFAULT_HANDSHAKE_TIMEOUT_MS,
    private val pairingWaitMs: Long = DEFAULT_PAIRING_WAIT_MS,
    private val sakeWatchdogTimeoutMs: Long = DEFAULT_SAKE_WATCHDOG_TIMEOUT_MS,
) {

    /** On-device constructor: real Android peripheral + dedicated SAKE worker thread. */
    constructor(context: Context, credentialStore: PumpCredentialProvider) : this(
        peripheral = AndroidMedtronicPeripheral(context),
        credentialStore = credentialStore,
        worker = HandlerThreadSerialWorker(SAKE_WORKER_THREAD_NAME),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) {
        // The test seam (primary constructor) has no adapter callbacks; on-device the peripheral
        // forwards adapter on/off so a killed radio pauses instead of tearing the session down.
        (peripheral as? AndroidMedtronicPeripheral)?.onAdapterStateChanged = { enabled ->
            worker.post { onAdapterStateChanged(enabled) }
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

    /** Advertised identity; changed by [setLocalName] when the user asks for a fresh pairing. */
    @Volatile
    private var localName: String = localName

    /** The identity currently advertised to the pump. */
    val currentLocalName: String
        get() = localName

    /** Replace the advertised identity. Serialized on the worker with every connection attempt. */
    fun setLocalName(name: String) = worker.post { localName = name }

    /** Observable BLE/auth connection lifecycle. */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _fault = MutableStateFlow<MedtronicConnectionFault?>(null)

    /** The reason the connection is not progressing, or `null` when there is nothing to report. */
    val fault: StateFlow<MedtronicConnectionFault?> = _fault.asStateFlow()

    /** The live SAKE session after a successful handshake -- the read layer (Milestone C) decrypts with it. */
    @Volatile
    var sakeSession: MedtronicSakeSession? = null
        private set

    /**
     * The connected pump's [BluetoothDevice], captured by the peripheral when the pump connected to
     * our GATT server. The Milestone D `BluetoothGatt`-client transport ([AndroidMedtronicGattLink])
     * opens a client connection back to this device over the same link to read the pump's GATT server
     * -- it must not start a fresh scan/advertise cycle. `null` until a pump is connected.
     */
    val connectedPumpDevice: BluetoothDevice?
        get() = peripheral.connectedDevice()

    @Volatile
    private var autoReconnect = false

    @Volatile
    private var connectedAddress: String? = null

    @Volatile
    private var onDiscovered: ((DiscoveredDevice) -> Unit)? = null

    /** Optional sink for technical BLE diagnostics shown in the in-app log. */
    @Volatile
    var diagnosticSink: ((String) -> Unit)? = null

    /** Re-advertise attempts after an unexpected drop during first pairing. */
    @Volatile
    private var pairRetries = 0

    // All connection-lifecycle state below is confined to the SerialWorker thread: every public
    // entry point and every BLE callback hops onto [worker] before touching it, so the check-then-set
    // transitions run on a single thread with no locking (the same discipline SakeHandshakeDriver
    // uses). The fields stay @Volatile so the read layer / UI can still observe the latest value.
    @Volatile
    private var authTimeoutJob: Job? = null

    @Volatile
    private var pairingWaitJob: Job? = null

    /** Pending re-advertise: a backoff retry or the economy-mode refresh loop. */
    @Volatile
    private var reconnectJob: Job? = null

    /** Link-up -> SAKE-subscribe stall guard; the auth timeout owns the window once subscribed. */
    @Volatile
    private var sakeWatchdogJob: Job? = null

    /** The watchdog closed the server underneath the link; swallow the trailing disconnect once. */
    @Volatile
    private var ignoreNextDisconnect = false

    /** Last advertised mode, so watchdog/adapter recovery re-advertises the same service. */
    @Volatile
    private var lastMode: AdvertisingMode = AdvertisingMode.FIRST_PAIR

    /** Bluetooth radio state; while off no advertising is attempted. */
    @Volatile
    private var adapterOff = false

    private val handshakeListener = object : SakeHandshakeDriver.HandshakeListener {
        // Invoked on the worker thread already (the driver runs on [worker]).
        override fun onAuthenticated(session: MedtronicSakeSession) = onHandshakeComplete(session)
        override fun onAuthFailed(cause: Throwable?) = onHandshakeFailed(cause)
    }

    private val driver = SakeHandshakeDriver(
        worker = worker,
        sessionFactory = { MedtronicSakeSession(keyDatabase) },
        notifySink = peripheral::sendSakeNotification,
        listener = handshakeListener,
    )

    // BLE callbacks arrive on the binder thread; hop onto the worker so all lifecycle mutation is
    // serialized with the handshake. onSakeUnsubscribed/onSakeWrite already post via the driver.
    private val peripheralListener = object : PeripheralListener {
        override fun onAdvertiseStarted(mode: AdvertisingMode) = worker.post { onAdvertising(mode) }
        override fun onAdvertiseFailed(errorCode: Int) = worker.post { onAdvertisingFailed(errorCode) }
        override fun onPumpConnected(address: String) = worker.post { onCentralConnected(address) }
        override fun onBondStateChanged(state: Int, previousState: Int, reason: Int) = worker.post {
            // Bonding is ATT progress: a pump that bonds will subscribe next, so restart the stall clock.
            pokeSakeWatchdog()
            if (state == BluetoothDevice.BOND_NONE && previousState == BluetoothDevice.BOND_BONDING) {
                Timber.w("BLE pairing failed (reason=%d)", reason)
                _fault.value = MedtronicConnectionFault.PAIRING_FAILED
                diagnosticSink?.invoke("pairing failed reason=$reason")
            }
        }

        override fun onSakeSubscribed() = worker.post { startAuthentication() }
        override fun onSakeUnsubscribed() = driver.onUnsubscribed()
        override fun onSakeWrite(value: ByteArray) {
            // A frame before the subscribe is still progress (some stacks write early).
            worker.post { pokeSakeWatchdog() }
            driver.onPumpWrite(value)
        }

        override fun onPumpDisconnected(status: Int) = worker.post { onCentralDisconnected(status) }
        override fun onDiagnostic(line: String) {
            // Also to logcat: a tester cannot hand over the in-app log, but `adb logcat` can be pulled
            // off the device. Debug-only, because Timber has no tree in release builds.
            Timber.d("%s", line)
            diagnosticSink?.invoke(line)
            // ATT reads/writes (incl. the 2A50/CCCD probes) are signs of life: anything moving
            // through the GATT server restarts the link-up stall clock. Runs on the binder thread,
            // so hop onto the worker like every other lifecycle mutation.
            if (isAttTraffic(line)) worker.post { pokeSakeWatchdog() }
        }
    }

    // -- Public lifecycle ---------------------------------------------------

    /**
     * Begin a connection: advertise + stand up the GATT server and wait for the pump to connect and
     * complete SAKE. Picks [AdvertisingMode.RECONNECT] for an already-paired pump unless
     * [forceFirstPair] is set (e.g. the user is re-pairing). No-op if the device cannot advertise as
     * a peripheral.
     */
    fun startSession(forceFirstPair: Boolean = false) = worker.post {
        val mode = advertisingModeFor(credentialStore.isPaired(), forceFirstPair)
        if (!ensureSupported()) {
            // Radio off (hardware present but disabled) is not a fault: hold a resumable state
            // and let the adapter-on callback restart advertising. Truly unsupported hardware
            // keeps PERIPHERAL_UNSUPPORTED from ensureSupported.
            if (holdForAdapterOff(mode, ConnectionState.CONNECTING)) {
                autoReconnect = true
                pairRetries = 0
            }
            return@post
        }
        autoReconnect = true
        pairRetries = 0
        beginAdvertising(mode, ConnectionState.CONNECTING)
    }

    /** Auto-reconnect to a previously paired pump. No-op if no pump is paired. */
    fun reconnectIfPaired() = worker.post {
        if (!credentialStore.isPaired()) {
            Timber.d("reconnectIfPaired: no paired pump")
            return@post
        }
        if (!ensureSupported()) {
            if (holdForAdapterOff(AdvertisingMode.RECONNECT, ConnectionState.RECONNECTING)) {
                autoReconnect = true
                pairRetries = 0
            }
            return@post
        }
        autoReconnect = true
        beginAdvertising(AdvertisingMode.RECONNECT, ConnectionState.CONNECTING)
    }

    /** Stop advertising + reconnect and drop the session, but keep the pairing. Safe to call when idle. */
    fun disconnect() = worker.post {
        autoReconnect = false
        pairRetries = 0
        teardown(ConnectionState.DISCONNECTED, fault = null)
    }

    /** Clear the pairing, remove the BLE bond, and disconnect. */
    fun unpair() = worker.post {
        val address = credentialStore.getPairedAddress() ?: connectedAddress
        autoReconnect = false
        pairRetries = 0
        teardown(ConnectionState.DISCONNECTED, fault = null)
        credentialStore.clearPairing()
        val bondRemoved = if (address != null) peripheral.removeBond(address) else false
        diagnosticSink?.invoke("unpair: bond removed=$bondRemoved")
        Timber.d("Medtronic pump unpaired; credentials cleared, bondRemoved=%b", bondRemoved)
    }

    /**
     * Discovery in the inverted topology. There is **no active central scan** here -- the pump is
     * the central and *it* finds *us*, so discovery is advertise-and-wait: this starts first-pair
     * advertising and emits a [DiscoveredDevice] when a pump connects to the GATT server. Cancelling
     * collection stops advertising if no pump has connected yet. The connection that a discovered
     * pump initiates then proceeds straight into the SAKE handshake (the central cannot be made to
     * "connect later" the way an active scan implies). C wires this into `DevicePlugin.scan()`.
     */
    fun scan(): Flow<DiscoveredDevice> = callbackFlow {
        if (!peripheral.isSupported()) {
            // Handled, expected condition (some phones can't advertise as a peripheral) -- surfaced to
            // the user via the PERIPHERAL_UNSUPPORTED fault, so log at WARN (a breadcrumb, not a
            // Sentry-eligible error event).
            Timber.w("BLE peripheral advertising not supported; cannot scan")
            worker.post { _fault.value = MedtronicConnectionFault.PERIPHERAL_UNSUPPORTED }
            close()
            return@callbackFlow
        }
        onDiscovered = { trySend(it) }
        worker.post {
            autoReconnect = false
            // Discovery with the radio off holds SCANNING (watcher armed) instead of failing;
            // the adapter-on callback resumes it.
            if (holdForAdapterOff(AdvertisingMode.FIRST_PAIR, ConnectionState.SCANNING)) return@post
            beginAdvertising(AdvertisingMode.FIRST_PAIR, ConnectionState.SCANNING)
        }
        awaitClose {
            onDiscovered = null
            worker.post {
                // Only tear down if discovery was cancelled before a pump actually connected; once a
                // pump is connecting/authenticating the session owns the lifecycle, not the scan.
                if (_connectionState.value == ConnectionState.SCANNING) {
                    teardown(ConnectionState.DISCONNECTED, fault = null)
                }
            }
        }
    }

    /** Release the worker thread and cancel the scope. The manager is unusable afterwards. */
    fun close() {
        // Tear down synchronously on the caller thread: the worker is about to stop, so a posted
        // teardown could be dropped before it runs.
        autoReconnect = false
        cancelTimers()
        resetSessionState()
        peripheral.stop()
        _fault.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        worker.stop()
        scope.cancel()
    }

    // -- Advertising / peripheral callbacks ---------------------------------

    private fun beginAdvertising(mode: AdvertisingMode, state: ConnectionState) {
        cancelTimers()
        resetSessionState()
        // A fresh advertise cycle means the radio is usable; clear any stale off-hold.
        adapterOff = false
        lastMode = mode
        _fault.value = null
        _connectionState.value = state
        Timber.d("Advertising as '%s' in %s mode", localName, mode)
        peripheral.start(mode, localName, peripheralListener)
    }

    /** Drop the live session, the remembered peer, and arm a fresh handshake. */
    private fun resetSessionState() {
        sakeSession = null
        connectedAddress = null
        driver.reset()
    }

    private fun onAdvertising(mode: AdvertisingMode) {
        Timber.d("Advertising started (%s)", mode)
        // First-pair single-peer detection (Sec. 7): if no pump connects in the window, the pump is
        // probably still bound to another phone. Reconnect mode waits indefinitely because the pump
        // scans infrequently. TODO(48.A2): tune/confirm this window against a real pump.
        if (mode == AdvertisingMode.FIRST_PAIR) armPairingWaitTimer()
    }

    private fun onAdvertisingFailed(errorCode: Int) {
        Timber.e("Advertising failed (errorCode=%d)", errorCode)
        // The radio may have died between the support check and the start; pause and wait
        // for it instead of failing (scan holds SCANNING, sessions hold RECONNECTING).
        val holdState =
            if (_connectionState.value == ConnectionState.SCANNING) ConnectionState.SCANNING
            else ConnectionState.RECONNECTING
        if (holdForAdapterOff(lastMode, holdState)) return
        autoReconnect = false
        teardown(ConnectionState.DISCONNECTED, fault = MedtronicConnectionFault.ADVERTISE_FAILED)
    }

    private fun onCentralConnected(address: String) {
        // Debug level only: the MAC is a device identifier and WARN+/INFO can be Sentry-eligible.
        Timber.d("Pump connected: %s", address)
        // A fresh link supersedes any watchdog reset still draining through the stack.
        ignoreNextDisconnect = false
        pairingWaitJob?.cancel()
        pairingWaitJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        armSakeWatchdog()
        _fault.value = null
        connectedAddress = address
        // Match the proven OpenMinimed implementations: stop advertising as soon as the pump connects.
        // Leaving the advertiser running through bonding made some Android 16 stacks drop the link.
        peripheral.stopAdvertising()
        // A pump connecting from any advertising state (first pair, scan, or reconnect) is "connected,
        // pre-auth"; AUTHENTICATING/CONNECTED are reached via onSakeSubscribed/handshake completion.
        when (_connectionState.value) {
            ConnectionState.SCANNING,
            ConnectionState.CONNECTING,
            ConnectionState.RECONNECTING -> _connectionState.value = ConnectionState.CONNECTING

            else -> Unit
        }
        onDiscovered?.invoke(
            DiscoveredDevice(
                name = localName,
                address = address,
                pluginId = PLUGIN_ID,
            ),
        )
    }

    private fun startAuthentication() {
        Timber.d("Pump subscribed to SAKE; starting handshake")
        _connectionState.value = ConnectionState.AUTHENTICATING
        // Progress arrived: the auth timeout below owns the rest of the window.
        sakeWatchdogJob?.cancel()
        sakeWatchdogJob = null
        armAuthTimeout()
        driver.onSubscribed()
    }

    private fun onCentralDisconnected(status: Int) {
        Timber.w("Pump disconnected (status=%d)", status)
        // A watchdog reset closes the server first; the stack's trailing disconnect is expected
        // and must not schedule a second recovery on top of the one already running.
        if (ignoreNextDisconnect) {
            ignoreNextDisconnect = false
            Timber.d("Ignoring disconnect after watchdog reset")
            return
        }
        val hadPeer = connectedAddress != null
        diagnosticSink?.invoke("GATT disconnect, status=$status")
        cancelTimers()
        resetSessionState()

        // A terminal auth failure latches AUTH_FAILED; the inevitable disconnect must not reset it
        // or kick off a reconnect loop against a pump that just rejected us.
        if (_connectionState.value == ConnectionState.AUTH_FAILED) {
            peripheral.stopAdvertising()
            return
        }

        if (!autoReconnect) {
            teardown(ConnectionState.DISCONNECTED, fault = null)
            return
        }

        // Radio off pauses everything: hold a readable RECONNECTING and let the adapter-on
        // callback resume. Never advertise into a dead radio.
        if (adapterOff) {
            Timber.d("Adapter off; holding RECONNECTING until it is back")
            _connectionState.value = ConnectionState.RECONNECTING
            return
        }

        if (credentialStore.isPaired()) {
            scheduleReadvertise(AdvertisingMode.RECONNECT, ConnectionState.RECONNECTING)
            return
        }

        // First pairing: the pump often drops the link once while it finishes its own side (bonding,
        // or clearing the previous mobile entry). Re-advertising keeps the GATT server alive so the
        // pump can come straight back; tearing everything down cost the user a manual retry for
        // every drop, and the retry then raced the pump's own reconnect.
        if (hadPeer) {
            scheduleReadvertise(AdvertisingMode.FIRST_PAIR, ConnectionState.CONNECTING)
            return
        }

        teardown(ConnectionState.DISCONNECTED, fault = null)
    }

    /**
     * Bluetooth adapter on/off, forwarded by the peripheral (on-device only). Off pauses the
     * session visibly instead of tearing it down; on resumes with a fresh advertise cycle.
     */
    private fun onAdapterStateChanged(enabled: Boolean) {
        if (!enabled) {
            if (adapterOff) return
            adapterOff = true
            Timber.w("Bluetooth adapter off; pausing session")
            diagnosticSink?.invoke("Bluetooth adapter off; pausing")
            // Freeze every pending timer: the auth timeout must not latch AUTH_FAILED while the
            // radio is dead, or resume would find autoReconnect already cleared.
            cancelTimers()
            peripheral.stopAdvertising()
            // The GATT server stays open so resume is a single advertise; surface the pause.
            if (autoReconnect) {
                _connectionState.value = ConnectionState.RECONNECTING
            } else {
                teardown(ConnectionState.DISCONNECTED, fault = null)
            }
            return
        }
        val wasOff = adapterOff
        adapterOff = false
        if (!autoReconnect && _connectionState.value != ConnectionState.SCANNING) return
        // Resume a session paused mid-flight, a start that found the radio off (DISCONNECTED),
        // or a discovery held in SCANNING.
        if (!wasOff && _connectionState.value != ConnectionState.DISCONNECTED) return
        Timber.d("Bluetooth adapter on; resuming")
        diagnosticSink?.invoke("Bluetooth adapter on; resuming")
        pairRetries = 0
        if (_connectionState.value == ConnectionState.SCANNING) {
            beginAdvertising(AdvertisingMode.FIRST_PAIR, ConnectionState.SCANNING)
        } else {
            val resumeState =
                if (lastMode == AdvertisingMode.RECONNECT) ConnectionState.RECONNECTING
                else ConnectionState.CONNECTING
            beginAdvertising(lastMode, resumeState)
        }
    }

    /**
     * Cold path with the radio off (hardware present but disabled): not a fault. Arms the
     * adapter watcher and holds [state]; the adapter-on callback resumes with [mode].
     * Returns false when the hardware is truly unsupported (PERIPHERAL_UNSUPPORTED stands).
     */
    private fun holdForAdapterOff(mode: AdvertisingMode, state: ConnectionState): Boolean {
        val android = peripheral as? AndroidMedtronicPeripheral ?: return false
        if (!android.isRadioOff()) return false
        _fault.value = null
        _connectionState.value = state
        adapterOff = true
        lastMode = mode
        android.watchAdapterState()
        diagnosticSink?.invoke("Bluetooth off; waiting for adapter")
        Timber.w("Bluetooth adapter off; waiting for it to be enabled")
        return true
    }

    // -- Handshake callbacks (worker thread) --------------------------------

    private fun onHandshakeComplete(session: MedtronicSakeSession) {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        pairRetries = 0
        sakeSession = session
        _fault.value = null
        // Single-peer from here on: the pump is authenticated, so no second central may connect.
        peripheral.stopAdvertising()
        // Persist the pairing. SAKE has no pairing code (authentication is the static-key handshake
        // plus the OS BLE bond), so the credential "code" slot records the advertised identity.
        connectedAddress?.let { credentialStore.savePairing(it, localName) }
        _connectionState.value = ConnectionState.CONNECTED
        Timber.i("Pump authenticated; session ready")
    }

    private fun onHandshakeFailed(cause: Throwable?) {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        sakeSession = null
        autoReconnect = false
        // Stop attracting the pump; the user must re-pair. Matches the handshake-timeout path. The
        // GATT server stays open (the pump will drop the link; onCentralDisconnected keeps AUTH_FAILED).
        peripheral.stopAdvertising()
        _fault.value = MedtronicConnectionFault.AUTH_FAILED
        _connectionState.value = ConnectionState.AUTH_FAILED
        Timber.e(cause, "SAKE authentication failed")
    }

    // -- Timers -------------------------------------------------------------

    private fun armAuthTimeout() {
        authTimeoutJob?.cancel()
        // The delay runs on [scope]; the check-then-set hops back onto the worker so it is serialized
        // with onHandshakeComplete -- the guard then can't flip an already-CONNECTED session.
        authTimeoutJob = scope.launch {
            delay(handshakeTimeoutMs)
            worker.post {
                if (_connectionState.value == ConnectionState.AUTHENTICATING) {
                    Timber.e("SAKE handshake timed out after %d ms", handshakeTimeoutMs)
                    _fault.value = MedtronicConnectionFault.HANDSHAKE_TIMEOUT
                    _connectionState.value = ConnectionState.AUTH_FAILED
                    autoReconnect = false
                    peripheral.stopAdvertising()
                }
            }
        }
    }

    private fun armPairingWaitTimer() {
        pairingWaitJob?.cancel()
        pairingWaitJob = scope.launch {
            delay(pairingWaitMs)
            worker.post {
                val state = _connectionState.value
                if (state == ConnectionState.CONNECTING || state == ConnectionState.SCANNING) {
                    Timber.w("No pump connected after %d ms", pairingWaitMs)
                    _fault.value = MedtronicConnectionFault.PAIRING_TIMEOUT
                }
            }
        }
    }

    private fun cancelTimers() {
        authTimeoutJob?.cancel()
        authTimeoutJob = null
        pairingWaitJob?.cancel()
        pairingWaitJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        sakeWatchdogJob?.cancel()
        sakeWatchdogJob = null
    }

    /** Re-advertise with backoff after a drop; once fast retries are spent, fall into economy mode. */
    private fun scheduleReadvertise(mode: AdvertisingMode, state: ConnectionState) {
        lastMode = mode
        if (pairRetries < MAX_PAIR_RETRIES) {
            val delayMs = RECONNECT_BACKOFF_MS[pairRetries.coerceIn(0, RECONNECT_BACKOFF_MS.lastIndex)]
            pairRetries++
            diagnosticSink?.invoke("Re-advertising after drop ($pairRetries/$MAX_PAIR_RETRIES)")
            _connectionState.value = state
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(delayMs)
                worker.post {
                    // A newer link supersedes this retry; never advertise into a dead radio.
                    if (!autoReconnect || adapterOff) return@post
                    if (_connectionState.value == state && connectedAddress == null) {
                        Timber.d("Re-advertising (%s) after %d ms backoff", mode, delayMs)
                        peripheral.advertise(mode, localName)
                    }
                }
            }
            return
        }
        enterEconomyMode(mode)
    }

    /**
     * Power-saving fallback once the fast retries are spent: hold a readable RECONNECTING and
     * refresh the advertisement every [ECONOMY_RETRY_DELAY_MS] instead of hot-looping, so a pump
     * that comes back later still finds us without draining the battery in the meantime.
     */
    private fun enterEconomyMode(mode: AdvertisingMode) {
        lastMode = mode
        _connectionState.value = ConnectionState.RECONNECTING
        Timber.w(
            "Retries spent; entering economy mode, re-advertising every %d s",
            ECONOMY_RETRY_DELAY_MS / 1_000,
        )
        diagnosticSink?.invoke("Economy mode: re-advertising every 60 s (RECONNECTING)")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (autoReconnect) {
                delay(ECONOMY_RETRY_DELAY_MS)
                worker.post {
                    if (!autoReconnect || adapterOff) return@post
                    if (connectedAddress != null) return@post
                    if (_connectionState.value != ConnectionState.RECONNECTING) return@post
                    Timber.d("Economy re-advertise (%s)", mode)
                    peripheral.advertise(mode, localName)
                }
            }
        }
    }

    /**
     * Guards the link-up -> SAKE-subscribe window: with no ATT/SAKE progress for
     * [sakeWatchdogTimeoutMs] the pump is stuck (e.g. it read 2A50 and never wrote the CCCD),
     * so the GATT server is reset and advertising restarts instead of hanging in CONNECTING.
     */
    private fun armSakeWatchdog() {
        sakeWatchdogJob?.cancel()
        sakeWatchdogJob = scope.launch {
            delay(sakeWatchdogTimeoutMs)
            worker.post { onSakeWatchdogTimeout() }
        }
    }

    private fun pokeSakeWatchdog() {
        if (_connectionState.value == ConnectionState.CONNECTING && sakeWatchdogJob != null) {
            Timber.d("SAKE watchdog poked by ATT progress")
            armSakeWatchdog()
        }
    }

    /** ATT reads/writes (incl. the 2A50/CCCD probes) are signs of life for the watchdog. */
    private fun isAttTraffic(line: String): Boolean =
        line.startsWith("read ") || line.startsWith("write ") ||
            line.startsWith("descriptor read") || line.startsWith("descriptor write") ||
            line.startsWith("mtu=")

    private fun onSakeWatchdogTimeout() {
        if (_connectionState.value != ConnectionState.CONNECTING) return
        Timber.w("SAKE watchdog timeout, resetting")
        diagnosticSink?.invoke("SAKE watchdog timeout, resetting")
        if (!autoReconnect || adapterOff) {
            peripheral.stopAdvertising()
            return
        }
        pairRetries++
        peripheral.stop()
        if (pairRetries <= MAX_PAIR_RETRIES) {
            diagnosticSink?.invoke("GATT server reset ($pairRetries/$MAX_PAIR_RETRIES)")
            beginAdvertising(lastMode, ConnectionState.CONNECTING)
            // The reset closed the server underneath the link; the stack's trailing disconnect
            // arrives later and must not schedule a second recovery on top of this one.
            ignoreNextDisconnect = true
        } else {
            cancelTimers()
            resetSessionState()
            enterEconomyMode(lastMode)
            // The economy loop only refreshes the advertisement, so reopen the server once here.
            peripheral.start(lastMode, localName, peripheralListener)
        }
    }

    // -- Helpers ------------------------------------------------------------

    private fun ensureSupported(): Boolean {
        if (peripheral.isSupported()) return true
        // Handled, expected condition surfaced via the PERIPHERAL_UNSUPPORTED fault -- WARN (breadcrumb),
        // not a Sentry-eligible error event.
        Timber.w("BLE peripheral advertising not supported on this device")
        _fault.value = MedtronicConnectionFault.PERIPHERAL_UNSUPPORTED
        _connectionState.value = ConnectionState.DISCONNECTED
        return false
    }

    private fun teardown(state: ConnectionState, fault: MedtronicConnectionFault?) {
        cancelTimers()
        ignoreNextDisconnect = false
        // Terminal intent (disconnect/unpair/scan-cancelled); a later adapter-on must not resume.
        adapterOff = false
        resetSessionState()
        peripheral.stop()
        _fault.value = fault
        _connectionState.value = state
    }

    companion object {
        /** Default advertised local name. Must match `Mobile .{0,7}`. TODO(48.A2/D): per-install identity. */
        const val DEFAULT_LOCAL_NAME = "Mobile 000001"

        /**
         * Canonical reverse-domain plugin id, stamped on discovered devices and reused as the
         * [pl.mgsupport.mglycemia.core.domain.plugin.PluginMetadata.id] by `MedtronicDevicePlugin`/
         * `MedtronicPluginFactory` so there is a single source of truth (B2 shipped a `"medtronic"`
         * placeholder; Milestone C set the canonical id here).
         */
        const val PLUGIN_ID = "com.glycemicgpt.medtronic"

        private const val SAKE_WORKER_THREAD_NAME = "medtronic-sake"

        /** SAKE has six round trips of 20-byte frames; 30s is generous even on a slow BLE link. */
        private const val DEFAULT_HANDSHAKE_TIMEOUT_MS = 30_000L

        /** First-pair window before suspecting the pump is bound to another phone (Sec. 7). */
        private const val DEFAULT_PAIRING_WAIT_MS = 60_000L

        /**
         * No ATT/SAKE progress this long after link-up means the pump is stuck: reset the server.
         * Real hardware can be slow: on a Realme 14 Pro+ the pump read the CCCD after ~12 s and
         * only subscribed ~19 s after link-up, so 15 s was too tight for a valid session. A fresh
         * pairing after unpair took ~44 s because the pump pauses ~22 s before reading the DIS table.
         */
        private const val DEFAULT_SAKE_WATCHDOG_TIMEOUT_MS = 60_000L

        /** Backoff between re-advertises after a drop: 1s, 2s, 5s, 10s, 30s, then economy mode. */
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /** How many dropped links are fast-retried before falling into economy mode. */
        private const val MAX_PAIR_RETRIES = 5

        /** Economy cadence: refresh the advertisement this often once fast retries are spent. */
        private const val ECONOMY_RETRY_DELAY_MS = 60_000L
    }
}
