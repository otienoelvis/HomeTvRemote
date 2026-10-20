package com.example.hometv.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Persistent TLS client identity presented to Android TVs.
 *
 * The RSA key never leaves the Android Keystore. Pairing stores the certificate
 * fingerprint on each TV, so this identity can be paired with multiple TVs.
 *
 * Adapted from megarohas/megarohas_tv_voice (MIT License).
 * Copyright (c) 2026 megarohas.
 */
class TvClientIdentity(context: Context) {

    private companion object {
        private const val TAG = "HomeTvLog"
        private const val ALIAS = "HomeTvRemoteTls"
        private const val KEYSTORE = "AndroidKeyStore"
    }

    @Synchronized
    private fun keyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        if (!keyStore.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_DECRYPT
                )
                    .setKeySize(2048)
                    .setDigests(
                        KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_MD5,
                        KeyProperties.DIGEST_SHA1,
                        KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512
                    )
                    .setSignaturePaddings(
                        KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS
                    )
                    .setEncryptionPaddings(
                        KeyProperties.ENCRYPTION_PADDING_NONE,
                        KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1
                    )
                    .setRandomizedEncryptionRequired(false)
                    .setCertificateSubject(X500Principal("CN=HomeTv Remote"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
                    .setCertificateNotAfter(Date(now + 30L * 365 * 24 * 60 * 60 * 1000))
                    .build()
            )
            generator.generateKeyPair()
            Log.i(TAG, "Generated persistent TLS client identity")
        }
        return keyStore
    }

    fun certificate(): X509Certificate {
        return keyStore().getCertificate(ALIAS) as X509Certificate
    }

    fun publicKey(): RSAPublicKey = certificate().publicKey as RSAPublicKey

    fun createSocket(host: String, port: Int, timeoutMs: Int = 10_000): SSLSocket {
        val store = keyStore()
        val privateKey = store.getKey(ALIAS, null) as PrivateKey
        val cert = store.getCertificate(ALIAS) as X509Certificate

        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                arrayOf(ALIAS)

            override fun chooseClientAlias(
                keyType: Array<out String>?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String = ALIAS

            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

            override fun chooseServerAlias(
                keyType: String?,
                issuers: Array<out Principal>?,
                socket: Socket?
            ): String? = null

            override fun getCertificateChain(alias: String?): Array<X509Certificate> = arrayOf(cert)

            override fun getPrivateKey(alias: String?): PrivateKey = privateKey
        }

        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), SecureRandom())

        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    fun peerCertificate(socket: SSLSocket): X509Certificate {
        return socket.session.peerCertificates.first() as X509Certificate
    }

    fun fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
