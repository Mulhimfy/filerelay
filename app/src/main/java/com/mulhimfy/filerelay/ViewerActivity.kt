@file:OptIn(ExperimentalMaterial3Api::class)

package com.mulhimfy.filerelay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import java.io.File

class ViewerActivity : RelayActivity() {
    companion object {
        private const val EXTRA_PATH = "relay.path"
        private const val EXTRA_NAME = "relay.name"

        fun intent(ctx: Context, item: FileItem): Intent = Intent(ctx, ViewerActivity::class.java).apply {
            val f = item.file
            if (f != null) putExtra(EXTRA_PATH, f.absolutePath) else setDataAndType(item.uri, item.mime)
            putExtra(EXTRA_NAME, item.name)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val item = resolve()
        if (item == null) {
            toast("Can't open this file")
            finish()
            return
        }
        setContent { RelayTheme { ViewerRoot(this, item) } }
    }

    private fun resolve(): FileItem? {
        intent.getStringExtra(EXTRA_PATH)?.let { return FileItem.of(File(it)) }
        val uri = intent.data ?: return null
        if (uri.scheme == "file") return uri.path?.let { FileItem.of(File(it)) }
        val described = runCatching { Stage.describe(this, listOf(uri), intent.type) }.getOrNull()?.firstOrNull()
            ?: return null
        val name = intent.getStringExtra(EXTRA_NAME)
        return if (name != null) described.copy(name = name) else described
    }
}

@Composable
private fun ViewerRoot(activity: ViewerActivity, item: FileItem) {
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(activity, scope) }
    var failed by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val kind = item.kind

    CompositionLocalProvider(LocalActions provides actions) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { activity.finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    title = {
                        Column {
                            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOf(formatSize(item.size), item.mime).filter { it.isNotBlank() }.joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { actions.telegram(listOf(item)) }) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send to Telegram", tint = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { actions.share(listOf(item)) }) { Icon(Icons.Filled.Share, "Share") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Save to Downloads") },
                                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                                    onClick = { menu = false; actions.save(listOf(item)) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Open with…") },
                                    leadingIcon = { Icon(Icons.Filled.OpenInNew, null) },
                                    onClick = { menu = false; actions.openWith(item) },
                                )
                            }
                        }
                    },
                )
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                val note = failed
                if (note != null) {
                    InfoView(item, note)
                } else {
                    when (kind) {
                        Kind.IMAGE -> ZoomableImage(item) { failed = "This image can't be shown here." }
                        Kind.VIDEO, Kind.AUDIO -> MediaPlayerView(item.uri, audio = kind == Kind.AUDIO) {
                            failed = "This file can't be played here ($it)."
                        }
                        Kind.PDF -> PdfView(item) { failed = it }
                        Kind.TEXT -> TextFileView(item)
                        Kind.ZIP -> ZipView(item) { failed = it }
                        else -> InfoView(item, null)
                    }
                }
            }
        }
        actions.busy?.let { BusyOverlay(it) }
    }
}
