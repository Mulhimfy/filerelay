package com.mulhimfy.filerelay

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Serves our files with a generic type, so Telegram sends photos and videos as files (original quality). */
class RawFileProvider : FileProvider() {
    override fun getType(uri: Uri): String = BINARY
}

object Prefs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("relay", Context.MODE_PRIVATE)

    fun target(ctx: Context): String? = sp(ctx).getString("target", null)
    fun setTarget(ctx: Context, pkg: String?) = sp(ctx).edit { putString("target", pkg) }
    fun asFiles(ctx: Context) = sp(ctx).getBoolean("as_files", true)
    fun setAsFiles(ctx: Context, v: Boolean) = sp(ctx).edit { putBoolean("as_files", v) }
    fun showHidden(ctx: Context) = sp(ctx).getBoolean("hidden", false)
    fun setShowHidden(ctx: Context, v: Boolean) = sp(ctx).edit { putBoolean("hidden", v) }
    fun includeSent(ctx: Context) = sp(ctx).getBoolean("sent", false)
    fun setIncludeSent(ctx: Context, v: Boolean) = sp(ctx).edit { putBoolean("sent", v) }

    fun downloads(ctx: Context): List<Long> =
        sp(ctx).getString("dl", null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }

    fun addDownload(ctx: Context, id: Long) =
        sp(ctx).edit { putString("dl", (listOf(id) + downloads(ctx)).distinct().take(200).joinToString(",")) }

    fun removeDownload(ctx: Context, id: Long) =
        sp(ctx).edit { putString("dl", downloads(ctx).filter { it != id }.joinToString(",")) }
}

/** Telegram detection and the "quick send" target app. */
object Targets {
    val TELEGRAM = listOf(
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta",
        "org.thunderdog.challegram", "org.telegram.plus", "tw.nekomimi.nekogram", "com.exteragram.messenger",
        "uz.unnarsx.cherrygram", "xyz.nextalone.nagram", "com.radolyn.ayugram", "org.forkgram.messenger",
        "nekox.messenger",
    )

    data class ShareApp(val pkg: String, val label: String)

    fun installed(ctx: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        ctx.packageManager.getApplicationInfo(pkg, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun isTelegram(pkg: String?) = pkg != null && (pkg in TELEGRAM || pkg.contains("telegram", ignoreCase = true))

    fun quick(ctx: Context): String? =
        Prefs.target(ctx)?.takeIf { installed(ctx, it) } ?: TELEGRAM.firstOrNull { installed(ctx, it) }

    fun label(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    fun shareApps(ctx: Context): List<ShareApp> {
        val intent = Intent(Intent.ACTION_SEND).setType("*/*")
        @Suppress("DEPRECATION")
        val found = ctx.packageManager.queryIntentActivities(intent, 0)
        return found.map { it.activityInfo.packageName }.distinct()
            .filter { it != ctx.packageName }
            .map { ShareApp(it, label(ctx, it)) }
            .sortedWith(compareBy<ShareApp> { !isTelegram(it.pkg) }.thenBy { it.label.lowercase() })
    }
}

suspend fun copyStream(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit = {}) {
    val buf = ByteArray(256 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val n = input.read(buf)
        if (n < 0) break
        output.write(buf, 0, n)
        onBytes(n.toLong())
    }
}

/** Incoming shares get copied into the app cache (kept 24 h), unless we can point at the real file. */
object Stage {
    private fun base(ctx: Context) = File(ctx.cacheDir, "relay")

    fun cleanup(ctx: Context) {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        base(ctx).listFiles()?.forEach { if (it.lastModified() < cutoff) it.deleteRecursively() }
    }

    fun newBatch(ctx: Context): File =
        File(base(ctx), "${System.currentTimeMillis()}-${(1000..9999).random()}").apply { mkdirs() }

    fun uniqueFile(dir: File, name: String): File {
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var f = File(dir, name)
        var n = 2
        while (f.exists()) {
            f = File(dir, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext")
            n++
        }
        return f
    }

    /** Name, size and type of shared Uris, without copying anything. Call off the main thread. */
    fun describe(ctx: Context, uris: List<Uri>, typeHint: String?): List<FileItem> = uris.mapIndexed { i, uri ->
        if (uri.scheme == "file") {
            val f = File(uri.path.orEmpty())
            return@mapIndexed FileItem.of(f)
        }
        var name: String? = null
        var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
        } catch (e: Exception) {
            // Some providers don't support queries; fall back to the Uri itself.
        }
        val type = (runCatching { ctx.contentResolver.getType(uri) }.getOrNull() ?: typeHint)
            ?.takeIf { it.isNotBlank() && '*' !in it }
        val fileName = withExtension(safeName(name ?: uri.lastPathSegment ?: "file-${i + 1}"), type)
        FileItem(name = fileName, uri = uri, size = size, modified = System.currentTimeMillis(), mime = type ?: mimeOf(fileName))
    }

    /** Makes shared Uris into local files we can serve to any app. Reports copy progress 0..1. */
    suspend fun incoming(ctx: Context, uris: List<Uri>, typeHint: String?, onProgress: (Float) -> Unit): List<FileItem> =
        withContext(Dispatchers.IO) {
            val access = Storage.hasAccess(ctx)
            val resolved = describe(ctx, uris, typeHint).map { d ->
                val f = d.file
                when {
                    f != null && f.canRead() -> FileItem.of(f)
                    access && d.uri.authority?.contains("whatsapp") == true ->
                        WhatsApp.locate(d.name, d.size, d.mime)?.let { FileItem.of(it) } ?: d
                    else -> d.copy(file = null)
                }
            }
            val total = resolved.filter { it.file == null }.sumOf { maxOf(it.size, 0L) }.coerceAtLeast(1L)
            var copied = 0L
            val batch by lazy { newBatch(ctx) }
            resolved.map { item ->
                if (item.file != null) return@map item
                val out = uniqueFile(batch, safeName(item.name))
                val input = ctx.contentResolver.openInputStream(item.uri) ?: throw IOException("Can't read ${item.name}")
                input.use { i ->
                    out.outputStream().use { o ->
                        copyStream(i, o) { n ->
                            copied += n
                            onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                FileItem.of(out)
            }
        }

    /** Copies any readable item into the cache and returns the copy. */
    suspend fun copyIn(ctx: Context, item: FileItem): File = withContext(Dispatchers.IO) {
        val out = uniqueFile(newBatch(ctx), safeName(item.name))
        Relay.openInput(ctx, item).use { i -> out.outputStream().use { o -> copyStream(i, o) } }
        out
    }

    fun textFile(ctx: Context, text: String): FileItem {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        val f = File(newBatch(ctx), "Text $stamp.txt")
        f.writeText(text)
        return FileItem.of(f)
    }
}

/** Package of our classes (= manifest namespace), used for the share-target aliases. */
const val APP_NAMESPACE = "com.mulhimfy.filerelay"

object Relay {
    private fun authority(ctx: Context) = "${ctx.packageName}.files"
    private fun rawAuthority(ctx: Context) = "${ctx.packageName}.raw"

    fun openInput(ctx: Context, item: FileItem): InputStream =
        item.file?.let { FileInputStream(it) }
            ?: ctx.contentResolver.openInputStream(item.uri)
            ?: throw IOException("Can't read ${item.name}")

    /** A content:// Uri other apps can read. Points at the real file when possible, else at a cached copy. */
    suspend fun export(ctx: Context, item: FileItem, raw: Boolean): Uri = withContext(Dispatchers.IO) {
        val auth = if (raw) rawAuthority(ctx) else authority(ctx)
        val f = item.file?.takeIf { it.canRead() }
        if (f != null) {
            try {
                return@withContext FileProvider.getUriForFile(ctx, auth, f)
            } catch (e: IllegalArgumentException) {
                // Outside the provider's roots: copy it below.
            }
        }
        FileProvider.getUriForFile(ctx, auth, Stage.copyIn(ctx, item))
    }

    private fun ownShareTargets(ctx: Context) = arrayOf(
        ComponentName(ctx, ShareActivity::class.java),
        ComponentName(ctx, "$APP_NAMESPACE.ShareToTelegram"),
        ComponentName(ctx, "$APP_NAMESPACE.ShareSave"),
    )

    private fun commonMime(items: List<FileItem>): String {
        val mimes = items.map { it.mime }.distinct()
        if (mimes.size == 1) return mimes[0]
        val tops = mimes.map { it.substringBefore('/') }.distinct()
        return if (tops.size == 1) "${tops[0]}/*" else "*/*"
    }

    /**
     * Telegram gets SEND_MULTIPLE with type * / * even for one file: it then asks our provider for each
     * file's type, and only treats image/ and video/ as (compressed) media.
     */
    private fun sendIntent(uris: List<Uri>, items: List<FileItem>, text: String?, telegram: Boolean): Intent {
        val intent = when {
            uris.isEmpty() -> Intent(Intent.ACTION_SEND).setType("text/plain")
            uris.size == 1 && !telegram ->
                Intent(Intent.ACTION_SEND).setType(items[0].mime).putExtra(Intent.EXTRA_STREAM, uris[0])
            else -> Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(if (telegram) "*/*" else commonMime(items))
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        if (!text.isNullOrBlank()) intent.putExtra(Intent.EXTRA_TEXT, text)
        if (uris.isNotEmpty()) {
            val clip = ClipData.newRawUri("", uris[0])
            for (u in uris.drop(1)) clip.addItem(ClipData.Item(u))
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    /** One tap: hand the files to Telegram (or the app picked in Settings). */
    suspend fun quickSend(activity: Activity, items: List<FileItem>, text: String? = null) {
        val files = items.filter { !it.isDir }
        if (files.isEmpty() && text.isNullOrBlank()) throw IOException("Nothing to send (folders can't be sent)")
        val pkg = Targets.quick(activity)
        if (pkg == null) {
            activity.toast("Telegram isn't installed. Pick an app in Settings.")
            chooser(activity, files, text)
            return
        }
        val tg = Targets.isTelegram(pkg)
        val raw = tg && Prefs.asFiles(activity)
        val uris = files.map { export(activity, it, raw) }
        val intent = sendIntent(uris, files, text, telegram = tg).setPackage(pkg)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            chooser(activity, files, text)
        }
    }

    /** The system share sheet (shows Telegram's direct-share chats too). */
    suspend fun chooser(activity: Activity, items: List<FileItem>, text: String? = null) {
        val files = items.filter { !it.isDir }
        if (files.isEmpty() && text.isNullOrBlank()) throw IOException("Nothing to share (folders can't be shared)")
        val uris = files.map { export(activity, it, raw = false) }
        val title = when (files.size) {
            0 -> "Share text"
            1 -> "Share ${files[0].name}"
            else -> "Share ${files.size} files"
        }
        val chooser = Intent.createChooser(sendIntent(uris, files, text, telegram = false), title)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, ownShareTargets(activity))
        activity.startActivity(chooser)
    }

    suspend fun openWith(activity: Activity, item: FileItem) {
        val uri = export(activity, item, raw = false)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(view, "Open ${item.name} with")
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(activity, ViewerActivity::class.java)))
        activity.startActivity(chooser)
    }

    /** Copies items into Download/FileRelay. Returns the saved names. */
    suspend fun save(ctx: Context, items: List<FileItem>): List<String> = withContext(Dispatchers.IO) {
        val files = items.filter { !it.isDir }
        if (files.isEmpty()) throw IOException("Nothing to save (folders can't be saved)")
        files.map { saveOne(ctx, it) }
    }

    private suspend fun saveOne(ctx: Context, item: FileItem): String {
        val name = safeName(item.name)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, storeMime(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${Storage.RELAY_FOLDER}")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Couldn't create $name in Downloads")
            try {
                openInput(ctx, item).use { i ->
                    (resolver.openOutputStream(dest) ?: throw IOException("Couldn't write $name")).use { o -> copyStream(i, o) }
                }
                resolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(dest, null, null) }
                throw e
            }
            return runCatching {
                resolver.query(dest, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: name
        } else {
            val dir = Storage.relayDir.apply { mkdirs() }
            val out = Stage.uniqueFile(dir, name)
            openInput(ctx, item).use { i -> out.outputStream().use { o -> copyStream(i, o) } }
            MediaScannerConnection.scanFile(ctx, arrayOf(out.absolutePath), null, null)
            return out.name
        }
    }

    fun savedMessage(names: List<String>) =
        if (names.size == 1) "Saved to Download/${Storage.RELAY_FOLDER}/${names[0]}"
        else "Saved ${names.size} files to Download/${Storage.RELAY_FOLDER}"
}
