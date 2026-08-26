package top.yurin.voxcpm2.internal

import kotlinx.coroutines.CoroutineDispatcher
import top.yurin.voxcpm2.AudioBuffer
import top.yurin.voxcpm2.ProgressPhase
import top.yurin.voxcpm2.SynthesisRequest
import top.yurin.voxcpm2.VoxCPM2Config

internal data class NativeProgress(
    val phase: ProgressPhase,
    val label: String,
    val completed: Int,
    val total: Int,
)

internal fun interface NativeProgressCallback {
    fun onProgress(progress: NativeProgress): Boolean
}

internal interface NativeOperation : AutoCloseable {
    fun cancel()

    override fun close()
}

internal interface NativeEngine : AutoCloseable {
    val inputSampleRate: Int
    val outputSampleRate: Int

    fun generate(
        request: SynthesisRequest,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): AudioBuffer

    fun smokeComponents(
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    )

    fun missingRequiredComponents(): List<String>

    override fun close()
}

internal interface NativeRuntime {
    val dispatcher: CoroutineDispatcher

    fun createOperation(): NativeOperation

    fun open(
        config: VoxCPM2Config,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): NativeEngine
}

internal expect object NativePlatform : NativeRuntime {
    override val dispatcher: CoroutineDispatcher

    override fun createOperation(): NativeOperation

    override fun open(
        config: VoxCPM2Config,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): NativeEngine
}
