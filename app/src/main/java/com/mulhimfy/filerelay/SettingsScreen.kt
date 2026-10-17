@file:OptIn(ExperimentalMaterial3Api::class)

package com.mulhimfy.filerelay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(hasAccess: Boolean, onGrant: () -> Unit) {
    val ctx = LocalContext.current
    var target by remember { mutableStateOf(Targets.quick(ctx)) }
    var asFiles by remember { mutableStateOf(Prefs.asFiles(ctx)) }
    var showHidden by remember { mutableStateOf(Prefs.showHidden(ctx)) }
    var includeSent by remember { mutableStateOf(Prefs.includeSent(ctx)) }
    var picking by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section("Quick send")
            ListItem(
                headlineContent = { Text("Send button goes to") },
                supportingContent = {
                    Text(target?.let { Targets.label(ctx, it) } ?: "Telegram isn't installed. Tap to pick an app.")
                },
                leadingContent = { AppIcon(target) },
                modifier = Modifier.clickable { picking = true },
            )
            ListItem(
                headlineContent = { Text("Send photos & videos as files") },
                supportingContent = { Text("Telegram keeps the original quality instead of compressing them.") },
                leadingContent = { Icon(Icons.Filled.HighQuality, null) },
                trailingContent = {
                    Switch(checked = asFiles, onCheckedChange = { asFiles = it; Prefs.setAsFiles(ctx, it) })
                },
            )

            Section("Files")
            ListItem(
                headlineContent = { Text("All files access") },
                supportingContent = {
                    Text(if (hasAccess) "Allowed. FileRelay can read WhatsApp's folders." else "Needed to read WhatsApp's folders and browse storage.")
                },
                leadingContent = { Icon(Icons.Filled.FolderOpen, null) },
                trailingContent = {
                    if (hasAccess) Icon(Icons.Filled.Check, "Allowed", tint = MaterialTheme.colorScheme.primary)
                    else FilledTonalButton(onClick = onGrant) { Text("Allow") }
                },
            )
            ListItem(
                headlineContent = { Text("Show hidden files") },
                leadingContent = { Icon(Icons.Filled.Visibility, null) },
                trailingContent = {
                    Switch(checked = showHidden, onCheckedChange = { showHidden = it; Prefs.setShowHidden(ctx, it) })
                },
            )
            ListItem(
                headlineContent = { Text("Include files I sent on WhatsApp") },
                leadingContent = { Icon(Icons.Filled.Upload, null) },
                trailingContent = {
                    Switch(checked = includeSent, onCheckedChange = { includeSent = it; Prefs.setIncludeSent(ctx, it) })
                },
            )

            Section("How to use")
            Help(
                "From WhatsApp: open a chat, long-press a file, tap Share, then pick:\n" +
                    "• Relay to Telegram: goes straight to Telegram's chat picker.\n" +
                    "• Save to Downloads: saved to Download/${Storage.RELAY_FOLDER} at once.\n" +
                    "• FileRelay: shows every option (send, share, save, open).",
            )
            Help(
                "WhatsApp tab: every file WhatsApp has downloaded, newest first. Tap to open, " +
                    "tap the arrow to send to Telegram, long-press to pick several.",
            )
            Help(
                "Links: share a link to FileRelay (or paste it in Downloads) and it's downloaded to " +
                    "Download/${Storage.RELAY_FOLDER}.",
            )
            Help(
                "Tip: in WhatsApp go to Settings → Storage and data → Media auto-download and turn on " +
                    "Documents, so files are already on your phone when you open the chat.",
            )

            ListItem(
                headlineContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
                leadingContent = { Icon(Icons.Filled.Info, null) },
            )
        }
    }

    if (picking) {
        TargetPicker(
            current = target,
            onPick = { pkg ->
                Prefs.setTarget(ctx, pkg)
                target = Targets.quick(ctx)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun TargetPicker(current: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<Targets.ShareApp>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Targets.shareApps(ctx) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Send button goes to") },
        text = {
            val list = apps
            if (list == null) {
                CenteredProgress(Modifier.heightIn(min = 120.dp))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.pkg }) { a ->
                        ListItem(
                            headlineContent = { Text(a.label) },
                            supportingContent = if (Targets.isTelegram(a.pkg)) ({ Text("Telegram") }) else null,
                            leadingContent = { AppIcon(a.pkg, 36.dp) },
                            trailingContent = if (a.pkg == current) ({ Icon(Icons.Filled.Check, "Current") }) else null,
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { onPick(a.pkg) },
                        )
                    }
                }
            }
        },
    )
}
