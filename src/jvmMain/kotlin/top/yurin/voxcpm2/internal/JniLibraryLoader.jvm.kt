package top.yurin.voxcpm2.internal

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private object JvmJniLibraryState {
    var loaded: Boolean = false
}

internal actual fun loadJniLibraries() {
    synchronized(JvmJniLibraryState) {
        if (JvmJniLibraryState.loaded) return
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        require(os.startsWith("linux") && arch in setOf("amd64", "x86_64")) {
            "VoxCPM2 0.1.0 JVM runtime supports Linux x86_64 only; found $os/$arch"
        }

        val resources = listOf("libomp.so", "libvoxcpm2_ncnn.so", "libvoxcpm2_jni.so")
        val binaries = resources.associateWith { name ->
            JvmJniLibraryState::class.java.getResourceAsStream("/native/linux-x86_64/$name")
                ?.use { it.readBytes() }
                ?: error("missing VoxCPM2 native resource: $name")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        binaries.values.forEach(digest::update)
        val fingerprint = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }.take(24)
        val directory =
            Files.createDirectories(
                java.nio.file.Path.of(System.getProperty("user.home"))
                    .resolve(".cache/voxcpm2k/0.1.0/$fingerprint"),
            )

        val extracted = resources.map { name ->
            val destination = directory.resolve(name)
            if (!Files.exists(destination)) {
                val temporary = Files.createTempFile(directory, "$name.", ".tmp")
                Files.write(temporary, binaries.getValue(name))
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.FileAlreadyExistsException) {
                    Files.deleteIfExists(temporary)
                }
            }
            destination
        }

        extracted.forEach { library -> System.load(library.toAbsolutePath().toString()) }
        JvmJniLibraryState.loaded = true
    }
}
