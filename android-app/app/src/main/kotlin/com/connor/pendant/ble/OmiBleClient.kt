package com.connor.pendant.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.os.Build
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.UUID
/**
 * Minimal raw-Android BluetoothGatt wrapper for M1. No Nordic library yet — M2 swaps to
 * `no.nordicsemi.android.kotlin.ble` for clean coroutine semantics. For now we just need
 * to prove the GATT subscription works end-to-end.
 *
 * Battery-mode lifecycle: scan on explicit sync → connect → enable storage notify →
 * drain offline files → emit reconstructed raw frames → disconnect.
 */
@SuppressLint("MissingPermission")  // permissions checked at activity layer before construct
class OmiBleClient(private val context: Context) {

    enum class State { IDLE, SCANNING, CONNECTING, DISCOVERING, READY, SUBSCRIBED, SYNCING, DISCONNECTED }
    enum class FrameSource { LIVE, STORAGE }

    data class AudioFrame(
        val bytes: ByteArray,
        val source: FrameSource,
    )

    data class StorageFilePayload(
        val index: Int,
        val timestamp: Long,
        val size: Long,
        val frames: List<ByteArray>,
    )

    data class StorageSyncStatus(
        val active: Boolean = false,
        val filesTotal: Int = 0,
        val filesDone: Int = 0,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
        val framesDone: Long = 0,
        val cleanupActive: Boolean = false,
        val cleanupDeleted: Int = 0,
        val cleanupFailed: Int = 0,
        val cleanupLastIndex: Int? = null,
        val cleanupLastStatus: String? = null,
        val missingTimestampFiles: Int = 0,
        val lastCompletedAt: Long = 0L,
        val lastError: String? = null,
    )

    data class StorageStatsStatus(
        val active: Boolean = false,
        val proofActive: Boolean = false,
        val usedBytes: Long = -1L,
        val fileCount: Int = -1,
        val freeBytes: Long? = null,
        val statusFlags: Long = 0L,
        val previousUsedBytes: Long = -1L,
        val previousFileCount: Int = -1,
        val deltaBytes: Long? = null,
        val deltaFiles: Int? = null,
        val lastReadAt: Long = 0L,
        val lastError: String? = null,
        val message: String? = null,
    )

    data class MicGainStatus(
        val value: Int? = null,
        val target: Int = CAPTURE_MIC_GAIN_LEVEL,
        val lastSetAt: Long = 0L,
        val lastError: String? = null,
    )

    enum class ButtonEvent(val code: Int, val label: String) {
        IDLE(0, "Idle"),
        SINGLE_TAP(1, "Single tap"),
        DOUBLE_TAP(2, "Double tap"),
        LONG_TAP(3, "Long press"),
        PRESS(4, "Pressed"),
        RELEASE(5, "Released"),
        TRIPLE_TAP(6, "Triple tap"),
        UNKNOWN(-1, "Unknown");

        companion object {
            fun fromCode(code: Int): ButtonEvent =
                values().firstOrNull { it.code == code } ?: UNKNOWN
        }
    }

    data class ButtonStatus(
        val event: ButtonEvent = ButtonEvent.IDLE,
        val rawCode: Int = 0,
        val receivedAt: Long = 0L,
        val subscribed: Boolean = false,
    )

    private data class StorageFile(
        val index: Int,
        val timestamp: Long,
        val size: Long,
    )

    private val tag = "OmiBleClient"
    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = btManager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("omi_ble_client", Context.MODE_PRIVATE)

    private fun isDeviceActuallyConnected(): Boolean {
        val g = gatt ?: return false
        val device = g.device ?: return false
        return try {
            btManager.getConnectionState(device, BluetoothProfile.GATT) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    private fun writeCharacteristicCompat(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, value, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = value
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error writing characteristic ${ch.uuid}: ${e.message}", e)
            false
        }
    }

    private fun writeDescriptorCompat(
        g: BluetoothGatt,
        d: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error writing descriptor ${d.uuid}: ${e.message}", e)
            false
        }
    }

    private var gatt: BluetoothGatt? = null
    private var scanGeneration = 0

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _codec = MutableStateFlow(OmiUuids.Codec.UNKNOWN)
    val codec: StateFlow<OmiUuids.Codec> = _codec.asStateFlow()

    private val _micGain = MutableStateFlow(MicGainStatus())
    val micGain: StateFlow<MicGainStatus> = _micGain.asStateFlow()

    private val _storageSync = MutableStateFlow(StorageSyncStatus())
    val storageSync: StateFlow<StorageSyncStatus> = _storageSync.asStateFlow()

    private val _storageStats = MutableStateFlow(StorageStatsStatus())
    val storageStats: StateFlow<StorageStatsStatus> = _storageStats.asStateFlow()

    private val _buttonStatus = MutableStateFlow(ButtonStatus())
    val buttonStatus: StateFlow<ButtonStatus> = _buttonStatus.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    data class SettingsTelemetry(
        val batteryVoltage: Int,
        val batteryPercentage: Int,
        val chargingState: Int,
        val sdBacklog: Long,
        val micStatus: Int,
        val vadStatus: Int,
        val codecStatus: Int,
        val sdStatus: Int
    )

    private val _settingsTelemetry = MutableStateFlow<SettingsTelemetry?>(null)
    val settingsTelemetry: StateFlow<SettingsTelemetry?> = _settingsTelemetry.asStateFlow()

    private val _buttonEvents = MutableSharedFlow<ButtonStatus>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val buttonEvents: SharedFlow<ButtonStatus> = _buttonEvents.asSharedFlow()

    /**
     * Live notifications are best-effort, but storage replay must not silently drop.
     * Storage frames use suspending emit in [emitStorageFrame].
     */
    private val _frames = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val frames: SharedFlow<ByteArray> = _frames.asSharedFlow()

    private val _frameEvents = MutableSharedFlow<AudioFrame>(
        replay = 0,
        extraBufferCapacity = 256
    )
    val frameEvents: SharedFlow<AudioFrame> = _frameEvents.asSharedFlow()

    private var frameCount = 0L
    fun frameCount(): Long = frameCount
    private var droppedLiveFrames = 0L

    @Volatile var lastError: String? = null
        private set
    @Volatile var deviceAddr: String? = null
        private set
    @Volatile var mtu: Int = 0
        private set
    @Volatile var lastFrameSize: Int = 0
        private set

    private val _batteryPct = MutableStateFlow<Int?>(null)
    val batteryPct: StateFlow<Int?> = _batteryPct.asStateFlow()
    @Volatile var lastBatteryReadAt: Long = 0L
        private set
    @Volatile var lastRssi: Int? = null
        private set

    private var pendingStorageSync = false
    private var pendingStorageStatsRead = false
    private var storageStatsDisconnectAfterRead = false
    private var storageStatsProofStep = 0
    private var storageStatsProofGeneration = 0
    private var proofBaselineUsedBytes = -1L
    private var proofBaselineFileCount = -1
    private var proofWindowMs = STORAGE_PROOF_WINDOW_MS
    private var pendingLiveStream = false
    private var pendingButtonWatch = false
    private var pendingManualMicGain: Int? = null
    private var micGainWriteInFlight = false
    private var micGainWriteTarget = CAPTURE_MIC_GAIN_LEVEL
    private var afterMicGain = AfterMicGain.NONE
    private var storageNotifyEnabled = false
    private val notifyQueue = ArrayDeque<BluetoothGattCharacteristic>()
    private val notifyEnabled = mutableSetOf<UUID>()
    private var notifyWriteInFlight = false
    private var storageFiles: List<StorageFile> = emptyList()
    private var currentStorageFile: StorageFile? = null
    private var currentStorageBytes = ByteArrayOutputStream()
    private var storageReadGeneration = 0
    private var lastStorageReadProgressAt = 0L
    private var lastStorageReadProgressLogBytes = 0
    private var pendingStorageDelete: PendingStorageDelete? = null
    private var storageDeleteGeneration = 0
    private var storageCleanupDeleted = 0
    private var storageCleanupFailed = 0
    private var storageFileSafetyCheckpoint: (suspend (StorageFilePayload) -> Boolean)? = null
    private val completedStorageFileKeys = mutableSetOf<String>()
    private val skippedStorageFileKeys = mutableSetOf<String>()
    private var storageRetryGeneration = 0
    private var storageRetryAttempts = 0

    private enum class AfterMicGain { NONE, STORAGE_SYNC, LIVE_STREAM, DISCONNECT }

    private data class PendingStorageDelete(
        val file: StorageFile,
        val doneCount: Int,
        val generation: Int,
    )

    private fun StorageFile.key(): String = "$timestamp:$size"

    // ---- Scanning ----

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            if (name !in OmiUuids.ADVERTISED_NAMES) return
            Log.i(tag, "Found pendant: name=$name addr=${device.address} rssi=${result.rssi}")
            deviceAddr = "${device.address} (rssi=${result.rssi})"
            prefs.edit().putString(PREF_LAST_DEVICE_ADDRESS, device.address.uppercase()).apply()
            lastRssi = result.rssi
            scanGeneration++
            adapter.bluetoothLeScanner?.stopScan(this)
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "Scan failed: $errorCode")
            scanGeneration++
            lastError = "Scan failed code=$errorCode"
            failStorageSyncIfActive(lastError ?: "Scan failed code=$errorCode")
            failStorageStatsIfActive(lastError ?: "Scan failed code=$errorCode")
            _state.value = State.IDLE
        }
    }

    fun startScan(useCachedAddress: Boolean = true) {
        if (_state.value != State.IDLE && _state.value != State.DISCONNECTED) return
        if (adapter == null) { lastError = "BluetoothAdapter null"; return }
        if (!adapter.isEnabled) { lastError = "Bluetooth is OFF"; return }
        if (useCachedAddress) {
            val cached = prefs.getString(PREF_LAST_DEVICE_ADDRESS, null)
            if (!cached.isNullOrBlank() && connectCachedAddress(cached)) return
        }
        val scanner = adapter.bluetoothLeScanner ?: run { lastError = "No BLE scanner"; return }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        _state.value = State.SCANNING
        val generation = ++scanGeneration
        scanner.startScan(null, settings, scanCallback)
        Log.i(tag, "Scanning broadly for ${OmiUuids.ADVERTISED_NAMES}")
        handler.postDelayed({
            if (scanGeneration == generation && _state.value == State.SCANNING) {
                adapter.bluetoothLeScanner?.stopScan(scanCallback)
                lastError = "Pendant scan timed out. Bring Omi nearby, keep it awake, then tap Reconnect Pendant."
                Log.w(tag, lastError ?: "Pendant scan timed out")
                failStorageSyncIfActive(lastError ?: "Pendant scan timed out")
                failStorageStatsIfActive(lastError ?: "Pendant scan timed out")
                _state.value = State.DISCONNECTED
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun connectCachedAddress(address: String): Boolean {
        val adapter = adapter ?: return false
        return try {
            val addr = address.uppercase()
            val device = if (Build.VERSION.SDK_INT >= 34) {
                adapter.getRemoteLeDevice(addr, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            } else {
                adapter.getRemoteDevice(addr)
            }
            val generation = ++scanGeneration
            deviceAddr = "$addr (cached)"
            Log.i(tag, "Connecting to cached pendant address=$addr")
            connect(device, autoConnect = false)
            handler.postDelayed({
                if (scanGeneration == generation && _state.value == State.CONNECTING) {
                    Log.w(tag, "Cached pendant connect timed out; falling back to broad scan")
                    gatt?.disconnect()
                    gatt?.close()
                    gatt = null
                    _state.value = State.IDLE
                    startScan(useCachedAddress = false)
                }
            }, DIRECT_CONNECT_TIMEOUT_MS)
            true
        } catch (t: Throwable) {
            Log.w(tag, "Cached pendant address unusable: ${t.message}")
            false
        }
    }

    fun syncStorageNow() {
        syncStorageNowInternal(fromRetry = false)
    }

    private fun syncStorageNowInternal(fromRetry: Boolean) {
        if (!fromRetry) {
            storageRetryGeneration++
            storageRetryAttempts = 0
        }
        pendingStorageSync = true
        lastError = null
        resetStorageCleanupState()
        _storageSync.value = StorageSyncStatus(active = true)

        when (_state.value) {
            State.READY -> startStorageSyncAfterGain(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Storage sync queued while state=${_state.value}")
        }
    }

    fun probeStorageStats() {
        storageStatsProofStep = 0
        storageStatsProofGeneration++
        beginStorageStatsRead(
            disconnectAfterRead = _state.value in setOf(State.IDLE, State.DISCONNECTED),
            message = "Reading pendant flash counters",
        )
    }

    fun proveRecordingToStorage(windowMs: Long = STORAGE_PROOF_WINDOW_MS) {
        if (_state.value == State.SYNCING || _storageSync.value.active) {
            failStorageStats("Wait for the current storage sync to finish, then check recording proof.")
            return
        }
        if (_state.value == State.SUBSCRIBED || pendingLiveStream) {
            failStorageStats("Live capture is connected; the live frame counter is the proof. Stop Live to test offline flash growth.")
            return
        }

        val generation = ++storageStatsProofGeneration
        storageStatsProofStep = 1
        proofBaselineUsedBytes = -1L
        proofBaselineFileCount = -1
        proofWindowMs = windowMs.coerceIn(3_000L, 60_000L)
        _storageStats.value = StorageStatsStatus(
            active = true,
            proofActive = true,
            message = "Reading flash before sample",
        )
        beginStorageStatsRead(
            disconnectAfterRead = true,
            message = "Reading flash before sample",
            generation = generation,
        )
    }

    fun setStorageFileSafetyCheckpoint(checkpoint: (suspend (StorageFilePayload) -> Boolean)?) {
        storageFileSafetyCheckpoint = checkpoint
    }

    fun startLiveStream() {
        pendingLiveStream = true
        lastError = null

        when (_state.value) {
            State.READY, State.SUBSCRIBED -> startLiveStreamAfterGain(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Live stream queued while state=${_state.value}")
        }
    }

    fun setMicGain(level: Int) {
        val target = level.coerceIn(MIC_GAIN_MIN, MIC_GAIN_MAX)
        pendingManualMicGain = target
        lastError = null

        val g = gatt
        if (g != null && _state.value in setOf(State.READY, State.SUBSCRIBED, State.SYNCING)) {
            if (writeMicGain(g, target, AfterMicGain.DISCONNECT)) return
            pendingManualMicGain = null
            disconnect()
            return
        }

        when (_state.value) {
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Mic gain=$target queued while state=${_state.value}")
        }
    }

    fun armButtonWatch() {
        pendingButtonWatch = true
        lastError = null

        when (_state.value) {
            State.READY, State.SUBSCRIBED, State.SYNCING -> queueButtonNotify(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Button watch queued while state=${_state.value}")
        }
    }

    fun stopLiveStream() {
        pendingLiveStream = false
        disconnect()
    }

    fun playHaptic(pulseType: Byte = 2): Boolean {
        val g = gatt ?: return false
        val service = g.getService(OmiUuids.HAPTIC_SERVICE) ?: run {
            Log.w(tag, "Haptic service not found")
            return false
        }
        val ch = service.getCharacteristic(OmiUuids.HAPTIC_CHAR) ?: run {
            Log.w(tag, "Haptic characteristic not found")
            return false
        }
        val success = writeCharacteristicCompat(
            g,
            ch,
            byteArrayOf(pulseType),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        Log.i(tag, "Write haptic value=$pulseType success=$success")
        return success
    }

    fun stopScan() {
        scanGeneration++
        adapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // ---- GATT connection ----

    private fun connect(device: BluetoothDevice, autoConnect: Boolean = false) {
        _state.value = State.CONNECTING
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, autoConnect, gattCallback)
        }
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        pendingStorageSync = false
        pendingStorageStatsRead = false
        storageStatsDisconnectAfterRead = false
        pendingLiveStream = false
        pendingButtonWatch = false
        pendingManualMicGain = null
        micGainWriteInFlight = false
        afterMicGain = AfterMicGain.NONE
        storageNotifyEnabled = false
        notifyQueue.clear()
        notifyEnabled.clear()
        notifyWriteInFlight = false
        val button = _buttonStatus.value
        _buttonStatus.value = button.copy(subscribed = false)
        storageFiles = emptyList()
        currentStorageFile = null
        currentStorageBytes.reset()
        pendingStorageDelete = null
        completedStorageFileKeys.clear()
        skippedStorageFileKeys.clear()
        failStorageSyncIfActive("BLE disconnected during storage sync")
        failStorageStatsIfActive("BLE disconnected during storage stats read")
        _state.value = State.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(tag, "ConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = State.DISCOVERING
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    g.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val disconnectError = if (status != 0) {
                        "GATT disconnect status=$status"
                    } else {
                        "GATT disconnected during storage sync"
                    }
                    if (status != 0) lastError = disconnectError
                    _state.value = State.DISCONNECTED
                    failStorageSyncIfActive(disconnectError)
                    failStorageStatsIfActive(disconnectError)
                    pendingStorageSync = false
                    pendingStorageStatsRead = false
                    storageStatsDisconnectAfterRead = false
                    pendingLiveStream = false
                    pendingButtonWatch = false
                    pendingManualMicGain = null
                    micGainWriteInFlight = false
                    afterMicGain = AfterMicGain.NONE
                    storageNotifyEnabled = false
                    notifyQueue.clear()
                    notifyEnabled.clear()
                    notifyWriteInFlight = false
                    val button = _buttonStatus.value
                    _buttonStatus.value = button.copy(subscribed = false)
                    pendingStorageDelete = null
                    completedStorageFileKeys.clear()
                    skippedStorageFileKeys.clear()
                    g.close()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtuNegotiated: Int, status: Int) {
            Log.i(tag, "MTU negotiated: $mtuNegotiated (status=$status)")
            mtu = mtuNegotiated
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(tag, "Service discovery failed: $status")
                lastError = "Service discovery failed=$status"
                failStorageSyncIfActive(lastError ?: "Service discovery failed=$status")
                failStorageStatsIfActive(lastError ?: "Service discovery failed=$status")
                return
            }
            handler.postDelayed({
                if (gatt != g) return@postDelayed
                Log.i(tag, "Services discovered (${g.services.size}) - executing post-discovery actions")
                if (pendingStorageSync) {
                    startStorageSyncAfterGain(g)
                    return@postDelayed
                }

                _state.value = State.READY
                if (pendingStorageStatsRead && !pendingLiveStream) {
                    readStorageStats(g)
                    return@postDelayed
                }
                pendingManualMicGain?.let { target ->
                    if (writeMicGain(g, target, AfterMicGain.DISCONNECT)) return@postDelayed
                    pendingManualMicGain = null
                    disconnect()
                    return@postDelayed
                }

                val codecChar = g.getService(OmiUuids.AUDIO_SERVICE)
                    ?.getCharacteristic(OmiUuids.AUDIO_CODEC_CHAR)
                if (pendingLiveStream) {
                    startLiveStreamAfterGain(g)
                } else if (codecChar != null) {
                    val readSuccess = g.readCharacteristic(codecChar)
                    if (!readSuccess) {
                        Log.w(tag, "Failed to read codec characteristic; falling back to refreshBattery")
                        refreshBattery()
                    }
                } else {
                    if (pendingButtonWatch) {
                        queueButtonNotify(g)
                    }
                    refreshBattery()
                }
            }, 250)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (ch.uuid == OmiUuids.SETTINGS_MIC_GAIN_CHAR) {
                handleMicGainWrite(g, status)
            }
        }

        @Deprecated("Override for SDK 33+; old signature also called")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharRead(g, ch, ch.value, status)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharRead(g, ch, value, status)
        }

        private fun handleCharRead(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (ch.uuid == OmiUuids.AUDIO_CODEC_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                val codec = if (value.isNotEmpty()) OmiUuids.Codec.fromByte(value[0]) else OmiUuids.Codec.UNKNOWN
                _codec.value = codec
                Log.i(tag, "Audio codec: $codec (raw byte=0x${value.firstOrNull()?.toInt()?.and(0xFF)?.toString(16) ?: "?"})")
                if (pendingLiveStream) {
                    beginLiveStream(g)
                } else if (pendingButtonWatch) {
                    queueButtonNotify(g)
                } else {
                    refreshBattery()
                }
            } else if (ch.uuid == OmiUuids.BATTERY_LEVEL_CHAR && status == BluetoothGatt.GATT_SUCCESS) {
                val pct = if (value.isNotEmpty()) (value[0].toInt() and 0xFF) else -1
                if (pct in 0..100) {
                    _batteryPct.value = pct
                    lastBatteryReadAt = System.currentTimeMillis()
                    Log.i(tag, "Battery: $pct%")
                }
            } else if (ch.uuid == OmiUuids.STORAGE_READ_CHAR) {
                handleStorageStatsRead(g, value, status)
            }
        }

        @Deprecated("Override for SDK 33+; old signature also called")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic
        ) {
            handleNotify(ch.uuid, ch.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            ch: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotify(ch.uuid, value)
        }

        private fun handleNotify(uuid: java.util.UUID, value: ByteArray) {
            when (uuid) {
                OmiUuids.AUDIO_DATA_CHAR -> {
                    frameCount++
                    lastFrameSize = value.size
                    if (frameCount % 100L == 0L) {
                        Log.d(tag, "frames=$frameCount last_size=${value.size} hdr=${value.take(3).joinToString("") { "%02x".format(it) }}")
                    }
                    emitLiveFrame(value)
                }
                OmiUuids.STORAGE_WRITE_CHAR -> handleStorageNotify(value)
                OmiUuids.BUTTON_STATE_CHAR -> handleButtonNotify(value)
                OmiUuids.SETTINGS_TELEMETRY_CHAR -> handleSettingsTelemetryNotify(value)
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == OmiUuids.CCC_DESCRIPTOR) {
                val chUuid = descriptor.characteristic?.uuid
                notifyWriteInFlight = false
                if (status == BluetoothGatt.GATT_SUCCESS && chUuid != null) {
                    notifyEnabled += chUuid
                } else {
                    lastError = "Notify enable failed status=$status"
                    Log.w(tag, "${lastError} char=$chUuid")
                    drainNotifyQueue(g)
                    return
                }
                if (chUuid == OmiUuids.STORAGE_WRITE_CHAR) {
                    storageNotifyEnabled = true
                    Log.i(tag, "Storage notifications enabled")
                    requestStorageFileList(g)
                } else if (chUuid == OmiUuids.AUDIO_DATA_CHAR) {
                    _state.value = State.SUBSCRIBED
                    Log.i(tag, "Audio notifications enabled")
                    refreshBattery()
                } else if (chUuid == OmiUuids.BUTTON_STATE_CHAR) {
                    val current = _buttonStatus.value
                    _buttonStatus.value = current.copy(subscribed = true)
                    Log.i(tag, "Button notifications enabled")
                    if (notifyQueue.isEmpty()) refreshBattery()
                } else if (chUuid == OmiUuids.SETTINGS_TELEMETRY_CHAR) {
                    Log.i(tag, "Settings telemetry notifications enabled")
                    if (notifyQueue.isEmpty()) refreshBattery()
                } else {
                    _state.value = State.READY
                    refreshBattery()
                }
                drainNotifyQueue(g)
            }
        }
    }

    private fun startStorageSyncAfterGain(g: BluetoothGatt) {
        if (writeMicGain(g, CAPTURE_MIC_GAIN_LEVEL, AfterMicGain.STORAGE_SYNC)) return
        beginStorageSync(g)
    }

    private fun startLiveStreamAfterGain(g: BluetoothGatt) {
        if (writeMicGain(g, CAPTURE_MIC_GAIN_LEVEL, AfterMicGain.LIVE_STREAM)) return
        readCodecThenLive(g)
    }

    private fun readCodecThenLive(g: BluetoothGatt) {
        val codecChar = g.getService(OmiUuids.AUDIO_SERVICE)
            ?.getCharacteristic(OmiUuids.AUDIO_CODEC_CHAR)
        if (codecChar != null && g.readCharacteristic(codecChar)) return
        beginLiveStream(g)
    }

    private fun writeMicGain(g: BluetoothGatt, level: Int, after: AfterMicGain): Boolean {
        if (micGainWriteInFlight) {
            if (after != AfterMicGain.NONE) afterMicGain = after
            Log.i(tag, "Mic gain write already in flight; continuing after current write")
            return true
        }

        val target = level.coerceIn(MIC_GAIN_MIN, MIC_GAIN_MAX)
        val ch = g.getService(OmiUuids.SETTINGS_SERVICE)
            ?.getCharacteristic(OmiUuids.SETTINGS_MIC_GAIN_CHAR) ?: run {
                val error = "Mic gain characteristic not found"
                Log.w(tag, error)
                _micGain.value = _micGain.value.copy(target = target, lastError = error)
                return false
            }

        micGainWriteTarget = target
        afterMicGain = after
        micGainWriteInFlight = writeCharacteristicCompat(
            g,
            ch,
            byteArrayOf(target.toByte()),
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        )
        if (!micGainWriteInFlight) {
            val error = "Failed to write mic gain=$target"
            afterMicGain = AfterMicGain.NONE
            Log.w(tag, error)
            _micGain.value = _micGain.value.copy(target = target, lastError = error)
            return false
        }

        Log.i(tag, "Setting mic gain=$target (${micGainLabel(target)})")
        return true
    }

    private fun handleMicGainWrite(g: BluetoothGatt, status: Int) {
        micGainWriteInFlight = false
        val target = micGainWriteTarget
        val next = afterMicGain
        afterMicGain = AfterMicGain.NONE
        pendingManualMicGain = null

        if (status == BluetoothGatt.GATT_SUCCESS) {
            _micGain.value = MicGainStatus(
                value = target,
                target = CAPTURE_MIC_GAIN_LEVEL,
                lastSetAt = System.currentTimeMillis(),
            )
            Log.i(tag, "Mic gain set to $target (${micGainLabel(target)})")
        } else {
            val error = "Mic gain write failed status=$status"
            _micGain.value = _micGain.value.copy(target = target, lastError = error)
            Log.w(tag, error)
        }

        when (next) {
            AfterMicGain.STORAGE_SYNC -> beginStorageSync(g)
            AfterMicGain.LIVE_STREAM -> readCodecThenLive(g)
            AfterMicGain.DISCONNECT -> disconnect()
            AfterMicGain.NONE -> Unit
        }
    }

    /**
     * Read the standard BLE Battery Level characteristic. Safe to call repeatedly;
     * does nothing if we're not connected. Updates [batteryPct] + [lastBatteryReadAt]
     * via the GATT callback path.
     */
    fun refreshBattery() {
        val g = gatt ?: return
        if (!isDeviceActuallyConnected()) {
            Log.w(tag, "refreshBattery: Device is not actually connected according to BluetoothManager. Forcing disconnect.")
            disconnect()
            return
        }
        val ch = g.getService(OmiUuids.BATTERY_SERVICE)
            ?.getCharacteristic(OmiUuids.BATTERY_LEVEL_CHAR) ?: run {
                Log.w(tag, "Battery service/char not found")
                return
            }
        try {
            val success = g.readCharacteristic(ch)
            if (!success) {
                Log.w(tag, "refreshBattery: readCharacteristic returned false")
            }
        } catch (e: Exception) {
            Log.e(tag, "refreshBattery error: ${e.message}", e)
            disconnect()
        }
    }

    private fun beginStorageStatsRead(
        disconnectAfterRead: Boolean,
        message: String,
        generation: Int = storageStatsProofGeneration,
    ) {
        if (_state.value == State.SYNCING || _storageSync.value.active) {
            failStorageStats("Wait for the current storage sync to finish, then check recording proof.")
            return
        }

        pendingStorageStatsRead = true
        storageStatsDisconnectAfterRead = disconnectAfterRead
        val current = _storageStats.value
        _storageStats.value = current.copy(
            active = true,
            proofActive = storageStatsProofStep > 0 || current.proofActive,
            lastError = null,
            message = message,
        )

        when (_state.value) {
            State.READY, State.SUBSCRIBED -> readStorageStats(gatt ?: return)
            State.IDLE, State.DISCONNECTED -> startScan()
            else -> Log.i(tag, "Storage stats read queued while state=${_state.value} generation=$generation")
        }
    }

    private fun readStorageStats(g: BluetoothGatt) {
        val ch = g.getService(OmiUuids.STORAGE_SERVICE)
            ?.getCharacteristic(OmiUuids.STORAGE_READ_CHAR) ?: run {
                failStorageStats("Storage stats characteristic not found")
                return
            }
        if (!g.readCharacteristic(ch)) {
            failStorageStats("Failed to read storage stats characteristic")
        }
    }

    private fun handleStorageStatsRead(g: BluetoothGatt, value: ByteArray, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            failStorageStats("Storage stats read failed status=$status")
            return
        }
        if (value.size < 8) {
            failStorageStats("Storage stats payload too short (${value.size} bytes)")
            return
        }

        val current = _storageStats.value
        val usedBytes = readU32Le(value, 0)
        val fileCount = readU32Le(value, 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val freeBytes = if (value.size >= 12) readU32Le(value, 8) else null
        val statusFlags = if (value.size >= 16) readU32Le(value, 12) else 0L
        val previousUsed = current.usedBytes
        val previousFiles = current.fileCount
        val deltaBytes = if (previousUsed >= 0L) usedBytes - previousUsed else null
        val deltaFiles = if (previousFiles >= 0) fileCount - previousFiles else null
        val now = System.currentTimeMillis()
        pendingStorageStatsRead = false

        val proofStep = storageStatsProofStep
        val message = when {
            proofStep == 1 -> "Baseline captured; waiting at least ${proofWindowMs / 1000L}s with BLE disconnected"
            proofStep == 2 && (deltaBytes ?: 0L) > 0L -> "Recording proof passed: flash grew by $deltaBytes bytes"
            proofStep == 2 -> "No flash growth detected during the proof window"
            deltaBytes != null && deltaBytes > 0L -> "Flash grew by $deltaBytes bytes since last check"
            else -> "Flash counters read"
        }

        _storageStats.value = current.copy(
            active = false,
            proofActive = proofStep == 1,
            usedBytes = usedBytes,
            fileCount = fileCount,
            freeBytes = freeBytes,
            statusFlags = statusFlags,
            previousUsedBytes = previousUsed,
            previousFileCount = previousFiles,
            deltaBytes = deltaBytes,
            deltaFiles = deltaFiles,
            lastReadAt = now,
            lastError = null,
            message = message,
        )
        Log.i(tag, "Storage stats used=$usedBytes files=$fileCount deltaBytes=${deltaBytes ?: "n/a"}")

        if (proofStep == 1) {
            val generation = storageStatsProofGeneration
            proofBaselineUsedBytes = usedBytes
            proofBaselineFileCount = fileCount
            storageStatsProofStep = 2
            if (storageStatsDisconnectAfterRead && !pendingLiveStream) {
                storageStatsDisconnectAfterRead = false
                disconnect()
            }
            handler.postDelayed({
                if (storageStatsProofGeneration == generation && storageStatsProofStep == 2) {
                    beginStorageStatsRead(
                        disconnectAfterRead = true,
                        message = "Reading flash after sample",
                        generation = generation,
                    )
                }
            }, proofWindowMs)
            return
        }

        if (proofStep == 2) {
            storageStatsProofStep = 0
            proofBaselineUsedBytes = -1L
            proofBaselineFileCount = -1
            _storageStats.value = _storageStats.value.copy(proofActive = false)
        }

        if (storageStatsDisconnectAfterRead && !pendingLiveStream) {
            storageStatsDisconnectAfterRead = false
            disconnect()
        } else {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        }
    }

    private fun beginStorageSync(g: BluetoothGatt) {
        pendingStorageSync = true
        _state.value = State.SYNCING
        resetStorageCleanupState()
        _storageSync.value = StorageSyncStatus(active = true)
        pendingStorageDelete = null
        completedStorageFileKeys.clear()
        skippedStorageFileKeys.clear()
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        queueButtonNotify(g)
        queueTelemetryNotify(g)

        val ch = g.getService(OmiUuids.STORAGE_SERVICE)
            ?.getCharacteristic(OmiUuids.STORAGE_WRITE_CHAR) ?: run {
                completeStorageSync("Storage write/notify characteristic not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun beginLiveStream(g: BluetoothGatt) {
        pendingLiveStream = true
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        queueButtonNotify(g)
        queueTelemetryNotify(g)
        val ch = g.getService(OmiUuids.AUDIO_SERVICE)
            ?.getCharacteristic(OmiUuids.AUDIO_DATA_CHAR) ?: run {
                lastError = "Audio data characteristic not found"
                Log.w(tag, lastError ?: "Audio data characteristic not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun queueButtonNotify(g: BluetoothGatt) {
        val ch = g.getService(OmiUuids.BUTTON_SERVICE)
            ?.getCharacteristic(OmiUuids.BUTTON_STATE_CHAR) ?: run {
                Log.w(tag, "Button service/char not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun queueTelemetryNotify(g: BluetoothGatt) {
        val ch = g.getService(OmiUuids.SETTINGS_SERVICE)
            ?.getCharacteristic(OmiUuids.SETTINGS_TELEMETRY_CHAR) ?: run {
                Log.w(tag, "Settings service/telemetry char not found")
                return
            }
        enableNotify(g, ch)
    }

    private fun handleSettingsTelemetryNotify(value: ByteArray) {
        if (value.size < 12) {
            Log.w(tag, "Settings telemetry size mismatch: expected 12, got ${value.size}")
            return
        }
        val voltage = ((value[0].toInt() and 0xFF) or ((value[1].toInt() and 0xFF) shl 8))
        val percentage = value[2].toInt() and 0xFF
        val charging = value[3].toInt() and 0xFF
        val backlog = (value[4].toLong() and 0xFF) or
                ((value[5].toLong() and 0xFF) shl 8) or
                ((value[6].toLong() and 0xFF) shl 16) or
                ((value[7].toLong() and 0xFF) shl 24)
        val mic = value[8].toInt() and 0xFF
        val vad = value[9].toInt() and 0xFF
        val codec = value[10].toInt() and 0xFF
        val sd = value[11].toInt() and 0xFF

        val telemetry = SettingsTelemetry(
            batteryVoltage = voltage,
            batteryPercentage = percentage,
            chargingState = charging,
            sdBacklog = backlog,
            micStatus = mic,
            vadStatus = vad,
            codecStatus = codec,
            sdStatus = sd
        )
        _settingsTelemetry.value = telemetry
        Log.i(tag, "Settings telemetry updated: $telemetry")
    }

    private fun enableNotify(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        if (notifyEnabled.contains(ch.uuid) || notifyQueue.any { it.uuid == ch.uuid }) return
        notifyQueue.add(ch)
        drainNotifyQueue(g)
    }

    private fun drainNotifyQueue(g: BluetoothGatt) {
        if (notifyWriteInFlight) return
        val ch = notifyQueue.poll() ?: return
        g.setCharacteristicNotification(ch, true)
        val ccc = ch.getDescriptor(OmiUuids.CCC_DESCRIPTOR) ?: run {
            Log.e(tag, "CCC descriptor not found on ${ch.uuid}")
            return
        }
        notifyWriteInFlight = writeDescriptorCompat(g, ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (!notifyWriteInFlight) {
            lastError = "Failed to enable notifications for ${ch.uuid}"
            Log.w(tag, lastError ?: "Failed to enable notifications")
            drainNotifyQueue(g)
        }
    }

    private fun handleButtonNotify(value: ByteArray) {
        val raw = readI32Le(value)
        val event = ButtonEvent.fromCode(raw)
        val status = ButtonStatus(
            event = event,
            rawCode = raw,
            receivedAt = System.currentTimeMillis(),
            subscribed = true,
        )
        _buttonStatus.value = status
        _buttonEvents.tryEmit(status)
        Log.i(tag, "Button event ${event.label} raw=$raw bytes=${value.joinToString(" ") { "%02x".format(it) }}")
    }

    private fun requestStorageFileList(g: BluetoothGatt) {
        val ch = storageWriteChar(g) ?: return completeStorageSync("Storage write characteristic not found")
        _storageSync.value = _storageSync.value.copy(active = true)
        writeStorageCommand(g, ch, byteArrayOf(CMD_LIST_FILES))
    }

    private fun requestStorageFile(g: BluetoothGatt, file: StorageFile) {
        val ch = storageWriteChar(g) ?: return completeStorageSync("Storage write characteristic not found")
        currentStorageFile = file
        currentStorageBytes = ByteArrayOutputStream(file.size.coerceAtMost(512_000).toInt())
        val generation = ++storageReadGeneration
        lastStorageReadProgressAt = System.currentTimeMillis()
        lastStorageReadProgressLogBytes = 0
        Log.i(tag, "Reading pendant file index=${file.index} ts=${file.timestamp} size=${file.size}")
        scheduleStorageReadWatchdog(generation)
        val cmd = byteArrayOf(
            CMD_READ_FILE,
            file.index.toByte(),
            0,
            0,
            0,
            0,
        )
        writeStorageCommand(g, ch, cmd)
    }

    private fun writeStorageCommand(
        g: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        cmd: ByteArray,
        failSyncOnError: Boolean = true,
        retryCount: Int = 0,
        onComplete: (Boolean) -> Unit = {}
    ) {
        handler.post {
            if (gatt == null || gatt != g) {
                Log.w(tag, "Aborting writeStorageCommand: GATT connection changed or null")
                onComplete(false)
                return@post
            }
            val success = writeCharacteristicCompat(
                g,
                ch,
                cmd,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (!success) {
                if (retryCount < 10) {
                    Log.w(tag, "Failed to write storage command 0x${cmd.first().toInt().and(0xFF).toString(16)}, retrying (${retryCount + 1}/10)...")
                    handler.postDelayed({
                        writeStorageCommand(g, ch, cmd, failSyncOnError, retryCount + 1, onComplete)
                    }, 100)
                } else {
                    val message = "Failed to write storage command 0x${cmd.first().toInt().and(0xFF).toString(16)} after 10 attempts"
                    if (failSyncOnError) {
                        completeStorageSync(message)
                    } else {
                        Log.w(tag, message)
                    }
                    onComplete(false)
                }
            } else {
                onComplete(true)
            }
        }
    }

    private fun storageWriteChar(g: BluetoothGatt): BluetoothGattCharacteristic? =
        g.getService(OmiUuids.STORAGE_SERVICE)?.getCharacteristic(OmiUuids.STORAGE_WRITE_CHAR)

    private fun handleStorageNotify(value: ByteArray) {
        if (value.isEmpty()) return
        val g = gatt ?: return completeStorageSync("GATT missing during storage sync")

        if (pendingStorageDelete != null) {
            handleStorageDeleteResult(value, g)
            return
        }

        if (currentStorageFile == null) {
            handleStorageList(value, g)
            return
        }

        if (value.size == 1) {
            val status = value[0].toInt() and 0xFF
            when (status) {
                STORAGE_OK -> return
                STORAGE_DONE -> {
                    finishCurrentStorageFile(g)
                    return
                }
                FILE_NOT_FOUND -> {
                    handleStorageReadMissing(g, currentStorageFile ?: return, status)
                    return
                }
                FILE_INDEX_OUT_OF_RANGE -> {
                    handleStorageReadStaleIndex(g, currentStorageFile ?: return, status)
                    return
                }
                else -> {
                    completeStorageSync("Storage read status ${storageStatusLabel(status)} index=${currentStorageFile?.index}")
                    return
                }
            }
        }

        if (value.size <= STORAGE_TIMESTAMP_BYTES) return
        currentStorageBytes.write(value, STORAGE_TIMESTAMP_BYTES, value.size - STORAGE_TIMESTAMP_BYTES)
        noteStorageReadProgress(currentStorageFile ?: return)
        val s = _storageSync.value
        _storageSync.value = s.copy(bytesDone = s.bytesDone + value.size - STORAGE_TIMESTAMP_BYTES)
    }

    private fun noteStorageReadProgress(file: StorageFile) {
        val now = System.currentTimeMillis()
        lastStorageReadProgressAt = now
        val bytes = currentStorageBytes.size()
        if (bytes - lastStorageReadProgressLogBytes >= STORAGE_READ_PROGRESS_LOG_BYTES || bytes.toLong() >= file.size) {
            lastStorageReadProgressLogBytes = bytes
            Log.i(tag, "Storage read progress index=${file.index} bytes=$bytes/${file.size}")
        }
    }

    private fun scheduleStorageReadWatchdog(generation: Int) {
        handler.postDelayed({
            if (generation != storageReadGeneration) return@postDelayed
            val file = currentStorageFile ?: return@postDelayed
            if (!pendingStorageSync || _state.value != State.SYNCING) return@postDelayed
            val idleMs = System.currentTimeMillis() - lastStorageReadProgressAt
            if (idleMs >= STORAGE_READ_STALL_TIMEOUT_MS) {
                completeStorageSync(
                    "Storage read stalled ${idleMs / 1000}s index=${file.index} bytes=${currentStorageBytes.size()}/${file.size}"
                )
                return@postDelayed
            }
            scheduleStorageReadWatchdog(generation)
        }, STORAGE_READ_STALL_CHECK_MS)
    }

    private fun estimateStorageTimestamps(parsed: List<StorageFile>): List<StorageFile> {
        val result = parsed.toMutableList()
        val n = result.size
        if (n == 0) return result

        var i = n - 1
        while (i >= 0) {
            if (!isReasonableTimestamp(result[i].timestamp)) {
                var anchorMs = System.currentTimeMillis()
                var anchorIndex = i + 1
                while (anchorIndex < n) {
                    if (isReasonableTimestamp(result[anchorIndex].timestamp)) {
                        anchorMs = result[anchorIndex].timestamp * 1000L
                        break
                    }
                    anchorIndex++
                }

                var currentEnd = anchorMs
                var j = anchorIndex - 1
                while (j >= i) {
                    val file = result[j]
                    if (!isReasonableTimestamp(file.timestamp)) {
                        val dur = (file.size * 20) / 90
                        val estimatedStart = currentEnd - dur
                        result[j] = file.copy(timestamp = estimatedStart / 1000L)
                        currentEnd = estimatedStart - 1000L
                    } else {
                        break
                    }
                    j--
                }
                i = j
            } else {
                i--
            }
        }
        return result
    }

    private fun isReasonableTimestamp(timestamp: Long): Boolean {
        return timestamp in 1500000000L..4102444800L
    }

    private fun handleStorageList(value: ByteArray, g: BluetoothGatt) {
        if (value.size == 1) {
            val statusOrCount = value[0].toInt() and 0xFF
            if (statusOrCount == 0) {
                completeStorageSync()
            } else {
                completeStorageSync("Storage list status ${storageStatusLabel(statusOrCount)}")
            }
            return
        }

        val count = value[0].toInt() and 0xFF
        if (count == 0) {
            completeStorageSync()
            return
        }
        val parsed = mutableListOf<StorageFile>()
        var offset = 1
        for (i in 0 until count) {
            if (offset + 8 > value.size) break
            val ts = readU32Be(value, offset)
            val size = readU32Be(value, offset + 4)
            parsed += StorageFile(index = i, timestamp = ts, size = size)
            offset += 8
        }
        val estimated = estimateStorageTimestamps(parsed)
        storageFiles = estimated
        Log.i(tag, "Storage files listed: ${estimated.size}")
        val current = _storageSync.value
        val remainingKnown = parsed.count { it.key() !in completedStorageFileKeys && it.key() !in skippedStorageFileKeys }
        val remainingBytes = parsed
            .filter { it.key() !in completedStorageFileKeys && it.key() !in skippedStorageFileKeys }
            .sumOf { it.size }
        _storageSync.value = current.copy(
            active = true,
            filesTotal = maxOf(current.filesTotal, current.filesDone + remainingKnown),
            bytesTotal = maxOf(current.bytesTotal, current.bytesDone + remainingBytes),
        )
        requestNextStorageFile(g)
    }

    private fun requestNextStorageFile(g: BluetoothGatt) {
        val next = storageFiles.firstOrNull {
            it.key() !in completedStorageFileKeys && it.key() !in skippedStorageFileKeys
        }
        if (next == null) {
            Log.i(tag, "Storage file batch drained; requesting next batch")
            requestStorageFileList(g)
        } else {
            requestStorageFile(g, next)
        }
    }

    private fun handleStorageReadMissing(g: BluetoothGatt, file: StorageFile, status: Int) {
        skippedStorageFileKeys += file.key()
        val current = _storageSync.value
        val nextDone = (current.filesDone + 1).coerceAtMost(
            current.filesTotal.coerceAtLeast(current.filesDone + 1)
        )
        _storageSync.value = current.copy(filesDone = nextDone)
        Log.w(
            tag,
            "Storage read status ${storageStatusLabel(status)} index=${file.index} ts=${file.timestamp} size=${file.size}; refreshing list"
        )
        currentStorageFile = null
        currentStorageBytes.reset()
        requestStorageFileList(g)
    }

    private fun handleStorageReadStaleIndex(g: BluetoothGatt, file: StorageFile, status: Int) {
        Log.w(
            tag,
            "Storage read status ${storageStatusLabel(status)} index=${file.index} ts=${file.timestamp} size=${file.size}; refreshing list"
        )
        currentStorageFile = null
        currentStorageBytes.reset()
        requestStorageFileList(g)
    }

    private fun finishCurrentStorageFile(g: BluetoothGatt) {
        val file = currentStorageFile ?: return
        val rawBytes = currentStorageBytes.toByteArray()
        val currentFrameCount = frameCount
        scope.launch {
            val frames = buildStorageFrames(rawBytes, currentFrameCount)
            if (frames.isEmpty()) {
                handler.post {
                    if (file.size == 0L) {
                        val s = _storageSync.value
                        val doneCount = s.filesDone + 1
                        skippedStorageFileKeys += file.key()
                        _storageSync.value = s.copy(filesDone = doneCount)
                        Log.i(tag, "Storage file empty index=${file.index}; skipping zero-byte pendant file during sync")
                        currentStorageFile = null
                        currentStorageBytes.reset()
                        continueAfterStorageFile(g, doneCount)
                    } else {
                        completeStorageSync("Storage file had no parsed frames index=${file.index}")
                    }
                }
                return@launch
            }
            if (!runStorageSafetyCheckpoint(file, frames)) {
                handler.post {
                    completeStorageSync("Storage file not safe on phone index=${file.index}")
                }
                return@launch
            }
            
            handler.post {
                completedStorageFileKeys += file.key()
                Log.i(tag, "Storage file safe index=${file.index} frames=${frames.size}")
            }
            
            emitStorageFrames(frames)
            
            handler.post {
                val s = _storageSync.value
                val doneCount = s.filesDone + 1
                val missingTimestampFiles = if (file.timestamp == 0L) {
                    Log.w(tag, "Storage file index=${file.index} has missing timestamp ts=0; display sync/upload time as unverified")
                    s.missingTimestampFiles + 1
                } else {
                    s.missingTimestampFiles
                }
                _storageSync.value = s.copy(
                    filesDone = doneCount,
                    framesDone = s.framesDone + frames.size.toLong(),
                    missingTimestampFiles = missingTimestampFiles,
                )
                Log.i(tag, "Storage file synced index=${file.index} ts=${file.timestamp} size=${file.size} frames=${frames.size}")

                currentStorageFile = null
                currentStorageBytes.reset()
                requestDeleteStorageFile(g, file, doneCount)
            }
        }
    }

    private fun buildStorageFrames(bytes: ByteArray, firstSeq: Long): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var nextSeq = firstSeq
        var blockStart = 0
        while (blockStart < bytes.size) {
            val blockEnd = minOf(blockStart + STORAGE_BLOCK_BYTES, bytes.size)
            val usableEnd = minOf(blockStart + STORAGE_BLOCK_USABLE_BYTES, blockEnd)
            var offset = blockStart

            while (offset < usableEnd) {
                val payloadLen = bytes[offset].toInt() and 0xFF
                if (payloadLen == 0) break

                val packetEnd = offset + 1 + payloadLen
                if (payloadLen > MAX_STORAGE_OPUS_PAYLOAD || packetEnd > usableEnd) break

                val frame = ByteArray(OmiUuids.FRAME_HEADER_BYTES + payloadLen)
                val seq = nextSeq and 0xFFFF
                frame[0] = (seq and 0xFF).toByte()
                frame[1] = ((seq shr 8) and 0xFF).toByte()
                frame[2] = 0
                bytes.copyInto(frame, OmiUuids.FRAME_HEADER_BYTES, offset + 1, packetEnd)

                frames += frame
                nextSeq++
                offset = packetEnd
            }

            blockStart += STORAGE_BLOCK_BYTES
        }
        return frames
    }

    private suspend fun runStorageSafetyCheckpoint(file: StorageFile, frames: List<ByteArray>): Boolean {
        val checkpoint = storageFileSafetyCheckpoint
        if (checkpoint == null) {
            Log.w(tag, "Storage file safety checkpoint missing index=${file.index}; refusing pendant delete")
            return false
        }
        return try {
            checkpoint(
                StorageFilePayload(
                    index = file.index,
                    timestamp = file.timestamp,
                    size = file.size,
                    frames = frames,
                )
            )
        } catch (t: Throwable) {
            Log.w(tag, "Storage file safety checkpoint failed index=${file.index}: ${t.message}")
            false
        }
    }

    private suspend fun emitStorageFrames(frames: List<ByteArray>) {
        for (frame in frames) {
            frameCount++
            lastFrameSize = frame.size
            emitStorageFrame(frame)
        }
    }

    private fun requestDeleteStorageFile(g: BluetoothGatt, file: StorageFile, doneCount: Int) {
        val ch = storageWriteChar(g) ?: run {
            storageCleanupFailed++
            updateStorageCleanupStatus(file.index, false, "NO_STORAGE_WRITE_CHAR", active = false)
            Log.w(tag, "Pendant file deleted index=${file.index} ok=false status=NO_STORAGE_WRITE_CHAR")
            continueAfterStorageFile(g, doneCount)
            return
        }
        val generation = ++storageDeleteGeneration
        pendingStorageDelete = PendingStorageDelete(file = file, doneCount = doneCount, generation = generation)
        updateStorageCleanupStatus(file.index, null, "delete requested", active = true)
        Log.i(tag, "Deleting pendant file index=${file.index}")
        writeStorageCommand(
            g,
            ch,
            byteArrayOf(CMD_DELETE_FILE, file.index.toByte()),
            failSyncOnError = false,
        ) { wrote ->
            if (!wrote) {
                pendingStorageDelete = null
                storageCleanupFailed++
                updateStorageCleanupStatus(file.index, false, "LOCAL_WRITE_FAILED", active = false)
                Log.w(tag, "Pendant file deleted index=${file.index} ok=false status=LOCAL_WRITE_FAILED")
                continueAfterStorageFile(g, doneCount)
            } else {
                handler.postDelayed({
                    val pending = pendingStorageDelete
                    val activeGatt = gatt
                    if (pending != null && pending.generation == generation && activeGatt != null) {
                        pendingStorageDelete = null
                        storageCleanupFailed++
                        updateStorageCleanupStatus(pending.file.index, false, "TIMEOUT", active = false)
                        Log.w(tag, "Pendant file deleted index=${pending.file.index} ok=false status=TIMEOUT")
                        requestStorageFileList(activeGatt)
                    }
                }, STORAGE_DELETE_TIMEOUT_MS)
            }
        }
    }

    private fun handleStorageDeleteResult(value: ByteArray, g: BluetoothGatt) {
        val pending = pendingStorageDelete ?: return
        if (value.size != 1) return
        pendingStorageDelete = null
        val status = value[0].toInt() and 0xFF
        when (status) {
            STORAGE_OK -> {
                storageCleanupDeleted++
                updateStorageCleanupStatus(pending.file.index, true, storageStatusLabel(status), active = false)
                applyDeleteIndexShift(pending.file.index, pending.doneCount)
                Log.i(tag, "Pendant file deleted index=${pending.file.index} ok=true status=${storageStatusLabel(status)}")
                continueAfterStorageFile(g, pending.doneCount)
            }
            FILE_NOT_FOUND -> {
                storageCleanupDeleted++
                updateStorageCleanupStatus(pending.file.index, true, storageStatusLabel(status), active = false)
                Log.i(
                    tag,
                    "Pendant file deleted index=${pending.file.index} ok=true status=${storageStatusLabel(status)} alreadyGone=true"
                )
                requestStorageFileList(g)
            }
            else -> {
                storageCleanupFailed++
                updateStorageCleanupStatus(pending.file.index, false, storageStatusLabel(status), active = false)
                Log.w(tag, "Pendant file deleted index=${pending.file.index} ok=false status=${storageStatusLabel(status)}")
                requestStorageFileList(g)
            }
        }
    }

    private fun applyDeleteIndexShift(deletedIndex: Int, doneCount: Int) {
        storageFiles = storageFiles.mapIndexed { pos, file ->
            if (pos >= doneCount && file.index > deletedIndex) {
                file.copy(index = file.index - 1)
            } else {
                file
            }
        }
    }

    private fun continueAfterStorageFile(g: BluetoothGatt, doneCount: Int) {
        requestNextStorageFile(g)
    }

    private fun emitLiveFrame(frame: ByteArray) {
        val copy = frame.copyOf()
        _frameEvents.tryEmit(AudioFrame(copy, FrameSource.LIVE))
        if (_frames.tryEmit(copy)) return
        droppedLiveFrames++
        if (droppedLiveFrames < 10L || droppedLiveFrames % 100L == 0L) {
            Log.w(tag, "live frame flow full; dropped live frame (#$droppedLiveFrames)")
        }
    }

    private suspend fun emitStorageFrame(frame: ByteArray) {
        _frameEvents.emit(AudioFrame(frame, FrameSource.STORAGE))
        _frames.emit(frame)
    }

    private fun completeStorageSync(error: String? = null) {
        val now = System.currentTimeMillis()
        val current = _storageSync.value
        val shouldRetry = error?.let(::isRetryableStorageSyncError) == true
        if (error != null) {
            lastError = error
            Log.w(tag, "Storage sync failed: $error")
        } else {
            storageRetryAttempts = 0
            Log.i(
                tag,
                "Storage sync complete files=${current.filesDone}/${current.filesTotal} bytes=${current.bytesDone}/${current.bytesTotal} frames=${current.framesDone}"
            )
        }
        Log.i(tag, "Storage cleanup complete deleted=$storageCleanupDeleted failed=$storageCleanupFailed")
        _storageSync.value = current.copy(
            active = false,
            cleanupActive = false,
            cleanupDeleted = storageCleanupDeleted,
            cleanupFailed = storageCleanupFailed,
            lastCompletedAt = now,
            lastError = error,
        )
        pendingStorageSync = false
        storageNotifyEnabled = false
        currentStorageFile = null
        currentStorageBytes.reset()
        storageReadGeneration++
        pendingStorageDelete = null
        storageFiles = emptyList()
        completedStorageFileKeys.clear()
        skippedStorageFileKeys.clear()
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER)
        disconnect()
        if (shouldRetry) scheduleStorageSyncRetry(error ?: "storage sync retry")
    }

    private fun failStorageSyncIfActive(error: String) {
        val current = _storageSync.value
        if (!current.active) return
        lastError = error
        pendingStorageSync = false
        storageNotifyEnabled = false
        pendingStorageDelete = null
        _storageSync.value = current.copy(
            active = false,
            cleanupActive = false,
            cleanupDeleted = storageCleanupDeleted,
            cleanupFailed = storageCleanupFailed,
            lastCompletedAt = System.currentTimeMillis(),
            lastError = error,
        )
        currentStorageFile = null
        currentStorageBytes.reset()
        storageReadGeneration++
        storageFiles = emptyList()
        completedStorageFileKeys.clear()
        skippedStorageFileKeys.clear()
        if (isRetryableStorageSyncError(error)) {
            disconnect()
            scheduleStorageSyncRetry(error)
        }
    }

    private fun isRetryableStorageSyncError(error: String): Boolean =
        error.contains("STORAGE_NOT_READY", ignoreCase = true) ||
            error.contains("scan timed out", ignoreCase = true)

    private fun scheduleStorageSyncRetry(reason: String) {
        val generation = storageRetryGeneration
        storageRetryAttempts += 1
        val delayMs = STORAGE_RETRY_DELAY_MS
        Log.w(tag, "Storage sync will retry in ${delayMs / 1000}s after: $reason")
        handler.postDelayed({
            if (generation != storageRetryGeneration) return@postDelayed
            if (_storageSync.value.active || pendingLiveStream || pendingButtonWatch) return@postDelayed
            Log.i(tag, "Retrying storage sync attempt=$storageRetryAttempts")
            syncStorageNowInternal(fromRetry = true)
        }, delayMs)
    }

    private fun failStorageStatsIfActive(error: String) {
        val current = _storageStats.value
        if (!current.active) return
        failStorageStats(error)
    }

    private fun failStorageStats(error: String) {
        val current = _storageStats.value
        lastError = error
        pendingStorageStatsRead = false
        storageStatsDisconnectAfterRead = false
        storageStatsProofStep = 0
        proofBaselineUsedBytes = -1L
        proofBaselineFileCount = -1
        _storageStats.value = current.copy(
            active = false,
            proofActive = false,
            lastError = error,
            message = error,
        )
        Log.w(tag, "Storage stats failed: $error")
    }

    private fun readU32Be(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    private fun readU32Le(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        return (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun readI32Le(bytes: ByteArray): Int {
        if (bytes.isEmpty()) return 0
        if (bytes.size < 4) return bytes[0].toInt() and 0xFF
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8) or
            ((bytes[2].toInt() and 0xFF) shl 16) or
            ((bytes[3].toInt() and 0xFF) shl 24)
    }

    private fun storageStatusLabel(status: Int): String = when (status) {
        STORAGE_OK -> "OK(0)"
        INVALID_COMMAND -> "INVALID_COMMAND(6)"
        FILE_NOT_FOUND -> "FILE_ALREADY_GONE_OR_STALE_INDEX(7)"
        FILE_INDEX_OUT_OF_RANGE -> "FILE_INDEX_OUT_OF_RANGE(8)"
        STORAGE_NOT_READY -> "STORAGE_NOT_READY(9)"
        STORAGE_DONE -> "DONE(100)"
        else -> "UNKNOWN($status)"
    }

    private fun resetStorageCleanupState() {
        storageCleanupDeleted = 0
        storageCleanupFailed = 0
        storageDeleteGeneration = 0
    }

    private fun updateStorageCleanupStatus(
        index: Int,
        deletedOk: Boolean?,
        status: String,
        active: Boolean,
    ) {
        val current = _storageSync.value
        val deleted = when (deletedOk) {
            true -> storageCleanupDeleted
            else -> current.cleanupDeleted
        }
        val failed = when (deletedOk) {
            false -> storageCleanupFailed
            else -> current.cleanupFailed
        }
        _storageSync.value = current.copy(
            cleanupActive = active,
            cleanupDeleted = deleted,
            cleanupFailed = failed,
            cleanupLastIndex = index,
            cleanupLastStatus = status,
        )
    }

    companion object {
        private const val CMD_LIST_FILES: Byte = 0x10
        private const val CMD_READ_FILE: Byte = 0x11
        private const val CMD_DELETE_FILE: Byte = 0x12
        private const val STORAGE_OK = 0
        private const val INVALID_COMMAND = 6
        private const val FILE_NOT_FOUND = 7
        private const val FILE_INDEX_OUT_OF_RANGE = 8
        private const val STORAGE_NOT_READY = 9
        private const val STORAGE_DONE = 100
        private const val STORAGE_TIMESTAMP_BYTES = 4
        private const val MAX_STORAGE_OPUS_PAYLOAD = 127
        private const val STORAGE_BLOCK_BYTES = 440
        private const val STORAGE_BLOCK_USABLE_BYTES = STORAGE_BLOCK_BYTES - 1
        private const val STORAGE_DELETE_TIMEOUT_MS = 30_000L
        private const val STORAGE_READ_STALL_CHECK_MS = 10_000L
        private const val STORAGE_READ_STALL_TIMEOUT_MS = 30_000L
        private const val STORAGE_READ_PROGRESS_LOG_BYTES = 1_048_576
        private const val STORAGE_PROOF_WINDOW_MS = 15_000L
        private const val SCAN_TIMEOUT_MS = 60_000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 20_000L
        private const val STORAGE_RETRY_DELAY_MS = 30_000L
        private const val PREF_LAST_DEVICE_ADDRESS = "last_device_address"
        private const val MIC_GAIN_MIN = 0
        private const val MIC_GAIN_MAX = 8
        // Firmware level 8 maps to hardware gain byte 0x50, the max PDM gain.
        const val CAPTURE_MIC_GAIN_LEVEL = 8

        fun micGainLabel(level: Int): String = when (level) {
            0 -> "Mute"
            1 -> "-20dB"
            2 -> "-10dB"
            3 -> "+0dB"
            4 -> "+6dB"
            5 -> "+10dB"
            6 -> "+20dB"
            7 -> "+30dB"
            8 -> "+40dB"
            else -> "unknown"
        }
    }
}
