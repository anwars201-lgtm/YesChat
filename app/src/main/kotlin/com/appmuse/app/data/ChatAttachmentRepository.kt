package com.appmuse.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.appmuse.app.model.ChatAttachment
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Copies selected content into the local conversation; no public upload or URI grant. */
class ChatAttachmentRepository(private val context: Context) {
    suspend fun read(uri: Uri): ChatAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.filterNot { it.isISOControl() }?.take(160)?.ifBlank { null } ?: "Lampiran"
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var size = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= MAX_IMAGE_BYTES) { "Ukuran lampiran maksimal 5 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: throw ApiError.Validation("Berkas tidak dapat dibaca. Pilih berkas lain.")
        require(bytes.isNotEmpty()) { "Berkas kosong tidak dapat dilampirkan." }
        val imageType = detectImage(bytes)
        val mimeType: String
        val data: String
        if (imageType != null) {
            mimeType = imageType
            data = Base64.encodeToString(bytes, Base64.NO_WRAP)
        } else {
            val extension = name.substringAfterLast('.', "").lowercase()
            val declared = resolver.getType(uri).orEmpty().substringBefore(';')
            require(extension in setOf("txt", "md", "csv", "json") || declared in setOf("text/plain", "text/markdown", "text/csv", "application/json")) {
                "Pilih gambar PNG, JPEG, GIF, WebP atau dokumen TXT, MD, CSV, JSON. PDF belum didukung."
            }
            require(bytes.size <= MAX_TEXT_BYTES) { "Dokumen teks maksimal 256 KB." }
            data = decodeText(bytes)
            mimeType = "text/plain"
        }
        ChatAttachment(UUID.randomUUID().toString(), name, mimeType, bytes.size.toLong(), data)
    }

    companion object {
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_TEXT_BYTES = 256 * 1024
        const val MAX_TOTAL_BYTES = 12 * 1024 * 1024
        const val MAX_ATTACHMENTS = 4

        fun detectImage(bytes: ByteArray): String? {
            fun starts(vararg prefix: Int) = bytes.size >= prefix.size && prefix.indices.all { (bytes[it].toInt() and 255) == prefix[it] }
            return when {
                starts(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
                starts(0xFF, 0xD8, 0xFF) -> "image/jpeg"
                bytes.size >= 6 && bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
                bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" && bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
                else -> null
            }
        }

        fun decodeText(bytes: ByteArray): String {
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: java.nio.charset.CharacterCodingException) {
                throw ApiError.Validation("Dokumen harus berupa teks UTF-8.")
            }
            require(text.none { it.isISOControl() && it !in "\n\r\t" }) { "Berkas ini bukan dokumen teks yang didukung." }
            return text.removePrefix("\uFEFF")
        }
    }
}
