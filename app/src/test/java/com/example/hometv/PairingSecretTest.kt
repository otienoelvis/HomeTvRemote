package com.example.hometv

import com.example.hometv.data.pairing.PairingSecret
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class PairingSecretTest {

    private fun keyPair(): RSAPublicKey {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair().public as RSAPublicKey
    }

    @Test
    fun `derives the same secret for a valid code`() {
        val client = keyPair()
        val server = keyPair()
        val suffix = "1234"
        val hash = PairingSecret.hash(client, server, suffix)
        val prefix = "%02X".format(hash[0].toInt() and 0xFF)
        val code = prefix + suffix

        assertArrayEquals(hash, PairingSecret.compute(client, server, code))
    }

    @Test
    fun `rejects a code whose prefix does not match`() {
        val client = keyPair()
        val server = keyPair()
        val hash = PairingSecret.hash(client, server, "ABCD")
        val wrongPrefix = "%02X".format(((hash[0].toInt() and 0xFF) + 1) % 256)

        assertThrows(IOException::class.java) {
            PairingSecret.compute(client, server, wrongPrefix + "ABCD")
        }
    }

    @Test
    fun `secret is 32 bytes`() {
        val client = keyPair()
        val server = keyPair()
        val hash = PairingSecret.hash(client, server, "0000")
        assertEquals(32, hash.size)
    }
}
