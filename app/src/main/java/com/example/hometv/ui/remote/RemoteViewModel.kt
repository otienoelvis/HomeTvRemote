package com.example.hometv.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hometv.domain.model.ConnectionStatus
import com.example.hometv.domain.model.ProtocolType
import com.example.hometv.domain.model.RemoteCommand
import com.example.hometv.domain.model.TvDevice
import com.example.hometv.domain.repository.TvRemoteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RemoteViewModel(
    private val repository: TvRemoteRepository
) : ViewModel() {

    private companion object {
        private const val MAX_LOGS = 60
    }

    private val _uiState = MutableStateFlow(RemoteUiState())
    val uiState: StateFlow<RemoteUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var scanTimeoutJob: Job? = null

    init {
        observeRepository()
    }

    private fun observeRepository() {
        viewModelScope.launch {
            repository.discoveredDevices.collect { devices ->
                _uiState.update { state ->
                    val current = state.selectedDevice
                    val selected = current?.let { existing ->
                        devices.firstOrNull { it.id == existing.id }
                            ?: devices.firstOrNull { it.hostAddress == existing.hostAddress }
                    } ?: devices.firstOrNull { it.isPaired && it.isOnline }
                        ?: devices.firstOrNull()

                    state.copy(discoveredDevices = devices, selectedDevice = selected)
                }
            }
        }

        viewModelScope.launch {
            repository.connectionStatus.collect { status ->
                _uiState.update { state ->
                    state.copy(
                        connectionStatus = status,
                        statusMessage = statusMessage(status),
                        isDevicesSheetVisible = if (status == ConnectionStatus.CONNECTED) {
                            false
                        } else {
                            state.isDevicesSheetVisible
                        }
                    )
                }
            }
        }

        viewModelScope.launch {
            repository.activeProtocol.collect { protocol ->
                _uiState.update { it.copy(activeProtocol = protocol) }
            }
        }

        viewModelScope.launch {
            repository.activeDevice.collect { device ->
                if (device != null) {
                    _uiState.update {
                        it.copy(
                            selectedDevice = device,
                            activeDevice = device
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            repository.logs.collect { message ->
                addLog(message)
            }
        }
    }

    fun startScan() {
        addLog("Scan requested")
        _uiState.update { it.copy(statusMessage = "Scanning local network...") }
        repository.startDiscovery()

        scanTimeoutJob?.cancel()
        scanTimeoutJob = viewModelScope.launch {
            delay(15_000)
            val state = _uiState.value
            if (state.discoveredDevices.isEmpty() &&
                state.connectionStatus == ConnectionStatus.DISCOVERING
            ) {
                _uiState.update {
                    it.copy(
                        statusMessage = "No TVs found yet. Check Wi-Fi, TV power, and Remote & Accessories."
                    )
                }
                addLog(
                    "No mDNS services found after 15s. Check that the TV is on the same network " +
                        "and Android TV Remote Service is enabled."
                )
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        addLog("Stop scan requested")
        repository.stopDiscovery()
    }

    fun selectDevice(device: TvDevice) {
        addLog("Selected ${device.name} (${device.hostAddress})")
        _uiState.update { it.copy(selectedDevice = device) }
    }

    fun openDevicesSheet() {
        _uiState.update { it.copy(isDevicesSheetVisible = true) }
        val state = _uiState.value
        if (state.discoveredDevices.isEmpty() && !state.isScanning) {
            startScan()
        }
    }

    fun dismissDevicesSheet() {
        _uiState.update { it.copy(isDevicesSheetVisible = false) }
    }

    fun toggleDiagnostics() {
        _uiState.update { it.copy(isDiagnosticsVisible = !it.isDiagnosticsVisible) }
    }

    fun connectSelected() {
        val device = _uiState.value.selectedDevice ?: run {
            addLog("Select a TV first")
            return
        }
        _uiState.update { it.copy(isDevicesSheetVisible = false) }
        val state = _uiState.value
        if (state.connectionStatus == ConnectionStatus.CONNECTED &&
            state.selectedDevice?.hostAddress == device.hostAddress
        ) {
            addLog("Already connected to ${device.name}")
            return
        }
        val protocol = state.activeProtocol
        viewModelScope.launch {
            val result = repository.connect(device, protocol)
            if (result.isFailure) {
                addLog("Connect failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun pairSelected() {
        val device = _uiState.value.selectedDevice ?: run {
            addLog("Select a TV first")
            return
        }
        if (device.isPaired) {
            addLog("${device.name} is already paired")
            connectSelected()
            return
        }

        _uiState.update { it.copy(isDevicesSheetVisible = false) }
        viewModelScope.launch {
            addLog("Opening pairing session with ${device.name}")
            val result = repository.startPairing(device)
            if (result.isSuccess) {
                _uiState.update {
                    it.copy(
                        pairingDevice = device,
                        isPairingDialogVisible = true,
                        pairingPinInput = "",
                        statusMessage = "Enter the 6-character code shown on the TV"
                    )
                }
            } else {
                addLog("Could not start pairing: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun initiatePairing() {
        pairSelected()
    }

    fun updatePinInput(pin: String) {
        _uiState.update { it.copy(pairingPinInput = pin.uppercase()) }
    }

    fun submitPin() {
        val device = _uiState.value.pairingDevice ?: _uiState.value.selectedDevice ?: return
        val pin = _uiState.value.pairingPinInput.replace(" ", "").trim()
        if (!pin.matches(Regex("[0-9A-Fa-f]{6}"))) {
            addLog("Pairing code must be 6 characters: 0-9 and A-F")
            return
        }

        _uiState.update {
            it.copy(
                isPairingDialogVisible = false,
                statusMessage = "Checking pairing code..."
            )
        }
        addLog("Submitting pairing code for ${device.hostAddress}")

        viewModelScope.launch {
            val result = repository.finishPairing(pin)
            if (result.isSuccess) {
                val paired = result.getOrThrow()
                _uiState.update {
                    it.copy(
                        selectedDevice = paired,
                        pairingDevice = null,
                        pairingPinInput = ""
                    )
                }
                addLog("Paired successfully with ${paired.name}")
            } else {
                addLog("Pairing failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    fun dismissPairingDialog() {
        viewModelScope.launch { repository.cancelPairing() }
        _uiState.update {
            it.copy(
                isPairingDialogVisible = false,
                pairingDevice = null,
                pairingPinInput = ""
            )
        }
    }

    fun openManualIpDialog() {
        _uiState.update {
            it.copy(
                isManualIpDialogVisible = true,
                isDevicesSheetVisible = false
            )
        }
    }

    fun updateManualIpInput(ip: String) {
        _uiState.update { it.copy(manualIpInput = ip) }
    }

    fun connectManualIp() {
        val raw = _uiState.value.manualIpInput.trim()
        val host = raw
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()
        if (host.isBlank()) {
            addLog("Enter a valid IP address")
            return
        }

        val device = TvDevice(
            id = "manual:$host",
            name = "Android TV ($host)",
            hostAddress = host
        )
        _uiState.update {
            it.copy(
                isManualIpDialogVisible = false,
                manualIpInput = "",
                selectedDevice = device,
                statusMessage = "Added $host"
            )
        }
        addLog("Added manual TV at $host")

        viewModelScope.launch {
            val result = repository.connectManualIp(host, _uiState.value.activeProtocol)
            if (result.isSuccess) {
                addLog("Connected to $host")
            } else {
                addLog(result.exceptionOrNull()?.message ?: "Could not connect to $host")
            }
        }
    }

    fun dismissManualIpDialog() {
        _uiState.update { it.copy(isManualIpDialogVisible = false) }
    }

    fun toggleProtocol(protocol: ProtocolType) {
        val state = _uiState.value
        _uiState.update { it.copy(activeProtocol = protocol) }
        if (state.activeProtocol == protocol && state.connectionStatus == ConnectionStatus.CONNECTED) {
            return
        }
        addLog("Transport set to $protocol")
        val device = state.selectedDevice ?: return
        viewModelScope.launch {
            repository.connect(device, protocol)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
            addLog("Disconnected")
        }
    }

    fun forgetSelected() {
        val device = _uiState.value.selectedDevice ?: return
        viewModelScope.launch {
            repository.forgetDevice(device)
            _uiState.update { it.copy(selectedDevice = null) }
        }
    }

    fun sendCommand(command: RemoteCommand) {
        val device = _uiState.value.selectedDevice
        if (device == null) {
            addLog("Select a TV before sending commands")
            return
        }
        if (_uiState.value.connectionStatus != ConnectionStatus.CONNECTED) {
            addLog("Not connected. Tap Connect or Pair first.")
            return
        }

        viewModelScope.launch {
            val result = repository.sendCommand(command)
            if (result.isSuccess) {
                addLog("Sent ${command.javaClass.simpleName} to ${device.hostAddress}")
            } else {
                addLog("Command failed: ${result.exceptionOrNull()?.message}")
            }
        }
    }

    private fun statusMessage(status: ConnectionStatus): String = when (status) {
        ConnectionStatus.DISCONNECTED -> "Not connected"
        ConnectionStatus.DISCOVERING -> "Scanning local network..."
        ConnectionStatus.FOUND -> "Tap Devices to connect or pair"
        ConnectionStatus.CONNECTING -> "Connecting..."
        ConnectionStatus.PAIRING -> "Waiting for the TV pairing code..."
        ConnectionStatus.CONNECTED -> "Connected"
        ConnectionStatus.ERROR -> "Something went wrong; check the diagnostic log"
    }

    private fun addLog(message: String) {
        val stamped = "${timeFormat.format(Date())}  $message"
        _uiState.update { state ->
            state.copy(diagnosticLogs = (listOf(stamped) + state.diagnosticLogs).take(MAX_LOGS))
        }
    }
}
