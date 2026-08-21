package com.airplay.streamer.raop

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AlacEncoderTest {
    @Test
    fun encodesReferenceUncompressedFrameLayout() {
        val pcm = ByteArray(352 * 4)
        pcm[0] = 0x34
        pcm[1] = 0x12
        pcm[2] = 0xcd.toByte()
        pcm[3] = 0xab.toByte()
        pcm[5] = 0x80.toByte()

        val encoded = AlacEncoder().encode(pcm)

        assertEquals(1416, encoded.size)
        assertArrayEquals(
            byteArrayOf(0x20, 0, 0x12, 0, 0, 0x02, 0xc0.toByte()),
            encoded.copyOfRange(0, 7)
        )
        assertArrayEquals(
            byteArrayOf(0x24, 0x69, 0x57, 0x9b.toByte()),
            encoded.copyOfRange(7, 11)
        )
        assertEquals(1, encoded[encoded.lastIndex - 1].toInt())
        assertEquals(0xc0.toByte(), encoded.last())
    }
}
