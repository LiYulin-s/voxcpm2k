# JNI_OnLoad resolves these names and descriptors with RegisterNatives.
-keep class top.yurin.voxcpm2.internal.JniBindings { *; }
-keep interface top.yurin.voxcpm2.internal.JniProgressCallback { *; }
-keepclassmembers class * implements top.yurin.voxcpm2.internal.JniProgressCallback {
    public boolean onProgress(int, java.lang.String, int, int);
}

# Native failures instantiate this class by its binary name.
-keep class top.yurin.voxcpm2.VoxCPM2Exception { *; }
