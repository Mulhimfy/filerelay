package com.mulhimfy.filerelay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class WaApp(val label: String, private val pkg: String, private val dirName: String, val prefix: String) {
    WHATSAPP("WhatsApp", "com.whatsapp", "WhatsApp", "WhatsApp "),
    BUSINESS("WA Business", "com.whatsapp.w4b", "WhatsApp Business", "WhatsApp Business ");

    /** Android 11+ keeps WhatsApp media under Android/media; older installs use the storage root. */
    fun mediaRoots(): List<File> = listOf(
        File(Storage.primary, "Android/media/$pkg/$dirName/Media"),
        File(Storage.primary, "$dirName/Media"),
    ).filter { it.isDirectory }
}

enum class WaCat(val label: String, private val folder: String) {
    RECENT("Recent", ""),
    DOCUMENTS("Documents", "Documents"),
    IMAGES("Images", "Images"),
    VIDEOS("Videos", "Video"),
    AUDIO("Audio", "Audio"),
    VOICE("Voice notes", "Voice Notes"),
    STATUSES("Statuses", ".Statuses"),
    GIFS("GIFs", "Animated Gifs"),
    STICKERS("Stickers", "Stickers");

    fun dirName(app: WaApp) = if (this == STATUSES) folder else app.prefix + folder
}

object WhatsApp {
    private val RECENT_CATS = listOf(WaCat.DOCUMENTS, WaCat.IMAGES, WaCat.VIDEOS, WaCat.AUDIO, WaCat.VOICE, WaCat.GIFS)

    /** Last scan per screen, so switching tabs shows something right away. */
    val cache = HashMap<String, List<FileItem>>()

    fun installed(): List<WaApp> = WaApp.entries.filter { it.mediaRoots().isNotEmpty() }

    suspend fun scan(app: WaApp, cat: WaCat, includeSent: Boolean): List<FileItem> = withContext(Dispatchers.IO) {
        val cats = if (cat == WaCat.RECENT) RECENT_CATS else listOf(cat)
        val out = ArrayList<FileItem>()
        for (root in app.mediaRoots()) for (c in cats) walk(File(root, c.dirName(app)), 0, includeSent, out)
        val sorted = out.distinctBy { it.key }.sortedByDescending { it.modified }
        if (cat == WaCat.RECENT) sorted.take(400) else sorted
    }

    private fun walk(dir: File, depth: Int, includeSent: Boolean, out: MutableList<FileItem>) {
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.name.startsWith(".")) continue // .nomedia, .trashed-...
            if (f.isDirectory) {
                if (depth < 3 && (includeSent || f.name != "Sent")) walk(f, depth + 1, includeSent, out)
            } else {
                out += FileItem.of(f)
            }
        }
    }

    /**
     * Finds the real file behind a file WhatsApp shared with us (same name and size),
     * so it can be relayed directly instead of being copied first.
     */
    fun locate(name: String, size: Long, mime: String): File? {
        if (size <= 0 || name.isBlank()) return null
        val cats = when {
            mime.startsWith("image/") -> listOf(WaCat.IMAGES, WaCat.STICKERS, WaCat.STATUSES)
            mime.startsWith("video/") -> listOf(WaCat.VIDEOS, WaCat.GIFS, WaCat.STATUSES)
            mime.startsWith("audio/") -> listOf(WaCat.AUDIO, WaCat.VOICE)
            else -> emptyList()
        } + WaCat.DOCUMENTS
        for (app in WaApp.entries) for (root in app.mediaRoots()) for (c in cats) {
            val dir = File(root, c.dirName(app))
            val candidates = mutableListOf(File(dir, name), File(dir, "Sent/$name"), File(dir, "Private/$name"))
            if (c == WaCat.VOICE) dir.listFiles()?.filter { it.isDirectory }?.forEach { candidates += File(it, name) }
            candidates.firstOrNull { it.isFile && it.length() == size }?.let { return it }
        }
        return null
    }
}
