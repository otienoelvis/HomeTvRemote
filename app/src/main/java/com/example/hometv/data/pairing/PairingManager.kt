package com.example.hometv.data.pairing

import android.util.Log
import com.example.hometv.data.protocol.ProtoCodec
import com.example.hometv.data.security.TvClientIdentity
import com.example.hometv.domain.model.PairedTv
import com.example.hometv.domain.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/**
 * Android TV Remote v2 pairing state machine.
 *
 * Adapted from megarohas/megarohas_tv_voice (MIT License).
 * Copyright (c) 2026 megarohas.
 */
class PairingManager(
    private val identity: TvClientIdentity,
    private val onLog: (String) -> Unit
) {

    private companion object {
        private const val TAG = "HomeTvLog"
        private const val PAIRING_TIMEOUT_MS = 15_000
        private const val STATUS_OK = 200L
        private const val STATUS_BAD_SECRET = 402L
    }

    data class Started(
        val device: TvDevice,
        val serverFingerprint: String
    )

    private var socket: SSLSocket? = null
    private var activeDevice: TvDevice? = null
    private var serverCertificate: X509Certificate? = null
    private var serverFingerprint: String? = null

    suspend fun start(device: TvDevice): Result<Started> = withContext(Dispatchers.IO) {
        cancel()
        log("Pairing: opening TLS session to ${device.hostAddress}:${device.pairingPort}")
        try {
            val pairSocket = identity.createSocket(device.hostAddress, device.pairingPort, PAIRING_TIMEOUT_MS)
            pairSocket.soTimeout = PAIRING_TIMEOUT_MS

            val request = ProtoCodec.Writer()
                .str(1, "atvremote")
                .str(2, "HomeTv Remote")
            log("Pairing: sending PairingRequest")
            polo(pairSocket, 10, request, 11)

            val hexEncoding = ProtoCodec.Writer().varint(1, 3).varint(2, 6)
            val options = ProtoCodec.Writer()
                .msg(1, hexEncoding)
                .varint(3, 1)
            log("Pairing: negotiating a 6-character hexadecimal code")
            polo(pairSocket, 20, options, 20)

            val configuration = ProtoCodec.Writer()
                .msg(1, ProtoCodec.Writer().varint(1, 3).varint(2, 6))
                .varint(2, 1)
            log("Pairing: sending configuration")
            polo(pairSocket, 30, configuration, 31)

            val certificate = identity.peerCertificate(pairSocket)
            val fingerprint = identity.fingerprint(certificate)
            log("Pairing: TV certificate '${certificate.subjectX500Principal.name}', fingerprint ${fingerprint.take(12)}...")

            socket = pairSocket
            activeDevice = device
            serverCertificate = certificate
            serverFingerprint = fingerprint

            log("Pairing: code should now be displayed on the TV")
            Result.success(Started(device, fingerprint))
        } catch (e: Exception) {
            Log.e(TAG, "Pairing start failed", e)
            closeQuietly()
            Result.failure(e)
        }
    }

    suspend fun finish(code: String): Result<PairedTv> = withContext(Dispatchers.IO) {
        val pairSocket = socket
        val device = activeDevice
        val certificate = serverCertificate
        val fingerprint = serverFingerprint
        if (pairSocket == null || device == null || certificate == null || fingerprint == null) {
            return@withContext Result.failure(IllegalStateException("Start pairing before entering a code"))
        }

        val normalized = code.replace(" ", "").uppercase()
        if (!normalized.matches(Regex("[0-9A-F]{6}"))) {
            return@withContext Result.failure(IllegalArgumentException("Code must be 6 characters: 0-9 and A-F"))
        }

        try {
            val secret = PairingSecret.compute(
                clientPublicKey = identity.publicKey(),
                serverPublicKey = certificate.publicKey as RSAPublicKey,
                code = normalized
            )
            log("Pairing: code verified locally, sending secret")
            polo(pairSocket, 40, ProtoCodec.Writer().bytes(1, secret), 41)

            val paired = PairedTv(
                id = fingerprint,
                name = device.name,
                hostAddress = device.hostAddress,
                pairingPort = device.pairingPort,
                commandPort = device.commandPort,
                adbPort = device.adbPort,
                pairedAt = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis()
            )
            log("Pairing: success with ${device.name} (${device.hostAddress})")
            closeQuietly()
            Result.success(paired)
        } catch (e: Exception) {
            Log.e(TAG, "Pairing finish failed", e)
            log("Pairing failed: ${e.message ?: e.javaClass.simpleName}")
            closeQuietly()
            Result.failure(e)
        }
    }

    fun cancel() {
        if (socket != null) {
            log("Pairing: cancelled")
        }
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { socket?.close() }
        socket = null
        activeDevice = null
        serverCertificate = null
        serverFingerprint = null
    }

    /**
     * One pairing round trip: OuterMessage{protocol_version=2, status=OK, payload}.
     */
    private fun polo(socket: SSLSocket, field: Int, body: ProtoCodec.Writer, expect: Int) {
        val message = ProtoCodec.Writer()
            .varint(1, 2)
            .varint(2, STATUS_OK)
            .msg(field, body)
            .toByteArray()
        ProtoCodec.write(socket.outputStream, message)

        val response = ProtoCodec.read(socket.inputStream)
        val status = response.num(2, -1)
        if (status != STATUS_OK) {
            val error = if (status == STATUS_BAD_SECRET) "TV rejected the pairing code" else "TV returned status $status"
            throw IOException(error)
        }
        if (!response.has(expect)) {
            throw IOException("Unexpected pairing response, expected field $expect (${response.fieldNumbers()})")
        }
    }

    private fun log(message: String) {
        onLog(message)
    }
}
