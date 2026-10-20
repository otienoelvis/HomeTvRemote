package com.example.hometv.ui.remote

import com.example.hometv.domain.model.ConnectionStatus
import com.example.hometv.domain.model.ProtocolType
import com.example.hometv.domain.model.TvDevice

data class RemoteUiState(
    val discoveredDevices: List<TvDevice> = emptyList(),
    val selectedDevice: TvDevice? = null,
    val activeDevice: TvDevice? = null,
    val pairingDevice: TvDevice? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeProtocol: ProtocolType = ProtocolType.OFFICIAL_TLS,
    val isPairingDialogVisible: Boolean = false,
    val pairingPinInput: String = "",
    val isManualIpDialogVisible: Boolean = false,
    val manualIpInput: String = "",
    val isDevicesSheetVisible: Boolean = false,
    val isDiagnosticsVisible: Boolean = false,
    val statusMessage: String = "Ready to discover TV devices.",
    val diagnosticLogs: List<String> = listOf("System initialized. Tap Scan to discover Android TVs.")
) {
    val isScanning: Boolean
        get() = connectionStatus == ConnectionStatus.DISCOVERING ||
            connectionStatus == ConnectionStatus.FOUND

    val isConnected: Boolean
        get() = connectionStatus == ConnectionStatus.CONNECTED
}
