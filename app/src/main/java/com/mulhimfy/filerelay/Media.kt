package com.mulhimfy.filerelay

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaPlayerView(uri: Uri, audio: Boolean, onError: (String) -> Unit) {
    val ctx = LocalContext.current
    val exo = remember(uri) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError(error.errorCodeName)
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { exo.pause() }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        if (audio) {
            Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(120.dp))
        }
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    setPlayer(exo)
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    if (audio) {
                        setControllerShowTimeoutMs(0)
                        setControllerHideOnTouch(false)
                        showController()
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
