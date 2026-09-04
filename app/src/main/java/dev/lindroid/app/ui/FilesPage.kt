package dev.lindroid.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lindroid.app.SharedEntry
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FilesPage(
    entries: List<SharedEntry>,
    path: String,
    notice: String?,
    onRefresh: () -> Unit,
    onOpenDirectory: (SharedEntry) -> Unit,
    onUp: () -> Unit,
    onImport: () -> Unit,
    onExport: (SharedEntry) -> Unit,
    onDelete: (SharedEntry) -> Unit,
    onDismissNotice: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<SharedEntry?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Files", style = MaterialTheme.typography.displaySmall)
        Text(
            "Everything in this folder shows up inside Debian at /root/storage. Import files from your phone, and save Linux files back out.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        notice?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = onDismissNotice) { Icon(Icons.Default.Close, "Dismiss") }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(onClick = onImport) {
                Icon(Icons.Default.FileUpload, null)
                Spacer(Modifier.size(8.dp))
                Text("Import from phone")
            }
            if (path.isNotBlank()) {
                OutlinedButton(onClick = onUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Up one level")
                }
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            } else {
                OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Text(
                "Folder: /root/storage${path.split('/').filter { it.isNotBlank() }.joinToString("/", "/")}",
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            )
        }

        if (entries.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(topStart = 44.dp, topEnd = 18.dp, bottomEnd = 44.dp, bottomStart = 18.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Folder, null, Modifier.size(36.dp))
                    Text("This folder is empty", style = MaterialTheme.typography.titleLarge)
                    Text("Import files here and they will be available inside Debian at /root/storage.")
                }
            }
        } else {
            entries.forEach { entry ->
                SharedRow(
                    entry = entry,
                    onOpen = { if (entry.isDirectory) onOpenDirectory(entry) },
                    onExport = { onExport(entry) },
                    onDelete = { deleteTarget = entry },
                )
            }
        }
        Spacer(Modifier.size(8.dp))
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(if (target.isDirectory) "Delete folder?" else "Delete file?") },
            text = { Text("${target.name} will be removed from the shared folder. Debian sees the same deletion.") },
            confirmButton = {
                Button(onClick = {
                    onDelete(target)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun SharedRow(
    entry: SharedEntry,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, top = 10.dp, bottom = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (entry.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                null,
                Modifier.size(26.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (entry.isDirectory) "Folder" else "${formatSize(entry.size)} • ${formatDate(entry.lastModified)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!entry.isDirectory) {
                IconButton(onClick = onExport) { Icon(Icons.Default.FileDownload, "Save to phone") }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024f * 1024f * 1024f))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024f)
    else -> "$bytes B"
}

private fun formatDate(timestamp: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(timestamp))
