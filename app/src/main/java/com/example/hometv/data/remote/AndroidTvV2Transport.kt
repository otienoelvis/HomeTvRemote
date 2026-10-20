package com.example.hometv.data.remote

import android.os.Build
import android.util.Log
import com.example.hometv.data.protocol.ProtoCodec
import com.example.hometv.data.security.TvClientIdentity
import com.example.hometv.domain.model.RemoteCommand
import com.example.hometv.domain.model.TvDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import javax.net.ssl.SSLSocket

/**
 * Long-lived Android TV Remote v2 command session on port 6466.
 *
 * Adapted from megarohas/megarohas_tv_voice (MIT License).
 * Copyright (c) 2026 megarohas.
 */
class AndroidTvV2Transport(
    private val identity: TvClientIdentity,
    private val onLog: (String) -> Unit
) {

    private companion object {
        private const val TAG = "HomeTvLog"
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READY_TIMEOUT_MS = 10_000L
        private const val FEATURES = 1 or 2 or 32 or 64 or 512 // PING | KEY | POWER | VOLUME | APP_LINK
        private const val SHORT_PRESS = 3
    }

    sealed interface Event {
        data object Connected : Event
        data class Disconnected(val reason: String?) : Event
        data class Error(val message: String) : Event
        data class Volume(val level: Int, val max: Int, val muted: Boolean) : Event
        data class Powered(val isOn: Boolean) : Event
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val writeMutex = Mutex()
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var socket: SSLSocket? = null
    private var outputStream: OutputStream? = null
    private var readJob: Job? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var ready = false

    @Volatile
    private var shouldStayConnected = false

    @Volatile
    private var activeFeatures = FEATURES

    private var readyDeferred: CompletableDeferred<Result<Unit>>? = null
    private var activeDevice: TvDevice? = null
    private var activeFingerprint: String? = null

    suspend fun connect(device: TvDevice, expectedFingerprint: String): Result<Unit> = withContext(Dispatchers.IO) {
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal(notify = false)
        shouldStayConnected = true
        activeDevice = device
        activeFingerprint = expectedFingerprint

        var result = connectOnce(device, expectedFingerprint)
        if (result.isFailure) {
            log("Initial connection failed; retrying in 1s")
            delay(1_000)
            result = connectOnce(device, expectedFingerprint)
        }
        if (result.isFailure) {
            delay(2_000)
            result = connectOnce(device, expectedFingerprint)
        }
        result
    }

    suspend fun sendCommand(command: RemoteCommand): Result<Unit> = withContext(Dispatchers.IO) {
        if (!ready) {
            return@withContext Result.failure(IllegalStateException("TV session is not connected"))
        }
        try {
            when (command) {
                is RemoteCommand.KeyEventCommand -> {
                    val message = ProtoCodec.Writer()
                        .msg(
                            10,
                            ProtoCodec.Writer()
                                .varint(1, command.keycode.toLong())
                                .varint(2, SHORT_PRESS.toLong())
                        )
                    send(message)
                    log("Sent key ${command.label} (${command.keycode})")
                }

                is RemoteCommand.LaunchAppCommand -> {
                    val message = ProtoCodec.Writer()
                        .msg(90, ProtoCodec.Writer().str(1, command.appLink))
                    send(message)
                    log("Sent app link ${command.label} (${command.appLink})")
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command", e)
            handleLost(socket, e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        }
    }

    fun disconnect() {
        log("Disconnecting TV session")
        shouldStayConnected = false
        reconnectJob?.cancel()
        reconnectJob = null
        disconnectInternal(notify = true)
    }

    fun close() {
        disconnect()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun connectOnce(device: TvDevice, expectedFingerprint: String): Result<Unit> {
        if (ready && socket != null) {
            return Result.success(Unit)
        }
        log("Connecting to ${device.name} at ${device.hostAddress}:${device.commandPort}")
        return try {
            val newSocket = identity.createSocket(device.hostAddress, device.commandPort, CONNECT_TIMEOUT_MS)
            val peerFingerprint = identity.fingerprint(identity.peerCertificate(newSocket))
            if (!peerFingerprint.equals(expectedFingerprint, ignoreCase = true)) {
                newSocket.close()
                throw IOException("TV certificate does not match the paired TV")
            }
            log("TLS session established with ${device.hostAddress}")

            newSocket.soTimeout = 20_000
            socket = newSocket
            outputStream = newSocket.outputStream
            ready = false
            val deferred = CompletableDeferred<Result<Unit>>()
            readyDeferred = deferred
            readJob = scope.launch { readLoop(newSocket, device) }

            val result = withTimeoutOrNull(READY_TIMEOUT_MS) { deferred.await() }
                ?: Result.failure(IOException("Timed out waiting for the TV to accept the remote session"))
            if (result.isFailure) {
                disconnectInternal(notify = false)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Could not connect TV session", e)
            log("Connection failed: ${e.message ?: e.javaClass.simpleName}")
            disconnectInternal(notify = false)
            Result.failure(e)
        }
    }

    private suspend fun readLoop(socket: SSLSocket, device: TvDevice) {
        var wasReady = false
        val input: InputStream = try {
            socket.inputStream
        } catch (e: Exception) {
            handleLost(socket, e.message)
            return
        }
        try {
            while (scope.isActive && !socket.isClosed) {
                val message = ProtoCodec.read(input)
                handleMessage(message)
                if (ready) wasReady = true
            }
        } catch (e: SocketException) {
            handleLost(socket, e.message)
        } catch (e: Exception) {
            handleLost(socket, e.message ?: e.javaClass.simpleName)
        } finally {
            if (wasReady) {
                Log.d(TAG, "Read loop finished for ${device.hostAddress}")
            }
        }
    }

    private suspend fun handleMessage(message: ProtoCodec.Message) {
        when {
            message.has(1) -> {
                val tvFeatures = message.msg(1).num(1, FEATURES.toLong()).toInt()
                val negotiated = FEATURES and tvFeatures
                activeFeatures = negotiated
                val response = ProtoCodec.Writer()
                    .msg(
                        1,
                        ProtoCodec.Writer()
                            .varint(1, negotiated.toLong())
                            .msg(
                                2,
                                ProtoCodec.Writer()
                                    .str(1, Build.MODEL ?: "Android")
                                    .str(2, Build.MANUFACTURER ?: "Android")
                                    .varint(3, 1)
                                    .str(4, "1")
                                    .str(5, "HomeTv")
                                    .str(6, "1.0")
                            )
                    )
                send(response)
                log("Replied to TV configure (features=$negotiated)")
            }

            message.has(2) -> {
                send(ProtoCodec.Writer().msg(2, ProtoCodec.Writer().varint(1, activeFeatures.toLong())))
                if (!ready) {
                    ready = true
                    log("TV remote session is ready")
                    readyDeferred?.complete(Result.success(Unit))
                    _events.tryEmit(Event.Connected)
                }
            }

            message.has(8) -> {
                val value = message.msg(8).num(1, 0)
                send(ProtoCodec.Writer().msg(9, ProtoCodec.Writer().varint(1, value)))
            }

            message.has(40) -> {
                val isOn = message.msg(40).num(1, 0) != 0L
                _events.tryEmit(Event.Powered(isOn))
            }

            message.has(50) -> {
                val volume = message.msg(50)
                _events.tryEmit(
                    Event.Volume(
                        level = volume.num(7, -1).toInt(),
                        max = volume.num(6, -1).toInt(),
                        muted = volume.num(8, 0) != 0L
                    )
                )
            }

            message.has(3) -> {
                val error = "TV reported an error"
                Log.w(TAG, "$error (fields=${message.msg(3).fieldNumbers()})")
                _events.tryEmit(Event.Error(error))
            }

            else -> {
                Log.d(TAG, "Ignoring TV message with fields ${message.fieldNumbers()}")
            }
        }
    }

    private suspend fun send(message: ProtoCodec.Writer) {
        val output = outputStream ?: throw IOException("No active TV session")
        writeMutex.withLock {
            ProtoCodec.write(output, message.toByteArray())
        }
    }

    private fun handleLost(sourceSocket: SSLSocket?, reason: String?) {
        if (sourceSocket != null && socket !== sourceSocket) {
            return
        }
        val wasReady = ready
        if (!ready && readyDeferred?.isCompleted == false) {
            readyDeferred?.complete(Result.failure(IOException(reason ?: "Connection lost")))
        }
        ready = false
        if (shouldStayConnected) {
            log("TV session lost: ${reason ?: "connection closed"}")
            _events.tryEmit(Event.Disconnected(reason))
        }
        closeSocket()
        if (wasReady && shouldStayConnected) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldStayConnected || ready || reconnectJob?.isActive == true) return
        val device = activeDevice ?: return
        val fingerprint = activeFingerprint ?: return
        reconnectJob = scope.launch {
            var backoff = 1_000L
            while (shouldStayConnected) {
                log("Reconnecting to ${device.name} in ${backoff / 1000}s")
                delay(backoff)
                val result = connectOnce(device, fingerprint)
                if (result.isSuccess) {
                    log("Reconnected to ${device.name}")
                    return@launch
                }
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun disconnectInternal(notify: Boolean) {
        ready = false
        readyDeferred?.cancel()
        readyDeferred = null
        readJob?.cancel()
        readJob = null
        closeSocket()
        if (notify) {
            _events.tryEmit(Event.Disconnected(null))
        }
    }

    private fun closeSocket() {
        outputStream = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun log(message: String) {
        onLog(message)
    }
}
