plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.3.20"
    // Kotlin 2.0+ requires the separate Compose compiler plugin; its version
    // MUST equal the Kotlin version exactly (replaces the old
    // composeOptions.kotlinCompilerExtensionVersion).
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    // kotlinx-serialization for JSON (Retrofit converter + @Serializable DTOs).
    // Version MUST equal the Kotlin version.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20"
}

// Reads a -P Gradle property (the build-server injects -PAPI_URL=… etc., mirroring
// Flutter's --dart-define) and falls back to a dev default. These become
// BuildConfig.* compile-time constants — the Kotlin equivalent of Flutter's
// String.fromEnvironment in config.dart.
fun buildProp(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: fallback

android {
    namespace = "com.appmuse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.appmuse.pe9vgszgdmbce"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // Wire-contract (see AppConfig.kt). Android emulator localhost is 10.0.2.2.
        buildConfigField("String", "API_URL", "\"${buildProp("API_URL", "http://10.0.2.2:3000")}\"")
        buildConfigField("String", "API_KEY", "\"${buildProp("API_KEY", "")}\"")
        buildConfigField("String", "APP_ARCHETYPE", "\"${buildProp("APP_ARCHETYPE", "crud-content")}\"")
        buildConfigField("String", "APP_AUTH", "\"${buildProp("APP_AUTH", "required")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Stable, stack-neutral artifact name (app-debug.apk / app-release.apk).
    base {
        archivesName.set("app")
    }

    // Root-application layout: source roots live at the project root, not under
    // an :app submodule.
    sourceSets["main"].apply {
        manifest.srcFile("src/main/AndroidManifest.xml")
        java.srcDirs("src/main/kotlin")
        res.srcDirs("src/main/res")
    }
}

// Kotlin 2.x: jvmTarget MUST be set via the compilerOptions DSL below. The old
// android-extension form (jvmTarget as a String) is a HARD ERROR in Kotlin 2.3.x,
// not just a deprecation — verified empirically against the real toolchain.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.00")
    implementation(composeBom)

    // AndroidX core + lifecycle
    implementation("androidx.core:core-ktx:1.17.0")
    // Branded splash screen (backport: identical look on API 24-30, platform splash on
    // 31+). Drives Theme.Appmuse.Starting + installSplashScreen() in MainActivity.
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Jetpack Compose (versions resolved by the BOM)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Networking — Retrofit + OkHttp + kotlinx-serialization (talks to the SAME
    // Node/Express backend as the Flutter app; see ApiClient.kt).
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Portable contract tests: JVM-only, no device/emulator/screenshot required.
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
