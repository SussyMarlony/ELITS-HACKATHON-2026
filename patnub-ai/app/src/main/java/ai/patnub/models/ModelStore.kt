package ai.patnub.models

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
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
            p.id to (_state.value[p.id]?.takeIf { it is InstallState.Downloading || it is InstallState.Verifying }
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

    /**
     * Copies model files the user picked (USB, SD card, a browser download). Files are matched
     * to the catalog by size, then verified. Returns how many files were recognised.
     */
    suspend fun import(p: ModelPackage, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        var matched = 0
        for (uri in uris) {
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
            val f = p.files.firstOrNull { it.bytes == size } ?: continue
            matched++
            marker(f).delete()
            var copied = 0L
            context.contentResolver.openInputStream(uri)!!.use { input ->
                fileFor(f).outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        set(p, InstallState.Downloading(copied.toFloat() / f.bytes))
                    }
                }
            }
        }
        if (matched > 0) verify(p) else set(p, InstallState.Failed("Those files are not the ${p.title} model files."))
        matched
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
