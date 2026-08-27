import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.gradlePluginPublish) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}

allprojects {
    group = "top.yurin"
    version = "0.1.0"
}

@DisableCachingByDefault(because = "xmake maintains its own package and incremental build state")
abstract class XmakeBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val xmakeExecutable: Property<String>

    @get:Input
    abstract val platform: Property<String>

    @get:Input
    abstract val architecture: Property<String>

    @get:Input
    abstract val mode: Property<String>

    @get:Input
    abstract val profile: Property<Boolean>

    @get:Input
    abstract val target: Property<String>

    @get:Input
    abstract val configureArguments: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val nativeSources: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val toolchainMetadata: ConfigurableFileCollection

    @get:OutputFiles
    abstract val outputFiles: ConfigurableFileCollection

    @get:Internal
    abstract val nativeProjectDirectory: DirectoryProperty

    @get:Internal
    abstract val buildDirectory: DirectoryProperty

    @get:Internal
    abstract val configDirectory: DirectoryProperty

    @TaskAction
    fun buildNativeRuntime(): Unit {
        val nativeProject = nativeProjectDirectory.get().asFile
        val nativeBuild = buildDirectory.get().asFile
        val nativeConfig = configDirectory.get().asFile
        nativeBuild.mkdirs()
        nativeConfig.mkdirs()

        execOperations.exec {
            workingDir(nativeProject)
            environment("XMAKE_CONFIGDIR", nativeConfig.absolutePath)
            commandLine(
                buildList {
                    add(xmakeExecutable.get())
                    addAll(
                        listOf(
                            "f",
                            "-c",
                            "-y",
                            "-p",
                            platform.get(),
                            "-a",
                            architecture.get(),
                            "-m",
                            mode.get(),
                            "--builddir=${nativeBuild.absolutePath}",
                            "--profile=${profile.get()}",
                        ),
                    )
                    addAll(configureArguments.get())
                },
            )
        }
        execOperations.exec {
            workingDir(nativeProject)
            environment("XMAKE_CONFIGDIR", nativeConfig.absolutePath)
            commandLine(xmakeExecutable.get(), "build", target.get())
        }

        val missingOutputs = outputFiles.files.filterNot(File::isFile)
        check(missingOutputs.isEmpty()) {
            "xmake target ${target.get()} did not produce: ${missingOutputs.joinToString()}"
        }
    }
}

@CacheableTask
abstract class PrepareAndroidJniLibsTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraries: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare(): Unit {
        fileSystemOperations.sync {
            from(libraries)
            into(outputDirectory.dir("arm64-v8a"))
        }
    }
}

@CacheableTask
abstract class PrepareLinuxRuntimeTask @Inject constructor(
    private val fileSystemOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageDirectory: DirectoryProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare(): Unit {
        val packageRoot = packageDirectory.get().asFile
        val source = packageRoot.walkTopDown()
            .filter { it.isFile && it.name == "libomp.so" && it.parentFile.name == "lib" }
            .filter { library ->
                val manifest = library.parentFile.parentFile.resolve("manifest.txt")
                manifest.isFile && manifest.readText().let { metadata ->
                    "plat = \"linux\"" in metadata && "arch = \"x86_64\"" in metadata
                }
            }
            .maxByOrNull(File::lastModified)
            ?: error("xmake did not install a Linux x86_64 libomp.so below $packageRoot")
        val destination = outputFile.get().asFile
        fileSystemOperations.copy {
            from(source)
            into(destination.parentFile)
            rename { destination.name }
        }
    }
}

val nativeProjectDir = layout.projectDirectory.dir("native/voxcpm2-ncnn")
val nativeHeaderDir = nativeProjectDir.dir("include")
val xmakeRoot = layout.buildDirectory.dir("xmake")
val generatedResources = layout.buildDirectory.dir("generated/resources")
val generatedLicenseResources = layout.buildDirectory.dir("generated/licenseResources")
val isLinuxHost = System.getProperty("os.name").startsWith("Linux", ignoreCase = true)
val isMacHost = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
val signingConfigured = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.secretKeyRingFile").isPresent

val nativeSourceFiles = fileTree(nativeProjectDir) {
    include("xmake.lua", "include/**", "src/**")
}

fun registerXmakeBuild(
    taskName: String,
    variant: String,
    platformName: String,
    architectureName: String,
    targetName: String,
    hostEnabled: Boolean = true,
    configure: XmakeBuildTask.() -> Unit,
) = tasks.register<XmakeBuildTask>(taskName) {
    group = "xmake"
    description = "Build $targetName with xmake for $variant"
    xmakeExecutable.convention("xmake")
    platform.set(platformName)
    architecture.set(architectureName)
    mode.set("release")
    profile.set(true)
    target.set(targetName)
    configureArguments.convention(emptyList())
    nativeProjectDirectory.set(nativeProjectDir)
    buildDirectory.set(xmakeRoot.map { it.dir(variant) })
    configDirectory.set(xmakeRoot.map { it.dir("config/$variant") })
    nativeSources.from(nativeSourceFiles)
    enabled = hostEnabled
    configure()
}

val linuxSharedDirectory = xmakeRoot.map { it.dir("linuxX64/linux/x86_64/release/shared") }
val androidSharedDirectory = xmakeRoot.map { it.dir("androidArm64/android/arm64-v8a/release/shared") }
val iosDeviceSharedDirectory = xmakeRoot.map { it.dir("iosArm64/iphoneos/arm64/release/shared") }
val iosSimulatorSharedDirectory = xmakeRoot.map {
    it.dir("iosSimulatorArm64/iphoneos/arm64/release/shared")
}

val javaHome = providers.systemProperty("java.home")
val linuxConfigureArguments = javaHome.zip(
    providers.gradleProperty("voxcpm2.xmake.toolchain").orElse(""),
) { home, toolchain ->
    buildList {
        add("--jdk=$home")
        if (toolchain.isNotBlank()) add("--toolchain=$toolchain")
    }
}
val xmakeBuildLinuxX64Jni = registerXmakeBuild(
    taskName = "xmakeBuildLinuxX64Jni",
    variant = "linuxX64",
    platformName = "linux",
    architectureName = "x86_64",
    targetName = "voxcpm2_jni",
    hostEnabled = isLinuxHost,
) {
    configureArguments.set(linuxConfigureArguments)
    toolchainMetadata.from(javaHome.map { File(it, "release") })
    outputFiles.from(
        linuxSharedDirectory.map { it.file("libvoxcpm2_ncnn.so") },
        linuxSharedDirectory.map { it.file("libvoxcpm2_jni.so") },
    )
}

val androidComponents = extensions.getByType(KotlinMultiplatformAndroidComponentsExtension::class.java)
val androidNdkDirectory = androidComponents.sdkComponents.ndkDirectory
val androidConfigureArguments = androidNdkDirectory.map { ndk ->
    listOf(
        "--ndk=${ndk.asFile.absolutePath}",
        "--ndk_sdkver=${libs.versions.android.minSdk.get()}",
        "--runtimes=c++_shared",
    )
}
val xmakeBuildAndroidArm64Jni = registerXmakeBuild(
    taskName = "xmakeBuildAndroidArm64Jni",
    variant = "androidArm64",
    platformName = "android",
    architectureName = "arm64-v8a",
    targetName = "voxcpm2_jni",
) {
    configureArguments.set(androidConfigureArguments)
    toolchainMetadata.from(androidNdkDirectory.map { it.file("source.properties") })
    outputFiles.from(
        androidSharedDirectory.map { it.file("libvoxcpm2_ncnn.so") },
        androidSharedDirectory.map { it.file("libvoxcpm2_jni.so") },
    )
}
val androidCxxSharedLibrary = layout.file(androidNdkDirectory.map { ndk ->
    val prebuiltDirectory = ndk.asFile.resolve("toolchains/llvm/prebuilt")
    val candidates = prebuiltDirectory.listFiles()
        .orEmpty()
        .filter(File::isDirectory)
        .map {
            it.resolve("sysroot/usr/lib/aarch64-linux-android/libc++_shared.so")
        }
        .filter(File::isFile)
    check(candidates.size == 1) {
        "expected one arm64 libc++_shared.so below $prebuiltDirectory, found ${candidates.size}"
    }
    candidates.single()
})
val prepareAndroidArm64JniLibs = tasks.register<PrepareAndroidJniLibsTask>(
    "prepareAndroidArm64JniLibs",
) {
    group = "xmake"
    description = "Stage the arm64 VoxCPM2 JNI runtime for the Android AAR"
    dependsOn(xmakeBuildAndroidArm64Jni)
    libraries.from(
        androidSharedDirectory.map { it.file("libvoxcpm2_ncnn.so") },
        androidSharedDirectory.map { it.file("libvoxcpm2_jni.so") },
        androidCxxSharedLibrary,
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/androidJniLibs"))
}
androidComponents.onVariants { variant ->
    checkNotNull(variant.sources.jniLibs) {
        "Android variant ${variant.name} does not expose generated JNI library sources"
    }.addGeneratedSourceDirectory(prepareAndroidArm64JniLibs) { task ->
        task.outputDirectory
    }
}

val xmakeBuildIosArm64Shared = registerXmakeBuild(
    taskName = "xmakeBuildIosArm64Shared",
    variant = "iosArm64",
    platformName = "iphoneos",
    architectureName = "arm64",
    targetName = "voxcpm2_ncnn_shared",
    hostEnabled = isMacHost,
) {
    configureArguments.set(listOf("--appledev=iphone"))
    outputFiles.from(iosDeviceSharedDirectory.map { it.file("libvoxcpm2_ncnn.dylib") })
}
val xmakeBuildIosSimulatorArm64Shared = registerXmakeBuild(
    taskName = "xmakeBuildIosSimulatorArm64Shared",
    variant = "iosSimulatorArm64",
    platformName = "iphoneos",
    architectureName = "arm64",
    targetName = "voxcpm2_ncnn_shared",
    hostEnabled = isMacHost,
) {
    configureArguments.set(listOf("--appledev=simulator"))
    outputFiles.from(iosSimulatorSharedDirectory.map { it.file("libvoxcpm2_ncnn.dylib") })
}

val prepareLinuxX64Runtime = tasks.register<PrepareLinuxRuntimeTask>("prepareLinuxX64Runtime") {
    group = "xmake"
    description = "Place libomp beside the Linux VoxCPM2 shared runtime"
    dependsOn(xmakeBuildLinuxX64Jni)
    enabled = isLinuxHost
    packageDirectory.set(File(System.getProperty("user.home"), ".xmake/packages/l/libomp"))
    outputFile.set(linuxSharedDirectory.map { it.file("libomp.so") })
}
val prepareJvmNativeResources = tasks.register<Sync>("prepareJvmNativeResources") {
    group = "xmake"
    description = "Stage the Linux x64 JNI runtime for the JVM JAR"
    dependsOn(prepareLinuxX64Runtime)
    enabled = isLinuxHost
    from(linuxSharedDirectory.map { it.file("libvoxcpm2_ncnn.so") })
    from(linuxSharedDirectory.map { it.file("libvoxcpm2_jni.so") })
    from(prepareLinuxX64Runtime.flatMap { it.outputFile })
    into(generatedResources.map { it.dir("jvmMain/native/linux-x86_64") })
}

val linuxX64RuntimeZip = tasks.register<Zip>("linuxX64RuntimeZip") {
    group = "distribution"
    description = "Package the Linux x64 Kotlin/Native shared runtime"
    dependsOn(prepareLinuxX64Runtime)
    enabled = isLinuxHost
    archiveBaseName.set("voxcpm2-native-linuxx64")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(linuxSharedDirectory.map { it.file("libvoxcpm2_ncnn.so") }) { into("lib") }
    from(prepareLinuxX64Runtime.flatMap { it.outputFile }) { into("lib") }
    from(nativeHeaderDir.file("voxcpm2/c_api.h")) { into("include/voxcpm2") }
    from(
        listOf(
            nativeProjectDir.file("LICENSE"),
            nativeProjectDir.file("NOTICE"),
            nativeProjectDir.file("CREDITS.md"),
        ),
    ) {
        into("licenses")
    }
    from(nativeProjectDir.dir("LICENSES")) { into("licenses/LICENSES") }
}

val createIosXcframework = tasks.register<Exec>("createIosXcframework") {
    val output = layout.buildDirectory.dir("xcframework/VoxCPM2Native.xcframework")
    group = "distribution"
    description = "Create the VoxCPM2 device and simulator shared XCFramework"
    dependsOn(xmakeBuildIosArm64Shared, xmakeBuildIosSimulatorArm64Shared)
    enabled = isMacHost
    inputs.files(
        iosDeviceSharedDirectory.map { it.file("libvoxcpm2_ncnn.dylib") },
        iosSimulatorSharedDirectory.map { it.file("libvoxcpm2_ncnn.dylib") },
    )
    inputs.dir(nativeHeaderDir)
    outputs.dir(output)
    doFirst {
        output.get().asFile.deleteRecursively()
        commandLine(
            "xcodebuild",
            "-create-xcframework",
            "-library",
            iosDeviceSharedDirectory.get().file("libvoxcpm2_ncnn.dylib").asFile.absolutePath,
            "-headers",
            nativeHeaderDir.asFile.absolutePath,
            "-library",
            iosSimulatorSharedDirectory.get().file("libvoxcpm2_ncnn.dylib").asFile.absolutePath,
            "-headers",
            nativeHeaderDir.asFile.absolutePath,
            "-output",
            output.get().asFile.absolutePath,
        )
    }
}
val iosRuntimeZip = tasks.register<Zip>("iosRuntimeZip") {
    group = "distribution"
    description = "Package the iOS Kotlin/Native shared runtime"
    dependsOn(createIosXcframework)
    enabled = isMacHost
    archiveBaseName.set("voxcpm2-native-ios")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from(layout.buildDirectory.dir("xcframework/VoxCPM2Native.xcframework")) {
        into("VoxCPM2Native.xcframework")
    }
    from(
        listOf(
            nativeProjectDir.file("LICENSE"),
            nativeProjectDir.file("NOTICE"),
            nativeProjectDir.file("CREDITS.md"),
        ),
    ) {
        into("licenses")
    }
    from(nativeProjectDir.dir("LICENSES")) { into("licenses/LICENSES") }
}

val prepareLicenseResources = tasks.register<Sync>("prepareLicenseResources") {
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF/licenses/voxcpm2k")
        rename { "LICENSE-voxcpm2k.txt" }
    }
    from(
        listOf(
            nativeProjectDir.file("LICENSE"),
            nativeProjectDir.file("NOTICE"),
            nativeProjectDir.file("CREDITS.md"),
        ),
    ) {
        into("META-INF/licenses/voxcpm2k/native")
    }
    from(nativeProjectDir.dir("LICENSES")) {
        into("META-INF/licenses/voxcpm2k/native/LICENSES")
    }
    into(generatedLicenseResources)
}

kotlin {
    explicitApi()
    applyDefaultHierarchyTemplate()
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
    androidLibrary {
        namespace = "top.yurin.voxcpm2"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        optimization {
            consumerKeepRules.file("consumer-rules.pro")
        }
        withJava()
        withHostTestBuilder {}.configure {}
        withDeviceTestBuilder { sourceSetTreeName = "test" }
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
    iosArm64()
    iosSimulatorArm64()
    linuxX64()

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("voxcpm2") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/voxcpm2.def"))
            includeDirs(nativeHeaderDir.asFile)
        }
    }

    targets.named("linuxX64", KotlinNativeTarget::class).configure {
        binaries.all {
            linkerOpts(
                "-L${linuxSharedDirectory.get().asFile.absolutePath}",
                "-lvoxcpm2_ncnn",
                "-Wl,-rpath,${linuxSharedDirectory.get().asFile.absolutePath}",
            )
            linkTaskProvider.configure { dependsOn(prepareLinuxX64Runtime) }
        }
    }
    targets.named("iosArm64", KotlinNativeTarget::class).configure {
        binaries.all {
            linkerOpts(
                "-L${iosDeviceSharedDirectory.get().asFile.absolutePath}",
                "-lvoxcpm2_ncnn",
                "-Wl,-rpath,${iosDeviceSharedDirectory.get().asFile.absolutePath}",
            )
            linkTaskProvider.configure { dependsOn(xmakeBuildIosArm64Shared) }
        }
    }
    targets.named("iosSimulatorArm64", KotlinNativeTarget::class).configure {
        binaries.all {
            linkerOpts(
                "-L${iosSimulatorSharedDirectory.get().asFile.absolutePath}",
                "-lvoxcpm2_ncnn",
                "-Wl,-rpath,${iosSimulatorSharedDirectory.get().asFile.absolutePath}",
            )
            linkTaskProvider.configure { dependsOn(xmakeBuildIosSimulatorArm64Shared) }
        }
    }

    sourceSets {
        val commonMain by getting {
            resources.srcDir(generatedLicenseResources)
            dependencies {
                api(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val jniMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(jniMain)
        }
        val jvmMain by getting {
            dependsOn(jniMain)
            resources.srcDir(generatedResources.map { it.dir("jvmMain") })
        }
    }
}

tasks.named("jvmProcessResources").configure {
    dependsOn(prepareJvmNativeResources, prepareLicenseResources)
}
tasks.configureEach {
    if (name.endsWith("ProcessResources") || name.contains("JavaRes", ignoreCase = true)) {
        dependsOn(prepareLicenseResources)
    }
}

mavenPublishing {
    publishToMavenCentral()
    if (signingConfigured) signAllPublications()
    coordinates("top.yurin", "voxcpm2", project.version.toString())

    pom {
        name = "VoxCPM2 Kotlin Multiplatform"
        description = "Coroutine-first Kotlin Multiplatform binding for the VoxCPM2 ncnn runtime."
        inceptionYear = "2026"
        url = "https://github.com/LiYulin-s/voxcpm2k"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
            license {
                name = "MIT License"
                url = "https://opensource.org/license/mit"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "LiYulin-s"
                name = "Yurin"
                email = "liyulin.china@gmail.com"
            }
        }
        scm {
            url = "https://github.com/LiYulin-s/voxcpm2k"
            connection = "scm:git:https://github.com/LiYulin-s/voxcpm2k.git"
            developerConnection = "scm:git:ssh://git@github.com/LiYulin-s/voxcpm2k.git"
        }
    }
}

fun MavenPom.configureNativeRuntimePom(displayName: String, summary: String): Unit {
    name.set(displayName)
    description.set(summary)
    packaging = "zip"
}

publishing {
    publications {
        create<MavenPublication>("linuxX64Runtime") {
            artifactId = "${project.name}-native-linuxx64"
            artifact(linuxX64RuntimeZip.flatMap { it.archiveFile }) {
                extension = "zip"
                builtBy(linuxX64RuntimeZip)
            }
            pom.configureNativeRuntimePom(
                displayName = "VoxCPM2 Linux x64 Native Runtime",
                summary = "Shared VoxCPM2 ncnn runtime for Kotlin/Native Linux x64 applications.",
            )
        }
        create<MavenPublication>("iosRuntime") {
            artifactId = "${project.name}-native-ios"
            artifact(iosRuntimeZip.flatMap { it.archiveFile }) {
                extension = "zip"
                builtBy(iosRuntimeZip)
            }
            pom.configureNativeRuntimePom(
                displayName = "VoxCPM2 iOS Native Runtime",
                summary = "Shared VoxCPM2 ncnn runtime for Kotlin/Native iOS arm64 applications.",
            )
        }
    }
}
