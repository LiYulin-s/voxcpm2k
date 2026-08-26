package top.yurin.voxcpm2.internal

private object AndroidJniLibraryState {
    var loaded: Boolean = false
}

internal actual fun loadJniLibraries() {
    synchronized(AndroidJniLibraryState) {
        if (AndroidJniLibraryState.loaded) return
        System.loadLibrary("voxcpm2_ncnn")
        System.loadLibrary("voxcpm2_jni")
        AndroidJniLibraryState.loaded = true
    }
}
