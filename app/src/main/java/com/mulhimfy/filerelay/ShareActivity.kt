package com.mulhimfy.filerelay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Receives shares. Launched as:
 *  - "FileRelay": a sheet with every option,
 *  - "Relay to Telegram" (alias): straight to the quick-send app,
 *  - "Save to Downloads" (alias): saves and closes (links get downloaded).
 */
class ShareActivity : RelayActivity() {
    enum class Mode { SHEET, TELEGRAM, SAVE }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val mode = when (intent.component?.className?.substringAfterLast('.')) {
            "ShareToTelegram" -> Mode.TELEGRAM
            "ShareSave" -> Mode.SAVE
            else -> Mode.SHEET
        }
        val uris = incomingUris(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
        if (uris.isEmpty() && text == null) {
            toast("Nothing to relay")
            finish()
            return
        }
        Thread { Stage.cleanup(applicationContext) }.start()
        val type = intent.type
        setContent { RelayTheme { ShareFlow(this, mode, uris, text, type) } }
    }

    private fun incomingUris(intent: Intent): List<Uri> {
        val out = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { out += it }
        }
        if (out.isEmpty()) {
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { out += it } }
        }
        return out.toList()
    }
}

@Composable
private fun ShareFlow(activity: ShareActivity, mode: ShareActivity.Mode, uris: List<Uri>, text: String?, type: String?) {
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(activity, scope) }
    var items by remember { mutableStateOf<List<FileItem>?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var saved by remember { mutableStateOf(false) }
    val link = remember(text) { Downloader.findUrl(text) }
    val quickName = remember { Targets.quick(activity)?.let { Targets.label(activity, it) } ?: "Telegram" }
    val close: () -> Unit = { activity.finish() }

    LaunchedEffect(Unit) {
        if (mode == ShareActivity.Mode.SHEET) {
            try {
                items = Stage.incoming(activity, uris, type) { progress = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast("Couldn't read the shared file: ${e.message}")
                close()
            }
            return@LaunchedEffect
        }
        try {
            if (mode == ShareActivity.Mode.TELEGRAM) {
                val list = Stage.incoming(activity, uris, type) { progress = it }
                Relay.quickSend(activity, list, text.takeIf { list.isEmpty() })
            } else if (uris.isEmpty() && link != null) {
                if (!activity.ensureDownloadsWrite()) throw IOException("Storage permission is needed to download")
                activity.toast("Downloading ${Downloader.start(activity, link)}")
            } else {
                val list = withContext(Dispatchers.IO) {
                    if (uris.isNotEmpty()) Stage.describe(activity, uris, type)
                    else listOf(Stage.textFile(activity, text.orEmpty()))
                }
                if (!activity.ensureDownloadsWrite()) throw IOException("Storage permission is needed to save")
                activity.toast(Relay.savedMessage(Relay.save(activity, list)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            activity.toast(e.message ?: "Something went wrong")
        } finally {
            close()
        }
    }

    CompositionLocalProvider(LocalActions provides actions) {
        SheetFrame(onDismiss = close) {
            if (mode != ShareActivity.Mode.SHEET) {
                QuickCard(
                    label = if (mode == ShareActivity.Mode.TELEGRAM) "Sending to $quickName…" else "Saving to Downloads…",
                    progress = progress,
                    onCancel = close,
                )
            } else {
                SheetContent(
                    items = items,
                    uriCount = uris.size,
                    text = text,
                    link = link,
                    progress = progress,
                    quickName = quickName,
                    saved = saved,
                    onTelegram = { actions.telegram(items.orEmpty(), text.takeIf { items.isNullOrEmpty() }, close) },
                    onShare = { actions.share(items.orEmpty(), text.takeIf { items.isNullOrEmpty() }, close) },
                    onSave = {
                        val list = items.orEmpty().ifEmpty { listOf(Stage.textFile(activity, text.orEmpty())) }
                        actions.save(list) { saved = true }
                    },
                    onOpen = { actions.open(it) },
                    onDownload = { url -> actions.download(url, close) },
                )
            }
        }
        actions.busy?.let { BusyOverlay(it) }
    }
}

@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = shown, enter = slideInVertically { it } + fadeIn()) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 3.dp,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            ) {
                Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)) {
                    Box(
                        Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    Spacer(Modifier.height(14.dp))
                    content()
                }
            }
        }
    }
}

@Composable
private fun QuickCard(label: String, progress: Float, onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (progress > 0f) CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp))
        else CircularProgressIndicator(Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (progress > 0f) {
                Text(
                    "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun SheetContent(
    items: List<FileItem>?,
    uriCount: Int,
    text: String?,
    link: String?,
    progress: Float,
    quickName: String,
    saved: Boolean,
    onTelegram: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onDownload: (String) -> Unit,
) {
    val title = when {
        items != null && items.size == 1 -> items[0].name
        uriCount > 1 -> "$uriCount files"
        uriCount == 1 -> "1 file"
        link != null -> "Link"
        else -> "Text"
    }
    val subtitle = when {
        items == null -> "Preparing…"
        items.isNotEmpty() -> formatSize(items.sumOf { maxOf(it.size, 0L) })
        link != null -> link
        else -> "${text?.length ?: 0} characters"
    }
    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(12.dp))

    when {
        items == null -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        items.size > 1 -> LazyColumn(Modifier.heightIn(max = 240.dp)) {
            items(items, key = { it.key }) { item -> SheetRow(item) { onOpen(item) } }
        }
        items.size == 1 -> SheetRow(items[0]) { onOpen(items[0]) }
        text != null -> Text(
            text,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
        )
    }

    Spacer(Modifier.height(16.dp))
    val ready = items != null
    Button(onClick = onTelegram, enabled = ready, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(10.dp))
        Text("Send to $quickName", style = MaterialTheme.typography.titleSmall)
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onShare, enabled = ready, modifier = Modifier.weight(1f), contentPadding = SmallButtonPadding) {
            Icon(Icons.Filled.Share, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(6.dp))
            Text("Share", maxLines = 1)
        }
        OutlinedButton(onClick = onSave, enabled = ready && !saved, modifier = Modifier.weight(1f), contentPadding = SmallButtonPadding) {
            Icon(if (saved) Icons.Filled.Check else Icons.Filled.Download, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(6.dp))
            Text(if (saved) "Saved" else "Save", maxLines = 1)
        }
        val single = items?.singleOrNull()
        if (single != null) {
            OutlinedButton(onClick = { onOpen(single) }, modifier = Modifier.weight(1f), contentPadding = SmallButtonPadding) {
                Icon(Icons.Filled.Visibility, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(6.dp))
                Text("Open", maxLines = 1)
            }
        }
    }
    if (link != null) {
        Spacer(Modifier.height(8.dp))
        FilledTonalButton(onClick = { onDownload(link) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CloudDownload, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(8.dp))
            Text("Download this link")
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Tip: pick \"Relay to Telegram\" in the share menu to skip this screen.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val SmallButtonPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)

@Composable
private fun SheetRow(item: FileItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(item, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatSize(item.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
