@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")

package top.yurin.voxcpm2.internal

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import top.yurin.voxcpm2.AudioBuffer
import top.yurin.voxcpm2.ComputeBackend
import top.yurin.voxcpm2.ProgressPhase
import top.yurin.voxcpm2.SynthesisRequest
import top.yurin.voxcpm2.VoxCPM2Config

internal expect fun loadJniLibraries()

internal fun interface JniProgressCallback {
    fun onProgress(
        phase: Int,
        label: String,
        completed: Int,
        total: Int,
    ): Boolean
}

internal object JniBindings {
    @JvmStatic
    external fun nativeAbiVersion(): Int

    @JvmStatic
    external fun nativeCreateOperation(): Long

    @JvmStatic
    external fun nativeCancelOperation(operation: Long)

    @JvmStatic
    external fun nativeDestroyOperation(operation: Long)

    @JvmStatic
    external fun nativeCreate(
        modelDirectory: String,
        useVulkan: Boolean,
        profile: Boolean,
        threads: Int,
        vulkanDevice: Int,
        operation: Long,
        progress: JniProgressCallback?,
    ): Long

    @JvmStatic
    external fun nativeDestroy(handle: Long)

    @JvmStatic
    external fun nativeGenerate(
        handle: Long,
        text: String,
        promptText: String?,
        promptAudio: FloatArray?,
        referenceAudio: FloatArray?,
        minPatches: Int,
        inferenceTimesteps: Int,
        cfgValue: Float,
        operation: Long,
        progress: JniProgressCallback?,
    ): FloatArray

    @JvmStatic
    external fun nativeSmokeComponents(
        handle: Long,
        operation: Long,
        progress: JniProgressCallback?,
    )

    @JvmStatic
    external fun nativeGetInputSampleRate(handle: Long): Int

    @JvmStatic
    external fun nativeGetOutputSampleRate(handle: Long): Int

    @JvmStatic
    external fun nativeGetMissingRequiredComponents(handle: Long): Array<String>
}

private class JniOperation(handle: Long) : NativeOperation {
    private var handle: Long = handle

    fun requireHandle(): Long = synchronized(this) {
        check(handle != 0L) { "native operation is closed" }
        handle
    }

    override fun cancel() {
        synchronized(this) {
            if (handle != 0L) JniBindings.nativeCancelOperation(handle)
        }
    }

    override fun close() {
        val released = synchronized(this) {
            val current = handle
            handle = 0L
            current
        }
        if (released != 0L) JniBindings.nativeDestroyOperation(released)
    }
}

private class JniEngine private constructor(
    private var handle: Long,
    override val inputSampleRate: Int,
    override val outputSampleRate: Int,
) : NativeEngine {
    private val lifecycleLock = java.lang.Object()
    private var closing: Boolean = false
    private var activeCalls: Int = 0
    private val activeOperations: MutableSet<JniOperation> = mutableSetOf()

    override fun generate(
        request: SynthesisRequest,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): AudioBuffer {
        val jniOperation = operation as? JniOperation
            ?: error("operation was created by a different native platform")
        return call(jniOperation) { nativeHandle ->
            val samples = JniBindings.nativeGenerate(
                handle = nativeHandle,
                text = request.text,
                promptText = request.promptText,
                promptAudio = request.promptAudio?.samples,
                referenceAudio = request.referenceAudio?.samples,
                minPatches = request.minPatches,
                inferenceTimesteps = request.inferenceTimesteps,
                cfgValue = request.cfgValue,
                operation = jniOperation.requireHandle(),
                progress = progress.jniValue(),
            )
            AudioBuffer(samples, outputSampleRate, 1)
        }
    }

    override fun smokeComponents(
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ) {
        val jniOperation = operation as? JniOperation
            ?: error("operation was created by a different native platform")
        call(jniOperation) { nativeHandle ->
            JniBindings.nativeSmokeComponents(
                nativeHandle,
                jniOperation.requireHandle(),
                progress.jniValue(),
            )
        }
    }

    override fun missingRequiredComponents(): List<String> =
        call(operation = null) { nativeHandle ->
            JniBindings.nativeGetMissingRequiredComponents(nativeHandle).asList()
        }

    private inline fun <Result> call(
        operation: JniOperation?,
        block: (Long) -> Result,
    ): Result {
        val nativeHandle = synchronized(lifecycleLock) {
            check(!closing && handle != 0L) { "VoxCPM2 is closed" }
            activeCalls += 1
            if (operation != null) activeOperations += operation
            handle
        }
        return try {
            block(nativeHandle)
        } finally {
            synchronized(lifecycleLock) {
                if (operation != null) activeOperations -= operation
                activeCalls -= 1
                lifecycleLock.notifyAll()
            }
        }
    }

    override fun close() {
        val operations: List<JniOperation>
        synchronized(lifecycleLock) {
            if (handle == 0L) return
            if (closing) {
                waitUninterruptibly { handle != 0L }
                return
            }
            closing = true
            operations = activeOperations.toList()
        }
        operations.forEach(JniOperation::cancel)
        val released = synchronized(lifecycleLock) {
            waitUninterruptibly { activeCalls != 0 }
            handle
        }
        if (released != 0L) JniBindings.nativeDestroy(released)
        synchronized(lifecycleLock) {
            handle = 0L
            lifecycleLock.notifyAll()
        }
    }

    private inline fun waitUninterruptibly(condition: () -> Boolean) {
        var interrupted = false
        while (condition()) {
            try {
                lifecycleLock.wait()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        fun create(handle: Long): JniEngine {
            try {
                return JniEngine(
                    handle = handle,
                    inputSampleRate = JniBindings.nativeGetInputSampleRate(handle),
                    outputSampleRate = JniBindings.nativeGetOutputSampleRate(handle),
                )
            } catch (failure: Throwable) {
                JniBindings.nativeDestroy(handle)
                throw failure
            }
        }
    }
}

internal actual object NativePlatform : NativeRuntime {
    actual override val dispatcher: CoroutineDispatcher = Dispatchers.IO

    actual override fun createOperation(): NativeOperation {
        ensureLoaded()
        val handle = JniBindings.nativeCreateOperation()
        check(handle != 0L) { "failed to allocate native operation" }
        return JniOperation(handle)
    }

    actual override fun open(
        config: VoxCPM2Config,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): NativeEngine {
        ensureLoaded()
        val jniOperation = operation as? JniOperation
            ?: error("operation was created by a different native platform")
        val handle = JniBindings.nativeCreate(
            modelDirectory = config.modelDirectory,
            useVulkan = config.backend == ComputeBackend.VULKAN,
            profile = config.profile,
            threads = config.threads,
            vulkanDevice = config.vulkanDevice,
            operation = jniOperation.requireHandle(),
            progress = progress.jniValue(),
        )
        check(handle != 0L) { "native create returned a null handle" }
        return JniEngine.create(handle)
    }

    private fun ensureLoaded() {
        loadJniLibraries()
        check(JniBindings.nativeAbiVersion() == EXPECTED_ABI_VERSION) {
            "VoxCPM2 native ABI mismatch: expected $EXPECTED_ABI_VERSION"
        }
    }

    private const val EXPECTED_ABI_VERSION: Int = 1 shl 16
}

private fun NativeProgressCallback?.jniValue(): JniProgressCallback? =
    this?.let { callback ->
        JniProgressCallback { phase, label, completed, total ->
            callback.onProgress(
                NativeProgress(
                    phase = phase.progressPhase(),
                    label = label,
                    completed = completed,
                    total = total,
                ),
            )
        }
    }

private fun Int.progressPhase(): ProgressPhase =
    when (this) {
        0 -> ProgressPhase.MODEL_LOAD
        1 -> ProgressPhase.PREFIX
        2 -> ProgressPhase.GENERATION
        3 -> ProgressPhase.DECODE
        4 -> ProgressPhase.SMOKE
        else -> throw IllegalArgumentException("unknown native progress phase: $this")
    }
