package com.appmuse.app.model

import kotlinx.serialization.Serializable

@Serializable
data class LocalConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val profileId: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
)

@Serializable
data class ConversationMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val profileId: String? = null,
    val status: String = "complete",
)

@Serializable
data class ConversationArchive(
    val version: Int = 1,
    val selectedConversationId: String? = null,
    val conversations: List<LocalConversation> = emptyList(),
)

/** Pure archive operations shared by device persistence and portable tests. */
object ConversationChanges {
    fun create(archive: ConversationArchive, conversation: LocalConversation): ConversationArchive {
        require(archive.conversations.none { it.id == conversation.id })
        return archive.copy(
            selectedConversationId = conversation.id,
            conversations = listOf(conversation) + archive.conversations,
        )
    }

    fun select(archive: ConversationArchive, id: String): ConversationArchive {
        require(archive.conversations.any { it.id == id }) { "Percakapan tidak ditemukan." }
        return archive.copy(selectedConversationId = id)
    }

    fun rename(archive: ConversationArchive, id: String, title: String): ConversationArchive {
        val trimmed = title.trim()
        require(trimmed.isNotEmpty()) { "Nama percakapan tidak boleh kosong." }
        require(trimmed.length <= 120) { "Nama percakapan maksimal 120 karakter." }
        require(archive.conversations.any { it.id == id }) { "Percakapan tidak ditemukan." }
        return archive.copy(conversations = archive.conversations.map {
            if (it.id == id) it.copy(title = trimmed) else it
        })
    }

    fun delete(archive: ConversationArchive, id: String): ConversationArchive = archive.copy(
        selectedConversationId = archive.selectedConversationId.takeUnless { it == id },
        conversations = archive.conversations.filterNot { it.id == id },
    )
}
