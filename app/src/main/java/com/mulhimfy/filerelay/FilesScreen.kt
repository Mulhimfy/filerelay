@file:OptIn(ExperimentalMaterial3Api::class)

package com.mulhimfy.filerelay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.io.File

private fun shortcutIcon(label: String): ImageVector = when (label) {
    "Downloads" -> Icons.Filled.Download
    "FileRelay" -> Icons.Filled.Inventory2
    "WhatsApp" -> Icons.Filled.Chat
    "Telegram" -> Icons.AutoMirrored.Filled.Send
    "Camera" -> Icons.Filled.PhotoCamera
    "Documents" -> Icons.Filled.Description
    else -> Icons.Filled.Folder
}

@Composable
fun FilesScreen(hasAccess: Boolean, onGrant: () -> Unit) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    val volumes = remember(hasAccess) { Storage.volumes(ctx) }
    val shortcuts = remember(hasAccess) { if (hasAccess) Storage.shortcuts() else emptyList() }
    var path by rememberSaveable { mutableStateOf(Storage.primary.absolutePath) }
    var sort by rememberSaveable { mutableStateOf(Sort.NAME) }
    var showHidden by remember { mutableStateOf(Prefs.showHidden(ctx)) }
    var listing by remember { mutableStateOf<Pair<String, List<FileItem>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var resumedOnce by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selection = remember { mutableStateMapOf<String, FileItem>() }
    val scrolls = remember { HashMap<String, LazyListState>() }

    val dir = File(path)
    val volume = volumes.filter { path == it.second.absolutePath || path.startsWith(it.second.absolutePath + "/") }
        .maxByOrNull { it.second.absolutePath.length } ?: volumes.first()
    val volumeRoot = volume.second.absolutePath
    val atRoot = path == volumeRoot

    fun go(p: String) {
        selection.clear()
        path = p
    }

    fun up() {
        val parent = dir.parentFile?.absolutePath
        go(if (parent != null && (parent == volumeRoot || parent.startsWith("$volumeRoot/"))) parent else volumeRoot)
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (resumedOnce) reload++ else resumedOnce = true }
    LaunchedEffect(hasAccess, path, sort, showHidden, reload) {
        if (hasAccess) listing = path to Storage.list(File(path), showHidden, sort)
    }
    BackHandler(enabled = selection.isNotEmpty() || !atRoot) {
        if (selection.isNotEmpty()) selection.clear() else up()
    }

    Column(Modifier.fillMaxSize()) {
        val current = listing?.takeIf { it.first == path }?.second
        if (selection.isNotEmpty()) {
            SelectionTopBar(
                count = selection.size,
                onClose = { selection.clear() },
                onSelectAll = { current?.forEach { selection[it.key] = it } },
                onTelegram = { actions.telegram(selection.values.toList()); selection.clear() },
                onShare = { actions.share(selection.values.toList()); selection.clear() },
                onSave = { actions.save(selection.values.toList()); selection.clear() },
                onDelete = { confirmDelete = true },
            )
        } else {
            TopAppBar(
                navigationIcon = {
                    if (!atRoot) IconButton(onClick = { up() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Up") }
                },
                title = {
                    Column {
                        Text(if (atRoot) volume.first else dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!atRoot) {
                            Text(
                                path.removePrefix(volumeRoot).trimStart('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text("Sort: ${s.label}") },
                                    leadingIcon = { RadioButton(selected = sort == s, onClick = null) },
                                    onClick = { sort = s; menu = false },
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Show hidden files") },
                                leadingIcon = { Checkbox(checked = showHidden, onCheckedChange = null) },
                                onClick = {
                                    showHidden = !showHidden
                                    Prefs.setShowHidden(ctx, showHidden)
                                    menu = false
                                },
                            )
                        }
                    }
                },
            )
        }

        if (!hasAccess) {
            AccessCard(onGrant)
            return@Column
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            items(volumes + shortcuts, key = { it.second.absolutePath }) { (label, file) ->
                val icon = when {
                    file == Storage.primary -> Icons.Filled.PhoneAndroid
                    volumes.any { it.second == file } -> Icons.Filled.SdCard
                    else -> shortcutIcon(label)
                }
                FilterChip(
                    selected = path == file.absolutePath,
                    onClick = { go(file.absolutePath) },
                    label = { Text(label) },
                    leadingIcon = { Icon(icon, null, Modifier.size(18.dp)) },
                )
            }
        }

        when {
            current == null -> CenteredProgress()
            current.isEmpty() -> EmptyState(Icons.Filled.FolderOpen, "This folder is empty", null)
            else -> {
                val state = scrolls.getOrPut(path) { LazyListState() }
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(current, key = { it.key }) { item ->
                        FileRow(
                            item = item,
                            selected = selection.containsKey(item.key),
                            selectionMode = selection.isNotEmpty(),
                            onClick = {
                                when {
                                    selection.isNotEmpty() -> toggle(selection, item.key, item)
                                    item.isDir -> go(item.key)
                                    else -> actions.open(item)
                                }
                            },
                            onLongClick = { toggle(selection, item.key, item) },
                            onSend = if (item.isDir) null else ({ actions.telegram(listOf(item)) }),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            count = selection.size,
            onConfirm = {
                val doomed = selection.values.toList()
                selection.clear()
                confirmDelete = false
                actions.delete(doomed) { reload++ }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
