package top.yurin.voxcpm2.example

/**
 * Minimal blocking transport used by [ModelDownloader]. The JVM implementation is
 * shared by Android and Desktop; future iOS support provides its own actual.
 */
expect class NetworkTransport() {
    /**
     * Probes the candidate download bases in parallel and returns the first one that
     * answers a HEAD request for [probePath], or null when none is reachable.
     */
    fun selectBase(probePath: String): String?

    /** HEAD request content length in bytes, or null when unavailable. */
    fun contentLength(url: String): Long?

    /**
     * Streams [url] into [destination] through a `.part` file, resuming a previous
     * partial download when the server honors Range requests. [onDelta] receives
     * each written chunk so callers can drive progress and cancellation.
     */
    suspend fun downloadFile(url: String, destination: String, onDelta: (bytes: Long) -> Unit)

    /** Size of the file at [path], or null when it does not exist. */
    fun fileSize(path: String): Long?

    fun deleteFile(path: String)

    fun readTextFile(path: String): String

    fun usableSpaceBytes(path: String): Long
}
