package com.mulhimfy.filerelay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Sort(val label: String) { NAME("Name"), NEWEST("Newest"), LARGEST("Largest") }

fun sortItems(items: List<FileItem>, sort: Sort): List<FileItem> {
    val dirsFirst = compareBy<FileItem> { !it.isDir }
    return when (sort) {
        Sort.NAME -> items.sortedWith(dirsFirst.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        Sort.NEWEST -> items.sortedWith(dirsFirst.thenByDescending { it.modified })
        Sort.LARGEST -> items.sortedWith(dirsFirst.thenByDescending { it.size })
    }
}

object Storage {
    const val RELAY_FOLDER = "FileRelay"

    val primary: File get() = Environment.getExternalStorageDirectory()
    val downloads: File get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val relayDir: File get() = File(downloads, RELAY_FOLDER)

    /** Whether we can read shared storage (WhatsApp folders, Downloads, ...). */
    fun hasAccess(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else granted(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** Android 8–9 need a runtime permission before writing into Downloads. */
    fun canWriteDownloads(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 || granted(ctx, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    @RequiresApi(30)
    fun allFilesIntent(ctx: Context) =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))

    fun volumes(ctx: Context): List<Pair<String, File>> {
        val out = mutableListOf("Internal storage" to primary)
        for (appDir in ctx.getExternalFilesDirs(null).filterNotNull()) {
            val path = appDir.absolutePath
            val cut = path.indexOf("/Android/data/")
            if (cut <= 0) continue
            val root = File(path.substring(0, cut))
            if (root.absolutePath != primary.absolutePath && out.none { it.second == root }) {
                out += (if (out.size == 1) "SD card" else "SD card ${out.size}") to root
            }
        }
        return out
    }

    fun shortcuts(): List<Pair<String, File>> = listOfNotNull(
        "Downloads" to downloads,
        "FileRelay" to relayDir,
        firstDir(File(primary, "Android/media/com.whatsapp/WhatsApp/Media"), File(primary, "WhatsApp/Media"))
            ?.let { "WhatsApp" to it },
        firstDir(
            File(primary, "Android/media/org.telegram.messenger/Telegram"),
            File(primary, "Android/media/org.telegram.messenger.web/Telegram"),
            File(primary, "Telegram"),
        )?.let { "Telegram" to it },
        "Camera" to File(primary, "DCIM"),
        "Documents" to File(primary, "Documents"),
    ).filter { it.second.isDirectory }

    private fun firstDir(vararg dirs: File): File? = dirs.firstOrNull { it.isDirectory }

    suspend fun list(dir: File, showHidden: Boolean, sort: Sort): List<FileItem> = withContext(Dispatchers.IO) {
        val files = dir.listFiles() ?: emptyArray()
        sortItems(files.filter { showHidden || !it.name.startsWith(".") }.map { FileItem.of(it, countChildren = true) }, sort)
    }
}
