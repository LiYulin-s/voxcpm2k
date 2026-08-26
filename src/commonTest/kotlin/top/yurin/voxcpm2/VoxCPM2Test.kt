@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package top.yurin.voxcpm2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.yurin.voxcpm2.internal.NativeEngine
import top.yurin.voxcpm2.internal.NativeOperation
import top.yurin.voxcpm2.internal.NativeProgress
import top.yurin.voxcpm2.internal.NativeProgressCallback
import top.yurin.voxcpm2.internal.NativeRuntime

class VoxCPM2Test {
    @Test
    fun audioBufferReportsFrames() {
        val audio = AudioBuffer(floatArrayOf(0f, 1f, 2f, 3f), sampleRate = 24_000, channels = 2)
        assertEquals(2, audio.frameCount)
    }

    @Test
    fun invalidConfigIsRejectedBeforeNativeCode() {
        assertFailsWith<IllegalArgumentException> { VoxCPM2Config("") }
        assertFailsWith<IllegalArgumentException> { VoxCPM2Config("model", threads = -1) }
        assertFailsWith<IllegalArgumentException> { VoxCPM2Config("model", vulkanDevice = -1) }
    }

    @Test
    fun synthesisEventsContainProgressAndResult() = runBlocking {
        val runtime = FakeRuntime()
        val engine = FakeEngine()
        val synthesizer = VoxCPM2.createForTesting(engine, runtime)

        val events = synthesizer.synthesizeEvents(SynthesisRequest("你好")).toList()

        assertTrue(events.any { it is SynthesisEvent.ProgressEvent })
        val completed = assertIs<SynthesisEvent.Completed>(events.last())
        assertEquals(24_000, completed.audio.sampleRate)
        assertTrue(completed.audio.samples.isNotEmpty())
    }

    @Test
    fun requestValidationIsSharedByBothSurfaces() = runBlocking {
        val synthesizer = VoxCPM2.createForTesting(FakeEngine(), FakeRuntime())
        assertFailsWith<IllegalArgumentException> {
            synthesizer.synthesize(SynthesisRequest(""))
        }
        assertFailsWith<IllegalArgumentException> {
            synthesizer.synthesize(
                SynthesisRequest(
                    text = "hello",
                    promptText = "voice",
                    promptAudio = null,
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            synthesizer.synthesize(
                SynthesisRequest(
                    text = "hello",
                    referenceAudio = AudioBuffer(floatArrayOf(0f), 24_000),
                ),
            )
        }
        Unit
    }

    @Test
    fun cancellationReachesTheNativeOperation() = runBlocking {
        val runtime = FakeRuntime(Dispatchers.Default)
        val engine = FakeEngine(blockUntilCancelled = true)
        val synthesizer = VoxCPM2.createForTesting(engine, runtime)

        val job = launch { synthesizer.synthesize(SynthesisRequest("cancel me")) }
        withTimeout(5_000) { engine.started.await() }
        job.cancelAndJoin()

        assertTrue(runtime.lastOperation?.cancelled?.load() == true)
        assertTrue(engine.cancellationObserved.load())
    }

    @Test
    fun cancellationClosesAnOpenResultThatCannotBeDelivered() = runBlocking {
        val runtime = CancellationIgnoringOpenRuntime()

        val job = launch {
            VoxCPM2.openForTesting(VoxCPM2Config("model"), runtime)
        }
        withTimeout(5_000) { runtime.started.await() }
        job.cancelAndJoin()

        assertTrue(runtime.lastOperation.cancelled.load())
        assertEquals(1, runtime.engine.closeCount)
    }

    @Test
    fun initializationEventsHandOwnershipToTheReadyCollector() = runBlocking {
        val runtime = FakeRuntime()

        val events = VoxCPM2.openEventsForTesting(VoxCPM2Config("model"), runtime).toList()

        assertTrue(events.any { it is InitializationEvent.ProgressEvent })
        val ready = assertIs<InitializationEvent.Ready>(events.last())
        assertEquals(0, runtime.openedEngine.closeCount)
        ready.synthesizer.close()
        assertEquals(1, runtime.openedEngine.closeCount)
    }

    @Test
    fun initializationReadyKeepsOwnershipThroughFirstOperator() = runBlocking {
        val runtime = FakeRuntime()

        val ready = VoxCPM2.openEventsForTesting(VoxCPM2Config("model"), runtime)
            .filterIsInstance<InitializationEvent.Ready>()
            .first()

        assertEquals(0, runtime.openedEngine.closeCount)
        ready.synthesizer.close()
        assertEquals(1, runtime.openedEngine.closeCount)
    }

    @Test
    fun cancellingInitializationWhileRenderingProgressClosesTheLoadedEngine() = runBlocking {
        val runtime = FakeRuntime()
        val progressReceived = CompletableDeferred<Unit>()

        val job = launch {
            VoxCPM2.openEventsForTesting(VoxCPM2Config("model"), runtime).collect { event ->
                if (event is InitializationEvent.ProgressEvent) {
                    progressReceived.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        withTimeout(5_000) { progressReceived.await() }
        withTimeout(5_000) {
            while (runtime.lastOperation?.closed?.load() != true) yield()
        }
        yield()
        job.cancelAndJoin()

        assertEquals(1, runtime.openedEngine.closeCount)
    }

    @Test
    fun closeIsIdempotent() {
        val engine = FakeEngine()
        val synthesizer = VoxCPM2.createForTesting(engine, FakeRuntime())
        synthesizer.close()
        synthesizer.close()
        assertEquals(1, engine.closeCount)
    }
}

private class FakeOperation : NativeOperation {
    val cancelled = AtomicBoolean(false)
    val closed = AtomicBoolean(false)

    override fun cancel() {
        cancelled.store(true)
    }

    override fun close() {
        closed.store(true)
    }
}

private class FakeRuntime(
    override val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : NativeRuntime {
    var lastOperation: FakeOperation? = null
    val openedEngine = FakeEngine()

    override fun createOperation(): NativeOperation = FakeOperation().also { lastOperation = it }

    override fun open(
        config: VoxCPM2Config,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): NativeEngine {
        progress?.onProgress(NativeProgress(ProgressPhase.MODEL_LOAD, "model load", 1, 1))
        return openedEngine
    }
}

private class CancellationIgnoringOpenRuntime : NativeRuntime {
    override val dispatcher: CoroutineDispatcher = Dispatchers.Default
    val started = CompletableDeferred<Unit>()
    val engine = FakeEngine()
    val lastOperation = FakeOperation()

    override fun createOperation(): NativeOperation = lastOperation

    override fun open(
        config: VoxCPM2Config,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): NativeEngine {
        val fakeOperation = operation as FakeOperation
        started.complete(Unit)
        while (!fakeOperation.cancelled.load()) {
            // Simulates native create completing successfully after cancellation raced with
            // the final native safe point.
        }
        return engine
    }
}

private class FakeEngine(
    private val blockUntilCancelled: Boolean = false,
) : NativeEngine {
    override val inputSampleRate: Int = 16_000
    override val outputSampleRate: Int = 24_000
    val started = CompletableDeferred<Unit>()
    val cancellationObserved = AtomicBoolean(false)
    var closeCount: Int = 0

    override fun generate(
        request: SynthesisRequest,
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ): AudioBuffer {
        val fakeOperation = operation as FakeOperation
        started.complete(Unit)
        if (blockUntilCancelled) {
            while (!fakeOperation.cancelled.load()) {
                // Native inference is synchronous; this spin emulates a call that
                // can only observe cancellation through its operation token.
            }
            cancellationObserved.store(true)
            throw CancellationException("cancelled by test")
        }
        progress?.onProgress(NativeProgress(ProgressPhase.PREFIX, "prefix", 0, 1))
        progress?.onProgress(NativeProgress(ProgressPhase.GENERATION, "patch", 1, 1))
        progress?.onProgress(NativeProgress(ProgressPhase.DECODE, "decode", 1, 1))
        return AudioBuffer(floatArrayOf(0.25f, -0.25f), outputSampleRate)
    }

    override fun smokeComponents(
        operation: NativeOperation,
        progress: NativeProgressCallback?,
    ) = Unit

    override fun missingRequiredComponents(): List<String> = emptyList()

    override fun close() {
        if (closeCount == 0) closeCount += 1
    }
}
