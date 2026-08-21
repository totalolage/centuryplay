package com.airplay.streamer.raop

/** Encodes one 352-frame, 16-bit stereo PCM packet as uncompressed ALAC. */
class AlacEncoder {
    companion object {
        private const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4
        private const val PCM_PACKET_SIZE = FRAMES_PER_PACKET * BYTES_PER_FRAME
    }

    /** Input is interleaved little-endian PCM. */
    fun encode(pcmData: ByteArray): ByteArray {
        require(pcmData.size == PCM_PACKET_SIZE)

        // ALAC's uncompressed stereo frame is bit-aligned; the first sample's
        // sign bit shares the final byte of the frame-count header. Framing follows
        // airplay-cli's GPL-3.0 pcm_to_alac_raw reference implementation.
        val output = ByteArray(8 + PCM_PACKET_SIZE)
        var out = 0
        output[out++] = 0x20
        output[out++] = 0
        output[out++] = 0x12
        val frameBits = FRAMES_PER_PACKET shl 1
        output[out++] = (frameBits ushr 24).toByte()
        output[out++] = (frameBits ushr 16).toByte()
        output[out++] = (frameBits ushr 8).toByte()
        output[out++] = (frameBits or (sample(pcmData, 0) ushr 15)).toByte()

        for (frame in 0 until FRAMES_PER_PACKET) {
            val left = sample(pcmData, frame * BYTES_PER_FRAME)
            val right = sample(pcmData, frame * BYTES_PER_FRAME + 2)
            val nextLeftSign = if (frame + 1 < FRAMES_PER_PACKET) {
                sample(pcmData, (frame + 1) * BYTES_PER_FRAME) ushr 15
            } else {
                0
            }
            output[out++] = ((left and 0x7f80) ushr 7).toByte()
            output[out++] = (((left and 0x7f) shl 1) or (right ushr 15)).toByte()
            output[out++] = ((right and 0x7f80) ushr 7).toByte()
            output[out++] = (((right and 0x7f) shl 1) or nextLeftSign).toByte()
        }

        output[out - 1] = (output[out - 1].toInt() or 1).toByte()
        output[out] = 0xc0.toByte()
        return output
    }

    fun getExpectedPcmSize(): Int = PCM_PACKET_SIZE

    private fun sample(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}
