package com.appmuse.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import retrofit2.HttpException
import java.io.IOException

/**
 * Type-classified API error — the Kotlin equivalent of the Flutter app's
 * ApiError. Lets the UI branch on the failure kind (retry vs re-login vs field
 * errors) without every screen re-implementing the network/HTTP branching.
 *
 * Crucially, [from] reads the backend's error BODY so the user sees the real
 * reason ("Password must be at least 8 characters") instead of a vague
 * "check the form". It handles both shapes the shared backend emits:
 *   - AppError ............... {"error":"<human message>"}
 *   - validate middleware .... {"error":"Validation failed","details":[{"path","message"}]}
 */
sealed class ApiError(open val userMessage: String) : Exception() {
    data class Network(override val userMessage: String = "No connection. Check your internet and try again.") :
        ApiError(userMessage)

    data class Validation(
        override val userMessage: String,
        val fieldErrors: Map<String, String> = emptyMap(),
    ) : ApiError(userMessage)

    data class Auth(override val userMessage: String = "Your session expired. Please log in again.") :
        ApiError(userMessage)

    data class Server(override val userMessage: String = "Something went wrong on the server. Try again.") :
        ApiError(userMessage)

    data class NotFound(override val userMessage: String = "Not found.") : ApiError(userMessage)

    data class Unknown(override val userMessage: String = "Unexpected error.") : ApiError(userMessage)

    val retryable: Boolean get() = this is Network || this is Server

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Classify any throwable thrown from a Retrofit call into an ApiError.
         *
         * [authAttempt] = true for an explicit login/register submission, so a 401
         * reads "Invalid email or password" (a credential failure) instead of the
         * mid-session "Your session expired" copy — the two share the HTTP code but
         * mean very different things to the user.
         */
        fun from(t: Throwable, authAttempt: Boolean = false): ApiError = when (t) {
            is ApiError -> t
            is IOException -> Network()
            is HttpException -> {
                val parsed = parseErrorBody(t)
                val serverMsg = parsed.first
                val fieldErrors = parsed.second
                when (t.code()) {
                    400, 422 -> Validation(serverMsg ?: "Please check the form and try again.", fieldErrors)
                    401, 403 -> if (authAttempt) Auth(serverMsg ?: "Invalid email or password.") else Auth()
                    404 -> NotFound()
                    409 -> Validation(serverMsg ?: "That already exists.", fieldErrors)
                    in 500..599 -> Server()
                    else -> Unknown(serverMsg ?: "Request failed (${t.code()}).")
                }
            }
            else -> Unknown()
        }

        /**
         * Best-effort extraction of a human message + per-field errors from the
         * backend error body. Returns (message?, fieldErrors). Never throws — a
         * body we can't parse falls back to (null, empty) so [from] uses its
         * per-status default.
         */
        private fun parseErrorBody(e: HttpException): Pair<String?, Map<String, String>> {
            val raw = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            if (raw.isNullOrBlank()) return null to emptyMap()
            return try {
                val root = json.parseToJsonElement(raw).jsonObject
                val fieldErrors = LinkedHashMap<String, String>()
                (root["details"] as? JsonArray)?.forEach { d ->
                    val o = d as? JsonObject ?: return@forEach
                    val path = str(o["path"])
                    val msg = str(o["message"])
                    if (!path.isNullOrBlank() && !msg.isNullOrBlank()) fieldErrors[path] = msg
                }
                val errorEl = root["error"]
                val errorField = (errorEl as? JsonPrimitive)?.contentOrNull
                    ?: str((errorEl as? JsonObject)?.get("message"))
                val messageField = str(root["message"])
                // Prefer the specific field message(s) when the top-level text is the
                // generic "Validation failed"; otherwise prefer the explicit error/message.
                val combined = if (fieldErrors.isNotEmpty() && (errorField == null || errorField == "Validation failed")) {
                    fieldErrors.values.joinToString(" ")
                } else {
                    errorField ?: messageField
                }
                combined?.takeIf { it.isNotBlank() } to fieldErrors
            } catch (_: Exception) {
                null to emptyMap()
            }
        }

        /** Safe JSON string extraction — null unless the element is a string primitive. */
        private fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull
    }
}
