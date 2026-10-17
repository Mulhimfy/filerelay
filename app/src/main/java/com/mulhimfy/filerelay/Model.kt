package com.mulhimfy.filerelay

import android.net.Uri
import android.text.format.DateUtils
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

const val DIR_MIME = "inode/directory"
const val BINARY = "application/octet-stream"

/** A file on disk ([file] set) or behind a content:// Uri from another app. */
data class FileItem(
    val name: String,
    val uri: Uri,
    val size: Long = -1L,
    val modified: Long = 0L,
    val mime: String = mimeOf(name),
    val file: File? = null,
    val isDir: Boolean = false,
    val children: Int = -1,
) {
    val key: String get() = file?.absolutePath ?: uri.toString()
    val kind: Kind get() = kindOf(name, mime, isDir)

    companion object {
        fun of(f: File, countChildren: Boolean = false): FileItem {
            val dir = f.isDirectory
            return FileItem(
                name = f.name,
                uri = Uri.fromFile(f),
                size = if (dir) -1L else f.length(),
                modified = f.lastModified(),
                mime = if (dir) DIR_MIME else mimeOf(f.name),
                file = f,
                isDir = dir,
                children = if (dir && countChildren) (f.list()?.size ?: 0) else -1,
            )
        }
    }
}

enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, ZIP, APK, DOC, SHEET, SLIDES, OTHER }

private val TEXT_EXT = setOf(
    "txt", "md", "log", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "kt", "kts", "java",
    "py", "sh", "c", "h", "cpp", "yml", "yaml", "toml", "ini", "conf", "cfg", "srt", "vtt", "sql",
    "gradle", "properties", "vcf", "ics", "tex", "bat",
)
private val DOC_EXT = setOf("doc", "docx", "odt", "rtf", "pages")
private val SHEET_EXT = setOf("xls", "xlsx", "ods", "numbers")
private val SLIDES_EXT = setOf("ppt", "pptx", "odp", "key")

fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun mimeOf(name: String): String {
    val ext = extOf(name)
    if (ext.isEmpty()) return BINARY
    return when (ext) {
        "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "heic", "heif" -> "image/heif"
        "webp" -> "image/webp"
        "apk" -> "application/vnd.android.package-archive"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (ext in TEXT_EXT) "text/plain" else BINARY
    }
}

/** MIME type exactly as Android's MediaStore expects it for this file name (so it won't rename the file). */
fun storeMime(name: String): String =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(extOf(name)) ?: BINARY

fun kindOf(name: String, mime: String, isDir: Boolean): Kind {
    if (isDir) return Kind.FOLDER
    val ext = extOf(name)
    val m = if (mime.isBlank() || mime == BINARY || '*' in mime) mimeOf(name) else mime
    return when {
        ext == "pdf" || m == "application/pdf" -> Kind.PDF
        ext == "apk" -> Kind.APK
        ext == "zip" || m == "application/zip" -> Kind.ZIP
        ext in DOC_EXT -> Kind.DOC
        ext in SHEET_EXT -> Kind.SHEET
        ext in SLIDES_EXT -> Kind.SLIDES
        ext in TEXT_EXT -> Kind.TEXT
        m.startsWith("image/") -> Kind.IMAGE
        m.startsWith("video/") -> Kind.VIDEO
        m.startsWith("audio/") -> Kind.AUDIO
        m.startsWith("text/") || m == "application/json" || m == "application/xml" -> Kind.TEXT
        else -> Kind.OTHER
    }
}

fun formatSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

fun formatTime(ms: Long): String =
    if (ms <= 0) "" else DateUtils.getRelativeTimeSpanString(
        ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

private val BAD_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")

/** A file name that is safe to create on disk. */
fun safeName(raw: String?): String {
    var n = (raw ?: "").replace(BAD_CHARS, "_").trim().trimStart('.')
    if (n.isBlank()) n = "file"
    if (n.length > 150) {
        val ext = extOf(n).take(10)
        n = n.take(140) + if (ext.isNotEmpty()) ".$ext" else ""
    }
    return n
}

/** Adds an extension from the MIME type when the name has none. */
fun withExtension(name: String, mime: String?): String {
    if (extOf(name).isNotEmpty() || mime.isNullOrBlank() || '*' in mime) return name
    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
    return "$name.$ext"
}
