package top.yurin.voxcpm2.example

/** Plays mono float PCM on the platform audio output. */
expect class AudioPlayer {
    constructor()

    /** Starts playback on a background thread; replaces any previous playback. */
    fun play(samples: FloatArray, sampleRate: Int)

    /** Stops the current playback, if any. */
    fun stop()
}

/** Directory the example writes exported WAV files into. */
expect fun defaultOutputDirectory(): String

/** Writes [bytes] to [path], creating or replacing the file. */
expect fun writeBytes(path: String, bytes: ByteArray)
