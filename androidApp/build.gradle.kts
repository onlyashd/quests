import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/** `android=` from versions.properties, bumped by .cicd/bump-version.sh in the release workflow. */
val appVersion: String = Properties()
    .apply { rootProject.file("versions.properties").reader().use { load(it) } }
    .getProperty("android")
    ?: error("versions.properties has no 'android' entry")

/**
 * Monotonic versionCode from SemVer: MAJOR*10^7 + MINOR*10^5 + PATCH*10^3 + STAGE*100 + N,
 * STAGE = canary 0, alpha 1, beta 2, rc 3, final 9 — so 1.2.0-rc.4 < 1.2.0 < 1.2.1-alpha.0.
 */
fun versionCodeOf(version: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-(canary|alpha|beta|rc)\.(\d+))?$""").matchEntire(version)
        ?: error("android version '$version' is not MAJOR.MINOR.PATCH[-tag.N]")
    val (major, minor, patch) = m.destructured.toList().take(3).map { it.toInt() }
    val stage = when (m.groupValues[4]) { "canary" -> 0; "alpha" -> 1; "beta" -> 2; "rc" -> 3; else -> 9 }
    val n = m.groupValues[5].ifEmpty { "0" }.toInt()
    require(minor < 100 && patch < 100 && n < 100 && major <= 209) { "android version '$version' out of versionCode range" }
    return major * 10_000_000 + minor * 100_000 + patch * 1_000 + stage * 100 + n
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
}

// Android UI: Jetpack Compose. Business logic comes from :shared (app.md §3).
android {
    namespace = "org.ferabrasil.quests.android"
    compileSdk = 37

    defaultConfig {
        minSdk = 30
        targetSdk = 37

        applicationId = "org.ferabrasil.quests.androidApp"
        versionCode = versionCodeOf(appVersion)
        versionName = appVersion
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(kotlin("test"))
}
