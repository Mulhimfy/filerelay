@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.mulhimfy.filerelay

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay

fun kindIcon(kind: Kind): ImageVector = when (kind) {
    Kind.FOLDER -> Icons.Filled.Folder
    Kind.IMAGE -> Icons.Filled.Image
    Kind.VIDEO -> Icons.Filled.Movie
    Kind.AUDIO -> Icons.Filled.MusicNote
    Kind.PDF -> Icons.Filled.PictureAsPdf
    Kind.TEXT, Kind.DOC -> Icons.Filled.Description
    Kind.ZIP -> Icons.Filled.Archive
    Kind.APK -> Icons.Filled.Android
    Kind.SHEET -> Icons.Filled.TableChart
    Kind.SLIDES -> Icons.Filled.Slideshow
    Kind.OTHER -> Icons.Filled.InsertDriveFile
}

fun kindColor(kind: Kind): Color = when (kind) {
    Kind.FOLDER -> Color(0xFFE8A317)
    Kind.IMAGE -> Color(0xFF7C4DFF)
    Kind.VIDEO -> Color(0xFFE53935)
    Kind.AUDIO -> Color(0xFFFF7043)
    Kind.PDF -> Color(0xFFD32F2F)
    Kind.TEXT -> Color(0xFF607D8B)
    Kind.ZIP -> Color(0xFF8D6E63)
    Kind.APK -> Color(0xFF43A047)
    Kind.DOC -> Color(0xFF1E88E5)
    Kind.SHEET -> Color(0xFF2E7D32)
    Kind.SLIDES -> Color(0xFFF57C00)
    Kind.OTHER -> Color(0xFF78909C)
}

/** Picture for images/videos, colored type icon for everything else. */
@Composable
fun Thumb(item: FileItem, size: Dp = 48.dp, preview: Boolean = true) {
    val shape = RoundedCornerShape(size / 4)
    val kind = item.kind
    if (preview && (kind == Kind.IMAGE || kind == Kind.VIDEO)) {
        Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Icon(kindIcon(kind), null, Modifier.align(Alignment.Center), tint = kindColor(kind).copy(alpha = 0.5f))
            AsyncImage(
                model = item.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (kind == Kind.VIDEO) {
                Icon(
                    Icons.Filled.PlayArrow, null,
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.Center).size(size / 2.4f)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(2.dp),
                )
            }
        }
    } else {
        val color = kindColor(kind)
        Box(Modifier.size(size).clip(shape).background(color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(kindIcon(kind), null, tint = color, modifier = Modifier.size(size / 2))
        }
    }
}

fun subtitle(item: FileItem): String {
    val first = if (item.isDir) {
        if (item.children >= 0) "${item.children} item${if (item.children == 1) "" else "s"}" else "Folder"
    } else formatSize(item.size)
    return listOf(first, formatTime(item.modified)).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSend: (() -> Unit)?,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumb(item)
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle, "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(20.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (onSend != null && !item.isDir && !selectionMode) {
            IconButton(onClick = onSend) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send to Telegram", tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

fun <T> toggle(selection: MutableMap<String, T>, key: String, value: T) {
    if (selection.remove(key) == null) selection[key] = value
}

@Composable
fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onTelegram: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Clear selection") } },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onTelegram) { Icon(Icons.AutoMirrored.Filled.Send, "Send to Telegram") }
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Share") }
            IconButton(onClick = onSave) { Icon(Icons.Filled.Download, "Save to Downloads") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Select all") },
                        leadingIcon = { Icon(Icons.Filled.SelectAll, null) },
                        onClick = { menu = false; onSelectAll() },
                    )
                    if (onDelete != null) {
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onDelete() },
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    )
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
fun AccessCard(onGrant: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.FolderOpen,
        title = "Allow file access",
        text = "FileRelay needs \"All files access\" to read WhatsApp's folders and browse your storage. " +
            "Nothing leaves your phone unless you send it.",
        action = "Allow access",
        onAction = onGrant,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String?, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun CenteredProgress(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** Full-screen "working…" card; only shows if the task takes longer than a moment. */
@Composable
fun BusyOverlay(label: String) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(label) { delay(250); visible = true }
    Box(
        Modifier.fillMaxSize()
            .background(if (visible) Color.Black.copy(alpha = 0.35f) else Color.Transparent)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 6.dp, shadowElevation = 6.dp) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
fun DeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $count item${if (count == 1) "" else "s"}?") },
        text = { Text("They'll be removed from your phone. This can't be undone.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun AppIcon(pkg: String?, size: Dp = 40.dp) {
    val ctx = LocalContext.current
    val bmp = remember(pkg) {
        pkg?.let { runCatching { ctx.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    if (bmp != null) Image(bmp, null, Modifier.size(size))
    else Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(size), tint = MaterialTheme.colorScheme.primary)
}
