package top.yurin.voxcpm2.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property

/** Coordinates and extraction location used by the VoxCPM2 native runtime plugin. */
public abstract class VoxCPM2NativeRuntimeExtension {
    /** Version of the native sidecar artifacts. Defaults to the plugin version. */
    public abstract val version: Property<String>

    /** Maven group containing the native sidecar artifacts. */
    public abstract val coordinatesGroup: Property<String>

    /** Directory into which resolved native sidecars are extracted. */
    public abstract val extractionDirectory: DirectoryProperty
}
