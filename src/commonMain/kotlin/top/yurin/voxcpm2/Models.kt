package top.yurin.voxcpm2

/** Compute backend used by the native VoxCPM2 runtime. */
public enum class ComputeBackend {
    CPU,
    VULKAN,
}

/** Configuration used while loading a VoxCPM2 model. */
public data class VoxCPM2Config(
    public val modelDirectory: String,
    public val backend: ComputeBackend = ComputeBackend.CPU,
    public val profile: Boolean = false,
    /** Worker threads for CPU inference; `0` selects topology-aware automatic thread counts. */
    public val threads: Int = 0,
    public val vulkanDevice: Int = 0,
) {
    init {
        require(modelDirectory.isNotEmpty()) { "modelDirectory must not be empty" }
        require('\u0000' !in modelDirectory) { "modelDirectory must not contain NUL" }
        require(threads >= 0) { "threads must not be negative" }
        require(vulkanDevice >= 0) { "vulkanDevice must not be negative" }
    }
}

/** Interleaved floating-point PCM owned by Kotlin. VoxCPM2 currently requires mono audio. */
public class AudioBuffer(
    public val samples: FloatArray,
    public val sampleRate: Int,
    public val channels: Int = 1,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels > 0) { "channels must be positive" }
        require(samples.size % channels == 0) { "sample count must be divisible by channels" }
    }

    public val frameCount: Int
        get() = samples.size / channels
}

/** Inputs and inference controls for a single synthesis operation. */
public data class SynthesisRequest(
    public val text: String,
    public val promptText: String? = null,
    public val promptAudio: AudioBuffer? = null,
    public val referenceAudio: AudioBuffer? = null,
    public val minPatches: Int = 2,
    public val inferenceTimesteps: Int = 10,
    public val cfgValue: Float = 2.0f,
)

/** Stable progress phases exposed by all bindings. */
public enum class ProgressPhase {
    MODEL_LOAD,
    PREFIX,
    GENERATION,
    DECODE,
    SMOKE,
}

/** A coalescible progress snapshot for the active phase. */
public data class Progress(
    public val phase: ProgressPhase,
    public val label: String,
    public val completed: Int,
    public val total: Int,
) {
    public val fraction: Float?
        get() = if (total > 0) completed.toFloat() / total.toFloat() else null
}

/** Events emitted while asynchronously loading a model. */
public sealed interface InitializationEvent {
    public data class ProgressEvent(public val progress: Progress) : InitializationEvent

    public data class Ready(public val synthesizer: VoxCPM2) : InitializationEvent
}

/** Events emitted by one synthesis call. */
public sealed interface SynthesisEvent {
    public data class ProgressEvent(public val progress: Progress) : SynthesisEvent

    public data class Completed(public val audio: AudioBuffer) : SynthesisEvent
}

/** An unexpected failure reported by the native VoxCPM2 runtime. */
public class VoxCPM2Exception(public override val message: String) : RuntimeException(message)
