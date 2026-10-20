package com.example.hometv.data.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal protobuf wire codec used by the Android TV Remote v2 protocol.
 *
 * Adapted from megarohas/megarohas_tv_voice (MIT License).
 * Copyright (c) 2026 megarohas.
 * See THIRD_PARTY_NOTICES.md for the full license text.
 */
object ProtoCodec {

    /**
     * Message builder: `Writer().varint(1, 2).msg(10, Writer().str(1, "x")).toByteArray()`.
     */
    class Writer {
        private val buffer = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): Writer {
            raw((field.toLong() shl 3))
            raw(value)
            return this
        }

        fun bytes(field: Int, value: ByteArray): Writer {
            raw(((field.toLong() shl 3) or 2L))
            raw(value.size.toLong())
            buffer.write(value, 0, value.size)
            return this
        }

        fun str(field: Int, value: String): Writer = bytes(field, value.toByteArray(StandardCharsets.UTF_8))

        fun msg(field: Int, value: Writer): Writer = bytes(field, value.toByteArray())

        fun toByteArray(): ByteArray = buffer.toByteArray()

        private fun raw(value: Long) {
            var v = value
            while ((v and 0x7FL.inv()) != 0L) {
                buffer.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
            buffer.write(v.toInt())
        }
    }

    /**
     * Parsed message: field number to a list of values. Varints are [Long], everything else [ByteArray].
     */
    class Message private constructor(
        private val fields: Map<Int, List<Any>>
    ) {
        fun has(field: Int): Boolean = fields.containsKey(field)

        fun num(field: Int, default: Long = 0L): Long {
            val values = fields[field] ?: return default
            val first = values.firstOrNull()
            return if (first is Long) first else default
        }

        fun raw(field: Int): ByteArray? {
            val values = fields[field] ?: return null
            val first = values.firstOrNull()
            return if (first is ByteArray) first else null
        }

        fun str(field: Int): String = raw(field)?.toString(StandardCharsets.UTF_8) ?: ""

        @Throws(IOException::class)
        fun msg(field: Int): Message {
            val raw = raw(field) ?: return Message(emptyMap())
            return parse(raw)
        }

        fun fieldNumbers(): Set<Int> = fields.keys

        override fun toString(): String = "ProtoMessage(fields=${fields.keys})"

        companion object {
            @Throws(IOException::class)
            fun parse(data: ByteArray): Message {
                val fields = mutableMapOf<Int, MutableList<Any>>()
                var position = 0

                fun readVarint(): Long {
                    var value = 0L
                    var shift = 0
                    while (shift < 64) {
                        if (position >= data.size) throw IOException("Truncated varint")
                        val byte = data[position++].toInt() and 0xFF
                        value = value or ((byte and 0x7F).toLong() shl shift)
                        if ((byte and 0x80) == 0) return value
                        shift += 7
                    }
                    throw IOException("Varint too long")
                }

                fun slice(length: Int): ByteArray {
                    if (length < 0 || position + length > data.size) throw IOException("Truncated field")
                    val result = data.copyOfRange(position, position + length)
                    position += length
                    return result
                }

                while (position < data.size) {
                    val tag = readVarint()
                    val field = (tag ushr 3).toInt()
                    val value: Any = when ((tag and 0x7).toInt()) {
                        0 -> readVarint()
                        1 -> slice(8)
                        2 -> slice(readVarint().toInt())
                        5 -> slice(4)
                        else -> throw IOException("Unsupported wire type in tag $tag")
                    }
                    fields.getOrPut(field) { mutableListOf() }.add(value)
                }
                return Message(fields)
            }
        }
    }

    @Throws(IOException::class)
    fun write(output: OutputStream, message: ByteArray) {
        val buffer = ByteArrayOutputStream(message.size + 5)
        var length = message.size.toLong()
        while ((length and 0x7FL.inv()) != 0L) {
            buffer.write(((length and 0x7F) or 0x80).toInt())
            length = length ushr 7
        }
        buffer.write(length.toInt())
        buffer.write(message, 0, message.size)
        output.write(buffer.toByteArray())
        output.flush()
    }

    @Throws(IOException::class)
    fun read(input: InputStream): Message {
        var length = 0L
        var shift = 0
        while (true) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Connection closed while reading message length")
            length = length or ((byte and 0x7F).toLong() shl shift)
            if ((byte and 0x80) == 0) break
            shift += 7
            if (shift > 28) throw IOException("Invalid message length prefix")
        }
        if (length > MAX_MESSAGE_SIZE) throw IOException("Message too large: $length bytes")
        val data = ByteArray(length.toInt())
        DataInputStream(input).readFully(data)
        return Message.parse(data)
    }

    private const val MAX_MESSAGE_SIZE = 1L shl 20
}
