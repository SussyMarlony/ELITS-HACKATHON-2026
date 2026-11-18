package ai.patnub.models

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface InstallState {
    data object NotInstalled : InstallState
    /** [progress] 0..1 over all files of the package. */
    data class Downloading(val progress: Float) : InstallState
    data class Verifying(val progress: Float) : InstallState
    /** Copying imported files into the app, [progress] 0..1. */
    data class Copying(val progress: Float) : InstallState
    data object Ready : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * Model files live in the app's own external storage folder (no storage permission, removed
 * with the app). A file counts only after its SHA-256 matches the catalog; a `.ok` marker
 * records that so the 2–3 GB hash runs once.
 */
class ModelStore(private val context: Context) {
    private val dir: File get() = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("models", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<Map<String, InstallState>>(emptyMap())
    val state: StateFlow<Map<String, InstallState>> = _state.asStateFlow()

    val totalRamBytes: Long by lazy {
        ActivityManager.MemoryInfo().also { context.getSystemService<ActivityManager>()!!.getMemoryInfo(it) }.totalMem
    }

    /** The package this phone uses: the user's choice if any, else the one for its RAM tier. */
    var selected: ModelPackage
        get() = prefs.getString("selected", null)?.let(ModelCatalog::byId) ?: ModelCatalog.recommendedFor(totalRamBytes)
        set(value) { prefs.edit().putString("selected", value.id).apply() }

    fun fileFor(f: ModelFile) = File(dir, f.fileName)
    private fun marker(f: ModelFile) = File(dir, f.fileName + ".ok")
    fun isVerified(f: ModelFile) = fileFor(f).length() == f.bytes && marker(f).readTextOrNull() == f.sha256
    fun isReady(p: ModelPackage) = p.files.all(::isVerified)

    fun refresh() {
        _state.value = ModelCatalog.packages.associate { p ->
            p.id to (_state.value[p.id]?.takeIf { it is InstallState.Downloading || it is InstallState.Verifying || it is InstallState.Copying }
                ?: if (isReady(p)) InstallState.Ready else InstallState.NotInstalled)
        }
    }

    private fun set(p: ModelPackage, s: InstallState) { _state.value = _state.value + (p.id to s) }

    /** Free space on the storage that holds the models. */
    fun freeBytes(): Long = dir.usableSpace

    /**
     * Downloads missing files with the system download manager (resumes after network drops,
     * keeps going in the background), then verifies them. Suspends until done.
     */
    suspend fun download(p: ModelPackage) {
        val dm = context.getSystemService<DownloadManager>()!!
        val missing = p.files.filterNot(::isVerified)
        val need = missing.sumOf { it.bytes - fileFor(it).length().coerceAtLeast(0) }
        if (need > freeBytes()) {
            set(p, InstallState.Failed("Not enough free space: need ${need.gib()} GB, have ${freeBytes().gib()} GB."))
            return
        }
        val ids = missing.associateWith { f ->
            fileFor(f).delete()
            dm.enqueue(
                DownloadManager.Request(f.url.toUri())
                    .setTitle("patnub AI model")
                    .setDescription(f.fileName)
                    .setDestinationUri(Uri.fromFile(fileFor(f)))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true),
            )
        }
        val doneBytes = p.files.filter(::isVerified).sumOf { it.bytes }
        while (true) {
            var got = doneBytes
            var finished = 0
            for ((f, id) in ids) {
                dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                    if (!c.moveToFirst()) { set(p, InstallState.Failed("Download was cancelled.")); return }
                    when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> { got += f.bytes; finished++ }
                        DownloadManager.STATUS_FAILED -> {
                            set(p, InstallState.Failed("Download failed (code ${c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}). Try again on Wi-Fi."))
                            return
                        }
                        else -> got += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                    }
                }
            }
            set(p, InstallState.Downloading(got.toFloat() / p.totalBytes))
            if (finished == ids.size) break
            delay(1_000)
        }
        verify(p)
    }

    /** Which catalog files the user picked (USB, SD card, a browser download), by exact size. Others are left out. */
    suspend fun identify(uris: List<Uri>): Map<Uri, ModelFile> = withContext(Dispatchers.IO) {
        uris.mapNotNull { uri -> sizeOf(uri)?.let(ModelCatalog::fileOfSize)?.let { uri to it } }.toMap()
    }

    private fun sizeOf(uri: Uri): Long? {
        val fd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
        if (fd != null && fd >= 0) return fd
        // Some providers report an unknown length on the descriptor but know it in the metadata.
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    }

    /**
     * Copies the [files] that belong to [p] into the app, then verifies the package. A failed copy
     * removes its partial file, so a half-copied model is never mistaken for a real one.
     */
    suspend fun import(p: ModelPackage, files: Map<Uri, ModelFile>) = withContext(Dispatchers.IO) {
        val toCopy = files.filterValues { it in p.files && !isVerified(it) }
        val need = toCopy.values.sumOf { it.bytes }
        if (need > freeBytes()) {
            set(p, InstallState.Failed("Not enough free space: need ${need.gib()} GB, have ${freeBytes().gib()} GB."))
            return@withContext
        }
        var copied = 0L
        for ((uri, f) in toCopy) {
            marker(f).delete()
            try {
                val input = context.contentResolver.openInputStream(uri) ?: error("cannot open ${f.fileName}")
                input.use {
                    fileFor(f).outputStream().use { out ->
                        val buf = ByteArray(1 shl 20)
                        while (true) {
                            val n = it.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            copied += n
                            if (copied % (32L shl 20) < (1 shl 20)) set(p, InstallState.Copying(copied.toFloat() / need))
                        }
                    }
                }
            } catch (e: Exception) {
                fileFor(f).delete()
                set(p, InstallState.Failed("Copying ${f.fileName} failed: ${e.message ?: e.javaClass.simpleName}"))
                return@withContext
            }
        }
        verify(p)
    }

    private suspend fun verify(p: ModelPackage) = withContext(Dispatchers.IO) {
        var done = 0L
        for (f in p.files) {
            if (isVerified(f)) { done += f.bytes; continue }
            val file = fileFor(f)
            if (file.length() != f.bytes) { set(p, InstallState.Failed("${f.fileName} is missing or incomplete.")); return@withContext }
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    done += n
                    if (done % (64L shl 20) < (1 shl 20)) set(p, InstallState.Verifying(done.toFloat() / p.totalBytes))
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != f.sha256) {
                file.delete()
                set(p, InstallState.Failed("${f.fileName} did not match its checksum and was removed. Download it again."))
                return@withContext
            }
            marker(f).writeText(f.sha256)
        }
        set(p, InstallState.Ready)
    }

    /** Deletes files not shared with another installed package. */
    fun delete(p: ModelPackage) {
        val keep = ModelCatalog.packages.filter { it.id != p.id && isReady(it) }.flatMap { it.files }.toSet()
        p.files.filterNot { it in keep }.forEach { fileFor(it).delete(); marker(it).delete() }
        refresh()
    }

    private fun File.readTextOrNull() = runCatching { readText() }.getOrNull()
    private fun Long.gib() = "%.1f".format(this / (1L shl 30).toDouble())
}
