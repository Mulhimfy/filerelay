package com.mulhimfy.filerelay

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

class MainActivity : RelayActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Thread { Stage.cleanup(applicationContext) }.start()
        setContent { RelayTheme { MainScreen(this) } }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    WHATSAPP("WhatsApp", Icons.Filled.Chat),
    FILES("Files", Icons.Filled.Folder),
    DOWNLOADS("Downloads", Icons.Filled.Download),
    SETTINGS("Settings", Icons.Filled.Settings),
}

@Composable
private fun MainScreen(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val actions = remember { AppActions(activity, scope) }
    var tab by rememberSaveable { mutableStateOf(Tab.WHATSAPP) }
    var hasAccess by remember { mutableStateOf(Storage.hasAccess(activity)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasAccess = Storage.hasAccess(activity) }

    CompositionLocalProvider(LocalActions provides actions) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(
                Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Horizontal)),
            ) {
                when (tab) {
                    Tab.WHATSAPP -> WhatsAppScreen(hasAccess, activity::requestAccess)
                    Tab.FILES -> FilesScreen(hasAccess, activity::requestAccess)
                    Tab.DOWNLOADS -> DownloadsScreen()
                    Tab.SETTINGS -> SettingsScreen(hasAccess, activity::requestAccess)
                }
            }
        }
        actions.busy?.let { BusyOverlay(it) }
    }
}
