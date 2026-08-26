package top.yurin.voxcpm2.gradle

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testkit.runner.GradleRunner
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class VoxCPM2NativeRuntimePluginTest {
    @Test
    fun resolvesAndExtractsConfiguredLinuxSidecar(): Unit {
        if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) return
        val projectDirectory = Files.createTempDirectory("voxcpm2-native-plugin-test")
        writeFakeRuntimeRepository(projectDirectory.resolve("repo"))
        projectDirectory.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }
            rootProject.name = "consumer"
            """.trimIndent(),
        )
        projectDirectory.resolve("build.gradle.kts").writeText(
            """
            plugins {
                kotlin("multiplatform") version "2.3.10"
                id("top.yurin.voxcpm2.native-runtime")
            }

            repositories {
                maven { url = uri("repo") }
                mavenCentral()
            }

            voxcpm2NativeRuntime {
                version.set("9.8.7")
            }

            kotlin {
                linuxX64 {
                    binaries.executable()
                }
            }

            """.trimIndent(),
        )
        projectDirectory.resolve("src/linuxMain/kotlin").createDirectories()
        projectDirectory.resolve("src/linuxMain/kotlin/main.kt").writeText(
            "fun main() = println(\"VoxCPM2 plugin smoke\")",
        )

        val result = GradleRunner.create()
            .withProjectDir(projectDirectory.toFile())
            .withPluginClasspath()
            .withArguments(
                "linkDebugExecutableLinuxX64",
                "embedAndSignVoxCPM2ForXcode",
                "--stacktrace",
                "--configuration-cache",
            )
            .build()

        assertTrue(result.output.contains("BUILD SUCCESSFUL"))
        assertTrue(
            Files.isRegularFile(
                projectDirectory.resolve("build/voxcpm2/native/linuxX64/lib/libvoxcpm2_ncnn.so"),
            ),
        )
        assertTrue(
            Files.isRegularFile(
                projectDirectory.resolve("build/bin/linuxX64/debugExecutable/libvoxcpm2_ncnn.so"),
            ),
        )
        assertTrue(
            Files.isRegularFile(
                projectDirectory.resolve("build/voxcpm2/xcode/Frameworks/libvoxcpm2_ncnn.dylib"),
            ),
        )
    }

    private fun writeFakeRuntimeRepository(repository: Path): Unit {
        repository.createDirectories()
        val sharedLibrary = buildFakeSharedLibrary(repository)
        val artifactDirectory = repository.resolve("top/yurin/voxcpm2-native-linuxx64/9.8.7")
        artifactDirectory.createDirectories()
        artifactDirectory.resolve("voxcpm2-native-linuxx64-9.8.7.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>top.yurin</groupId>
              <artifactId>voxcpm2-native-linuxx64</artifactId>
              <version>9.8.7</version>
              <packaging>zip</packaging>
            </project>
            """.trimIndent(),
        )
        ZipOutputStream(
            Files.newOutputStream(artifactDirectory.resolve("voxcpm2-native-linuxx64-9.8.7.zip")),
        ).use { zip ->
            zip.putNextEntry(ZipEntry("lib/libvoxcpm2_ncnn.so"))
            zip.write(sharedLibrary)
            zip.closeEntry()
        }

        val iosArtifactDirectory = repository.resolve("top/yurin/voxcpm2-native-ios/9.8.7")
        iosArtifactDirectory.createDirectories()
        iosArtifactDirectory.resolve("voxcpm2-native-ios-9.8.7.pom").writeText(
            """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>top.yurin</groupId>
              <artifactId>voxcpm2-native-ios</artifactId>
              <version>9.8.7</version>
              <packaging>zip</packaging>
            </project>
            """.trimIndent(),
        )
        ZipOutputStream(
            Files.newOutputStream(iosArtifactDirectory.resolve("voxcpm2-native-ios-9.8.7.zip")),
        ).use { zip ->
            listOf("ios-arm64", "ios-arm64-simulator").forEach { slice ->
                zip.putNextEntry(
                    ZipEntry("VoxCPM2Native.xcframework/$slice/libvoxcpm2_ncnn.dylib"),
                )
                zip.write(sharedLibrary)
                zip.closeEntry()
            }
        }
    }

    private fun buildFakeSharedLibrary(directory: Path): ByteArray {
        val source = directory.resolve("fake.c")
        val library = directory.resolve("libvoxcpm2_ncnn.so")
        source.writeText("void voxcpm2_plugin_test_symbol(void) {}")
        val process = ProcessBuilder(
            "cc",
            "-shared",
            "-fPIC",
            source.toString(),
            "-o",
            library.toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "failed to build test shared library: $output" }
        return Files.readAllBytes(library)
    }
}
