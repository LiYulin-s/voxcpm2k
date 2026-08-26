package top.yurin.voxcpm2.gradle

import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.KonanTarget

/** Links published VoxCPM2 shared-library sidecars into Kotlin/Native binaries. */
public class VoxCPM2NativeRuntimePlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = with(project) {
        val extension = extensions.create(
            "voxcpm2NativeRuntime",
            VoxCPM2NativeRuntimeExtension::class.java,
        ).apply {
            version.convention(
                VoxCPM2NativeRuntimePlugin::class.java.`package`.implementationVersion
                    ?: FALLBACK_VERSION,
            )
            coordinatesGroup.convention("top.yurin")
            extractionDirectory.convention(layout.buildDirectory.dir("voxcpm2/native"))
        }

        val linuxRuntime = runtimeConfiguration(
            name = "voxcpm2LinuxX64Runtime",
            extension = extension,
            artifactId = "voxcpm2-native-linuxx64",
        )
        val iosRuntime = runtimeConfiguration(
            name = "voxcpm2IosRuntime",
            extension = extension,
            artifactId = "voxcpm2-native-ios",
        )

        val extractLinux = tasks.register("extractVoxCPM2LinuxX64Runtime", Sync::class.java) { task ->
            task.group = "voxcpm2"
            task.description = "Resolves and extracts the VoxCPM2 Linux x64 native sidecar"
            task.from(provider { zipTree(linuxRuntime.singleFile) })
            task.into(extension.extractionDirectory.dir("linuxX64"))
        }
        val extractIos = tasks.register("extractVoxCPM2IosRuntime", Sync::class.java) { task ->
            task.group = "voxcpm2"
            task.description = "Resolves and extracts the VoxCPM2 iOS native sidecar"
            task.from(provider { zipTree(iosRuntime.singleFile) })
            task.into(extension.extractionDirectory.dir("ios"))
        }

        tasks.register(
            "embedAndSignVoxCPM2ForXcode",
            EmbedAndSignVoxCPM2ForXcode::class.java,
        ) { task ->
            task.group = "voxcpm2"
            task.description = "Embeds and signs the VoxCPM2 dylib in the active Xcode app bundle"
            task.dependsOn(extractIos)
            task.runtimeDirectory.set(extension.extractionDirectory.dir("ios"))
            task.platformName.convention(providers.environmentVariable("PLATFORM_NAME").orElse("iphonesimulator"))
            task.architectures.convention(providers.environmentVariable("ARCHS").orElse("arm64"))
            task.codeSignIdentity.convention(providers.environmentVariable("EXPANDED_CODE_SIGN_IDENTITY").orElse(""))
            task.embeddedLibrary.convention(
                layout.file(
                    providers.environmentVariable("TARGET_BUILD_DIR")
                        .zip(providers.environmentVariable("FRAMEWORKS_FOLDER_PATH")) { root, folder ->
                            File(File(root, folder), "libvoxcpm2_ncnn.dylib")
                        }
                        .orElse(
                            layout.buildDirectory.file(
                                "voxcpm2/xcode/Frameworks/libvoxcpm2_ncnn.dylib",
                            ).map { it.asFile },
                        ),
                ),
            )
        }

        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            extensions.getByType(KotlinMultiplatformExtension::class.java)
                .targets
                .withType(KotlinNativeTarget::class.java)
                .configureEach { target ->
                    when (target.konanTarget) {
                        KonanTarget.LINUX_X64 -> configureLinuxTarget(target, extension, extractLinux)
                        KonanTarget.IOS_ARM64 -> configureIosTarget(
                            target,
                            extension,
                            extractIos,
                            "ios-arm64",
                        )
                        KonanTarget.IOS_SIMULATOR_ARM64 -> configureIosTarget(
                            target,
                            extension,
                            extractIos,
                            "ios-arm64-simulator",
                        )
                        else -> Unit
                    }
                }
        }
    }

    private fun Project.runtimeConfiguration(
        name: String,
        extension: VoxCPM2NativeRuntimeExtension,
        artifactId: String,
    ): Configuration = configurations.create(name) { configuration ->
        configuration.isCanBeConsumed = false
        configuration.isCanBeResolved = true
        configuration.isTransitive = false
        configuration.description = "VoxCPM2 $artifactId shared runtime sidecar"
        configuration.defaultDependencies { dependencySet ->
            dependencySet.add(
                dependencies.create(
                    "${extension.coordinatesGroup.get()}:$artifactId:${extension.version.get()}@zip",
                ),
            )
        }
    }

    private fun Project.configureLinuxTarget(
        target: KotlinNativeTarget,
        extension: VoxCPM2NativeRuntimeExtension,
        extractRuntime: org.gradle.api.tasks.TaskProvider<Sync>,
    ): Unit {
        val libraryDirectory = extension.extractionDirectory.dir("linuxX64/lib")
        target.binaries.configureEach { binary ->
            binary.linkerOpts(
                "-L${libraryDirectory.get().asFile.absolutePath}",
                "-lvoxcpm2_ncnn",
                "-Wl,-rpath,\$ORIGIN",
            )
            val prepareRuntime = tasks.register(
                "prepareVoxCPM2RuntimeFor${binary.linkTaskName.capitalized()}",
                Copy::class.java,
            ) { copy ->
                copy.dependsOn(extractRuntime)
                copy.from(libraryDirectory)
                copy.include("*.so")
                copy.into(binary.outputDirectoryProperty)
            }
            binary.linkTaskProvider.configure { link -> link.dependsOn(prepareRuntime) }
        }
    }

    private fun Project.configureIosTarget(
        target: KotlinNativeTarget,
        extension: VoxCPM2NativeRuntimeExtension,
        extractRuntime: org.gradle.api.tasks.TaskProvider<Sync>,
        slice: String,
    ): Unit {
        val libraryDirectory = extension.extractionDirectory.dir(
            "ios/VoxCPM2Native.xcframework/$slice",
        )
        target.binaries.configureEach { binary ->
            binary.linkerOpts(
                "-L${libraryDirectory.get().asFile.absolutePath}",
                "-lvoxcpm2_ncnn",
                "-Wl,-rpath,@executable_path/Frameworks",
                "-Wl,-rpath,@loader_path/Frameworks",
            )
            binary.linkTaskProvider.configure { link -> link.dependsOn(extractRuntime) }
        }
    }

    private fun String.capitalized(): String = replaceFirstChar { character ->
        if (character.isLowerCase()) character.titlecase() else character.toString()
    }

    private companion object {
        const val FALLBACK_VERSION: String = "0.1.0"
    }
}
