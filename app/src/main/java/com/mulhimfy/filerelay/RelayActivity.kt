package com.mulhimfy.filerelay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

open class RelayActivity : ComponentActivity() {
    private var permWaiter: CompletableDeferred<Boolean>? = null

    private val storagePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permWaiter?.complete(result.isNotEmpty() && result.values.all { it })
        permWaiter = null
    }

    /** True once we may write into Downloads (asks on Android 8–9, always true later). */
    suspend fun ensureDownloadsWrite(): Boolean {
        if (Storage.canWriteDownloads(this)) return true
        val waiter = CompletableDeferred<Boolean>()
        permWaiter = waiter
        storagePerms.launch(LEGACY_PERMS)
        return waiter.await()
    }

    /** Opens the "All files access" screen (Android 11+) or asks for storage permission. */
    fun requestAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Storage.allFilesIntent(this))
            } catch (e: Exception) {
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        } else {
            storagePerms.launch(LEGACY_PERMS)
        }
    }

    companion object {
        val LEGACY_PERMS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

/** Every user action (send, share, save, ...) with a busy indicator and error toasts. */
class AppActions(private val activity: RelayActivity, private val scope: CoroutineScope) {
    var busy by mutableStateOf<String?>(null)
        private set

    fun task(label: String?, block: suspend () -> Unit) {
        scope.launch {
            busy = label
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activity.toast(e.message ?: "Something went wrong")
            } finally {
                busy = null
            }
        }
    }

    fun telegram(items: List<FileItem>, text: String? = null, then: () -> Unit = {}) =
        task("Preparing…") { Relay.quickSend(activity, items, text); then() }

    fun share(items: List<FileItem>, text: String? = null, then: () -> Unit = {}) =
        task("Preparing…") { Relay.chooser(activity, items, text); then() }

    fun openWith(item: FileItem) = task("Preparing…") { Relay.openWith(activity, item) }

    fun save(items: List<FileItem>, then: () -> Unit = {}) = task("Saving…") {
        if (!activity.ensureDownloadsWrite()) throw IOException("Storage permission is needed to save")
        activity.toast(Relay.savedMessage(Relay.save(activity, items)))
        then()
    }

    fun download(url: String, then: () -> Unit = {}) = task("Starting download…") {
        if (!activity.ensureDownloadsWrite()) throw IOException("Storage permission is needed to download")
        activity.toast("Downloading ${Downloader.start(activity, url)}")
        then()
    }

    fun open(item: FileItem) {
        if (item.isDir) return
        try {
            activity.startActivity(ViewerActivity.intent(activity, item))
        } catch (e: Exception) {
            activity.toast("Can't open ${item.name}")
        }
    }

    fun delete(items: List<FileItem>, then: () -> Unit = {}) = task("Deleting…") {
        val failed = withContext(Dispatchers.IO) { items.count { it.file?.deleteRecursively() != true } }
        if (failed > 0) activity.toast("$failed item(s) couldn't be deleted")
        then()
    }
}

val LocalActions = staticCompositionLocalOf<AppActions> { error("AppActions not provided") }
