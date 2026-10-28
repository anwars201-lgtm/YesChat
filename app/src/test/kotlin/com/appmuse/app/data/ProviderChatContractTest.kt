package com.appmuse.app.data

import com.appmuse.app.model.ChatAttachment
import com.appmuse.app.model.ConversationArchive
import com.appmuse.app.model.ConversationMessage
import com.appmuse.app.model.LocalConversation
import com.appmuse.app.model.ProviderProfile
import com.appmuse.app.model.TokenUsage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderChatContractTest {
    private val profile = ProviderProfile(
        id = "profile-test",
        name = "Penyedia pengujian",
        baseUrl = "https://provider.example/v1/",
        apiKey = "test-credential",
        model = "model-test",
        systemPrompt = "Jawab dalam Bahasa Indonesia.",
    )

    private fun message(role: String, content: String, attachments: List<ChatAttachment> = emptyList()) =
        ConversationMessage("message-$role", role, content, 100L, attachments = attachments)

    private fun body(profile: ProviderProfile = this.profile, history: List<ConversationMessage> = emptyList()) =
        Json.parseToJsonElement(Buffer().also { ProviderChatRepository.buildRequest(profile, history).body!!.writeTo(it) }.readUtf8()).jsonObject

    @Test
    fun requestsUseProviderOnlyWithoutAppServerCredentials() {
        val request = ProviderChatRepository.buildRequest(profile, listOf(message("user", "Halo")))
        assertEquals("https://provider.example/v1/chat/completions", request.url.toString())
        assertEquals("Bearer test-credential", request.header("Authorization"))
        assertNull(request.header("X-API-Key"))
        val payload = body()
        assertTrue(payload.getValue("stream").jsonPrimitive.boolean)
        assertTrue(payload.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.boolean)
        assertFalse(payload.containsKey("reasoning_effort"))
    }

    @Test
    fun reasoningPresetsSendExactProviderValues() {
        listOf("low", "medium", "high").forEach { value ->
            assertEquals(value, body(profile.copy(reasoningEffort = value)).getValue("reasoning_effort").jsonPrimitive.content)
        }
        assertFailsWith<IllegalArgumentException> { body(profile.copy(reasoningEffort = "invalid")) }
    }

    @Test
    fun switchedProfileUsesFullHistoryAndOnlyItsOwnSystemPrompt() {
        val history = listOf(message("user", "Pertanyaan pertama"), message("assistant", "Balasan parsial"), message("user", "Lanjutkan"))
        val switched = profile.copy(id = "other-profile", model = "other-model", systemPrompt = "Instruksi baru.")
        val payload = body(switched, history)
        assertEquals("other-model", payload.getValue("model").jsonPrimitive.content)
        val messages = payload.getValue("messages") as JsonArray
        assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(listOf("Instruksi baru.", "Pertanyaan pertama", "Balasan parsial", "Lanjutkan"), messages.map { it.jsonObject.getValue("content").jsonPrimitive.content })
    }

    @Test
    fun textDocumentsAndImagesAreIncludedInTheSameMessage() {
        val text = ChatAttachment("text", "catatan.txt", "text/plain", 7L, "Isi dokumen")
        val image = ChatAttachment("image", "gambar.png", "image/png", 8L, "iVBORw0KGgo=")
        val messages = body(history = listOf(message("user", "Jelaskan", listOf(text, image)))).getValue("messages") as JsonArray
        val parts = messages.last().jsonObject.getValue("content") as JsonArray
        assertTrue(parts.first().jsonObject.getValue("text").jsonPrimitive.content.contains("[Lampiran: catatan.txt]\nIsi dokumen"))
        assertEquals("image_url", parts.last().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("data:image/png;base64,iVBORw0KGgo=", parts.last().jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun attachmentOnlyMessagesAreNotDroppedFromHistory() {
        val text = ChatAttachment("text", "catatan.md", "text/plain", 3L, "Hai")
        val messages = body(history = listOf(message("user", "", listOf(text)))).getValue("messages") as JsonArray
        assertEquals(2, messages.size)
        assertTrue(messages.last().jsonObject.getValue("content").jsonPrimitive.content.contains("Hai"))
    }

    @Test
    fun streamParsesContentAndUsageOnlyFinalEvents() {
        val content = ProviderChatRepository.parseChunk("""{"choices":[{"delta":{"content":"Halo"},"finish_reason":null}]}""")
        assertEquals("Halo", content.content)
        assertNull(content.usage)
        assertFalse(content.finished)
        val usage = ProviderChatRepository.parseChunk("""{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":8,"total_tokens":20}}""")
        assertEquals(TokenUsage(12, 8, 20), usage.usage)
        assertEquals("", usage.content)
        assertTrue(ProviderChatRepository.parseChunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""").finished)
    }

    @Test
    fun missingUsageRemainsUnknownRatherThanEstimated() {
        assertNull(ProviderChatRepository.parseChunk("""{"choices":[],"usage":null}""").usage)
        assertNull(ProviderChatRepository.parseChunk("""{"usage":{"total_tokens":-1}}""").usage)
        val partial = ProviderChatRepository.parseChunk("""{"usage":{"prompt_tokens":9}}""").usage
        assertEquals(9L, partial?.promptTokens)
        assertNull(partial?.totalTokens)
        assertEquals(15L, ProviderChatRepository.parseChunk("""{"usage":{"prompt_tokens":9,"completion_tokens":6}}""").usage?.totalTokens)
    }

    @Test
    fun providerErrorsAreClassifiedAndDoNotExposeCredentials() {
        assertIs<ApiError.Auth>(ProviderChatRepository.httpError(401))
        assertIs<ApiError.Validation>(ProviderChatRepository.httpError(400))
        assertIs<ApiError.Validation>(ProviderChatRepository.httpError(302))
        assertFailsWith<ApiError> { ProviderChatRepository.parseChunk("""{"error":{"message":"private provider details"}}""") }
        assertFailsWith<ApiError.Validation> { ProviderChatRepository.parseChunk("""{"choices":[{"finish_reason":"content_filter"}]}""") }
    }

    @Test
    fun unsafeEndpointsAreRejectedBeforeCredentialsCanBeSent() {
        listOf("http://provider.example/v1", "https://person:secret@provider.example", "https://provider.example?key=secret", "https://provider.example#fragment").forEach { baseUrl ->
            assertFailsWith<IllegalArgumentException> { ProviderChatRepository.buildRequest(profile.copy(baseUrl = baseUrl), emptyList()) }
            assertFailsWith<IllegalArgumentException> { ProviderChatRepository.buildModelsRequest(profile.copy(baseUrl = baseUrl)) }
        }
    }

    @Test
    fun modelsResponseSupportsEmptyDeduplicatedAndManualFallbackCases() {
        assertEquals(listOf("a", "b"), ProviderChatRepository.parseModels("""{"data":[{"id":"b"},{"id":"a"},{"id":"a"},{"id":""}]}"""))
        assertTrue(ProviderChatRepository.parseModels("""{"data":[]}""").isEmpty())
        assertFailsWith<ApiError.Validation> { ProviderChatRepository.parseModels("""{}""") }
    }

    @Test
    fun attachmentFormatsAreValidatedByBytesAndUtf8() {
        assertEquals("image/png", ChatAttachmentRepository.detectImage(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)))
        assertEquals("image/jpeg", ChatAttachmentRepository.detectImage(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertEquals("image/gif", ChatAttachmentRepository.detectImage("GIF89a".toByteArray()))
        assertEquals("image/webp", ChatAttachmentRepository.detectImage("RIFF0000WEBP".toByteArray()))
        assertNull(ChatAttachmentRepository.detectImage("bukan gambar".toByteArray()))
        assertEquals("Halo\nDunia", ChatAttachmentRepository.decodeText("\uFEFFHalo\nDunia".toByteArray()))
        assertFailsWith<ApiError.Validation> { ChatAttachmentRepository.decodeText(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertFailsWith<IllegalArgumentException> { ChatAttachmentRepository.decodeText(byteArrayOf(0, 1, 2)) }
    }

    @Test
    fun oldArchivesStillLoadAndNewOptionsUsageAttachmentsSurviveRoundTrip() {
        val old = Json.decodeFromString<ConversationArchive>("""{"conversations":[{"id":"old","title":"Lama","createdAt":1,"updatedAt":1,"messages":[{"id":"m","role":"user","content":"Halo","createdAt":1}]}]}""")
        assertNull(old.conversations.single().modelOverride)
        assertTrue(old.conversations.single().messages.single().attachments.isEmpty())
        val attachment = ChatAttachment("a", "catatan.txt", "text/plain", 3L, "Hai")
        val conversation = LocalConversation(
            "new", "Baru", 2, 3, profile.id,
            listOf(message("user", "", listOf(attachment)), message("assistant", "Balasan").copy(usage = TokenUsage(9, 6, 15), status = "stopped")),
            modelOverride = "other-model", reasoningOverride = "high",
        )
        val archive = ConversationArchive(selectedConversationId = conversation.id, conversations = listOf(conversation))
        val restored = Json.decodeFromString<ConversationArchive>(Json.encodeToString(archive))
        assertEquals(archive, restored)
        assertNotNull(restored.conversations.single().messages.last().usage)
    }
}
