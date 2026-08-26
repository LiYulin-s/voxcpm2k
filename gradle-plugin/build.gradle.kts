import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.gradlePluginPublish)
    `java-gradle-plugin`
    `maven-publish`
}

tasks.withType<Jar>().configureEach {
    manifest.attributes["Implementation-Version"] = project.version
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("META-INF/licenses/voxcpm2-gradle-plugin")
        rename { "LICENSE.txt" }
    }
}

kotlin {
    explicitApi()
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
}

gradlePlugin {
    website = "https://github.com/LiYulin-s/voxcpm2k"
    vcsUrl = "https://github.com/LiYulin-s/voxcpm2k.git"
    plugins {
        create("voxcpm2NativeRuntime") {
            id = "top.yurin.voxcpm2.native-runtime"
            implementationClass = "top.yurin.voxcpm2.gradle.VoxCPM2NativeRuntimePlugin"
            displayName = "VoxCPM2 Native Runtime"
            description = "Resolves and links VoxCPM2 shared runtime sidecars for Kotlin/Native applications."
            tags = listOf("kotlin", "kotlin-native", "multiplatform", "xmake", "voxcpm2")
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "VoxCPM2 Native Runtime Gradle Plugin"
            description = "Links published VoxCPM2 shared runtimes into Kotlin/Native applications."
            url = "https://github.com/LiYulin-s/voxcpm2k"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
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
}

val signingConfigured = providers.gradleProperty("signingInMemoryKey").isPresent ||
    providers.gradleProperty("signing.secretKeyRingFile").isPresent

if (signingConfigured) {
    pluginManager.apply("signing")
    configure<SigningExtension> {
        sign(publishing.publications)
    }
}
