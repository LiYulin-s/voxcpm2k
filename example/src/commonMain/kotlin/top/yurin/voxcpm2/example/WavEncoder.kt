package top.yurin.voxcpm2.example

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Encodes mono float PCM as a canonical 16-bit little-endian RIFF/WAVE file. */
object WavEncoder {
    private const val HEADER_SIZE = 44

    fun encode(samples: FloatArray, sampleRate: Int): ByteArray {
        val data = pcm16LittleEndian(samples)
        val output = ByteArray(HEADER_SIZE + data.size)

        fun ascii(offset: Int, value: String) {
            for (index in value.indices) {
                output[offset + index] = value[index].code.toByte()
            }
        }

        fun littleEndianInt32(offset: Int, value: Int) {
            output[offset] = (value and 0xff).toByte()
            output[offset + 1] = ((value ushr 8) and 0xff).toByte()
            output[offset + 2] = ((value ushr 16) and 0xff).toByte()
            output[offset + 3] = ((value ushr 24) and 0xff).toByte()
        }

        fun littleEndianInt16(offset: Int, value: Int) {
            output[offset] = (value and 0xff).toByte()
            output[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }

        val byteRate = sampleRate * CHANNELS * BYTES_PER_SAMPLE
        ascii(0, "RIFF")
        littleEndianInt32(4, 36 + data.size)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        littleEndianInt32(16, 16)
        littleEndianInt16(20, PCM_FORMAT)
        littleEndianInt16(22, CHANNELS)
        littleEndianInt32(24, sampleRate)
        littleEndianInt32(28, byteRate)
        littleEndianInt16(32, CHANNELS * BYTES_PER_SAMPLE)
        littleEndianInt16(34, BITS_PER_SAMPLE)
        ascii(36, "data")
        littleEndianInt32(40, data.size)
        data.copyInto(output, HEADER_SIZE)
        return output
    }

    /** Converts float samples in [-1, 1] to signed 16-bit little-endian PCM. */
    fun pcm16LittleEndian(samples: FloatArray): ByteArray {
        val output = ByteArray(samples.size * BYTES_PER_SAMPLE)
        for (index in samples.indices) {
            val clamped = max(-1f, min(1f, samples[index]))
            val value = (clamped * 32767f).roundToInt().toInt()
            output[index * 2] = (value and 0xff).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return output
    }

    private const val PCM_FORMAT = 1
    private const val CHANNELS = 1
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = 2
}
