import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The Azure function key, kept out of git. First hit wins:
 *   -Pgpspush.authKey=<key>          one-off command line builds
 *   local.properties                 Android Studio (gitignored)
 *   GPSPUSH_AUTH_KEY                 CI, from a repository secret
 * Blank is allowed — the app then sends no auth header at all.
 */
val gpsAuthKey: String = run {
    val fromLocalProperties: String? = rootProject.file("local.properties")
        .takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { stream -> load(stream) } } }
        ?.getProperty("gpspush.authKey")

    val fromCommandLine = findProperty("gpspush.authKey") as String?
    val fromEnvironment: String? = System.getenv("GPSPUSH_AUTH_KEY")

    fromCommandLine ?: fromLocalProperties ?: fromEnvironment ?: ""
}

if (gpsAuthKey.isBlank()) {
    logger.warn(
        "gpspush: no auth key configured, the APK will POST without an auth header " +
            "and Azure will reject uploads with 401. Set gpspush.authKey or GPSPUSH_AUTH_KEY."
    )
}

android {
    namespace = "com.example.gpspush"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gpspush"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "AUTH_HEADER_VALUE",
            "\"${gpsAuthKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildTypes {
        release {
            // Personal build, never shipped: no shrinking, no obfuscation.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.8.2")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
