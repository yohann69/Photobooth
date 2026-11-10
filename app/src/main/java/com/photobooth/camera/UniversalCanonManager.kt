package com.photobooth.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.photobooth.camera.camera2.Camera2Source
import com.photobooth.camera.camera2.TabletCamera2Engine
import com.photobooth.camera.ccapi.CanonCcapiEngine
import com.photobooth.camera.ccapi.CcapiDiscovery
import com.photobooth.camera.ptp.CanonUsbPtpEngine
import com.photobooth.camera.uvc.UvcPlusPtpEngine
import com.photobooth.core.PhotoboothLog.TAG
import com.photobooth.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the best available camera strategy at runtime and keeps it connected:
 * UVC+PTP -> USB PTP -> CCAPI -> tablet camera (automatic fallback). While on the fallback it
 * rescans periodically and on USB attach, and upgrades as soon as a Canon camera shows up.
 */
@Singleton
class UniversalCanonManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : CameraController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)
    private val discovery = CcapiDiscovery(context)

    private val _state = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    override val connectionState: StateFlow<CameraConnectionState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val liveViewFrameFlow: SharedFlow<Bitmap> = _frames.asSharedFlow()

    private val _rotation = MutableStateFlow(0)
    override val liveViewRotationDegrees: StateFlow<Int> = _rotation.asStateFlow()

    private val _activeStrategy = MutableStateFlow<CameraStrategy?>(null)
    val activeStrategy: StateFlow<CameraStrategy?> = _activeStrategy.asStateFlow()

    // Why no external camera is in use (null when none is plugged in or one is working); shown in the admin diagnostics.
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()
    @Volatile private var lastFailure: String? = null

    @Volatile private var active: CameraController? = null
    @Volatile var forcedStrategy: CameraStrategy? = null
        private set

    private val lock = Any()
    private var supervisor: Job? = null
    private var rateJob: Job? = null
    private var forwardJob: Job? = null
    private var receiverRegistered = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val captureMutex = Mutex()
    private val permissionResults = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val deniedDevices = ConcurrentHashMap.newKeySet<String>()
    private val frameCount = AtomicInteger(0)

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    device?.let { permissionResults[it.deviceName]?.complete(granted) }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    Log.i(TAG, "UniversalCanonManager: USB attached ${device?.describe()}")
                    device?.let { deniedDevices.remove(it.deviceName) }
                    wake.trySend(Unit)
                    // The camera service lists a new USB camera a moment after the attach broadcast.
                    scope.launch { repeat(3) { delay(2_500); wake.trySend(Unit) } }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    Log.i(TAG, "UniversalCanonManager: USB detached ${device?.describe()}")
                    val current = active
                    if (device != null && current is UsbBackedCamera && current.usbDeviceName == device.deviceName) {
                        scope.launch { current.disconnect() }
                    }
                    wake.trySend(Unit)
                }
            }
        }
    }

    // ---- Lifecycle ---------------------------------------------------------------------------

    override suspend fun connect(): Result<Unit> {
        start()
        return Result.success(Unit)
    }

    override suspend fun disconnect() = stop()

    fun start() {
        synchronized(lock) {
            if (supervisor?.isActive == true) return
            registerReceiver()
            val previous = supervisor
            Log.i(TAG, "UniversalCanonManager: starting")
            supervisor = scope.launch {
                previous?.join()
                supervise()
            }
            rateJob?.cancel()
            rateJob = scope.launch { reportStreamingRate() }
        }
    }

    fun stop() {
        synchronized(lock) {
            supervisor?.cancel()
            rateJob?.cancel()
            unregisterReceiver()
            Log.i(TAG, "UniversalCanonManager: stopping")
        }
    }

    /** Pin a strategy (null = automatic). The current camera is dropped and selection reruns. */
    fun setForcedStrategy(strategy: CameraStrategy?) {
        forcedStrategy = strategy
        val current = active
        if (current != null) scope.launch { current.disconnect() }
        wake.trySend(Unit)
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        receiverRegistered = false
    }

    // ---- Supervision loop --------------------------------------------------------------------

    private enum class Trigger { LOST, WAKE, TICK }

    private suspend fun supervise() {
        var current: CameraController? = null
        try {
            while (currentCoroutineContext().isActive) {
                if (current == null) {
                    _state.value = CameraConnectionState.Connecting("Searching for cameras")
                    lastFailure = null
                    current = connectBest(allowFallback = true)
                    if (current == null) {
                        _state.value = CameraConnectionState.Failed("No camera available")
                        delay(RETRY_DELAY_MS)
                        continue
                    }
                    activate(current)
                }
                when (awaitTrigger(current)) {
                    Trigger.LOST -> {
                        Log.w(TAG, "UniversalCanonManager: camera lost (${_activeStrategy.value}), reselecting")
                        deactivate(current)
                        current = null
                    }
                    Trigger.WAKE, Trigger.TICK -> {
                        if (current is TabletCamera2Engine) {
                            val better = connectBest(allowFallback = false)
                            if (better != null) {
                                Log.i(TAG, "UniversalCanonManager: upgrading from tablet camera")
                                val old = current
                                activate(better)
                                old.disconnect()
                                current = better
                            }
                        }
                    }
                }
            }
        } finally {
            val last = current
            withContext(NonCancellable) {
                last?.let { deactivate(it) }
                _state.value = CameraConnectionState.Disconnected
            }
        }
    }

    private suspend fun awaitTrigger(current: CameraController): Trigger = merge(
        current.connectionState.filter { it !is CameraConnectionState.Connected }.map { Trigger.LOST },
        wake.receiveAsFlow().map { Trigger.WAKE },
        flow {
            if (current is TabletCamera2Engine) {
                while (true) {
                    delay(RESCAN_INTERVAL_MS)
                    emit(Trigger.TICK)
                }
            } else {
                awaitCancellation()
            }
        },
    ).first()

    private fun activate(engine: CameraController) {
        forwardJob?.cancel()
        active = engine
        forwardJob = scope.launch {
            launch { engine.liveViewFrameFlow.collect { frameCount.incrementAndGet(); _frames.tryEmit(it) } }
            launch { engine.liveViewRotationDegrees.collect { _rotation.value = it } }
            launch { engine.connectionState.collect { _state.value = it } }
        }
        val connected = engine.connectionState.value as? CameraConnectionState.Connected
        _activeStrategy.value = connected?.camera?.strategy
        Log.i(TAG, "UniversalCanonManager: active = ${connected?.camera}")
    }

    private suspend fun deactivate(engine: CameraController) {
        if (active === engine) {
            forwardJob?.cancel()
            active = null
            _activeStrategy.value = null
        }
        runCatching { engine.disconnect() }
    }

    // ---- Strategy selection ------------------------------------------------------------------

    private suspend fun connectBest(allowFallback: Boolean): CameraController? {
        _notice.value = null
        val forced = forcedStrategy
        fun allowed(s: CameraStrategy) = forced == null || forced == s

        val canon = if (allowed(CameraStrategy.USB_PTP) || allowed(CameraStrategy.UVC_PLUS_PTP)) findCanonUsbDevice() else null
        Log.i(TAG, "UniversalCanonManager: scan usb=${canon?.describe() ?: "none"} usbDevices=${usbManager.deviceList.values.joinToString { it.describe() }} externalCameras=${Camera2Source.externalCameraIds(context)} forced=$forced")
        if (canon != null && ensureUsbPermission(canon)) {
            if (allowed(CameraStrategy.UVC_PLUS_PTP) && Camera2Source.hasExternalCamera(context)) {
                tryConnect(UvcPlusPtpEngine(context, usbManager, canon), "UVC_PLUS_PTP")?.let { return it }
            }
            if (allowed(CameraStrategy.USB_PTP)) {
                tryConnect(CanonUsbPtpEngine(context, usbManager, canon), "USB_PTP")?.let { return it }
            }
        }

        if (allowed(CameraStrategy.CCAPI)) {
            val saved = settings.settings.first()
            val endpoint = discovery.discover(saved.ccapiHost, saved.ccapiPort)
            Log.i(TAG, "UniversalCanonManager: scan ccapi=${endpoint ?: "none"}")
            if (endpoint != null) {
                val engine = CanonCcapiEngine(context, endpoint.host, endpoint.port)
                tryConnect(engine, "CCAPI")?.let {
                    if (endpoint.host != saved.ccapiHost || endpoint.port != saved.ccapiPort) settings.setCcapi(endpoint.host, endpoint.port)
                    return it
                }
            }
        }

        _notice.value = diagnoseMissingCamera(canon)

        if (allowFallback) return tryConnect(TabletCamera2Engine(context), "TABLET_CAMERA")
        return null
    }

    private suspend fun tryConnect(engine: CameraController, label: String): CameraController? {
        val started = SystemClock.elapsedRealtime()
        val result = engine.connect()
        return if (result.isSuccess) {
            Log.i(TAG, "UniversalCanonManager: $label connected in ${SystemClock.elapsedRealtime() - started} ms")
            engine
        } else {
            lastFailure = result.exceptionOrNull()?.message
            Log.w(TAG, "UniversalCanonManager: $label unavailable: $lastFailure")
            runCatching { engine.disconnect() }
            null
        }
    }

    private fun findCanonUsbDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.vendorId == CANON_VENDOR_ID && hasPtpInterface(it) }

    /** Only real stills are accepted, so a camera that exposes just a video stream is reported rather than used. */
    private fun diagnoseMissingCamera(canonWithPtp: UsbDevice?): String? {
        val devices = usbManager.deviceList.values
        return when {
            canonWithPtp != null -> "Appareil Canon détecté mais connexion photo impossible: ${lastFailure ?: "raison inconnue"}"
            devices.any { it.vendorId == CANON_VENDOR_ID } ->
                "Appareil Canon branché sans mode photo USB (PTP). Mettez-le en mode photo normal, pas en mode webcam."
            findUvcDevice() != null ->
                "Un appareil en mode webcam est branché : il ne fournit qu'un flux vidéo, pas de vraie photo. Il est ignoré."
            else -> null
        }
    }

    private fun findUvcDevice(): UsbDevice? =
        usbManager.deviceList.values
            .filter { d -> (0 until d.interfaceCount).any { d.getInterface(it).let { i -> i.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_VIDEO && i.interfaceSubclass == 2 } } }
            .sortedByDescending { it.vendorId == CANON_VENDOR_ID }
            .firstOrNull()

    private fun hasPtpInterface(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any {
            val c = device.getInterface(it).interfaceClass
            c == android.hardware.usb.UsbConstants.USB_CLASS_STILL_IMAGE || c == android.hardware.usb.UsbConstants.USB_CLASS_VENDOR_SPEC
        }

    private suspend fun ensureUsbPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        if (device.deviceName in deniedDevices) return false
        val pending = CompletableDeferred<Boolean>()
        permissionResults[device.deviceName] = pending
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        Log.i(TAG, "UniversalCanonManager: requesting USB permission for ${device.describe()}")
        usbManager.requestPermission(device, pi)
        val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) { pending.await() } ?: false
        Log.i(TAG, "UniversalCanonManager: USB permission for ${device.describe()} granted=$granted")
        permissionResults.remove(device.deviceName)
        if (!granted) deniedDevices.add(device.deviceName)
        return granted
    }

    private fun UsbDevice.describe() = "$deviceName vid=0x${vendorId.toString(16)} pid=0x${productId.toString(16)} ${productName.orEmpty()}"

    // ---- Capture & telemetry -----------------------------------------------------------------

    override suspend fun captureHighResPhoto(targetFile: File): Result<File> {
        val engine = active ?: return Result.failure(IllegalStateException("No camera connected"))
        return captureMutex.withLock { engine.captureHighResPhoto(targetFile) }
    }

    override suspend fun prepareCapture() {
        active?.prepareCapture()
    }

    override suspend fun readBatteryLevel(): String? = active?.readBatteryLevel()

    override suspend fun deleteFromCamera(file: File): Boolean = active?.deleteFromCamera(file) ?: true

    private suspend fun reportStreamingRate() {
        var lastCount = frameCount.get()
        var lastTime = SystemClock.elapsedRealtime()
        while (true) {
            delay(FPS_LOG_INTERVAL_MS)
            val count = frameCount.get()
            val now = SystemClock.elapsedRealtime()
            val fps = (count - lastCount) * 1000f / (now - lastTime)
            lastCount = count
            lastTime = now
            if (fps > 0f) Log.i(TAG, "UniversalCanonManager: streaming %.1f fps via %s".format(fps, _activeStrategy.value))
        }
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.photobooth.USB_PERMISSION"
        const val CANON_VENDOR_ID = 0x04A9
        const val RESCAN_INTERVAL_MS = 10_000L
        const val RETRY_DELAY_MS = 3_000L
        const val PERMISSION_TIMEOUT_MS = 30_000L
        const val FPS_LOG_INTERVAL_MS = 2_000L
    }
}
