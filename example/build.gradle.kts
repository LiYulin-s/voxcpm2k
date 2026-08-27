import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidLibrary {
        namespace = "top.yurin.voxcpm2.example.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions.jvmTarget.set(JvmTarget.JVM_11)
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(project(":"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        // Shared JVM code for the Android and Desktop download transport.
        val jvmShared by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(jvmShared)
        val desktopMain by getting {
            dependsOn(jvmShared)
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }

    jvmToolchain(21)
}

compose.desktop.application {
    mainClass = "top.yurin.voxcpm2.example.MainKt"
}
