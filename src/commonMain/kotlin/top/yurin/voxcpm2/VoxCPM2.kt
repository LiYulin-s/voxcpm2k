package top.yurin.voxcpm2

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yurin.voxcpm2.internal.NativeEngine
import top.yurin.voxcpm2.internal.NativeOperation
import top.yurin.voxcpm2.internal.NativePlatform
import top.yurin.voxcpm2.internal.NativeProgress
import top.yurin.voxcpm2.internal.NativeProgressCallback
import top.yurin.voxcpm2.internal.NativeRuntime

/** A loaded VoxCPM2 synthesizer. Calls on one instance are serialized by the native runtime. */
public class VoxCPM2 private constructor(
    private val engine: NativeEngine,
    private val runtime: NativeRuntime,
) : AutoCloseable {
    public val inputSampleRate: Int = engine.inputSampleRate
    public val outputSampleRate: Int = engine.outputSampleRate

    /** Synthesizes one PCM buffer. Cancelling the coroutine cancels native inference. */
    public suspend fun synthesize(request: SynthesisRequest): AudioBuffer {
        validate(request, inputSampleRate)
        return nativeOperation(runtime) { operation -> engine.generate(request, operation, null) }
    }

    /**
     * Synthesizes one PCM buffer while emitting conflated progress snapshots.
     * Cancelling collection cancels native inference.
     */
    public fun synthesizeEvents(request: SynthesisRequest): Flow<SynthesisEvent> {
        validate(request, inputSampleRate)
        return channelFlow {
            val audio = nativeOperation(runtime) { operation ->
                engine.generate(
                    request,
                    operation,
                    NativeProgressCallback { progress ->
                        trySend(SynthesisEvent.ProgressEvent(progress.publicValue())).isSuccess
                    },
                )
            }
            send(SynthesisEvent.Completed(audio))
        }.buffer(Channel.CONFLATED)
    }

    /** Runs a lightweight graph/component validation without generating audio. */
    public suspend fun smokeComponents(): Unit =
        nativeOperation(runtime) { operation -> engine.smokeComponents(operation, null) }

    /** Returns required component names that are absent from the loaded model directory. */
    public fun missingRequiredComponents(): List<String> = engine.missingRequiredComponents()

    /** Cancels active work, waits for native callers to leave, and releases the model. */
    override fun close(): Unit = engine.close()

    public companion object {
        /** Loads a model on a background dispatcher. Cancellation reaches native model loading. */
        public suspend fun open(config: VoxCPM2Config): VoxCPM2 =
            VoxCPM2(
                engine = openEngine(config, NativePlatform, null),
                runtime = NativePlatform,
            )

        /**
         * Loads a model while emitting conflated progress snapshots. The collector owns the
         * synthesizer delivered by [InitializationEvent.Ready] and must close it.
         */
        public fun openEvents(config: VoxCPM2Config): Flow<InitializationEvent> =
            initializationEvents(config, NativePlatform)

        internal fun createForTesting(engine: NativeEngine, runtime: NativeRuntime): VoxCPM2 =
            VoxCPM2(engine, runtime)

        internal suspend fun openForTesting(config: VoxCPM2Config, runtime: NativeRuntime): VoxCPM2 =
            VoxCPM2(
                engine = openEngine(config, runtime, null),
                runtime = runtime,
            )

        internal fun openEventsForTesting(
            config: VoxCPM2Config,
            runtime: NativeRuntime,
        ): Flow<InitializationEvent> = initializationEvents(config, runtime)

        private fun initializationEvents(
            config: VoxCPM2Config,
            runtime: NativeRuntime,
        ): Flow<InitializationEvent> =
            flow {
                coroutineScope {
                    val progressEvents = Channel<InitializationEvent.ProgressEvent>(Channel.CONFLATED)
                    var openedEngine: NativeEngine? = null
                    val loader = async {
                        try {
                            openEngine(
                                config,
                                runtime,
                                NativeProgressCallback { progress ->
                                    progressEvents.trySend(
                                        InitializationEvent.ProgressEvent(progress.publicValue()),
                                    ).isSuccess
                                },
                            ).also { engine -> openedEngine = engine }
                        } finally {
                            progressEvents.close()
                        }
                    }
                    try {
                        for (event in progressEvents) emit(event)
                        val engine = loader.await()

                        val synthesizer = VoxCPM2(
                            engine = engine,
                            runtime = runtime,
                        )
                        openedEngine = null
                        var handedOff = false
                        try {
                            emit(InitializationEvent.Ready(synthesizer))
                            handedOff = true
                        } catch (failure: Throwable) {
                            // Short-circuiting operators such as first() throw after accepting the
                            // element while leaving the collecting context active.
                            if (currentCoroutineContext().isActive) handedOff = true
                            throw failure
                        } finally {
                            if (!handedOff) synthesizer.close()
                        }
                    } finally {
                        loader.cancel()
                        progressEvents.cancel()
                        withContext(NonCancellable) {
                            loader.join()
                            openedEngine?.close()
                        }
                    }
                }
            }

        private suspend fun openEngine(
            config: VoxCPM2Config,
            runtime: NativeRuntime,
            progress: NativeProgressCallback?,
        ): NativeEngine =
            nativeOperation(
                runtime = runtime,
                onDiscard = { engine -> engine.close() },
            ) { operation ->
                runtime.open(config, operation, progress)
            }
    }
}

private suspend fun <Result> nativeOperation(
    runtime: NativeRuntime,
    onDiscard: (Result) -> Unit = {},
    block: (NativeOperation) -> Result,
): Result =
    coroutineScope {
        val operation = runtime.createOperation()
        var outcome: NativeOperationResult<Result>? = null
        var delivered = false
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                operation.cancel()
            }
        }
        try {
            withContext(runtime.dispatcher) {
                ensureActive()
                outcome = NativeOperationResult(block(operation))
            }
            ensureActive()
            val result = checkNotNull(outcome).value
            delivered = true
            result
        } finally {
            withContext(NonCancellable) {
                cancellationWatcher.cancel()
                try {
                    cancellationWatcher.join()
                    if (!delivered) outcome?.let { onDiscard(it.value) }
                } finally {
                    operation.close()
                }
            }
        }
    }

private class NativeOperationResult<out Result>(val value: Result)

private fun NativeProgress.publicValue(): Progress =
    Progress(
        phase = phase,
        label = label,
        completed = completed,
        total = total,
    )

private fun validate(request: SynthesisRequest, inputSampleRate: Int) {
    require(request.text.isNotEmpty()) { "text must not be empty" }
    require('\u0000' !in request.text) { "text must not contain NUL" }
    require(request.promptText?.contains('\u0000') != true) { "promptText must not contain NUL" }
    require(request.minPatches >= 0) { "minPatches must not be negative" }
    require(request.inferenceTimesteps > 0) { "inferenceTimesteps must be positive" }
    require(request.cfgValue.isFinite()) { "cfgValue must be finite" }
    require(request.promptText.isNullOrEmpty() || request.promptAudio != null) {
        "promptText requires promptAudio"
    }
    validateAudio("promptAudio", request.promptAudio, inputSampleRate)
    validateAudio("referenceAudio", request.referenceAudio, inputSampleRate)
}

private fun validateAudio(name: String, audio: AudioBuffer?, inputSampleRate: Int) {
    if (audio == null) return
    require(audio.samples.isNotEmpty()) { "$name must not be empty" }
    require(audio.channels == 1) { "$name must be mono" }
    require(audio.sampleRate == inputSampleRate) {
        "$name sampleRate must equal inputSampleRate ($inputSampleRate)"
    }
}
