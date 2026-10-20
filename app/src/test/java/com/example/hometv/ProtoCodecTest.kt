package com.example.hometv

import com.example.hometv.data.protocol.ProtoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class ProtoCodecTest {

    @Test
    fun `round trips nested messages and varints`() {
        val message = ProtoCodec.Writer()
            .varint(1, 2)
            .varint(2, 200)
            .msg(10, ProtoCodec.Writer().str(1, "atvremote").str(2, "HomeTv"))
            .toByteArray()

        val stream = ByteArrayOutputStream()
        ProtoCodec.write(stream, message)
        val decoded = ProtoCodec.read(ByteArrayInputStream(stream.toByteArray()))

        assertEquals(2, decoded.num(1))
        assertEquals(200, decoded.num(2))
        assertEquals("atvremote", decoded.msg(10).str(1))
        assertEquals("HomeTv", decoded.msg(10).str(2))
    }

    @Test
    fun `encodes key inject exactly as the TV protocol expects`() {
        val keyPacket = ProtoCodec.Writer()
            .msg(
                10,
                ProtoCodec.Writer()
                    .varint(1, 26)
                    .varint(2, 3)
            )
            .toByteArray()

        assertArrayEquals(
            byteArrayOf(0x52, 0x04, 0x08, 0x1A, 0x10, 0x03),
            keyPacket
        )
    }

    @Test
    fun `length prefix is a varint`() {
        val payload = ByteArray(200) { it.toByte() }
        val stream = ByteArrayOutputStream()
        ProtoCodec.write(stream, payload)
        val encoded = stream.toByteArray()

        assertEquals(0xC8.toByte(), encoded[0])
        assertEquals(0x01.toByte(), encoded[1])
        assertEquals(202, encoded.size)
        assertTrue(encoded.drop(2).toByteArray().contentEquals(payload))
    }
}
