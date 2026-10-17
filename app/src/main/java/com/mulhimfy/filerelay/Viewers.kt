package com.mulhimfy.filerelay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

// ---------- Images: pinch to zoom, double-tap to toggle ----------

@Composable
fun ZoomableImage(item: FileItem, onFail: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val request = remember(item.key) { ImageRequest.Builder(ctx).data(item.uri).size(2048).build() }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = box.width * (s - 1f) / 2f
        val my = box.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).clipToBounds()
            .onSizeChanged { box = it }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = 2.5f
                    }
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val s = (scale * zoom).coerceIn(1f, 8f)
                    scale = s
                    offset = clamp(offset + pan, s)
                }
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            onError = { onFail() },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
    }
}

// ---------- Text ----------

private fun readUpTo(input: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < max) {
        val n = input.read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

@Composable
fun TextFileView(item: FileItem) {
    val ctx = LocalContext.current
    var chunks by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(item.key) {
        chunks = withContext(Dispatchers.IO) {
            try {
                Relay.openInput(ctx, item).use { input ->
                    val limit = 2 * 1024 * 1024
                    val bytes = readUpTo(input, limit + 1)
                    val text = String(bytes, 0, minOf(bytes.size, limit), Charsets.UTF_8).removePrefix("﻿")
                    val parts = text.lines().chunked(60).map { it.joinToString("\n") }
                    if (bytes.size > limit) parts + "… (showing the first 2 MB)" else parts
                }
            } catch (e: Exception) {
                listOf("Couldn't read this file: ${e.message}")
            }
        }
    }
    val list = chunks
    if (list == null) {
        CenteredProgress()
    } else {
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                items(list.size) { i ->
                    Text(list[i], fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

// ---------- ZIP: list entries, tap one to open it ----------

private data class ZipEntryInfo(val path: String, val size: Long)

private fun <T> withZip(file: File, block: (ZipFile) -> T): T =
    try {
        ZipFile(file, Charsets.UTF_8).use(block)
    } catch (e: Exception) {
        // Old Windows zips store names in CP437.
        ZipFile(file, Charset.forName("CP437")).use(block)
    }

private fun readZip(ctx: Context, item: FileItem): List<ZipEntryInfo> {
    val f = item.file
    if (f != null) {
        return withZip(f) { z ->
            z.entries().asSequence().filter { !it.isDirectory }.map { ZipEntryInfo(it.name, it.size) }.toList()
        }
    }
    ZipInputStream(Relay.openInput(ctx, item)).use { zin ->
        val out = mutableListOf<ZipEntryInfo>()
        while (true) {
            val e = zin.nextEntry ?: break
            if (!e.isDirectory) out += ZipEntryInfo(e.name, e.size)
        }
        return out
    }
}

private suspend fun extractEntry(ctx: Context, item: FileItem, entryPath: String): File = withContext(Dispatchers.IO) {
    val out = Stage.uniqueFile(Stage.newBatch(ctx), safeName(entryPath.substringAfterLast('/')))
    val f = item.file
    if (f != null) {
        withZip(f) { z ->
            val entry = z.getEntry(entryPath) ?: throw IOException("Not found in the archive")
            z.getInputStream(entry).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        }
    } else {
        ZipInputStream(Relay.openInput(ctx, item)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: throw IOException("Not found in the archive")
                if (e.name == entryPath) {
                    out.outputStream().use { o -> zin.copyTo(o) }
                    break
                }
            }
        }
    }
    out
}

@Composable
fun ZipView(item: FileItem, onFail: (String) -> Unit) {
    val ctx = LocalContext.current
    val actions = LocalActions.current
    var entries by remember { mutableStateOf<List<ZipEntryInfo>?>(null) }
    LaunchedEffect(item.key) {
        try {
            entries = withContext(Dispatchers.IO) { readZip(ctx, item) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFail("Can't read this archive (${e.message}).")
        }
    }
    val list = entries
    if (list == null) {
        CenteredProgress()
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "${list.size} file${if (list.size == 1) "" else "s"} inside. Tap one to open it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(list.size) { i ->
                val e = list[i]
                val name = e.path.substringAfterLast('/')
                val icon = remember(e.path) { FileItem(name = name, uri = Uri.EMPTY, size = e.size) }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            actions.task("Opening…") { actions.open(FileItem.of(extractEntry(ctx, item, e.path))) }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumb(icon, 40.dp, preview = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(e.path.substringBeforeLast('/', ""), formatSize(e.size)).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// ---------- PDF ----------

private class PdfDoc(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer, val ratios: FloatArray) {
    private val lock = Mutex()
    private var closed = false

    suspend fun render(index: Int, width: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        val page = renderer.openPage(index)
        try {
            val h = (width * page.height.toFloat() / page.width.coerceAtLeast(1)).toInt().coerceIn(1, 6000)
            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
                it.eraseColor(android.graphics.Color.WHITE)
                page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            }
        } finally {
            page.close()
        }
    }

    suspend fun close() = lock.withLock {
        if (!closed) {
            closed = true
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
    }
}

private fun openPdf(pfd: ParcelFileDescriptor): PdfDoc {
    val renderer = try {
        PdfRenderer(pfd)
    } catch (e: Exception) {
        pfd.close()
        throw e
    }
    try {
        val ratios = FloatArray(renderer.pageCount) { i ->
            val p = renderer.openPage(i)
            try {
                (p.height.toFloat() / p.width).takeIf { it.isFinite() && it > 0f } ?: 1.414f
            } finally {
                p.close()
            }
        }
        return PdfDoc(pfd, renderer, ratios)
    } catch (e: Exception) {
        renderer.close()
        pfd.close()
        throw e
    }
}

private suspend fun loadPdf(ctx: Context, item: FileItem): PdfDoc = withContext(Dispatchers.IO) {
    val f = item.file
    if (f != null) return@withContext openPdf(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY))
    try {
        val pfd = ctx.contentResolver.openFileDescriptor(item.uri, "r") ?: throw IOException("Can't open the file")
        openPdf(pfd)
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        // Some apps hand out streams that can't seek; work on a copy.
        openPdf(ParcelFileDescriptor.open(Stage.copyIn(ctx, item), ParcelFileDescriptor.MODE_READ_ONLY))
    }
}

@Composable
fun PdfView(item: FileItem, onFail: (String) -> Unit) {
    val ctx = LocalContext.current
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    LaunchedEffect(item.key) {
        try {
            doc = loadPdf(ctx, item)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            onFail("This PDF is password-protected. Open it with another app.")
        } catch (e: Exception) {
            onFail("This PDF can't be shown here (${e.message}).")
        }
    }
    DisposableEffect(doc) {
        val d = doc
        onDispose { if (d != null) CoroutineScope(Dispatchers.IO).launch { d.close() } }
    }
    val d = doc
    if (d == null) CenteredProgress() else PdfPages(d)
}

@Composable
private fun PdfPages(d: PdfDoc) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xFF5F6368)).clipToBounds()
            .pointerInput(Unit) {
                // Two fingers zoom/pan; one finger keeps scrolling the pages.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            val maxX = size.width * (scale - 1f) / 2f
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxX, maxX)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        val widthPx = constraints.maxWidth
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
            },
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(d.ratios.size) { i -> PdfPage(d, i, widthPx) }
        }
        Text(
            "$page / ${d.ratios.size}",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PdfPage(d: PdfDoc, index: Int, widthPx: Int) {
    var bmp by remember(index) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(index, widthPx) {
        val w = (widthPx * 1.5f).toInt().coerceIn(1, 2000)
        bmp = withContext(Dispatchers.IO) { d.render(index, w) }?.asImageBitmap()
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / d.ratios[index]).background(Color.White)) {
        bmp?.let { Image(it, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

// ---------- Anything else ----------

@Composable
fun InfoView(item: FileItem, note: String?) {
    val actions = LocalActions.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Thumb(item, 96.dp, preview = false)
            Spacer(Modifier.height(16.dp))
            Text(item.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text(
                listOf(formatSize(item.size), item.mime, formatTime(item.modified)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            item.file?.parent?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (note != null) {
                Spacer(Modifier.height(12.dp))
                Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { actions.openWith(item) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(8.dp))
                Text("Open with another app")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { actions.telegram(listOf(item)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(8.dp))
                Text("Send to Telegram")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { actions.share(listOf(item)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Share, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(8.dp))
                Text("Share…")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { actions.save(listOf(item)) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Download, null, Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(8.dp))
                Text("Save to Downloads")
            }
        }
    }
}
