package com.appmuse.app.data

import com.appmuse.app.model.AuthRequest
import com.appmuse.app.model.AuthResponse
import com.appmuse.app.model.GuestRequest
import com.appmuse.app.model.MeResponse
import com.appmuse.app.model.LogoutRequest
import com.appmuse.app.model.RefreshRequest
import com.appmuse.app.model.RefreshResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit interface for the shared backend. The auth routes match the Node/
 * Express backend's contract (the same routes the Flutter app calls). Add your
 * feature endpoints here as suspend functions returning @Serializable models.
 */
interface ApiService {
    @POST("auth/register")
    suspend fun register(@Body body: AuthRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body body: AuthRequest): AuthResponse

    /** Per-device guest session (APP_AUTH=none) — 404 in every other mode. */
    @POST("auth/guest")
    suspend fun guest(@Body body: GuestRequest): AuthResponse

    @GET("auth/me")
    suspend fun me(): MeResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): RefreshResponse

    @POST("auth/logout")
    suspend fun logout(@Body body: LogoutRequest)

    // AI: Add feature endpoints below
}
