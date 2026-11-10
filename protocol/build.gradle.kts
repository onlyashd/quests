import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlinx.serialization)
}

// Shared wire formats and crypto (docs/specs/protocol.md). Everything that produces or
// verifies bytes on both client and server lives here — never re-implemented elsewhere.
kotlin {
    android {
        namespace = "org.ferabrasil.quests.protocol"
        compileSdk = 37
        minSdk = 30
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        withHostTest {}
    }

    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
