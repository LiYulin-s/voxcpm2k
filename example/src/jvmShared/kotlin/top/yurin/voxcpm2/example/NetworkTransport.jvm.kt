package top.yurin.voxcpm2.example

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val MAX_REDIRECTS = 5
private const val CONNECT_TIMEOUT_MS = 15_000
private const val PROBE_TIMEOUT_MS = 12_000
private const val READ_TIMEOUT_MS = 60_000

private val BASE_CANDIDATES = listOf("https://huggingface.co", "https://hf-mirror.com")

/** Common local HTTP proxy ports for Clash/v2ray-style setups. */
private val LOCAL_PROXY_PORTS = listOf(7890, 7897, 7891, 2080)

actual class NetworkTransport {
    private val proxy by lazy { detectLocalProxy() }

    actual fun selectBase(probePath: String): String? {
        // Resolve the proxy up front; its detection request already proves that
        // huggingface.co is reachable through it.
        val proxy = this.proxy
        if (proxy != null) {
            return "https://huggingface.co"
        }
        val winner = AtomicReference<String?>(null)
        val decided = CountDownLatch(1)
        val probes = BASE_CANDIDATES.map { base ->
            thread(isDaemon = true, name = "probe-$base") {
                try {
                    openConnection(
                        base + probePath,
                        "GET",
                        connectTimeout = PROBE_TIMEOUT_MS,
                        // Any HTTP answer (including a redirect) proves the base is
                        // reachable; following redirects would cost extra handshakes.
                        followRedirects = false,
                    )?.let { connection ->
                        try {
                            if (connection.responseCode in 200..399) {
                                winner.compareAndSet(null, base)
                                decided.countDown()
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                } catch (_: Exception) {
                    // Try the next base instead.
                }
            }
        }
        decided.await(PROBE_TIMEOUT_MS / 1000L + 3, TimeUnit.SECONDS)
        return winner.get()
    }

    actual fun contentLength(url: String): Long? {
        // GET with a one-byte Range instead of HEAD: mirrors commonly reject or
        // redirect HEAD requests while still serving ranged GETs.
        val connection = openConnection(
            url,
            "GET",
            connectTimeout = CONNECT_TIMEOUT_MS,
            rangeOffset = 0,
            probeByte = true,
            proxy = proxy,
        ) ?: return null
        try {
            val status = connection.responseCode
            if (status !in 200..299 && status != 206) {
                return null
            }
            connection.contentRangeTotal?.let { return it }
            return connection.contentLengthLong.takeIf { it >= 0 }
        } finally {
            connection.disconnect()
        }
    }

    actual suspend fun downloadFile(url: String, destination: String, onDelta: (Long) -> Unit) {
        val target = File(destination)
        val partial = File("$destination.part")
        target.parentFile?.mkdirs()

        val resumed = partial.exists()
        val offset = if (resumed) partial.length() else 0L
        val connection = openConnection(
            url,
            "GET",
            connectTimeout = CONNECT_TIMEOUT_MS,
            rangeOffset = offset,
            proxy = proxy,
        ) ?: throw IOException("failed to open $url")
        try {
            val status = connection.responseCode
            if (status == 416 && resumed) {
                // The partial file already covers the whole range; finalize it and let
                // the caller's size check confirm the result.
                finalize(partial, target)
                return
            }
            if (status !in 200..299 && status != 206) {
                throw IOException("HTTP $status for $url")
            }
            val append = status == 206 && resumed
            if (!append && resumed) {
                partial.delete()
            }
            connection.inputStream.use { input ->
                FileOutputStream(partial, append).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) {
                            break
                        }
                        output.write(buffer, 0, read)
                        onDelta(read.toLong())
                    }
                    output.fd.sync()
                }
            }
            finalize(partial, target)
        } finally {
            connection.disconnect()
        }
    }

    actual fun fileSize(path: String): Long? {
        val file = File(path)
        return if (file.isFile) file.length() else null
    }

    actual fun deleteFile(path: String) {
        File(path).delete()
        File("$path.part").delete()
    }

    actual fun readTextFile(path: String): String = File(path).readText()

    actual fun usableSpaceBytes(path: String): Long {
        val directory = File(path)
        return directory.getUsableSpace()
    }

    private fun finalize(partial: File, target: File) {
        if (partial.renameTo(target)) {
            return
        }
        target.delete()
        if (!partial.renameTo(target)) {
            throw IOException("failed to finalize ${target.path}")
        }
    }

    /**
     * Detects a usable local proxy (Clash/v2ray-style mixed port) that can reach
     * huggingface.co. SOCKS is tried first because HTTP CONNECT is unreliable
     * against some proxy cores; returns null when no local proxy works.
     */
    private fun detectLocalProxy(): Proxy? {
        for (port in LOCAL_PROXY_PORTS) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), PROXY_CONNECT_MS)
                }
            } catch (_: Exception) {
                continue
            }
            for (type in listOf(Proxy.Type.SOCKS, Proxy.Type.HTTP)) {
                val candidate = Proxy(type, InetSocketAddress("127.0.0.1", port))
                try {
                    val probe = URL("https://huggingface.co/").openConnection(candidate) as HttpURLConnection
                    probe.connectTimeout = PROBE_TIMEOUT_MS
                    probe.readTimeout = PROBE_TIMEOUT_MS
                    probe.requestMethod = "GET"
                    try {
                        if (probe.responseCode in 200..499) {
                            return candidate
                        }
                    } finally {
                        probe.disconnect()
                    }
                } catch (_: Exception) {
                    // This proxy type is unusable; try the next one.
                }
            }
        }
        return null
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val PROXY_CONNECT_MS = 300
    }
}

private val HttpURLConnection.contentRangeTotal: Long?
    get() {
        val header = getHeaderField("Content-Range") ?: return null
        val total = header.substringAfterLast('/', missingDelimiterValue = "")
        return total.toLongOrNull()
    }

/** Opens [url] following redirects manually; HuggingFace resolve links redirect to a CDN. */
private fun openConnection(
    url: String,
    method: String,
    connectTimeout: Int,
    rangeOffset: Long = 0,
    probeByte: Boolean = false,
    followRedirects: Boolean = true,
    proxy: Proxy? = null,
): HttpURLConnection? {
    var current = url
    repeat(MAX_REDIRECTS) {
        val connection = (
            if (proxy != null) {
                URL(current).openConnection(proxy)
            } else {
                URL(current).openConnection()
            }
        ) as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeout
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        if (rangeOffset > 0) {
            connection.setRequestProperty("Range", "bytes=$rangeOffset-")
        } else if (probeByte) {
            connection.setRequestProperty("Range", "bytes=0-0")
        }
        val status = connection.responseCode
        if (followRedirects && status in 300..399) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                return null
            }
            current = URL(URL(current), location).toExternalForm()
            return@repeat
        }
        return connection
    }
    return null
}
