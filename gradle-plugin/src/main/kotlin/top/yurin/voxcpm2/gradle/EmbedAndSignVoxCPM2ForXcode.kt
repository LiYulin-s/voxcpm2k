package top.yurin.voxcpm2.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/** Embeds the appropriate VoxCPM2 dylib in an Xcode app bundle and signs it. */
@DisableCachingByDefault(because = "The output is an Xcode-owned app bundle and may be signed in place")
public abstract class EmbedAndSignVoxCPM2ForXcode @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val runtimeDirectory: DirectoryProperty

    @get:Input
    public abstract val platformName: Property<String>

    @get:Input
    public abstract val architectures: Property<String>

    @get:Input
    @get:Optional
    public abstract val codeSignIdentity: Property<String>

    @get:OutputFile
    public abstract val embeddedLibrary: RegularFileProperty

    @TaskAction
    public fun embedAndSign(): Unit {
        val requestedArchitectures = architectures.get()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .toSet()
        require(requestedArchitectures == setOf("arm64")) {
            "VoxCPM2 supports arm64 iOS binaries only; ARCHS=${architectures.get()}"
        }
        val slice = when (platformName.get()) {
            "iphoneos" -> "ios-arm64"
            "iphonesimulator" -> "ios-arm64-simulator"
            else -> error("unsupported Apple platform: ${platformName.get()}")
        }
        val library = runtimeDirectory.file(
            "VoxCPM2Native.xcframework/$slice/libvoxcpm2_ncnn.dylib",
        ).get().asFile
        check(library.isFile) { "VoxCPM2 runtime slice is missing: $library" }

        val destination = embeddedLibrary.get().asFile
        fileSystemOperations.copy { copy ->
            copy.from(library)
            copy.into(destination.parentFile)
            copy.rename { destination.name }
        }

        val identity = codeSignIdentity.orNull.orEmpty()
        if (identity.isNotBlank() && identity != "-") {
            execOperations.exec { invocation ->
                invocation.commandLine("codesign", "--force", "--sign", identity, "--timestamp=none", destination)
            }
        }
    }
}
