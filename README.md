# voxcpm2k

Coroutine-first Kotlin Multiplatform bindings for
[`voxcpm2-ncnn`](https://github.com/LiYulin-s/voxcpm2-ncnn). The public Kotlin
API is shared across Android, JVM, Linux, and iOS; platform code is limited to
loading and calling the native runtime.

| Kotlin target | Native boundary | Shipped runtime |
| --- | --- | --- |
| Android arm64 | JNI | `.so` files embedded in the KMP AAR |
| JVM Linux x64 | JNI | `.so` files embedded in the JVM JAR |
| linuxX64 | C interop | shared-library Maven sidecar |
| iosArm64 / iosSimulatorArm64 | C interop | shared XCFramework sidecar |

The model is deliberately not bundled. Callers provide a real filesystem path
to an exported VoxCPM2 ncnn model. The binding accepts and returns float PCM;
it does not depend on FFmpeg.

## Dependency

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("top.yurin:voxcpm2:0.1.0")
    }
}
```

Android and JVM consumers need no additional configuration. Android supports
`arm64-v8a` at API 26 or newer. The JVM artifact currently supports Linux x64.

Kotlin/Native applications also apply the runtime plugin. It resolves the
matching shared-library ZIP, adds the native linker options, and places Linux
`.so` files next to each executable:

```kotlin
plugins {
    kotlin("multiplatform") version "2.3.10"
    id("top.yurin.voxcpm2.native-runtime") version "0.1.0"
}

voxcpm2NativeRuntime {
    // Defaults to the plugin version and top.yurin.
    version.set("0.1.0")
    coordinatesGroup.set("top.yurin")
}
```

For an iOS application, add an Xcode Run Script phase after the Kotlin
framework is embedded:

```sh
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignVoxCPM2ForXcode
```

The task reads `PLATFORM_NAME`, `ARCHS`, `TARGET_BUILD_DIR`,
`FRAMEWORKS_FOLDER_PATH`, and `EXPANDED_CODE_SIGN_IDENTITY`. It selects the
device or arm64-simulator dylib, copies it into the app's Frameworks directory,
and signs it when Xcode supplies a signing identity. iOS Vulkan execution is
provided by ncnn through MoltenVK.

## Usage

```kotlin
import top.yurin.voxcpm2.ComputeBackend
import top.yurin.voxcpm2.SynthesisRequest
import top.yurin.voxcpm2.VoxCPM2
import top.yurin.voxcpm2.VoxCPM2Config

val synthesizer = VoxCPM2.open(
    VoxCPM2Config(
        modelDirectory = "/absolute/path/to/voxcpm2-model",
        backend = ComputeBackend.VULKAN,
    ),
)

try {
    val audio = synthesizer.synthesize(
        SynthesisRequest(text = "你好，欢迎使用 VoxCPM2。"),
    )
    // audio.samples is owned float PCM at audio.sampleRate.
} finally {
    synthesizer.close()
}
```

Both model loading and synthesis have progress-flow forms:

```kotlin
VoxCPM2.openEvents(config).collect { event ->
    when (event) {
        is InitializationEvent.ProgressEvent -> render(event.progress)
        is InitializationEvent.Ready -> useSynthesizer(event.synthesizer)
    }
}

synthesizer.synthesizeEvents(request).collect { event ->
    when (event) {
        is SynthesisEvent.ProgressEvent -> render(event.progress)
        is SynthesisEvent.Completed -> play(event.audio)
    }
}
```

Progress is conflated so a slow UI does not stall inference. Cancelling the
calling coroutine or flow cancels the native operation token; the C++ runtime
checks that token at model-load, generation, decode, and smoke-test safe points.
Calls on one synthesizer are serialized. `close()` is idempotent, cancels an
active call, waits for native code to leave, and rejects new calls.

Prompt and reference audio must be non-empty mono float PCM at
`synthesizer.inputSampleRate`. Generated audio is mono float PCM at
`synthesizer.outputSampleRate`.

## Example app

A Compose Multiplatform example lives in `example/` (shared UI and state) plus
`example-app/` (the Android entry point). It loads a model directory, streams
load and synthesis progress, cancels through the native operation token, plays
the generated PCM, and exports a 16-bit WAV:

```sh
# Desktop (Linux x64)
./gradlew :example:run

# Android
./gradlew :example-app:assembleDebug
adb install example-app/build/outputs/apk/debug/example-app-debug.apk
```

The model is never bundled. Export it once with the submodule
`tools/export_*.py` scripts and point the app at a real filesystem path. On
Android, push the directory somewhere the app can read (for example
`/sdcard/Download/voxcpm2-model`) and type that path into the field; WAV
exports are written to the app's external files directory shown after saving.

## Artifacts

| Coordinate | Contents |
| --- | --- |
| `top.yurin:voxcpm2:0.1.0` | KMP API plus embedded JVM and Android JNI runtimes |
| `top.yurin:voxcpm2-native-linuxx64:0.1.0@zip` | Linux core `.so`, `libomp`, C header |
| `top.yurin:voxcpm2-native-ios:0.1.0@zip` | device/simulator shared XCFramework |
| `top.yurin.voxcpm2.native-runtime` | Kotlin/Native consumer Gradle plugin |

Runtime archives contain the native license, notice, credits, and third-party
license texts. JVM and Android artifacts carry the same notices under
`META-INF/licenses/voxcpm2k`.

## Build from source

Clone recursively because the native runtime is a Git submodule:

```sh
git clone --recurse-submodules https://github.com/LiYulin-s/voxcpm2k.git
cd voxcpm2k
```

Linux/JVM requires xmake, a C++26 compiler with `#embed`, Vulkan development
files, and FFmpeg development files for the upstream host tools. The binding
core itself remains FFmpeg-free.

```sh
./gradlew jvmTest linuxX64Test :gradle-plugin:test
```

Android builds use SDK 36, NDK `29.0.14206865`, and API 26. The root build's
typed xmake task builds the same `voxcpm2_jni` target used by JVM and registers
its output with AGP as generated JNI libraries. NDK r29 is required because the
native Vulkan layers use the C++26 `#embed` directive:

```sh
./gradlew assembleAndroidMain
```

iOS sidecars must be built on macOS with Xcode and xmake:

```sh
./gradlew iosRuntimeZip
```

Linux and iOS sidecar ZIPs are produced by `linuxX64RuntimeZip` and
`iosRuntimeZip`. The native C/C++ regression suite remains available in the
submodule with `xmake test -j 1`.

## License

The Kotlin binding and Gradle plugin are Apache-2.0 licensed. The submodule and
native runtime are primarily MIT licensed; their Apache-2.0 and BSD-3-Clause
derivative scopes are documented in `native/voxcpm2-ncnn/NOTICE`.
