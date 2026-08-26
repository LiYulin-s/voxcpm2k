@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
)
@file:Suppress("DEPRECATION")

package top.yurin.voxcpm2.internal

import cnames.structs.voxcpm2_operation
import cnames.structs.voxcpm2_synthesizer
import kotlinx.cinterop.AutofreeScope
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.atomics.AtomicInt
import platform.posix.sched_yield
import platform.posix.usleep
import top.yurin.voxcpm2.AudioBuffer
import top.yurin.voxcpm2.ComputeBackend
import top.yurin.voxcpm2.ProgressPhase
import top.yurin.voxcpm2.SynthesisRequest
import top.yurin.voxcpm2.VoxCPM2Config
import top.yurin.voxcpm2.VoxCPM2Exception
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_PROGRESS_DECODE
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_PROGRESS_GENERATION
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_PROGRESS_MODEL_LOAD
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_PROGRESS_PREFIX
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_PROGRESS_SMOKE
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_STATUS_CANCELLED
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_STATUS_INVALID_ARGUMENT
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_STATUS_INVALID_STATE
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_STATUS_OK
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_STATUS_OUT_OF_MEMORY
import top.yurin.voxcpm2.internal.cinterop.VOXCPM2_STATUS_RUNTIME_ERROR
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_audio_buffer
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_audio_buffer_free
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_audio_view
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_c_abi_version
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_config
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_error
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_error_free
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_operation_cancel
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_operation_create
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_operation_destroy
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_progress_callback
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_string_list
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_string_list_free
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesis_options
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesizer_create
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesizer_destroy
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesizer_generate
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesizer_input_sample_rate
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesizer_missing_required_components
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesizer_output_sample_rate
import top.yurin.voxcpm2.internal.cinterop.voxcpm2_synthesizer_smoke_components

private class SpinLock {
    private val state = AtomicInt(0)

    inline fun <Result> withLock(block: () -> Result): Result {
        while (!state.compareAndSet(0, 1)) sched_yield()
        return try {
            block()
        } finally {
            state.store(0)
        }
    }
}

private class COperation(pointer: CPointer<voxcpm2_operation>) : NativeOperation {
    private val lock = SpinLock()
    private var pointer: CPointer<voxcpm2_operation>? = pointer

    fun requirePointer(): CPointer<voxcpm2_operation> = lock.withLock {
        checkNotNull(pointer) { "native operation is closed" }
    }

    override fun cancel() {
        lock.withLock { pointer?.let(::voxcpm2_operation_cancel) }
    }

    override fun close() {
        val released = lock.withLock {
            val current = pointer
            pointer = null
            current
        }
        released?.let(::voxcpm2_operation_destroy)
    }
}

private class CEngine private constructor(
    private var pointer: CPointer<voxcpm2_synthesizer>?,
    override val inputSampleRate: Int,
    override val outputSampleRate: Int,
) : NativeEngine {
    private val lock = SpinLock()
    private var closing: Boolean = false
    private var activeCalls: Int = 0
    private val activeOperations: MutableSet<COperation> = mutableSetOf()

    override fun generate(
        request: SynthesisRequest,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): AudioBuffer {
        val cOperation = operation as? COperation
            ?: error("operation was created by a different native platform")
        return call(cOperation) { synthesizer ->
            memScoped {
                val options = alloc<voxcpm2_synthesis_options>()
                val output = alloc<voxcpm2_audio_buffer>().apply {
                    samples = null
                    sample_count = 0uL
                    sample_rate = 0
                    channels = 0
                }
                val error = allocError()
                options.text_utf8 = request.text.cstr.ptr
                options.prompt_text_utf8 = request.promptText?.cstr?.ptr
                options.min_patches = request.minPatches
                options.inference_timesteps = request.inferenceTimesteps
                options.cfg_value = request.cfgValue

                request.promptAudio.withPinnedView(this) { promptView ->
                    request.referenceAudio.withPinnedView(this) { referenceView ->
                        options.prompt_audio = promptView
                        options.reference_audio = referenceView
                        progress.withCProgress { nativeProgress, userData ->
                            checkStatus(
                                voxcpm2_synthesizer_generate(
                                    synthesizer,
                                    options.ptr,
                                    cOperation.requirePointer(),
                                    nativeProgress,
                                    userData,
                                    output.ptr,
                                    error.ptr,
                                ),
                                error,
                            )
                        }
                    }
                }

                try {
                    val count = output.sample_count
                    require(count <= Int.MAX_VALUE.toULong()) { "generated audio is too large for FloatArray" }
                    val nativeSamples = output.samples
                    require(count == 0uL || nativeSamples != null) { "native output samples are null" }
                    val samples = FloatArray(count.toInt()) { index -> nativeSamples!![index] }
                    AudioBuffer(samples, output.sample_rate, output.channels)
                } finally {
                    voxcpm2_audio_buffer_free(output.ptr)
                }
            }
        }
    }

    override fun smokeComponents(
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ) {
        val cOperation = operation as? COperation
            ?: error("operation was created by a different native platform")
        call(cOperation) { synthesizer ->
            memScoped {
                val error = allocError()
                progress.withCProgress { nativeProgress, userData ->
                    checkStatus(
                        voxcpm2_synthesizer_smoke_components(
                            synthesizer,
                            cOperation.requirePointer(),
                            nativeProgress,
                            userData,
                            error.ptr,
                        ),
                        error,
                    )
                }
            }
        }
    }

    override fun missingRequiredComponents(): List<String> =
        call(operation = null) { synthesizer ->
            memScoped {
                val output = alloc<voxcpm2_string_list>().apply {
                    items = null
                    count = 0uL
                }
                val error = allocError()
                checkStatus(
                    voxcpm2_synthesizer_missing_required_components(synthesizer, output.ptr, error.ptr),
                    error,
                )
                try {
                    val count = output.count
                    require(count <= Int.MAX_VALUE.toULong()) { "native string list is too large" }
                    val items = output.items
                    List(count.toInt()) { index ->
                        checkNotNull(items?.get(index)?.toKString()) { "native component name is null" }
                    }
                } finally {
                    voxcpm2_string_list_free(output.ptr)
                }
            }
        }

    private inline fun <Result> call(
        operation: COperation?,
        block: (CPointer<voxcpm2_synthesizer>) -> Result,
    ): Result {
        val nativePointer = lock.withLock {
            check(!closing) { "VoxCPM2 is closed" }
            val current = checkNotNull(pointer) { "VoxCPM2 is closed" }
            activeCalls += 1
            if (operation != null) activeOperations += operation
            current
        }
        return try {
            block(nativePointer)
        } finally {
            lock.withLock {
                if (operation != null) activeOperations -= operation
                activeCalls -= 1
            }
        }
    }

    override fun close() {
        var waitForAnotherClose = false
        val operations = lock.withLock {
            if (pointer == null) return
            if (closing) {
                waitForAnotherClose = true
                emptyList()
            } else {
                closing = true
                activeOperations.toList()
            }
        }
        if (waitForAnotherClose) {
            while (lock.withLock { pointer != null }) usleep(1_000u)
            return
        }
        operations.forEach(COperation::cancel)
        while (lock.withLock { activeCalls != 0 }) usleep(1_000u)
        val released = lock.withLock { pointer }
        released?.let(::voxcpm2_synthesizer_destroy)
        lock.withLock { pointer = null }
    }

    companion object {
        fun create(pointer: CPointer<voxcpm2_synthesizer>): CEngine {
            try {
                return CEngine(
                    pointer = pointer,
                    inputSampleRate = sampleRate(pointer, input = true),
                    outputSampleRate = sampleRate(pointer, input = false),
                )
            } catch (failure: Throwable) {
                voxcpm2_synthesizer_destroy(pointer)
                throw failure
            }
        }
    }
}

internal actual object NativePlatform : NativeRuntime {
    actual override val dispatcher: CoroutineDispatcher = Dispatchers.Default

    actual override fun createOperation(): NativeOperation {
        ensureAbi()
        return COperation(voxcpm2_operation_create() ?: throw OutOfMemoryError("native allocation failed"))
    }

    actual override fun open(
        config: VoxCPM2Config,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): NativeEngine {
        ensureAbi()
        val cOperation = operation as? COperation
            ?: error("operation was created by a different native platform")
        return memScoped {
            val nativeConfig = alloc<voxcpm2_config>().apply {
                model_dir_utf8 = config.modelDirectory.cstr.ptr
                use_vulkan = if (config.backend == ComputeBackend.VULKAN) 1 else 0
                profile = if (config.profile) 1 else 0
                threads = config.threads
                vulkan_device = config.vulkanDevice
            }
            val output = allocPointerTo<voxcpm2_synthesizer>().apply { value = null }
            val error = allocError()
            progress.withCProgress { nativeProgress, userData ->
                checkStatus(
                    voxcpm2_synthesizer_create(
                        nativeConfig.ptr,
                        cOperation.requirePointer(),
                        nativeProgress,
                        userData,
                        output.ptr,
                        error.ptr,
                    ),
                    error,
                )
            }
            CEngine.create(checkNotNull(output.value) { "native create returned a null handle" })
        }
    }

    private fun ensureAbi() {
        check(voxcpm2_c_abi_version() == EXPECTED_ABI_VERSION) {
            "VoxCPM2 native ABI mismatch: expected $EXPECTED_ABI_VERSION"
        }
    }

    private const val EXPECTED_ABI_VERSION: UInt = 0x0001_0000u
}

private inline fun <Result> AudioBuffer?.withPinnedView(
    scope: AutofreeScope,
    block: (CPointer<voxcpm2_audio_view>?) -> Result,
): Result {
    if (this == null) return block(null)
    return samples.usePinned { pinned ->
        val view = scope.alloc<voxcpm2_audio_view>().apply {
            this.samples = pinned.addressOf(0)
            sample_count = this@withPinnedView.samples.size.toULong()
            sample_rate = this@withPinnedView.sampleRate
            channels = this@withPinnedView.channels
        }
        block(view.ptr)
    }
}

private class ProgressBox(val callback: NativeProgressCallback)

private val cProgressCallback: voxcpm2_progress_callback =
    staticCFunction {
            userData: COpaquePointer?,
            phase: UInt,
            label: CPointer<ByteVar>?,
            completed: Int,
            total: Int,
        ->
        try {
            val callback = checkNotNull(userData).asStableRef<ProgressBox>().get().callback
            if (
                callback.onProgress(
                    NativeProgress(
                        phase = phase.progressPhase(),
                        label = label?.toKString().orEmpty(),
                        completed = completed,
                        total = total,
                    ),
                )
            ) {
                1
            } else {
                0
            }
        } catch (_: Throwable) {
            0
        }
    }

private inline fun <Result> NativeProgressCallback?.withCProgress(
    block: (voxcpm2_progress_callback?, COpaquePointer?) -> Result,
): Result {
    if (this == null) return block(null, null)
    val reference = StableRef.create(ProgressBox(this))
    return try {
        block(cProgressCallback, reference.asCPointer())
    } finally {
        reference.dispose()
    }
}

private fun AutofreeScope.allocError(): voxcpm2_error =
    alloc<voxcpm2_error>().apply {
        code = VOXCPM2_STATUS_OK
        message = null
    }

private fun checkStatus(status: UInt, error: voxcpm2_error) {
    if (status == VOXCPM2_STATUS_OK) {
        voxcpm2_error_free(error.ptr)
        return
    }
    val message = error.message?.toKString() ?: "VoxCPM2 native operation failed"
    voxcpm2_error_free(error.ptr)
    when (status) {
        VOXCPM2_STATUS_INVALID_ARGUMENT -> throw IllegalArgumentException(message)
        VOXCPM2_STATUS_INVALID_STATE -> throw IllegalStateException(message)
        VOXCPM2_STATUS_CANCELLED -> throw CancellationException(message)
        VOXCPM2_STATUS_OUT_OF_MEMORY -> throw OutOfMemoryError(message)
        VOXCPM2_STATUS_RUNTIME_ERROR -> throw VoxCPM2Exception(message)
        else -> throw VoxCPM2Exception("unknown native status $status: $message")
    }
}

private fun sampleRate(pointer: CPointer<voxcpm2_synthesizer>, input: Boolean): Int =
    memScoped {
        val output = alloc<IntVar>()
        val error = allocError()
        val status =
            if (input) {
                voxcpm2_synthesizer_input_sample_rate(pointer, output.ptr, error.ptr)
            } else {
                voxcpm2_synthesizer_output_sample_rate(pointer, output.ptr, error.ptr)
            }
        checkStatus(status, error)
        output.value
    }

private fun UInt.progressPhase(): ProgressPhase =
    when (this) {
        VOXCPM2_PROGRESS_MODEL_LOAD -> ProgressPhase.MODEL_LOAD
        VOXCPM2_PROGRESS_PREFIX -> ProgressPhase.PREFIX
        VOXCPM2_PROGRESS_GENERATION -> ProgressPhase.GENERATION
        VOXCPM2_PROGRESS_DECODE -> ProgressPhase.DECODE
        VOXCPM2_PROGRESS_SMOKE -> ProgressPhase.SMOKE
        else -> throw IllegalArgumentException("unknown native progress phase: $this")
    }
