package app.voidfiles.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.io.IOException

data class StorageVolumeInfo(val label: String, val root: File, val isPrimary: Boolean) {
    val total: Long get() = runCatching { StatFs(root.absolutePath).totalBytes }.getOrDefault(0L)
    val free: Long get() = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L)
}


object Storage {
    val primaryRoot: File get() = Environment.getExternalStorageDirectory()

    fun volumes(context: Context): List<StorageVolumeInfo> {
        val result = ArrayList<StorageVolumeInfo>()
        val sm = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (v in sm.storageVolumes) {
                val dir = v.directory ?: continue
                val label = if (v.isPrimary) "Interner Speicher" else (v.getDescription(context) ?: "SD-Karte")
                result += StorageVolumeInfo(label, dir, v.isPrimary)
            }
        } else {
            result += StorageVolumeInfo("Interner Speicher", primaryRoot, true)
            // Secondary volumes on older Android: derive roots from app-specific external dirs.
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { f ->
                val root = f.absolutePath.substringBefore("/Android/")
                result += StorageVolumeInfo("SD-Karte", File(root), false)
            }
        }
        if (result.none { it.isPrimary }) result.add(0, StorageVolumeInfo("Interner Speicher", primaryRoot, true))
        return result
    }

    private val defaultDirs = listOf(
        Environment.DIRECTORY_DOWNLOADS to "Downloads",
        Environment.DIRECTORY_DCIM to "Kamera",
        Environment.DIRECTORY_PICTURES to "Bilder",
        Environment.DIRECTORY_DOCUMENTS to "Dokumente",
        Environment.DIRECTORY_MUSIC to "Musik",
        Environment.DIRECTORY_MOVIES to "Videos",
    )

    fun defaultQuickPaths(): List<String> =
        defaultDirs.map { Environment.getExternalStoragePublicDirectory(it.first) }.filter { it.exists() }.map { it.absolutePath }

    /** Friendly German name for well-known folders, otherwise the folder name. */
    fun labelFor(path: String): String {
        defaultDirs.firstOrNull { Environment.getExternalStoragePublicDirectory(it.first).absolutePath == path }?.let { return it.second }
        if (path == primaryRoot.absolutePath) return "Interner Speicher"
        return File(path).name.ifEmpty { path }
    }

    /**
     * Most recently modified files on all volumes, newest first, via the MediaStore index
     * (available to us because the app holds "all files" access).
     */
    fun recentFiles(context: Context, limit: Int = 200, showHidden: Boolean = false): List<LocalNode> {
        val uri = MediaStore.Files.getContentUri("external")
        val data = MediaStore.Files.FileColumns.DATA
        val projection = arrayOf(data, MediaStore.Files.FileColumns.DATE_MODIFIED)
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL")
            append(" AND $data NOT LIKE ?")
            if (!showHidden) append(" AND $data NOT LIKE ?")
        }
        val args = if (showHidden) arrayOf("%/.VoidTrash/%") else arrayOf("%/.VoidTrash/%", "%/.%")
        val out = ArrayList<LocalNode>()
        context.contentResolver.query(uri, projection, selection, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC")?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(0) ?: continue
                val f = File(path)
                if (f.isFile) out += LocalNode.of(f)
            }
        }
        return out.sortedByDescending { it.lastModified }
    }

    /** The volume root that contains [file], used to build breadcrumb chains. */
    fun rootFor(context: Context, file: File): File? {
        val path = file.absolutePath
        return volumes(context).map { it.root }
            .filter { path == it.absolutePath || path.startsWith(it.absolutePath + "/") }
            .maxByOrNull { it.absolutePath.length }
    }

    /** Folders from the volume root down to [file] (inclusive). */
    fun chain(context: Context, file: File): List<File> {
        val root = rootFor(context, file)
        val out = ArrayList<File>()
        var cur: File? = file
        while (cur != null) {
            out += cur
            if (root != null && cur.absolutePath == root.absolutePath) break
            cur = cur.parentFile
        }
        return out.reversed()
    }
}

/** Simple trash: items are moved into ".VoidTrash" on the same volume, with a sidecar storing the original path. */
class Trash(private val context: Context) {
    data class Entry(val stored: File, val originalPath: String, val deletedAt: Long) {
        val name: String get() = File(originalPath).name
    }

    private fun trashDirFor(file: File): File {
        val root = Storage.rootFor(context, file) ?: Storage.primaryRoot
        return File(root, ".VoidTrash").apply { mkdirs() }
    }

    fun allTrashDirs(): List<File> =
        Storage.volumes(context).map { File(it.root, ".VoidTrash") }.filter { it.isDirectory }

    fun moveToTrash(file: File) {
        val dir = trashDirFor(file)
        if (file.absolutePath.startsWith(dir.absolutePath)) {
            if (!file.deleteRecursively()) throw IOException("Löschen fehlgeschlagen")
            return
        }
        val id = "${System.currentTimeMillis()}_${(0..9999).random()}"
        val stored = File(dir, id)
        if (!file.renameTo(stored)) {
            // Different mount point: copy then delete.
            file.copyRecursively(stored, overwrite = true)
            if (!file.deleteRecursively()) throw IOException("\"${file.name}\" konnte nicht gelöscht werden")
        }
        File(dir, "$id.meta").writeText(file.absolutePath)
    }

    fun entries(): List<Entry> = allTrashDirs().flatMap { dir ->
        dir.listFiles().orEmpty().filter { !it.name.endsWith(".meta") }.mapNotNull { stored ->
            val meta = File(dir, stored.name + ".meta")
            val original = if (meta.exists()) meta.readText().trim() else return@mapNotNull null
            Entry(stored, original, stored.name.substringBefore('_').toLongOrNull() ?: stored.lastModified())
        }
    }.sortedByDescending { it.deletedAt }

    fun restore(entry: Entry): File {
        var target = File(entry.originalPath)
        target.parentFile?.mkdirs()
        if (target.exists()) {
            val base = target.nameWithoutExtension
            val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
            var i = 1
            while (target.exists()) {
                target = File(target.parentFile, "$base ($i)$ext"); i++
            }
        }
        if (!entry.stored.renameTo(target)) {
            entry.stored.copyRecursively(target, overwrite = true)
            entry.stored.deleteRecursively()
        }
        File(entry.stored.parentFile, entry.stored.name + ".meta").delete()
        return target
    }

    fun deleteForever(entry: Entry) {
        entry.stored.deleteRecursively()
        File(entry.stored.parentFile, entry.stored.name + ".meta").delete()
    }

    fun empty() = entries().forEach { deleteForever(it) }
}
