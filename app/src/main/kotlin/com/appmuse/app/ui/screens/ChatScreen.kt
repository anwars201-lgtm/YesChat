// APPBUILDER-NATIVE-CAPABILITY:chat.send_and_stream
// APPBUILDER-NATIVE-CAPABILITY:chat.stop_keep_partial
// APPBUILDER-NATIVE-CAPABILITY:chat.switch_profile
// APPBUILDER-NATIVE-CAPABILITY:chat.copy_content
// APPBUILDER-NATIVE-CAPABILITY:navigation.chat
package com.appmuse.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.appmuse.app.ui.components.ChatComposer
import com.appmuse.app.ui.components.ModelDialog
import com.appmuse.app.ui.components.ReasoningDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appmuse.app.R
import com.appmuse.app.model.ConversationMessage
import com.appmuse.app.ui.ChatUiState
import com.appmuse.app.ui.ChatViewModel
import com.appmuse.app.ui.components.EmptyState
import com.appmuse.app.ui.components.ErrorView
import com.appmuse.app.ui.components.SkeletonList
import com.appmuse.app.ui.theme.AppSpacing
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(
    vm: ChatViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val actionError by vm.actionError.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showReasoning by rememberSaveable { mutableStateOf(false) }
    val attachments by vm.attachments.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::attachFile)
    }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val copyLabel = stringResource(R.string.appmuse_action_chat_copy)

    DisposableEffect(vm) { onDispose { vm.stopKeepPartial() } }
    LaunchedEffect(notice) {
        notice?.let {
            vm.consumeNotice()
            snackbar.showSnackbar(it)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        when (val current = state) {
            ChatUiState.Loading -> Box(Modifier.weight(1f)) { SkeletonList() }
            is ChatUiState.Error -> Column(Modifier.weight(1f)) {
                ErrorView(
                    message = current.message,
                    title = "Chat belum dapat dibuka",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::load, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Coba lagi") }
            }
            is ChatUiState.Success -> {
                val messages = current.conversation?.messages.orEmpty()
                if (messages.isEmpty()) {
                    EmptyState(
                        title = stringResource(R.string.appmuse_feature_chat_title),
                        message = if (current.selectedProfile == null) "Tambahkan profil di Pengaturan. API Key hanya dikirim langsung ke penyedia pilihan Anda."
                        else "Tulis pesan pertama Anda. Riwayat tersimpan di perangkat dan dikirim ke penyedia saat meminta balasan.",
                        icon = Icons.AutoMirrored.Filled.Chat,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    BubbleList(
                        messages = messages,
                        modifier = Modifier.weight(1f),
                        onCopy = { content ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText(copyLabel, vm.copyContent(content)))
                        },
                    )
                }
                actionError?.let {
                    Text(
                        it,
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(AppSpacing.md),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                if (streaming) {
                    Text(
                        "Menerima balasan…",
                        Modifier.padding(horizontal = AppSpacing.base),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                ChatComposer(
                    draft = draft,
                    onDraftChange = vm::setDraft,
                    busy = busy,
                    streaming = streaming,
                    model = current.selectedProfile?.model,
                    reasoning = current.selectedProfile?.reasoningEffort,
                    attachments = attachments,
                    onSend = vm::sendAndStream,
                    onStop = vm::stopKeepPartial,
                    onReasoning = { showReasoning = true },
                    onModel = { showModels = true; vm.loadModels() },
                    onAttach = { filePicker.launch(arrayOf("image/png", "image/jpeg", "image/gif", "image/webp", "text/plain", "text/markdown", "text/csv", "application/json")) },
                    onRemoveAttachment = vm::removeAttachment,
                )
                if (showReasoning && current.selectedProfile != null) ReasoningDialog(
                    selected = current.selectedProfile.reasoningEffort,
                    onSelect = { vm.setReasoning(it); showReasoning = false },
                    onDismiss = { showReasoning = false },
                )
                if (showModels && current.selectedProfile != null) ModelDialog(
                    selected = current.selectedProfile.model,
                    state = models,
                    onRetry = vm::loadModels,
                    onSelect = { vm.setModel(it); showModels = false },
                    onDismiss = { showModels = false },
                )
            }
        }
    }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.TopCenter).padding(AppSpacing.base),
        )
    }
}

@Composable
private fun BubbleList(
    messages: List<ConversationMessage>,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    val listState = rememberLazyListState()
    val nearBottom = !listState.canScrollForward
    LaunchedEffect(messages.size, messages.lastOrNull()?.content, messages.lastOrNull()?.status) {
        if (messages.isNotEmpty() && (nearBottom || messages.last().status == "streaming" && messages.last().content.isEmpty())) {
            listState.scrollToItem(messages.lastIndex)
        }
    }
    // Keep the conversation glued to the bottom when the viewport shrinks or grows
    // (the keyboard opening/closing resizes the window): remember whether the user
    // had the list at the bottom - updated only while THEY scroll - and re-anchor
    // to the end of the last message on every viewport height change.
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canForward) -> if (scrolling) stickToBottom = !canForward }
    }
    LaunchedEffect(listState.layoutInfo.viewportSize.height, messages.size) {
        if (messages.isNotEmpty() && stickToBottom) {
            listState.scrollToItem(messages.lastIndex, Int.MAX_VALUE)
        }
    }
    BoxWithConstraints(modifier) {
        val maxBubbleWidth = maxWidth * 0.75f
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(AppSpacing.base),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
        ) {
            itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
                val outgoing = message.role == "user"
                val locale = Locale("id", "ID")
                val dayFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
                val day = dayFormat.format(Date(message.createdAt))
                if (index == 0 || dayFormat.format(Date(messages[index - 1].createdAt)) != day) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = AppSpacing.sm),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ) {
                            Text(
                                day,
                                Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    ElevatedCard(
                        modifier = Modifier.widthIn(max = maxBubbleWidth),
                        shape = MaterialTheme.shapes.large.copy(
                            bottomStart = if (outgoing) MaterialTheme.shapes.large.bottomStart else CornerSize(AppSpacing.xs),
                            bottomEnd = if (outgoing) CornerSize(AppSpacing.xs) else MaterialTheme.shapes.large.bottomEnd,
                        ),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = if (outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                            contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Column(Modifier.padding(AppSpacing.md), verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)) {
                            Text(if (outgoing) "Anda" else "AI", style = MaterialTheme.typography.labelMedium)
                            message.attachments.forEach { attachment ->
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                ) {
                                    Text(
                                        "Lampiran: ${attachment.name}",
                                        Modifier.padding(AppSpacing.sm),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            if (message.content.isEmpty() && message.status == "streaming") {
                                LinearProgressIndicator()
                            } else {
                                if (outgoing) {
                                    SelectionContainer {
                                        Text(message.content, style = MaterialTheme.typography.bodyMedium)
                                    }
                                } else {
                                    MarkdownMessage(
                                        content = message.content.ifEmpty { "Tidak ada teks balasan." },
                                        onCopyCode = onCopy,
                                    )
                                }
                            }
                            Text(
                                DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(message.createdAt)) + when (message.status) {
                                    "pending" -> " · Menyimpan…"
                                    "streaming" -> " · Menerima…"
                                    "stopped" -> " · Dihentikan"
                                    "failed" -> " · Gagal; teks parsial disimpan"
                                    "interrupted" -> " · Terputus; teks parsial disimpan"
                                    else -> " · Tersimpan"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                            if (message.content.isNotEmpty() && message.status != "streaming") {
                                TextButton(onClick = { onCopy(message.content) }) {
                                    val color = if (outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = color)
                                    Text(stringResource(R.string.appmuse_action_chat_copy), Modifier.padding(start = AppSpacing.sm), color = color)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class MessageMarkdownBlock(
    val kind: String,
    val text: String,
    val label: String = "",
    val level: Int = 0,
)

private val markdownFence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
private val markdownHeading = Regex("^ {0,3}(#{1,6})\\s+(.+)$")
private val markdownListEntry = Regex("^\\s*([-+*]|\\d+[.)])\\s+(.+)$")
private val markdownRule = Regex("^ {0,3}(?:\\*\\s*){3,}$|^ {0,3}(?:-\\s*){3,}$|^ {0,3}(?:_\\s*){3,}$")
private val markdownInline = Regex(
    """\\([\\`*{}\[\]()#+.!_>~-])|`([^`\n]+)`|\[([^\]\n]+)]\((https?://[^\s)]+|mailto:[^\s)]+)\)|\*\*(.+?)\*\*|__(.+?)__|~~(.+?)~~|\*([^*\n]+)\*|_([^_\n]+)_""",
)

private fun parseMessageMarkdown(content: String): List<MessageMarkdownBlock> {
    val lines = content.replace("\r\n", "\n").lines()
    val blocks = mutableListOf<MessageMarkdownBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MessageMarkdownBlock("paragraph", paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val fence = markdownFence.matchEntire(line)
        val heading = markdownHeading.matchEntire(line)
        val listEntry = markdownListEntry.matchEntire(line)
        when {
            fence != null -> {
                flushParagraph()
                val delimiter = fence.groupValues[1]
                val code = mutableListOf<String>()
                index++
                while (index < lines.size) {
                    val closing = lines[index].trim()
                    if (closing.length >= delimiter.length && closing.all { it == delimiter.first() }) break
                    code += lines[index++]
                }
                // An unclosed fence still renders while a reply streams or after Stop.
                blocks += MessageMarkdownBlock("code", code.joinToString("\n"), fence.groupValues[2].trim())
            }
            line.isBlank() -> flushParagraph()
            heading != null -> {
                flushParagraph()
                blocks += MessageMarkdownBlock("heading", heading.groupValues[2].trimEnd().replace(Regex("\\s+#+$"), ""), level = heading.groupValues[1].length)
            }
            markdownRule.matches(line) -> {
                flushParagraph()
                blocks += MessageMarkdownBlock("rule", "")
            }
            line.trimStart().startsWith(">") -> {
                flushParagraph()
                blocks += MessageMarkdownBlock("quote", line.trimStart().removePrefix(">").trimStart())
            }
            listEntry != null -> {
                flushParagraph()
                val marker = listEntry.groupValues[1]
                blocks += MessageMarkdownBlock("list", listEntry.groupValues[2], if (marker.first().isDigit()) marker else "•")
            }
            else -> paragraph += line
        }
        index++
    }
    flushParagraph()
    return blocks
}

@Composable
private fun MarkdownMessage(content: String, onCopyCode: (String) -> Unit) {
    val blocks = remember(content) { parseMessageMarkdown(content) }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        blocks.forEach { block ->
            when (block.kind) {
                "code" -> Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceContainerHighest,
                    contentColor = colors.onSurface,
                ) {
                    Column(Modifier.padding(AppSpacing.sm)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                block.label.ifBlank { "Kode" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            TextButton(
                                onClick = { onCopyCode(block.text) },
                                enabled = block.text.isNotEmpty(),
                                modifier = Modifier.heightIn(min = AppSpacing.xxl),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                                Text("Salin kode", Modifier.padding(start = AppSpacing.xs))
                            }
                        }
                        SelectionContainer {
                            Text(
                                block.text,
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                softWrap = false,
                            )
                        }
                    }
                }
                "rule" -> HorizontalDivider(color = colors.outlineVariant)
                "list", "quote" -> Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                    Text(
                        if (block.kind == "quote") "│" else block.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.primary,
                    )
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(
                            markdownText(block.text, colors.onSurface, colors.surfaceContainerHighest),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = if (block.kind == "quote") FontStyle.Italic else FontStyle.Normal,
                        )
                    }
                }
                else -> SelectionContainer {
                    Text(
                        markdownText(block.text, colors.onSurface, colors.surfaceContainerHighest),
                        style = when {
                            block.kind != "heading" -> MaterialTheme.typography.bodyMedium
                            block.level == 1 -> MaterialTheme.typography.headlineSmall
                            block.level == 2 -> MaterialTheme.typography.titleLarge
                            else -> MaterialTheme.typography.titleMedium
                        },
                        fontWeight = if (block.kind == "heading") FontWeight.Bold else null,
                    )
                }
            }
        }
    }
}

private fun markdownText(text: String, codeColor: Color, codeBackground: Color): AnnotatedString =
    buildAnnotatedString { appendMarkdownInline(text, codeColor, codeBackground) }

private fun AnnotatedString.Builder.appendMarkdownInline(
    text: String,
    codeColor: Color,
    codeBackground: Color,
    depth: Int = 0,
) {
    if (depth >= 8) {
        append(text)
        return
    }
    var end = 0
    markdownInline.findAll(text).forEach { match ->
        append(text.substring(end, match.range.first))
        val groups = match.groupValues
        when {
            groups[1].isNotEmpty() -> append(groups[1])
            groups[2].isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = codeBackground)) { append(groups[2]) }
            groups[3].isNotEmpty() -> withLink(
                LinkAnnotation.Url(groups[4], TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline))),
            ) { appendMarkdownInline(groups[3], codeColor, codeBackground, depth + 1) }
            else -> {
                val value = groups.drop(5).first { it.isNotEmpty() }
                val style = when {
                    groups[5].isNotEmpty() || groups[6].isNotEmpty() -> SpanStyle(fontWeight = FontWeight.Bold)
                    groups[7].isNotEmpty() -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    else -> SpanStyle(fontStyle = FontStyle.Italic)
                }
                withStyle(style) { appendMarkdownInline(value, codeColor, codeBackground, depth + 1) }
            }
        }
        end = match.range.last + 1
    }
    append(text.substring(end))
}
