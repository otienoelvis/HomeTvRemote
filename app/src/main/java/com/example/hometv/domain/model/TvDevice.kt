package com.example.hometv.domain.model

/**
 * Transport used to communicate with a TV.
 */
enum class ProtocolType {
    OFFICIAL_TLS,
    ADB_FALLBACK
}

/**
 * Connection state of a TV device.
 */
enum class ConnectionStatus {
    DISCONNECTED,
    DISCOVERING,
    FOUND,
    CONNECTING,
    PAIRING,
    CONNECTED,
    ERROR
}

/**
 * Represents a discovered or remembered TV on the local network.
 */
data class TvDevice(
    val id: String,
    val name: String,
    val hostAddress: String,
    val pairingPort: Int = 6467,
    val commandPort: Int = 6466,
    val adbPort: Int = 5555,
    val isPaired: Boolean = false,
    val isOnline: Boolean = true,
    val serviceType: String? = null,
    val lastSeen: Long = System.currentTimeMillis(),
    val activeProtocol: ProtocolType = ProtocolType.OFFICIAL_TLS,
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
)

/**
 * A TV that completed the TLS pairing handshake. [id] is the TV certificate fingerprint.
 */
data class PairedTv(
    val id: String,
    val name: String,
    val hostAddress: String,
    val pairingPort: Int = 6467,
    val commandPort: Int = 6466,
    val adbPort: Int = 5555,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis()
)
