package com.appmuse.app.config

import com.appmuse.app.BuildConfig

/**
 * App configuration — the Kotlin equivalent of the Flutter template's
 * config.dart. Values come from BuildConfig (compile-time constants injected by
 * the build-server via -P Gradle properties, mirroring Flutter's --dart-define).
 *
 * The app talks to the SAME Node/Express backend as the Flutter app, through the
 * platform proxy. Do NOT hardcode URLs or keys — always read them here.
 */
object AppConfig {
    /** Backend base URL. Trailing slash enforced so Retrofit resolves relative
     *  paths correctly (e.g. "auth/login", not "/auth/login"). Dev fallback is
     *  the Android-emulator localhost (10.0.2.2). */
    val apiUrl: String = BuildConfig.API_URL.let { if (it.endsWith("/")) it else "$it/" }

    /** Project-scoped proxy credential (sent as X-API-Key). Empty in pure local
     *  dev; injected by the platform for hosted builds. NOT a user secret. */
    val apiKey: String = BuildConfig.API_KEY

    /** App archetype (crud-content / utility-tool / …). Drives default UX. */
    val appArchetype: String = BuildConfig.APP_ARCHETYPE

    /** Auth mode (required / optional / none). */
    val authMode: String = BuildConfig.APP_AUTH

    /** Whether the app gates content behind login. */
    val authEnabled: Boolean = authMode != "none"
}
