package app.voidfiles.data

import com.github.junrar.Archive
import com.github.junrar.exception.UnsupportedRarV5Exception
import net.lingala.zip4j.exception.ZipException
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.apache.commons.compress.PasswordRequiredException
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class PasswordRequired(message: String = "Dieses Archiv ist passwortgeschützt") : IOException(message)

enum class ArchiveType { ZIP, SEVEN_Z, RAR, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ }

object Archives {

    fun typeOf(name: String): ArchiveType? {
        val n = name.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> ArchiveType.TAR_GZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> ArchiveType.TAR_BZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> ArchiveType.TAR_XZ
            n.endsWith(".zip") || n.endsWith(".jar") || n.endsWith(".cbz") -> ArchiveType.ZIP
            n.endsWith(".7z") || n.endsWith(".cb7") -> ArchiveType.SEVEN_Z
            n.endsWith(".rar") || n.endsWith(".cbr") -> ArchiveType.RAR
            n.endsWith(".tar") -> ArchiveType.TAR
            n.endsWith(".gz") -> ArchiveType.GZ
            n.endsWith(".bz2") -> ArchiveType.BZ2
            n.endsWith(".xz") -> ArchiveType.XZ
            else -> null
        }
    }

    fun isArchive(name: String) = typeOf(name) != null

    /** Name of the folder an archive would be extracted into ("fotos.tar.gz" -> "fotos"). */
    fun baseName(name: String): String {
        val lower = name.lowercase()
        val suffixes = listOf(".tar.gz", ".tar.bz2", ".tar.xz", ".tgz", ".tbz2", ".tbz", ".txz", ".zip", ".jar", ".cbz",
            ".7z", ".cb7", ".rar", ".cbr", ".tar", ".gz", ".bz2", ".xz")
        val s = suffixes.firstOrNull { lower.endsWith(it) } ?: return name
        return name.substring(0, name.length - s.length).ifEmpty { name }
    }

    /**
     * Extracts [archive] into [destDir]. Works for local and SAF sources/targets.
     * Throws [PasswordRequired] when a password is needed but [password] is null.
     */
    fun extract(
        fs: FileSystem,
        archive: Node,
        destDir: Node,
        password: String?,
        cacheDir: File,
        sink: ProgressSink,
    ) {
        val type = typeOf(archive.name) ?: throw IOException("Unbekanntes Archivformat")
        val writer = EntryWriter(fs, destDir, sink)
        when (type) {
            ArchiveType.ZIP -> extractZip(fs, archive, writer, password, sink)
            ArchiveType.SEVEN_Z -> withLocal(fs, archive, cacheDir) { extract7z(it, writer, password, sink) }
            ArchiveType.RAR -> withLocal(fs, archive, cacheDir) { extractRar(it, writer, sink) }
            ArchiveType.TAR -> extractStream(TarArchiveInputStream(buffered(fs, archive)), writer, sink)
            ArchiveType.TAR_GZ -> extractStream(TarArchiveInputStream(GzipCompressorInputStream(buffered(fs, archive), true)), writer, sink)
            ArchiveType.TAR_BZ2 -> extractStream(TarArchiveInputStream(BZip2CompressorInputStream(buffered(fs, archive), true)), writer, sink)
            ArchiveType.TAR_XZ -> extractStream(TarArchiveInputStream(XZCompressorInputStream(buffered(fs, archive), true)), writer, sink)
            ArchiveType.GZ -> writer.writeFile(baseName(archive.name), GzipCompressorInputStream(buffered(fs, archive), true))
            ArchiveType.BZ2 -> writer.writeFile(baseName(archive.name), BZip2CompressorInputStream(buffered(fs, archive), true))
            ArchiveType.XZ -> writer.writeFile(baseName(archive.name), XZCompressorInputStream(buffered(fs, archive), true))
        }
    }

    private fun buffered(fs: FileSystem, node: Node): InputStream = BufferedInputStream(fs.openInput(node), 256 * 1024)

    private inline fun withLocal(fs: FileSystem, node: Node, cacheDir: File, block: (File) -> Unit) {
        val file = fs.asLocalFile(node, cacheDir)
        try {
            block(file)
        } finally {
            if (node !is LocalNode) file.delete()
        }
    }

    private fun extractZip(fs: FileSystem, archive: Node, writer: EntryWriter, password: String?, sink: ProgressSink) {
        try {
            ZipInputStream(buffered(fs, archive), password?.toCharArray()).use { zin ->
                while (true) {
                    sink.checkCancelled()
                    val header = zin.nextEntry ?: break
                    if (header.isEncrypted && password == null) throw PasswordRequired()
                    if (header.isDirectory) writer.ensureDir(header.fileName)
                    else writer.writeFile(header.fileName, zin, closeInput = false)
                }
            }
        } catch (e: ZipException) {
            if (e.type == ZipException.Type.WRONG_PASSWORD) throw IOException("Falsches Passwort")
            throw IOException(e.message ?: "ZIP-Archiv konnte nicht gelesen werden", e)
        }
    }

    private fun extract7z(file: File, writer: EntryWriter, password: String?, sink: ProgressSink) {
        val builder = SevenZFile.builder().setFile(file)
        if (password != null) builder.setPassword(password.toCharArray())
        try {
            builder.get().use { z ->
                while (true) {
                    sink.checkCancelled()
                    val entry = z.nextEntry ?: break
                    if (entry.isDirectory) writer.ensureDir(entry.name)
                    else writer.writeFile(entry.name, z.getInputStream(entry), closeInput = false)
                }
            }
        } catch (e: PasswordRequiredException) {
            throw PasswordRequired()
        }
    }

    private fun extractRar(file: File, writer: EntryWriter, sink: ProgressSink) {
        try {
            Archive(file).use { rar ->
                if (rar.isEncrypted) throw IOException("Verschlüsselte RAR-Archive werden nicht unterstützt")
                for (header in rar.fileHeaders) {
                    sink.checkCancelled()
                    val name = header.fileName
                    if (header.isDirectory) {
                        writer.ensureDir(name)
                    } else {
                        if (header.isEncrypted) throw IOException("Verschlüsselte RAR-Archive werden nicht unterstützt")
                        writer.writeWith(name) { out -> rar.extractFile(header, out) }
                    }
                }
            }
        } catch (e: UnsupportedRarV5Exception) {
            throw IOException("RAR5-Archive werden leider nicht unterstützt (nur RAR4)")
        }
    }

    private fun <E : org.apache.commons.compress.archivers.ArchiveEntry> extractStream(
        input: ArchiveInputStream<E>,
        writer: EntryWriter,
        sink: ProgressSink,
    ) {
        input.use { ain ->
            while (true) {
                sink.checkCancelled()
                val entry = ain.nextEntry ?: break
                if (!ain.canReadEntryData(entry)) continue
                if (entry.isDirectory) writer.ensureDir(entry.name)
                else writer.writeFile(entry.name, ain, closeInput = false)
            }
        }
    }

    /** Creates a ZIP archive (optionally AES-256 encrypted) from [sources] in [destDir]. */
    fun compressZip(
        fs: FileSystem,
        sources: List<Node>,
        destDir: Node,
        zipName: String,
        password: String?,
        sink: ProgressSink,
    ): Node {
        val target = fs.createFile(destDir, fs.uniqueName(destDir, zipName), "application/zip")
        try {
            val pw = password?.takeIf { it.isNotEmpty() }?.toCharArray()
            ZipOutputStream(fs.openOutput(target), pw).use { zout ->
                for (src in sources) addToZip(fs, zout, src, "", pw != null, sink)
            }
        } catch (e: Throwable) {
            runCatching { fs.delete(target) }
            throw e
        }
        return target
    }

    private fun addToZip(fs: FileSystem, zout: ZipOutputStream, node: Node, prefix: String, encrypt: Boolean, sink: ProgressSink) {
        sink.checkCancelled()
        val path = prefix + node.name
        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            lastModifiedFileTime = if (node.lastModified > 0) node.lastModified else System.currentTimeMillis()
            if (encrypt && !node.isDirectory) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        if (node.isDirectory) {
            params.fileNameInZip = "$path/"
            zout.putNextEntry(params)
            zout.closeEntry()
            for (child in fs.list(node)) addToZip(fs, zout, child, "$path/", encrypt, sink)
        } else {
            sink.onFile(node.name)
            params.fileNameInZip = path
            zout.putNextEntry(params)
            fs.openInput(node).use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    sink.checkCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    zout.write(buf, 0, n)
                    sink.onBytes(n.toLong())
                }
            }
            zout.closeEntry()
        }
    }

    /** Writes archive entries below a destination, creating folders on demand and blocking path traversal. */
    private class EntryWriter(private val fs: FileSystem, private val root: Node, private val sink: ProgressSink) {
        private val dirs = HashMap<String, Node>()

        private fun segments(path: String): List<String> {
            val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (parts.any { it == ".." }) throw IOException("Unsicherer Pfad im Archiv: $path")
            return parts
        }

        fun ensureDir(path: String): Node = dirFor(segments(path))

        private fun dirFor(parts: List<String>): Node {
            var current = root
            val key = StringBuilder()
            for (part in parts) {
                key.append('/').append(part)
                val k = key.toString()
                current = dirs[k] ?: run {
                    val found = fs.findChild(current, part)
                    val dir = if (found != null && found.isDirectory) found else fs.createDirectory(current, part)
                    dirs[k] = dir
                    dir
                }
            }
            return current
        }

        fun writeFile(path: String, input: InputStream, closeInput: Boolean = true) {
            writeWith(path) { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    sink.checkCancelled()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sink.onBytes(n.toLong())
                }
            }
            if (closeInput) input.close()
        }

        fun writeWith(path: String, block: (OutputStream) -> Unit) {
            val parts = segments(path)
            if (parts.isEmpty()) return
            val dir = dirFor(parts.dropLast(1))
            val name = parts.last()
            sink.onFile(name)
            fs.findChild(dir, name)?.let { if (!it.isDirectory) fs.delete(it) }
            val file = fs.createFile(dir, name)
            fs.openOutput(file).use(block)
        }
    }
}
