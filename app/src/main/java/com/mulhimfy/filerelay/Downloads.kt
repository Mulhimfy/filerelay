package com.mulhimfy.filerelay

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

data class Dl(
    val id: Long,
    val title: String,
    val url: String,
    val status: Int,
    val downloaded: Long,
    val total: Long,
    val localUri: String?,
    val mime: String?,
    val reason: Int,
    val time: Long,
) {
    val ok get() = status == DownloadManager.STATUS_SUCCESSFUL
    val failed get() = status == DownloadManager.STATUS_FAILED
    val active get() = !ok && !failed
    val progress get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else -1f

    fun statusText(): String = when (status) {
        DownloadManager.STATUS_SUCCESSFUL -> "Done · ${formatSize(if (total > 0) total else downloaded)} · ${formatTime(time)}"
        DownloadManager.STATUS_RUNNING ->
            if (total > 0) "${(progress * 100).toInt()}% · ${formatSize(downloaded)} of ${formatSize(total)}"
            else "Downloading · ${formatSize(downloaded)}"
        DownloadManager.STATUS_PENDING -> "Waiting…"
        DownloadManager.STATUS_PAUSED -> when (reason) {
            DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
            DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
            DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection lost, retrying…"
            else -> "Paused"
        }
        DownloadManager.STATUS_FAILED -> "Failed: " + when (reason) {
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough space"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "file already exists"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "connection error"
            DownloadManager.ERROR_CANNOT_RESUME -> "can't resume"
            in 400..599 -> "server said $reason"
            else -> "error $reason"
        }
        else -> ""
    }
}

object Downloader {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val URL_RE = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)

    fun findUrl(text: String?): String? =
        text?.let { URL_RE.find(it)?.value?.trimEnd('.', ',', ')', ']', '!', '?', ';', ':') }

    private fun dm(ctx: Context) = ctx.getSystemService(DownloadManager::class.java)

    /** Starts a download into Download/FileRelay. Returns the file name. */
    suspend fun start(ctx: Context, url: String): String = withContext(Dispatchers.IO) {
        val (name, mime) = probe(url)
        val finalName = uniqueName(name)
        ensureRelayDir(ctx)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(finalName)
            .setDescription("FileRelay")
            .addRequestHeader("User-Agent", UA)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${Storage.RELAY_FOLDER}/$finalName")
        if (mime != null) req.setMimeType(mime)
        val id = dm(ctx).enqueue(req)
        Prefs.addDownload(ctx, id)
        finalName
    }

    /** DownloadManager won't create our subfolder itself; without file access, MediaStore can. */
    private fun ensureRelayDir(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29 || Storage.hasAccess(ctx)) {
            runCatching { Storage.relayDir.mkdirs() }
            return
        }
        runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "filerelay-folder-${System.nanoTime()}.bin")
                put(MediaStore.MediaColumns.MIME_TYPE, BINARY)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${Storage.RELAY_FOLDER}")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?.let { ctx.contentResolver.delete(it, null, null) }
        }
    }

    private fun uniqueName(name: String): String {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var candidate = name
        var n = 2
        while (runCatching { File(Storage.relayDir, candidate).exists() }.getOrDefault(false)) {
            candidate = if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"
            n++
        }
        return candidate
    }

    /** Asks the server for the real file name and type (follows redirects, also http→https). */
    private fun probe(url: String): Pair<String, String?> {
        var disposition: String? = null
        var type: String? = null
        var finalUrl = url
        try {
            var current = url
            for (hop in 0 until 6) {
                val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Range", "bytes=0-0")
                }
                try {
                    val code = conn.responseCode
                    val location = conn.getHeaderField("Location")
                    if (code in 300..399 && location != null) {
                        current = URL(URL(current), location).toString()
                        continue
                    }
                    disposition = conn.getHeaderField("Content-Disposition")
                    type = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                    finalUrl = current
                } finally {
                    conn.disconnect()
                }
                break
            }
        } catch (e: Exception) {
            // Offline or odd server: guess from the link itself.
        }
        val raw = nameFromDisposition(disposition)
            ?: nameFromUrl(finalUrl)
            ?: nameFromUrl(url)
            ?: URLUtil.guessFileName(finalUrl, disposition, type)
        var name = safeName(raw)
        val usefulType = type?.takeIf { it.isNotBlank() && it != BINARY && it != "text/html" }
        if (extOf(name).isEmpty() && usefulType != null) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(usefulType)?.let { name = "$name.$it" }
        }
        return name to usefulType
    }

    private fun nameFromDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        Regex("filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { m ->
            val charset = m.groupValues[1].ifBlank { "UTF-8" }
            runCatching { URLDecoder.decode(m.groupValues[2].trim().trim('"').replace("+", "%2B"), charset) }
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { return it }
        }
        Regex("filename\\s*=\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1] }
        Regex("filename\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(cd)?.let { return it.groupValues[1].trim() }
        return null
    }

    private fun nameFromUrl(url: String): String? =
        Uri.parse(url).lastPathSegment?.takeIf { it.contains('.') && extOf(it).length in 1..5 }

    fun list(ctx: Context): List<Dl> {
        val ids = Prefs.downloads(ctx)
        if (ids.isEmpty()) return emptyList()
        val out = mutableListOf<Dl>()
        try {
            dm(ctx).query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                fun col(name: String) = c.getColumnIndex(name)
                val iId = col(DownloadManager.COLUMN_ID)
                val iTitle = col(DownloadManager.COLUMN_TITLE)
                val iUri = col(DownloadManager.COLUMN_URI)
                val iStatus = col(DownloadManager.COLUMN_STATUS)
                val iDone = col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val iTotal = col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val iLocal = col(DownloadManager.COLUMN_LOCAL_URI)
                val iMime = col(DownloadManager.COLUMN_MEDIA_TYPE)
                val iReason = col(DownloadManager.COLUMN_REASON)
                val iTime = col(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
                while (c.moveToNext()) {
                    out += Dl(
                        id = c.getLong(iId),
                        title = c.getString(iTitle).orEmpty().ifBlank { "Download" },
                        url = c.getString(iUri).orEmpty(),
                        status = c.getInt(iStatus),
                        downloaded = c.getLong(iDone),
                        total = c.getLong(iTotal),
                        localUri = c.getString(iLocal),
                        mime = c.getString(iMime),
                        reason = c.getInt(iReason),
                        time = c.getLong(iTime),
                    )
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        // Forget ids the system dropped (e.g. cleared from its own Downloads app).
        val known = out.map { it.id }.toSet()
        ids.filter { it !in known }.forEach { Prefs.removeDownload(ctx, it) }
        return out.sortedByDescending { it.id }
    }

    /** The finished file as an item we can open or send. */
    fun item(ctx: Context, d: Dl): FileItem? {
        if (!d.ok) return null
        val path = d.localUri?.let { Uri.parse(it) }?.takeIf { it.scheme == "file" }?.path
        val f = path?.let { File(it) }
        if (f != null && f.canRead()) return FileItem.of(f)
        val uri = dm(ctx).getUriForDownloadedFile(d.id) ?: return null
        val type = d.mime?.takeIf { it.isNotBlank() && it != BINARY } ?: mimeOf(d.title)
        return FileItem(name = d.title, uri = uri, size = d.total, modified = d.time, mime = type)
    }

    /** Cancels a running download (or deletes a finished one's file). */
    fun cancel(ctx: Context, id: Long) {
        runCatching { dm(ctx).remove(id) }
        Prefs.removeDownload(ctx, id)
    }

    /** Hides it from our list but keeps the file. */
    fun forget(ctx: Context, id: Long) = Prefs.removeDownload(ctx, id)
}
