package com.appmuse.app.model

import kotlinx.serialization.Serializable

/**
 * Wire models mirroring the shared Node/Express backend's auth payloads (same
 * shapes the Flutter app's models use). Add your own feature models alongside
 * these as @Serializable data classes.
 */

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String? = null,
    val avatar: String? = null,
)

@Serializable
data class AuthRequest(
    val email: String,
    val password: String,
    val name: String? = null,
)

/** `POST auth/guest` body — the per-device guest session of an app without
 *  accounts (APP_AUTH=none, ADR-0046). Answered with the same [AuthResponse]
 *  as a login. */
@Serializable
data class GuestRequest(
    val deviceId: String,
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val user: User,
)

@Serializable
data class MeResponse(
    val user: User,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String? = null,
)

@Serializable
data class LogoutRequest(
    val refreshToken: String,
)

/**
 * Standard paginated envelope from the backend's paginatedResponse() helper.
 * Reuse this for any list endpoint instead of re-declaring the shape.
 */
@Serializable
data class Paginated<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    // Must byte-match the backend's paginatedResponse() helper, which emits
    // { items, total, page, totalPages, hasMore }. Every field defaulted so a
    // missing key never throws under kotlinx strict decoding.
    val totalPages: Int = 0,
    val hasMore: Boolean = false,
)
