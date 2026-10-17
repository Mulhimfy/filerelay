@file:OptIn(ExperimentalMaterial3Api::class)

package com.mulhimfy.filerelay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

private fun WaCat.icon(): ImageVector = when (this) {
    WaCat.RECENT -> Icons.Filled.History
    WaCat.DOCUMENTS -> Icons.Filled.Description
    WaCat.IMAGES -> Icons.Filled.Image
    WaCat.VIDEOS -> Icons.Filled.Movie
    WaCat.AUDIO -> Icons.Filled.MusicNote
    WaCat.VOICE -> Icons.Filled.Mic
    WaCat.STATUSES -> Icons.Filled.AutoAwesome
    WaCat.GIFS -> Icons.Filled.Gif
    WaCat.STICKERS -> Icons.Filled.EmojiEmotions
}

@Composable
fun WhatsAppScreen(hasAccess: Boolean, onGrant: () -> Unit) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    val apps = remember(hasAccess) { if (hasAccess) WhatsApp.installed() else emptyList() }
    var app by rememberSaveable { mutableStateOf(WaApp.WHATSAPP) }
    var cat by rememberSaveable { mutableStateOf(WaCat.RECENT) }
    var includeSent by remember { mutableStateOf(Prefs.includeSent(ctx)) }
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var items by remember { mutableStateOf<List<FileItem>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var resumedOnce by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selection = remember { mutableStateMapOf<String, FileItem>() }

    LaunchedEffect(apps) { if (apps.isNotEmpty() && app !in apps) app = apps.first() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (resumedOnce) reload++ else resumedOnce = true }
    LaunchedEffect(hasAccess, app, cat, includeSent, reload) {
        if (!hasAccess) return@LaunchedEffect
        val key = "$app/$cat/$includeSent"
        items = WhatsApp.cache[key]
        loading = true
        val fresh = WhatsApp.scan(app, cat, includeSent)
        WhatsApp.cache[key] = fresh
        items = fresh
        loading = false
    }
    val shown = remember(items, query) {
        val q = query.trim()
        items?.let { list -> if (q.isEmpty()) list else list.filter { it.name.contains(q, ignoreCase = true) } }
    }
    BackHandler(enabled = selection.isNotEmpty() || searching) {
        if (selection.isNotEmpty()) selection.clear() else { searching = false; query = "" }
    }

    Column(Modifier.fillMaxSize()) {
        if (selection.isNotEmpty()) {
            SelectionTopBar(
                count = selection.size,
                onClose = { selection.clear() },
                onSelectAll = { shown?.forEach { selection[it.key] = it } },
                onTelegram = { actions.telegram(selection.values.toList()); selection.clear() },
                onShare = { actions.share(selection.values.toList()); selection.clear() },
                onSave = { actions.save(selection.values.toList()); selection.clear() },
                onDelete = { confirmDelete = true },
            )
        } else {
            TopAppBar(
                title = { if (searching) SearchField(query, { query = it }, "Search WhatsApp files") else Text("WhatsApp files") },
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(if (searching) Icons.Filled.Close else Icons.Filled.Search, "Search")
                    }
                    if (!searching) IconButton(onClick = { reload++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Include files I sent") },
                                leadingIcon = { Checkbox(checked = includeSent, onCheckedChange = null) },
                                onClick = {
                                    includeSent = !includeSent
                                    Prefs.setIncludeSent(ctx, includeSent)
                                    menu = false
                                },
                            )
                        }
                    }
                },
            )
        }

        when {
            !hasAccess -> AccessCard(onGrant)
            apps.isEmpty() -> EmptyState(
                Icons.Filled.Folder,
                "No WhatsApp folder found",
                "WhatsApp stores files on your phone once they're downloaded in a chat. " +
                    "Tap a file in WhatsApp to download it, then come back here.",
            )
            else -> {
                if (apps.size > 1) {
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        apps.forEach { a ->
                            FilterChip(selected = app == a, onClick = { app = a; selection.clear() }, label = { Text(a.label) })
                        }
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(WaCat.entries) { c ->
                        FilterChip(
                            selected = cat == c,
                            onClick = { cat = c; selection.clear() },
                            label = { Text(c.label) },
                            leadingIcon = { Icon(c.icon(), null, Modifier.size(18.dp)) },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(4.dp)) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                val list = shown
                when {
                    list == null -> CenteredProgress()
                    list.isEmpty() -> EmptyState(
                        Icons.Filled.Inbox,
                        if (query.isBlank()) "Nothing here yet" else "No matches",
                        if (query.isBlank()) "Files show up here after WhatsApp downloads them." else "Try another search.",
                    )
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(list, key = { it.key }) { item ->
                            FileRow(
                                item = item,
                                selected = selection.containsKey(item.key),
                                selectionMode = selection.isNotEmpty(),
                                onClick = { if (selection.isNotEmpty()) toggle(selection, item.key, item) else actions.open(item) },
                                onLongClick = { toggle(selection, item.key, item) },
                                onSend = { actions.telegram(listOf(item)) },
                            )
                        }
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
