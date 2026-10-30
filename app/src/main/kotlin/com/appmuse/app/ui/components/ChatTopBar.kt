package com.appmuse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Toll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.appmuse.app.model.TokenUsage
import com.appmuse.app.ui.theme.AppSpacing
import java.text.NumberFormat
import java.util.Locale

/** Native centered island, respecting system status insets and large font sizes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    usage: TokenUsage?,
    streaming: Boolean,
    onOpenSidebar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var showUsage by rememberSaveable { mutableStateOf(false) }
    val number = NumberFormat.getIntegerInstance(Locale("id", "ID"))
    fun count(value: Long?) = value?.let(number::format) ?: "—"
    val total = count(usage?.totalTokens)
    CenterAlignedTopAppBar(
        title = {
            Surface(
                onClick = { showUsage = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.heightIn(min = AppSpacing.xxl).semantics {
                    contentDescription = "Penggunaan token: $total. Ketuk untuk rincian."
                },
            ) {
                Row(
                    Modifier.padding(horizontal = AppSpacing.base, vertical = AppSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Toll, contentDescription = null)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$total token", style = MaterialTheme.typography.titleSmall)
                        Text(if (streaming) "Menerima balasan…" else "Total percakapan", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenSidebar) {
                Icon(Icons.Filled.Menu, contentDescription = "Buka sidebar percakapan")
            }
        },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Buka pengaturan")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            navigationIconContentColor = MaterialTheme.colorScheme.primary,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
    if (showUsage) AlertDialog(
        onDismissRequest = { showUsage = false },
        title = { Text("Penggunaan token") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                Text("Total dalam percakapan ini", style = MaterialTheme.typography.titleSmall)
                Text("Masukan: ${count(usage?.promptTokens)}")
                Text("Keluaran: ${count(usage?.completionTokens)}")
                Text("Total: $total", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Angka aktual dilaporkan penyedia, biasanya setelah balasan selesai. Tanda — berarti belum tersedia, penyedia tidak melaporkan token, atau balasan dihentikan sebelum laporan diterima.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { showUsage = false }) { Text("Tutup") } },
    )
}
