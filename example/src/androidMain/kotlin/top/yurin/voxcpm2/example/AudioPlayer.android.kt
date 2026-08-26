package top.yurin.voxcpm2.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

actual class AudioPlayer {
    private var track: AudioTrack? = null

    actual fun play(samples: FloatArray, sampleRate: Int) {
        stop()
        thread(isDaemon = true, name = "voxcpm2-audio") {
            val output = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(sampleRate * Float.SIZE_BYTES)
                .build()
            track = output
            try {
                output.play()
                var offset = 0
                while (offset < samples.size) {
                    val written = output.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                    if (written <= 0) {
                        break
                    }
                    offset += written
                }
                output.stop()
            } finally {
                output.release()
                track = null
            }
        }
    }

    actual fun stop() {
        track?.let { current ->
            current.pause()
            current.flush()
            current.release()
        }
        track = null
    }
}
