@file:OptIn(ExperimentalMaterial3Api::class)

package com.mulhimfy.filerelay

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun clipboardText(ctx: Context): String? = runCatching {
    ctx.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
}.getOrNull()

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    var url by rememberSaveable { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Dl>?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        while (true) {
            val fresh = withContext(Dispatchers.IO) { Downloader.list(ctx) }
            list = fresh
            delay(if (fresh.any { it.active }) 700L else 3000L)
        }
    }

    val link = Downloader.findUrl(url)
    fun start() {
        val u = link ?: return
        actions.download(u) { url = ""; tick++ }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Downloads") })
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            label = { Text("Link to download") },
            placeholder = { Text("https://…") },
            leadingIcon = { Icon(Icons.Filled.Link, null) },
            trailingIcon = {
                IconButton(onClick = { clipboardText(ctx)?.let { url = Downloader.findUrl(it) ?: it } }) {
                    Icon(Icons.Filled.ContentPaste, "Paste")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { start() }),
        )
        Button(
            onClick = { start() },
            enabled = link != null,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(48.dp),
        ) {
            Icon(Icons.Filled.Download, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(8.dp))
            Text("Download to Download/${Storage.RELAY_FOLDER}")
        }

        val items = list
        when {
            items == null -> CenteredProgress()
            items.isEmpty() -> EmptyState(
                Icons.Filled.CloudDownload,
                "No downloads yet",
                "Paste a link above, or share a link to FileRelay from WhatsApp or your browser.",
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { d ->
                    DownloadRow(
                        d = d,
                        onOpen = { Downloader.item(ctx, d)?.let { actions.open(it) } ?: ctx.toast("File not found") },
                        onSend = { Downloader.item(ctx, d)?.let { actions.telegram(listOf(it)) } ?: ctx.toast("File not found") },
                        onShare = { Downloader.item(ctx, d)?.let { actions.share(listOf(it)) } ?: ctx.toast("File not found") },
                        onForget = { Downloader.forget(ctx, d.id); tick++ },
                        onCancel = { Downloader.cancel(ctx, d.id); tick++ },
                        onRetry = { Downloader.forget(ctx, d.id); actions.download(d.url) { tick++ } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    d: Dl,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onShare: () -> Unit,
    onForget: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val icon = remember(d.title) { FileItem(name = d.title, uri = Uri.EMPTY) }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = d.ok, onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(icon, preview = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                d.statusText(),
                style = MaterialTheme.typography.bodySmall,
                color = if (d.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (d.active) {
                Spacer(Modifier.height(6.dp))
                if (d.progress >= 0f) LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
        when {
            d.ok -> {
                IconButton(onClick = onSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send to Telegram", tint = MaterialTheme.colorScheme.primary)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open") },
                            leadingIcon = { Icon(Icons.Filled.Visibility, null) },
                            onClick = { menu = false; onOpen() },
                        )
                        DropdownMenuItem(
                            text = { Text("Share…") },
                            leadingIcon = { Icon(Icons.Filled.Share, null) },
                            onClick = { menu = false; onShare() },
                        )
                        DropdownMenuItem(
                            text = { Text("Remove from list") },
                            leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) },
                            onClick = { menu = false; onForget() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete file") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = { menu = false; onCancel() },
                        )
                    }
                }
            }
            d.active -> IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel download") }
            else -> {
                IconButton(onClick = onRetry) { Icon(Icons.Filled.Refresh, "Retry") }
                IconButton(onClick = onForget) { Icon(Icons.Filled.Close, "Remove") }
            }
        }
    }
}
