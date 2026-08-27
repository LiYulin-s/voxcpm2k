package top.yurin.voxcpm2.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yurin.voxcpm2.AudioBuffer
import top.yurin.voxcpm2.ComputeBackend
import top.yurin.voxcpm2.InitializationEvent
import top.yurin.voxcpm2.Progress
import top.yurin.voxcpm2.SynthesisEvent
import top.yurin.voxcpm2.SynthesisRequest
import top.yurin.voxcpm2.VoxCPM2
import top.yurin.voxcpm2.VoxCPM2Config

sealed interface UiState {
    data object Idle : UiState

    data class Loading(val progress: Progress?) : UiState

    data class Downloading(val progress: DownloadProgress?) : UiState

    data object Ready : UiState

    data class Synthesizing(val progress: Progress?) : UiState

    data class Result(val savedTo: String?) : UiState

    data class Failed(val message: String) : UiState
}

/** Holds the Compose state and drives the VoxCPM2 flows behind the example UI. */
class SpeechModel {
    var modelDirectory by mutableStateOf("")
    var text by mutableStateOf("你好，欢迎使用 VoxCPM2。")
    var useVulkan by mutableStateOf(false)
    var threadsText by mutableStateOf("0")

    var state by mutableStateOf<UiState>(UiState.Idle)
        private set

    private var synthesizer: VoxCPM2? = null
    private var activeJob: Job? = null
    private var lastAudio: AudioBuffer? = null
    private val player = AudioPlayer()
    private val downloader = ModelDownloader()

    private fun config(): VoxCPM2Config =
        VoxCPM2Config(
            modelDirectory = modelDirectory,
            backend = if (useVulkan) ComputeBackend.VULKAN else ComputeBackend.CPU,
            threads = threadsText.toIntOrNull() ?: 0,
        )

    fun load(scope: CoroutineScope) {
        cancelActiveWork()
        activeJob = scope.launch(Dispatchers.Default) { performLoad() }
    }

    /** Downloads the model into [defaultModelDirectory], then loads it automatically. */
    fun downloadModel(scope: CoroutineScope) {
        cancelActiveWork()
        val targetDirectory = defaultModelDirectory()
        activeJob = scope.launch(Dispatchers.Default) {
            state = UiState.Downloading(null)
            try {
                downloader.ensureModel(targetDirectory) { progress ->
                    state = UiState.Downloading(progress)
                }
                modelDirectory = targetDirectory
            } catch (cancelled: CancellationException) {
                state = UiState.Idle
            } catch (error: Exception) {
                state = UiState.Failed(error.message ?: "model download failed")
                return@launch
            }
            performLoad()
        }
    }

    private suspend fun performLoad() {
        state = UiState.Loading(null)
        try {
            VoxCPM2.openEvents(config()).collect { event ->
                when (event) {
                    is InitializationEvent.ProgressEvent -> state = UiState.Loading(event.progress)
                    is InitializationEvent.Ready -> {
                        synthesizer?.close()
                        synthesizer = event.synthesizer
                        state = UiState.Ready
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            state = if (synthesizer == null) UiState.Idle else UiState.Ready
        } catch (error: Exception) {
            state = UiState.Failed(error.message ?: "model loading failed")
        }
    }

    fun synthesize(scope: CoroutineScope) {
        val target = synthesizer ?: return
        cancelActiveWork()
        activeJob = scope.launch(Dispatchers.Default) {
            state = UiState.Synthesizing(null)
            try {
                target.synthesizeEvents(SynthesisRequest(text = text)).collect { event ->
                    when (event) {
                        is SynthesisEvent.ProgressEvent -> state = UiState.Synthesizing(event.progress)
                        is SynthesisEvent.Completed -> {
                            lastAudio = event.audio
                            state = UiState.Result(null)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                state = UiState.Ready
            } catch (error: Exception) {
                state = UiState.Failed(error.message ?: "synthesis failed")
            }
        }
    }

    fun cancelActiveWork() {
        activeJob?.cancel()
        activeJob = null
    }

    fun playLastResult() {
        val audio = lastAudio ?: return
        player.play(audio.samples, audio.sampleRate)
    }

    fun stopPlayback() {
        player.stop()
    }

    fun saveLastResultAsWav() {
        val audio = lastAudio ?: return
        val path = defaultOutputDirectory() + "/voxcpm2-output.wav"
        try {
            writeBytes(path, WavEncoder.encode(audio.samples, audio.sampleRate))
            state = UiState.Result(path)
        } catch (error: Exception) {
            state = UiState.Failed(error.message ?: "failed to write $path")
        }
    }

    /** Cancels active work, stops playback, and releases the loaded model. */
    fun close() {
        cancelActiveWork()
        player.stop()
        synthesizer?.close()
        synthesizer = null
        state = UiState.Idle
    }
}
