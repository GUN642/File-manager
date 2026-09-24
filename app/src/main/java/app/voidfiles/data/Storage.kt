package app.voidfiles.data

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File
import java.io.IOException

data class StorageVolumeInfo(val label: String, val root: File, val isPrimary: Boolean) {
    val total: Long get() = runCatching { StatFs(root.absolutePath).totalBytes }.getOrDefault(0L)
    val free: Long get() = runCatching { StatFs(root.absolutePath).availableBytes }.getOrDefault(0L)
}

data class QuickFolder(val label: String, val file: File)

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

    fun quickFolders(): List<QuickFolder> = listOf(
        QuickFolder("Downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)),
        QuickFolder("Kamera", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)),
        QuickFolder("Bilder", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)),
        QuickFolder("Dokumente", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)),
        QuickFolder("Musik", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)),
        QuickFolder("Videos", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)),
    ).filter { it.file.exists() }

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
