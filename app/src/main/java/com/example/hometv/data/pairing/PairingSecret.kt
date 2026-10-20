package com.example.hometv.data.pairing

import java.io.IOException
import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/**
 * Pairing secret derived from both RSA public keys and the last 4 characters of the TV code.
 *
 * Adapted from megarohas/megarohas_tv_voice (MIT License).
 * Copyright (c) 2026 megarohas.
 */
object PairingSecret {

    fun compute(clientPublicKey: RSAPublicKey, serverPublicKey: RSAPublicKey, code: String): ByteArray {
        val hash = hash(clientPublicKey, serverPublicKey, code.substring(2))
        val expectedPrefix = code.substring(0, 2).toInt(16)
        if ((hash[0].toInt() and 0xFF) != expectedPrefix) {
            throw IOException("Code does not match the TV; check the characters on screen")
        }
        return hash
    }

    fun hash(clientPublicKey: RSAPublicKey, serverPublicKey: RSAPublicKey, suffix: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(magnitude(clientPublicKey.modulus))
        digest.update(magnitude(clientPublicKey.publicExponent))
        digest.update(magnitude(serverPublicKey.modulus))
        digest.update(magnitude(serverPublicKey.publicExponent))
        digest.update(hex(suffix))
        return digest.digest()
    }

    private fun magnitude(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
    }

    private fun hex(value: String): ByteArray {
        val result = ByteArray(value.length / 2)
        for (index in result.indices) {
            result[index] = value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
