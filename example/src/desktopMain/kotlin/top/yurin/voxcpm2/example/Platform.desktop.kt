package top.yurin.voxcpm2.example

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

actual class AudioPlayer {
    private var line: SourceDataLine? = null

    actual fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        val pcm = WavEncoder.pcm16LittleEndian(samples)
        thread(isDaemon = true, name = "voxcpm2-audio") {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val output = AudioSystem.getSourceDataLine(format)
            line = output
            try {
                output.open(format)
                output.start()
                output.write(pcm, 0, pcm.size)
                output.drain()
            } finally {
                output.close()
                line = null
            }
        }
    }

    actual fun stop() {
        line?.let { current ->
            current.stop()
            current.close()
        }
        line = null
    }
}

actual fun defaultOutputDirectory(): String = System.getProperty("user.home") ?: "."

actual fun writeBytes(path: String, bytes: ByteArray) {
    File(path).writeBytes(bytes)
}
