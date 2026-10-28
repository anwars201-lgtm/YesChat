package com.appmuse.app.data

import com.appmuse.app.model.ConversationMessage
import com.appmuse.app.model.ProviderProfile
import com.appmuse.app.model.TokenUsage
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Direct BYOK transport, deliberately isolated from app-server auth and interceptors. */
class ProviderChatRepository {
    suspend fun fetchModels(profile: ProviderProfile): List<String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(buildModelsRequest(profile))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(providerError(error))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val models = response.use {
                        if (!it.isSuccessful) throw httpError(it.code)
                        val source = it.body?.source() ?: throw ApiError.Server("Daftar model dari penyedia kosong.")
                        source.request(MAX_EVENT_SIZE + 1L)
                        if (source.buffer.size > MAX_EVENT_SIZE) throw ApiError.Server("Daftar model terlalu besar untuk dibaca.")
                        parseModels(source.readUtf8())
                    }
                    if (continuation.isActive) continuation.resume(models)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(providerError(error))
                }
            }
        })
    }

    suspend fun testConnection(profile: ProviderProfile) {
        stream(profile, listOf(ConversationMessage(
            id = "connection-check",
            role = "user",
            content = "Balas singkat dengan OK.",
            createdAt = System.currentTimeMillis(),
            profileId = profile.id,
            status = "complete",
        ))).first { it.content.isNotBlank() }
    }

    fun stream(profile: ProviderProfile, history: List<ConversationMessage>): Flow<Chunk> = callbackFlow {
        val call = client.newCall(buildRequest(profile, history))
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!call.isCanceled()) close(providerError(error))
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw httpError(it.code)
                        if (it.header("Content-Type")?.substringBefore(';')?.trim()?.equals("text/event-stream", true) != true) {
                            throw ApiError.Validation("Penyedia tidak mengirim balasan streaming. Periksa dukungan Chat Completions.")
                        }
                        val source = it.body?.source() ?: throw ApiError.Server("Balasan penyedia kosong.")
                        val data = StringBuilder()
                        var finished = false
                        var sawFinish = false
                        fun dispatch(): Boolean {
                            if (data.isEmpty()) return false
                            val event = data.toString().trimEnd('\n')
                            data.setLength(0)
                            if (event.trim() == "[DONE]") return true
                            val chunk = parseChunk(event)
                            if (chunk.finished) sawFinish = true
                            if (chunk.content.isNotEmpty() || chunk.usage != null) trySend(chunk)
                            return false
                        }
                        while (!call.isCanceled()) {
                            val line = source.readUtf8LineStrictOrNull() ?: break
                            if (line.isEmpty()) {
                                if (dispatch()) { finished = true; break }
                            } else if (line.startsWith("data:")) {
                                data.append(line.substring(5).removePrefix(" ")).append('\n')
                                if (data.length > MAX_EVENT_SIZE) throw ApiError.Server("Balasan penyedia terlalu besar untuk dibaca.")
                            }
                        }
                        if (data.isNotEmpty()) finished = dispatch() || finished
                        if (!finished && !sawFinish && !call.isCanceled()) {
                            throw ApiError.Network("Koneksi terputus sebelum balasan selesai. Teks yang diterima tetap disimpan.")
                        }
                        close()
                    }
                } catch (error: Exception) {
                    if (!call.isCanceled()) close(providerError(error))
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED)

    companion object {
        private const val MAX_EVENT_SIZE = 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun buildModelsRequest(profile: ProviderProfile): Request {
            val url = profile.baseUrl.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Base URL harus berupa alamat HTTPS yang valid tanpa kredensial, kueri, atau fragmen."
            }
            require(profile.apiKey.trim().isNotBlank() && profile.apiKey.trim().all { it.code in 33..126 }) {
                "API Key wajib diisi tanpa spasi atau karakter kontrol."
            }
            return Request.Builder()
                .url(url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/models").build())
                .header("Authorization", "Bearer ${profile.apiKey.trim()}")
                .header("Accept", "application/json")
                .get()
                .build()
        }

        fun parseModels(body: String): List<String> {
            val root = json.parseToJsonElement(body).jsonObject
            if (root["error"] != null) throw ApiError.Server("Penyedia menolak pengambilan model. Periksa izin API Key.")
            val data = root["data"] as? JsonArray
                ?: throw ApiError.Validation("Format daftar model tidak kompatibel. Masukkan nama model secara manual.")
            return data.mapNotNull { element ->
                val id = ((element as? JsonObject)?.get("id") as? JsonPrimitive)
                id?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
            }.distinct().sorted()
        }

        fun buildRequest(profile: ProviderProfile, history: List<ConversationMessage>): Request {
            val url = profile.baseUrl.trim().toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) {
                "Base URL harus berupa alamat HTTPS yang valid tanpa kredensial, kueri, atau fragmen."
            }
            require(profile.model.isNotBlank() && profile.apiKey.isNotBlank()) { "Lengkapi API Key dan model di Pengaturan." }
            require(profile.reasoningEffort in setOf("default", "low", "medium", "high")) { "Tingkat penalaran tidak valid." }
            val endpoint = url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/chat/completions").build()
            val body = buildJsonObject {
                put("model", profile.model)
                put("stream", true)
                put("stream_options", buildJsonObject { put("include_usage", true) })
                if (profile.reasoningEffort != "default") put("reasoning_effort", profile.reasoningEffort)
                put("messages", buildJsonArray {
                    if (profile.systemPrompt.isNotBlank()) add(buildJsonObject {
                        put("role", "system"); put("content", profile.systemPrompt)
                    })
                    history.filter { it.role in setOf("user", "assistant") && (it.content.isNotBlank() || it.attachments.isNotEmpty()) }.forEach { message ->
                        add(buildJsonObject {
                            put("role", message.role)
                            val text = buildString {
                                append(message.content)
                                message.attachments.filterNot { it.isImage }.forEach { attachment ->
                                    append("\n\n[Lampiran: ").append(attachment.name).append("]\n")
                                    append(attachment.data)
                                }
                            }
                            val images = message.attachments.filter { it.isImage }
                            if (images.isEmpty()) put("content", text)
                            else put("content", buildJsonArray {
                                if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                                images.forEach { image ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", "data:${image.mimeType};base64,${image.data}") })
                                    })
                                }
                            })
                        })
                    }
                })
            }
            return Request.Builder().url(endpoint)
                .header("Authorization", "Bearer ${profile.apiKey.trim()}")
                .header("Accept", "text/event-stream")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
        }

        data class Chunk(val content: String = "", val finished: Boolean = false, val usage: TokenUsage? = null)

        fun parseChunk(data: String): Chunk {
            val root = json.parseToJsonElement(data).jsonObject
            if (root["error"] != null) throw ApiError.Server("Penyedia menghentikan permintaan. Periksa model, kuota, dan parameter profil.")
            val rawUsage = root["usage"] as? JsonObject
            fun count(key: String) = (rawUsage?.get(key) as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
            val prompt = count("prompt_tokens")
            val completion = count("completion_tokens")
            val total = count("total_tokens") ?: if (prompt != null && completion != null) prompt + completion else null
            val usage = if (prompt != null || completion != null || total != null) TokenUsage(prompt, completion, total) else null
            val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return Chunk(usage = usage)
            val delta = choice["delta"] as? JsonObject
            val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val reason = (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull
            if (reason == "content_filter") throw ApiError.Validation("Balasan dibatasi oleh kebijakan penyedia. Teks sebelumnya tetap disimpan.")
            return Chunk(content, reason != null, usage)
        }

        fun httpError(code: Int): ApiError = when (code) {
            401, 403 -> ApiError.Auth("API Key ditolak atau tidak memiliki izin. Periksa profil di Pengaturan.")
            404 -> ApiError.NotFound("Model atau endpoint tidak ditemukan. Periksa model dan Base URL, termasuk akhiran /v1 bila diperlukan.")
            400, 405, 422 -> ApiError.Validation("Model atau parameter tidak didukung. Periksa model, penalaran, dukungan gambar, serta stream_options untuk pelaporan token.")
            408, 504 -> ApiError.Network("Penyedia terlalu lama merespons. Coba lagi.")
            429 -> ApiError.Server("Batas permintaan atau kuota tercapai. Tunggu sebentar atau periksa saldo penyedia.")
            in 300..399 -> ApiError.Validation("Penyedia mengalihkan URL. Gunakan Base URL HTTPS tujuan secara langsung demi keamanan API Key.")
            in 500..599 -> ApiError.Server("Penyedia sedang mengalami gangguan. Coba lagi nanti.")
            else -> ApiError.Server("Permintaan ditolak penyedia (HTTP $code). Periksa profil Anda.")
        }

        fun providerError(error: Throwable): ApiError = when (error) {
            is ApiError -> error
            is SocketTimeoutException -> ApiError.Network("Waktu tunggu habis. Periksa koneksi atau coba lagi; teks parsial tetap disimpan.")
            is javax.net.ssl.SSLException -> ApiError.Network("Koneksi HTTPS tidak aman atau sertifikat tidak valid. Periksa Base URL.")
            is IOException -> ApiError.Network("Tidak dapat terhubung ke penyedia. Periksa internet dan Base URL; teks parsial tetap disimpan.")
            is SerializationException, is IllegalStateException -> ApiError.Server("Format balasan penyedia tidak kompatibel dengan OpenAI Chat Completions.")
            is IllegalArgumentException -> ApiError.Validation(error.message ?: "Periksa kembali pengaturan profil.")
            else -> ApiError.Unknown("Balasan tidak dapat diproses. Teks parsial tetap disimpan.")
        }
    }
}

private fun okio.BufferedSource.readUtf8LineStrictOrNull(): String? =
    if (exhausted()) null else readUtf8LineStrict(1024L * 1024L)
