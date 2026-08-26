pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("com.android.settings") version "9.0.1"
}

android {
    // The KMP Android DSL has no ndkVersion property; this also drives sdkComponents.ndkDirectory.
    ndkVersion = "29.0.14206865"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "voxcpm2k"
include(":gradle-plugin")
