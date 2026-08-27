package top.yurin.voxcpm2.example

import android.content.Context
import java.io.File

private var appContext: Context? = null

/** Captures the application context used for platform file locations. */
fun installApplicationContext(context: Context) {
    appContext = context.applicationContext
}

actual fun defaultOutputDirectory(): String {
    val context = appContext ?: error("application context is not installed")
    return context.getExternalFilesDir(null)?.absolutePath ?: context.filesDir.absolutePath
}

actual fun defaultModelDirectory(): String {
    val context = appContext ?: error("application context is not installed")
    return File(context.filesDir, "models/voxcpm2").absolutePath
}

actual fun writeBytes(path: String, bytes: ByteArray) {
    File(path).writeBytes(bytes)
}
