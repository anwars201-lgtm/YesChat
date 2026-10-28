package com.appmuse.app.data

import android.content.Context
import android.util.AtomicFile
import com.appmuse.app.model.ConversationArchive
import com.appmuse.app.model.ConversationChanges
import com.appmuse.app.model.LocalConversation
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Local-only source of truth. It never contacts the app server or a provider. */
class ConversationRepository private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "yeschat-conversations.json"))
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _archive = MutableStateFlow<ConversationArchive?>(null)
    val archive = _archive.asStateFlow()

    suspend fun load(): ConversationArchive = withContext(Dispatchers.IO) {
        mutex.withLock { readLocked().also { _archive.value = it } }
    }

    suspend fun create(
        title: String,
        profileId: String? = null,
        modelOverride: String? = null,
        reasoningOverride: String? = null,
    ): LocalConversation {
        val now = System.currentTimeMillis()
        val conversation = LocalConversation(
            UUID.randomUUID().toString(), title, now, now,
            profileId = profileId, modelOverride = modelOverride, reasoningOverride = reasoningOverride,
        )
        update { ConversationChanges.create(it, conversation) }
        return conversation
    }

    suspend fun browseAndOpen(id: String) {
        update { ConversationChanges.select(it, id) }
    }

    suspend fun rename(id: String, title: String) {
        update { ConversationChanges.rename(it, id, title) }
    }

    suspend fun delete(id: String) {
        update { ConversationChanges.delete(it, id) }
    }

    /** Used by Chat to persist profile switches and messages without losing other chats. */
    suspend fun updateConversation(id: String, transform: (LocalConversation) -> LocalConversation) {
        update { current ->
            require(current.conversations.any { it.id == id }) { "Percakapan tidak ditemukan." }
            current.copy(conversations = current.conversations.map {
                if (it.id == id) transform(it) else it
            })
        }
    }

    private fun readLocked(): ConversationArchive {
        _archive.value?.let { return it }
        return try {
            file.openRead().bufferedReader(Charsets.UTF_8).use {
                json.decodeFromString<ConversationArchive>(it.readText())
            }
        } catch (error: java.io.FileNotFoundException) {
            if (file.baseFile.exists()) throw error
            ConversationArchive()
        }
    }

    private suspend fun update(transform: (ConversationArchive) -> ConversationArchive) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val next = transform(readLocked())
                val stream = file.startWrite()
                try {
                    stream.write(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (error: Exception) {
                    file.failWrite(stream)
                    throw error
                }
                _archive.value = next
            }
        }

    companion object {
        @Volatile private var instance: ConversationRepository? = null
        fun get(context: Context): ConversationRepository = instance ?: synchronized(this) {
            instance ?: ConversationRepository(context.applicationContext).also { instance = it }
        }
    }
}
