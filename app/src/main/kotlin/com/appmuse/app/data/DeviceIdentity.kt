package com.appmuse.app.data

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Stable per-installation id used to open the guest session of an app without
 * accounts (APP_AUTH=none, ADR-0046). Not a secret: a random UUID generated once
 * and kept in noBackupFilesDir (never restored onto another device), OUTSIDE the
 * encrypted session file so logout / session invalidation keep the identity —
 * the next guest session resumes the same guest user and its data.
 */
fun interface DeviceIdentityProvider {
    fun deviceId(): String
}

class DeviceIdentity(context: Context) : DeviceIdentityProvider {
    private val file = File(context.applicationContext.noBackupFilesDir, DEVICE_ID_FILE)

    @Volatile
    private var cached: String? = null

    @Synchronized
    override fun deviceId(): String {
        cached?.let { return it }
        val existing = runCatching { file.readText().trim() }.getOrNull()
        if (!existing.isNullOrEmpty() && DEVICE_ID_PATTERN.matches(existing)) {
            cached = existing
            return existing
        }
        val fresh = UUID.randomUUID().toString().replace("-", "")
        file.writeText(fresh)
        cached = fresh
        return fresh
    }

    private companion object {
        const val DEVICE_ID_FILE = "device-id"
        /** Mirrors the backend's `/auth/guest` validation. */
        val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9_-]{16,64}$")
    }
}
