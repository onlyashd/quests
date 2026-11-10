import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.metro)
    alias(libs.plugins.buildConfig)
}

// Business logic shared by the Android (Jetpack Compose) and iOS (SwiftUI) apps.
// No UI code lives here: no Compose, no view models, no strings (app.md §3).
kotlin {
    android {
        namespace = "org.ferabrasil.quests.shared"
        compileSdk = 37
        minSdk = 30
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.multiplatformSettings)
            implementation(libs.cryptography.random)
            implementation(libs.napier)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.multiplatformSettings.test)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
    }

    targets
        .withType<KotlinNativeTarget>()
        .matching { it.konanTarget.family.isAppleFamily }
        .configureEach {
            binaries {
                framework {
                    baseName = "Shared"
                    isStatic = true
                    // DTOs and enums from :protocol are part of the Swift-facing API.
                    export(project(":protocol"))
                }
            }
        }
}

// App versions come from versions.properties (bumped by .cicd/bump-version.sh), exposed through
// `AppInfo`. Each app reads its own entry.
val appVersions = Properties().apply { rootProject.file("versions.properties").reader().use { load(it) } }
fun appVersion(target: String): String =
    appVersions.getProperty(target) ?: error("versions.properties has no '$target' entry")

buildConfig {
    packageName("org.ferabrasil.quests")
    buildConfigField("ANDROID_VERSION", appVersion("android"))
    buildConfigField("IOS_VERSION", appVersion("ios"))
    // Pinned values (relay URL, OHTTP key config, genesis admin set) land with A2.
}
