package top.yurin.voxcpm2.example

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Raised when the model download cannot start or complete. */
class ModelDownloadException(message: String) : Exception(message)

/** Aggregate snapshot of a model download. */
data class DownloadProgress(
    val currentFile: String,
    val bytesDone: Long,
    val bytesTotal: Long,
) {
    val fraction: Float?
        get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal.toFloat() else null
}

/**
 * Downloads the prebuilt VoxCPM2 ncnn assets published on HuggingFace into a local
 * directory. The file list is derived from the repository's own `model.json`, so
 * the downloader follows the manifest instead of a hardcoded list.
 */
class ModelDownloader(private val transport: NetworkTransport = NetworkTransport()) {
    /** Downloads any missing files into [directory]; resumable and idempotent. */
    suspend fun ensureModel(directory: String, onProgress: (DownloadProgress) -> Unit) {
        withContext(Dispatchers.IO) {
            val downloadContext = currentCoroutineContext()
            fun checkActive() {
                if (!downloadContext.isActive) {
                    throw CancellationException("model download cancelled")
                }
            }

            val base = transport.selectBase("$REPO_PATH/model.json")
                ?: throw ModelDownloadException(
                    "no download source is reachable (tried $BASE_CANDIDATES)",
                )
            val resolve = "$base/$REPO_PATH"

            transport.downloadFile("$resolve/model.json", "$directory/model.json") { checkActive() }
            val files = parseManifestFiles(transport.readTextFile("$directory/model.json"))
            if (files.isEmpty()) {
                throw ModelDownloadException("model.json references no component files")
            }

            val sizes = files.associateWith { transport.contentLength("$resolve/$it") }
            val bytesTotal = sizes.values.filterNotNull().sum()
            var bytesDone = 0L
            for (file in files) {
                val expected = sizes.getValue(file)
                if (expected != null && transport.fileSize("$directory/$file") == expected) {
                    bytesDone += expected
                }
            }
            onProgress(DownloadProgress("preparing", bytesDone, bytesTotal))

            val remaining = bytesTotal - bytesDone
            val freeSpace = transport.usableSpaceBytes(directory)
            if (freeSpace in 1..remaining) {
                throw ModelDownloadException(
                    "insufficient space for ${remaining / 1_000_000_000.0} GB of remaining assets " +
                        "(available ${freeSpace / 1_000_000_000.0} GB)",
                )
            }

            var lastReport = TimeSource.Monotonic.markNow()
            for (file in files) {
                checkActive()
                val expected = sizes.getValue(file)
                val destination = "$directory/$file"
                if (expected != null && transport.fileSize(destination) == expected) {
                    continue
                }
                transport.deleteFile(destination)
                transport.downloadFile("$resolve/$file", destination) { delta ->
                    checkActive()
                    bytesDone += delta
                    if (lastReport.elapsedNow() > REPORT_INTERVAL) {
                        lastReport = TimeSource.Monotonic.markNow()
                        onProgress(DownloadProgress(file, bytesDone, bytesTotal))
                    }
                }
                val actual = transport.fileSize(destination)
                if (expected != null && actual != expected) {
                    throw ModelDownloadException(
                        "size mismatch for $file: expected $expected bytes, downloaded $actual",
                    )
                }
                onProgress(DownloadProgress(file, bytesDone, bytesTotal))
            }
        }
    }

    internal fun parseManifestFiles(manifestJson: String): List<String> {
        val manifest = json.decodeFromString(RemoteManifest.serializer(), manifestJson)
        val referenced = buildList {
            manifest.params.values.forEach { files ->
                add(files.param)
                add(files.bin)
            }
            add(manifest.tokenizer.tokenizerJson)
        }
        return referenced.filter { it.isNotEmpty() }.distinct().sorted()
    }

    @Serializable
    private data class RemoteManifest(
        val params: Map<String, RemoteComponentFiles> = emptyMap(),
        val tokenizer: RemoteTokenizer = RemoteTokenizer(),
    )

    @Serializable
    private data class RemoteComponentFiles(
        val param: String = "",
        val bin: String = "",
    )

    @Serializable
    private data class RemoteTokenizer(
        @kotlinx.serialization.SerialName("tokenizer_json")
        val tokenizerJson: String = "",
    )

    private companion object {
        val BASE_CANDIDATES = listOf("https://hf-mirror.com", "https://huggingface.co")
        const val REPO_PATH = "lyrin/voxpm2-ncnn/resolve/main"
        val json = Json { ignoreUnknownKeys = true }
        val REPORT_INTERVAL = 200.milliseconds
    }
}
