package app.voidfiles.data

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** One file or folder inside an archive. [path] never starts or ends with "/". */
data class ArchiveItem(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val time: Long,
) {
    val name: String get() = path.substringAfterLast('/')
    val parent: String get() = if ('/' in path) path.substringBeforeLast('/') else ""
}

/** An opened archive: its entries and a folder tree built from their paths. */
class ArchiveListing(
    val archive: Node,
    val type: ArchiveType,
    val file: File,
    val isTemp: Boolean,
    val password: String?,
    val entries: List<ArchiveItem>,
) {
    private val children: Map<String, List<ArchiveItem>>

    init {
        val all = LinkedHashMap<String, ArchiveItem>()
        for (e in entries) {
            all[e.path] = e
            var p = e.parent
            while (p.isNotEmpty() && p !in all) {
                all[p] = ArchiveItem(p, isDirectory = true, size = 0, time = 0)
                p = if ('/' in p) p.substringBeforeLast('/') else ""
            }
        }
        children = all.values.groupBy { it.parent }
    }

    fun list(dir: String): List<ArchiveItem> =
        (children[dir] ?: emptyList()).sortedWith(compareBy<ArchiveItem> { !it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /** Total uncompressed size below [item]. */
    fun sizeOf(item: ArchiveItem): Long =
        if (!item.isDirectory) item.size else entries.filter { it.path.startsWith(item.path + "/") }.sumOf { it.size.coerceAtLeast(0) }

    fun close() {
        if (isTemp) file.delete()
    }
}

object ArchiveBrowser {

    private fun clean(path: String) = path.replace('\\', '/').trim('/')

    fun open(fs: FileSystem, archive: Node, cacheDir: File, password: String?): ArchiveListing {
        val type = Archives.typeOf(archive.name) ?: throw IOException("Unbekanntes Archivformat")
        val file = fs.asLocalFile(archive, cacheDir)
        val temp = archive !is LocalNode
        try {
            val entries = when (type) {
                ArchiveType.ZIP -> ZipFile(file).use { zip ->
                    zip.fileHeaders.map {
                        ArchiveItem(clean(it.fileName), it.isDirectory, it.uncompressedSize, it.lastModifiedTimeEpoch)
                    }
                }
                ArchiveType.SEVEN_Z -> sevenZ(file, password).use { z ->
                    z.entries.map {
                        ArchiveItem(clean(it.name), it.isDirectory, it.size, if (it.hasLastModifiedDate) it.lastModifiedDate.time else 0)
                    }
                }
                ArchiveType.RAR -> rar(file) { r ->
                    r.fileHeaders.map {
                        ArchiveItem(clean(it.fileName), it.isDirectory, it.fullUnpackSize, it.mTime?.time ?: 0)
                    }
                }
                ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ -> {
                    val out = ArrayList<ArchiveItem>()
                    tarStream(type, file).use { tin ->
                        while (true) {
                            val e = tin.nextEntry ?: break
                            out += ArchiveItem(clean(e.name), e.isDirectory, e.size, e.lastModifiedDate?.time ?: 0)
                        }
                    }
                    out
                }
                ArchiveType.GZ, ArchiveType.BZ2, ArchiveType.XZ ->
                    listOf(ArchiveItem(Archives.baseName(archive.name), false, -1, archive.lastModified))
            }.filter { it.path.isNotEmpty() }
            return ArchiveListing(archive, type, file, temp, password, entries)
        } catch (e: Throwable) {
            if (temp) file.delete()
            throw mapError(e)
        }
    }

    private fun mapError(e: Throwable): Throwable = when {
        e is PasswordRequiredException -> PasswordRequired()
        e is UnsupportedRarV5Exception -> IOException("RAR5-Archive werden leider nicht unterstützt (nur RAR4)")
        e is ZipException && e.type == ZipException.Type.WRONG_PASSWORD -> IOException("Falsches Passwort")
        else -> e
    }

    private fun sevenZ(file: File, password: String?): SevenZFile {
        val b = SevenZFile.builder().setFile(file)
        if (password != null) b.setPassword(password.toCharArray())
        return b.get()
    }

    private fun <T> rar(file: File, block: (Archive) -> T): T = Archive(file).use { r ->
        if (r.isEncrypted) throw IOException("Verschlüsselte RAR-Archive werden nicht unterstützt")
        block(r)
    }

    private fun tarStream(type: ArchiveType, file: File): TarArchiveInputStream {
        val raw: InputStream = BufferedInputStream(file.inputStream(), 256 * 1024)
        val inner: InputStream = when (type) {
            ArchiveType.TAR_GZ -> GzipCompressorInputStream(raw, true)
            ArchiveType.TAR_BZ2 -> BZip2CompressorInputStream(raw, true)
            ArchiveType.TAR_XZ -> XZCompressorInputStream(raw, true)
            else -> raw
        }
        return TarArchiveInputStream(inner)
    }

    /**
     * Extracts the selected [items] (files or whole folders) into [dest]. Paths are written relative to
     * [baseDir], the archive folder the user is looking at.
     */
    fun extract(
        fs: FileSystem,
        listing: ArchiveListing,
        items: List<ArchiveItem>,
        baseDir: String,
        dest: Node,
        sink: ProgressSink,
    ) {
        val writer = Archives.EntryWriter(fs, dest, sink)
        val prefixes = items.map { it.path }
        fun selected(path: String) = prefixes.any { path == it || path.startsWith("$it/") }
        fun relative(path: String) = if (baseDir.isEmpty()) path else path.removePrefix("$baseDir/")
        val pw = listing.password
        try {
            when (listing.type) {
                ArchiveType.ZIP -> {
                    val zip = if (pw != null) ZipFile(listing.file, pw.toCharArray()) else ZipFile(listing.file)
                    zip.use {
                        for (h in zip.fileHeaders) {
                            sink.checkCancelled()
                            val path = clean(h.fileName)
                            if (path.isEmpty() || !selected(path)) continue
                            if (h.isDirectory) {
                                writer.ensureDir(relative(path)); continue
                            }
                            if (h.isEncrypted && pw == null) throw PasswordRequired()
                            zip.getInputStream(h).use { writer.writeFile(relative(path), it, closeInput = false) }
                        }
                    }
                }
                ArchiveType.SEVEN_Z -> sevenZ(listing.file, pw).use { z ->
                    for (e in z.entries) {
                        sink.checkCancelled()
                        val path = clean(e.name)
                        if (path.isEmpty() || !selected(path)) continue
                        if (e.isDirectory) writer.ensureDir(relative(path))
                        else writer.writeFile(relative(path), z.getInputStream(e), closeInput = false)
                    }
                }
                ArchiveType.RAR -> rar(listing.file) { r ->
                    for (h in r.fileHeaders) {
                        sink.checkCancelled()
                        val path = clean(h.fileName)
                        if (path.isEmpty() || !selected(path)) continue
                        if (h.isDirectory) writer.ensureDir(relative(path))
                        else writer.writeWith(relative(path)) { out -> r.extractFile(h, out) }
                    }
                }
                ArchiveType.TAR, ArchiveType.TAR_GZ, ArchiveType.TAR_BZ2, ArchiveType.TAR_XZ ->
                    tarStream(listing.type, listing.file).use { tin -> extractTar(tin, writer, sink, ::selected, ::relative) }
                ArchiveType.GZ, ArchiveType.BZ2, ArchiveType.XZ -> {
                    val raw = BufferedInputStream(listing.file.inputStream(), 256 * 1024)
                    val input = when (listing.type) {
                        ArchiveType.GZ -> GzipCompressorInputStream(raw, true)
                        ArchiveType.BZ2 -> BZip2CompressorInputStream(raw, true)
                        else -> XZCompressorInputStream(raw, true)
                    }
                    writer.writeFile(Archives.baseName(listing.archive.name), input)
                }
            }
        } catch (e: Throwable) {
            throw mapError(e)
        }
    }

    private fun extractTar(
        tin: ArchiveInputStream<TarArchiveEntry>,
        writer: Archives.EntryWriter,
        sink: ProgressSink,
        selected: (String) -> Boolean,
        relative: (String) -> String,
    ) {
        while (true) {
            sink.checkCancelled()
            val e = tin.nextEntry ?: break
            val path = clean(e.name)
            if (path.isEmpty() || !selected(path)) continue
            if (e.isDirectory) writer.ensureDir(relative(path))
            else writer.writeFile(relative(path), tin, closeInput = false)
        }
    }
}
