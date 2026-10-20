package com.example.hometv.domain.repository

import com.example.hometv.domain.model.ConnectionStatus
import com.example.hometv.domain.model.ProtocolType
import com.example.hometv.domain.model.RemoteCommand
import com.example.hometv.domain.model.TvDevice
import kotlinx.coroutines.flow.Flow

/**
 * Discovery, pairing and command transport for Android TV / Google TV devices.
 */
interface TvRemoteRepository {
    val discoveredDevices: Flow<List<TvDevice>>
    val connectionStatus: Flow<ConnectionStatus>
    val activeProtocol: Flow<ProtocolType>
    val activeDevice: Flow<TvDevice?>
    val logs: Flow<String>

    fun startDiscovery()
    fun stopDiscovery()

    suspend fun startPairing(device: TvDevice): Result<Unit>
    suspend fun finishPairing(code: String): Result<TvDevice>
    suspend fun cancelPairing()

    suspend fun connect(device: TvDevice, protocol: ProtocolType): Result<Unit>
    suspend fun connectManualIp(ipAddress: String, protocol: ProtocolType): Result<Unit>
    suspend fun disconnect()
    suspend fun forgetDevice(device: TvDevice)

    suspend fun sendCommand(command: RemoteCommand): Result<Unit>
}
