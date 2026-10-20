package com.example.hometv.data.repository

import android.content.Context
import android.util.Log
import com.example.hometv.data.network.AdbTransport
import com.example.hometv.data.network.NsdDiscoveryManager
import com.example.hometv.data.pairing.PairedTvStore
import com.example.hometv.data.pairing.PairingManager
import com.example.hometv.data.remote.AndroidTvV2Transport
import com.example.hometv.data.security.TvClientIdentity
import com.example.hometv.domain.model.ConnectionStatus
import com.example.hometv.domain.model.PairedTv
import com.example.hometv.domain.model.ProtocolType
import com.example.hometv.domain.model.RemoteCommand
import com.example.hometv.domain.model.TvDevice
import com.example.hometv.domain.repository.TvRemoteRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TvRemoteRepositoryImpl(context: Context) : TvRemoteRepository {

    private companion object {
        private const val TAG = "HomeTvLog"
        private const val PAIRING_PORT = 6467
        private const val COMMAND_PORT = 6466
        private const val ADB_PORT = 5555
        private const val MAX_LOGS = 60
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val discoveryMutex = Mutex()

    private val identity = TvClientIdentity(appContext)
    private val pairedStore = PairedTvStore(appContext)

    private val _logs = MutableSharedFlow<String>(replay = MAX_LOGS, extraBufferCapacity = 32)
    override val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val discoveryManager = NsdDiscoveryManager(appContext) { addLog(it) }
    private val pairingManager = PairingManager(identity) { addLog(it) }
    private val v2Transport = AndroidTvV2Transport(identity) { addLog(it) }
    private val adbTransport = AdbTransport(appContext) { addLog(it) }

    private val _pairedDevices = MutableStateFlow(pairedStore.getAll())
    private val _discoveredDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    private val _manualDevices = MutableStateFlow<List<TvDevice>>(emptyList())
    private val _devices = MutableStateFlow<List<TvDevice>>(emptyList())
    override val discoveredDevices: StateFlow<List<TvDevice>> = _devices.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _activeProtocol = MutableStateFlow(ProtocolType.OFFICIAL_TLS)
    override val activeProtocol: StateFlow<ProtocolType> = _activeProtocol.asStateFlow()

    private val _activeDevice = MutableStateFlow<TvDevice?>(null)
    override val activeDevice: StateFlow<TvDevice?> = _activeDevice.asStateFlow()

    private var discoveryJob: Job? = null

    init {
        addLog("HomeTv ready. Remembered TVs: ${_pairedDevices.value.size}")

        scope.launch {
            combine(_discoveredDevices, _manualDevices, _pairedDevices) { discovered, manual, paired ->
                mergeDevices((discovered + manual).distinctBy { it.hostAddress }, paired)
            }.collect { devices ->
                _devices.value = devices
            }
        }

        scope.launch {
            v2Transport.events.collect { event ->
                when (event) {
                    is AndroidTvV2Transport.Event.Connected -> {
                        _connectionStatus.value = ConnectionStatus.CONNECTED
                        addLog("TV remote session ready")
                    }

                    is AndroidTvV2Transport.Event.Disconnected -> {
                        _connectionStatus.value = ConnectionStatus.DISCONNECTED
                        addLog("TV session disconnected${event.reason?.let { ": $it" } ?: ""}")
                    }

                    is AndroidTvV2Transport.Event.Error -> addLog("TV error: ${event.message}")

                    is AndroidTvV2Transport.Event.Volume -> addLog(
                        "TV volume: ${event.level}/${event.max}${if (event.muted) " (muted)" else ""}"
                    )

                    is AndroidTvV2Transport.Event.Powered -> addLog(
                        "TV power state: ${if (event.isOn) "on" else "off"}"
                    )
                }
            }
        }
    }

    override fun startDiscovery() {
        scope.launch {
            discoveryMutex.withLock {
                if (discoveryJob?.isActive == true) {
                    addLog("Scan already running")
                    return@withLock
                }
                discoveryJob?.cancelAndJoin()
                delay(300)

                when (_connectionStatus.value) {
                    ConnectionStatus.CONNECTED,
                    ConnectionStatus.CONNECTING,
                    ConnectionStatus.PAIRING -> Unit

                    else -> _connectionStatus.value = ConnectionStatus.DISCOVERING
                }
                addLog("Starting scan for Android TV devices")

                discoveryJob = scope.launch {
                    try {
                        discoveryManager.discoverDevices().collect { devices ->
                            _discoveredDevices.value = devices
                            if (devices.isNotEmpty() && _connectionStatus.value == ConnectionStatus.DISCOVERING) {
                                _connectionStatus.value = ConnectionStatus.FOUND
                            }
                            val detail = if (devices.isEmpty()) {
                                ""
                            } else {
                                devices.joinToString(prefix = " -> ", separator = ", ") {
                                    "${it.name} [${it.hostAddress}]"
                                }
                            }
                            addLog("Scan update: ${devices.size} device(s)$detail")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Discovery flow failed", e)
                        addLog("Scan failed: ${e.message ?: e.javaClass.simpleName}")
                        _connectionStatus.value = ConnectionStatus.ERROR
                    }
                }
            }
        }
    }

    override fun stopDiscovery() {
        scope.launch {
            discoveryMutex.withLock {
                if (discoveryJob == null) return@withLock
                discoveryJob?.cancelAndJoin()
                discoveryJob = null
                if (_connectionStatus.value == ConnectionStatus.DISCOVERING ||
                    _connectionStatus.value == ConnectionStatus.FOUND
                ) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                }
                addLog("Scan stopped")
            }
        }
    }

    override suspend fun startPairing(device: TvDevice): Result<Unit> {
        addLog("Pairing requested for ${device.name}")
        _connectionStatus.value = ConnectionStatus.PAIRING
        val result = pairingManager.start(device)
        if (result.isFailure) {
            _connectionStatus.value = ConnectionStatus.ERROR
        }
        return result.map { Unit }
    }

    override suspend fun finishPairing(code: String): Result<TvDevice> {
        val result = pairingManager.finish(code)
        if (result.isFailure) {
            _connectionStatus.value = ConnectionStatus.ERROR
            addLog("Pairing failed: ${result.exceptionOrNull()?.message}")
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException("Pairing failed"))
        }

        val paired = result.getOrThrow()
        _pairedDevices.value = pairedStore.upsert(paired)
        addLog("Paired and remembered ${paired.name} (${paired.hostAddress})")

        val pairedDevice = paired.toDevice(isOnline = true)
        val connected = connect(pairedDevice, ProtocolType.OFFICIAL_TLS)
        if (connected.isFailure) {
            addLog("Paired, but connecting failed: ${connected.exceptionOrNull()?.message}")
        }
        return Result.success(pairedDevice)
    }

    override suspend fun cancelPairing() {
        pairingManager.cancel()
        if (_connectionStatus.value == ConnectionStatus.PAIRING) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTED
        }
    }

    override suspend fun connect(device: TvDevice, protocol: ProtocolType): Result<Unit> {
        _activeProtocol.value = protocol
        _connectionStatus.value = ConnectionStatus.CONNECTING
        _activeDevice.value = device
        addLog("Connecting to ${device.name} (${device.hostAddress}) via $protocol")

        val result = when (protocol) {
            ProtocolType.OFFICIAL_TLS -> {
                val paired = _pairedDevices.value.firstOrNull { it.id == device.id }
                    ?: _pairedDevices.value.firstOrNull { it.hostAddress == device.hostAddress }
                if (paired == null) {
                    val error = IllegalStateException("This TV is not paired yet")
                    addLog("Connect failed: ${error.message}")
                    _connectionStatus.value = ConnectionStatus.ERROR
                    return Result.failure(error)
                }
                val pairedDevice = paired.toDevice(
                    isOnline = true,
                    hostAddress = device.hostAddress,
                    serviceType = device.serviceType
                )
                _activeDevice.value = pairedDevice
                v2Transport.connect(pairedDevice, paired.id)
            }

            ProtocolType.ADB_FALLBACK -> adbTransport.connect(device)
        }

        if (result.isSuccess) {
            _connectionStatus.value = ConnectionStatus.CONNECTED
            stopDiscovery()
            addLog("Connected to ${device.name}")
            _pairedDevices.value.firstOrNull { it.id == device.id || it.hostAddress == device.hostAddress }
                ?.let { paired ->
                    _pairedDevices.value = pairedStore.updateLastSeen(
                        paired.id,
                        device.hostAddress,
                        device.name
                    )
                }
        } else {
            _connectionStatus.value = ConnectionStatus.ERROR
            addLog("Connect failed: ${result.exceptionOrNull()?.message}")
        }
        return result
    }

    override suspend fun connectManualIp(ipAddress: String, protocol: ProtocolType): Result<Unit> {
        val host = ipAddress.removePrefix("http://").removePrefix("https://").substringBefore("/").trim()
        if (host.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter a valid IP address"))
        }

        val paired = _pairedDevices.value.firstOrNull { it.hostAddress == host }
        val device = paired?.toDevice(isOnline = true)
            ?: TvDevice(
                id = "manual:$host",
                name = "Android TV ($host)",
                hostAddress = host,
                pairingPort = PAIRING_PORT,
                commandPort = COMMAND_PORT,
                adbPort = ADB_PORT,
                isPaired = false,
                isOnline = true
            )

        if (paired == null) {
            _manualDevices.value = (_manualDevices.value.filterNot { it.hostAddress == host } + device)
            addLog("Added manual TV at $host")
            if (protocol == ProtocolType.OFFICIAL_TLS) {
                _connectionStatus.value = ConnectionStatus.FOUND
                return Result.failure(
                    IllegalStateException("TV added. Select it and tap Pair to complete pairing.")
                )
            }
        }

        return connect(device, protocol)
    }

    override suspend fun disconnect() {
        v2Transport.disconnect()
        adbTransport.disconnect()
        _activeDevice.value = null
        _connectionStatus.value = ConnectionStatus.DISCONNECTED
        addLog("Disconnected")
    }

    override suspend fun forgetDevice(device: TvDevice) {
        if (_activeDevice.value?.id == device.id || _activeDevice.value?.hostAddress == device.hostAddress) {
            disconnect()
        }
        _pairedDevices.value = pairedStore.remove(device.id)
        addLog("Forgot TV ${device.name}")
    }

    override suspend fun sendCommand(command: RemoteCommand): Result<Unit> {
        return when (_activeProtocol.value) {
            ProtocolType.OFFICIAL_TLS -> v2Transport.sendCommand(command)
            ProtocolType.ADB_FALLBACK -> adbTransport.sendCommand(command)
        }
    }

    fun close() {
        discoveryJob?.cancel()
        v2Transport.close()
        adbTransport.disconnect()
        scope.cancel()
    }

    private fun mergeDevices(discovered: List<TvDevice>, paired: List<PairedTv>): List<TvDevice> {
        val merged = mutableListOf<TvDevice>()

        paired.forEach { remembered ->
            val match = discovered.firstOrNull { it.hostAddress == remembered.hostAddress }
                ?: discovered.firstOrNull { it.name.equals(remembered.name, ignoreCase = true) }
            merged.add(
                remembered.toDevice(
                    isOnline = match != null,
                    hostAddress = match?.hostAddress ?: remembered.hostAddress,
                    serviceType = match?.serviceType,
                    lastSeen = match?.lastSeen ?: remembered.lastSeen
                )
            )
        }

        val knownHosts = merged.map { it.hostAddress }.toSet()
        discovered.filterNot { it.hostAddress in knownHosts }.forEach { merged.add(it) }

        return merged.sortedWith(
            compareByDescending<TvDevice> { it.isOnline }
                .thenByDescending { it.isPaired }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun PairedTv.toDevice(
        isOnline: Boolean,
        hostAddress: String = this.hostAddress,
        serviceType: String? = null,
        lastSeen: Long = this.lastSeen
    ): TvDevice = TvDevice(
        id = id,
        name = name,
        hostAddress = hostAddress,
        pairingPort = pairingPort,
        commandPort = commandPort,
        adbPort = adbPort,
        isPaired = true,
        isOnline = isOnline,
        serviceType = serviceType,
        lastSeen = lastSeen
    )

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.tryEmit(message)
    }
}
