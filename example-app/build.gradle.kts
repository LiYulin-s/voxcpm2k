import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

// The AGP core-library jlink transform rejects android-36 modules on newer JDKs.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    dependencies {
        implementation(project(":example"))
        implementation("androidx.activity:activity-compose:1.11.0")
    }
    target {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }
}

android {
    namespace = "top.yurin.voxcpm2.example"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "top.yurin.voxcpm2.example"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
