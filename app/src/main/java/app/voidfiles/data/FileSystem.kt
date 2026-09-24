package app.voidfiles.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Progress reporting for long running operations. */
interface ProgressSink {
    fun onFile(name: String)
    fun onBytes(bytes: Long)
    fun checkCancelled()
}

object NoProgress : ProgressSink {
    override fun onFile(name: String) {}
    override fun onBytes(bytes: Long) {}
    override fun checkCancelled() {}
}

enum class ConflictPolicy { OVERWRITE, KEEP_BOTH, SKIP }

/** All file operations, transparently working on local files and SAF documents. */
class FileSystem(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    // ---------------------------------------------------------------- listing

    fun list(dir: Node): List<Node> = when (dir) {
        is LocalNode -> dir.file.listFiles()?.map { LocalNode.of(it) } ?: emptyList()
        is SafNode -> listSaf(dir.treeUri, dir.documentId)
    }

    fun refresh(node: Node): Node? = when (node) {
        is LocalNode -> node.file.takeIf { it.exists() }?.let { LocalNode.of(it) }
        is SafNode -> querySaf(node.treeUri, node.documentId)
    }

    fun findChild(dir: Node, name: String): Node? = when (dir) {
        is LocalNode -> File(dir.file, name).takeIf { it.exists() }?.let { LocalNode.of(it) }
        is SafNode -> listSaf(dir.treeUri, dir.documentId).firstOrNull { it.name == name }
    }

    fun safRoot(treeUri: Uri): SafNode? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        return querySaf(treeUri, docId)
    }

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
    )

    private fun listSaf(tree: Uri, parentId: String): List<Node> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Node>()
        resolver.query(childrenUri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) out += readSaf(tree, c)
        }
        return out
    }

    private fun querySaf(tree: Uri, docId: String): SafNode? {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
        return try {
            resolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) readSaf(tree, c) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readSaf(tree: Uri, c: android.database.Cursor): SafNode {
        val mime = c.getString(2) ?: ""
        return SafNode(
            treeUri = tree,
            documentId = c.getString(0),
            name = c.getString(1) ?: "?",
            isDirectory = mime == Document.MIME_TYPE_DIR,
            size = if (c.isNull(3)) 0L else c.getLong(3),
            lastModified = if (c.isNull(4)) 0L else c.getLong(4),
            mime = mime,
            flags = if (c.isNull(5)) 0 else c.getInt(5),
        )
    }

    // ---------------------------------------------------------------- streams

    fun openInput(node: Node): InputStream = when (node) {
        is LocalNode -> FileInputStream(node.file)
        is SafNode -> resolver.openInputStream(node.uri) ?: throw IOException("Kann ${node.name} nicht lesen")
    }

    fun openOutput(node: Node): OutputStream = when (node) {
        is LocalNode -> FileOutputStream(node.file)
        is SafNode -> resolver.openOutputStream(node.uri, "wt") ?: throw IOException("Kann ${node.name} nicht schreiben")
    }

    /** Local file for libraries that need random access; SAF documents are copied into [cacheDir]. */
    fun asLocalFile(node: Node, cacheDir: File): File = when (node) {
        is LocalNode -> node.file
        is SafNode -> {
            val tmp = File(cacheDir, "saf_${System.currentTimeMillis()}_${node.name}")
            openInput(node).use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            tmp
        }
    }

    // ---------------------------------------------------------------- create / rename / delete

    fun createDirectory(parent: Node, name: String): Node = when (parent) {
        is LocalNode -> {
            val f = File(parent.file, name)
            if (!f.exists() && !f.mkdirs()) throw IOException("Ordner \"$name\" konnte nicht erstellt werden")
            LocalNode.of(f)
        }
        is SafNode -> {
            val uri = DocumentsContract.createDocument(resolver, parent.uri, Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Ordner \"$name\" konnte nicht erstellt werden")
            querySaf(parent.treeUri, DocumentsContract.getDocumentId(uri))
                ?: throw IOException("Ordner \"$name\" nicht gefunden")
        }
    }

    fun createFile(parent: Node, name: String, mime: String = Node.mimeFromName(name)): Node = when (parent) {
        is LocalNode -> {
            val f = File(parent.file, name)
            if (!f.exists() && !f.createNewFile()) throw IOException("Datei \"$name\" konnte nicht erstellt werden")
            LocalNode.of(f)
        }
        is SafNode -> {
            val uri = DocumentsContract.createDocument(resolver, parent.uri, mime, name)
                ?: throw IOException("Datei \"$name\" konnte nicht erstellt werden")
            querySaf(parent.treeUri, DocumentsContract.getDocumentId(uri))
                ?: throw IOException("Datei \"$name\" nicht gefunden")
        }
    }

    fun rename(node: Node, newName: String): Node = when (node) {
        is LocalNode -> {
            val target = File(node.file.parentFile, newName)
            if (target.exists()) throw IOException("\"$newName\" existiert bereits")
            if (!node.file.renameTo(target)) throw IOException("Umbenennen fehlgeschlagen")
            LocalNode.of(target)
        }
        is SafNode -> {
            val uri = DocumentsContract.renameDocument(resolver, node.uri, newName)
                ?: throw IOException("Umbenennen fehlgeschlagen")
            querySaf(node.treeUri, DocumentsContract.getDocumentId(uri)) ?: node.copy(name = newName)
        }
    }

    fun delete(node: Node) {
        when (node) {
            is LocalNode -> if (!node.file.deleteRecursively()) throw IOException("\"${node.name}\" konnte nicht gelöscht werden")
            is SafNode -> if (!DocumentsContract.deleteDocument(resolver, node.uri)) {
                throw IOException("\"${node.name}\" konnte nicht gelöscht werden")
            }
        }
    }

    // ---------------------------------------------------------------- size helpers

    /** Total bytes and file count below [node] (for progress and properties). */
    fun measure(node: Node, sink: ProgressSink = NoProgress): Pair<Long, Int> {
        if (!node.isDirectory) return node.size to 1
        if (node is LocalNode) {
            var bytes = 0L
            var count = 0
            node.file.walkTopDown().forEach {
                sink.checkCancelled()
                if (it.isFile) {
                    bytes += it.length(); count++
                }
            }
            return bytes to count
        }
        var bytes = 0L
        var count = 0
        for (child in list(node)) {
            sink.checkCancelled()
            val (b, c) = measure(child, sink)
            bytes += b; count += c
        }
        return bytes to count
    }

    fun uniqueName(dir: Node, name: String): String {
        val existing = list(dir).mapTo(HashSet()) { it.name }
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }

    // ---------------------------------------------------------------- copy / move

    fun isInside(child: Node, parent: Node): Boolean {
        if (child is LocalNode && parent is LocalNode) {
            val c = child.file.absolutePath
            val p = parent.file.absolutePath
            return c == p || c.startsWith("$p/")
        }
        if (child is SafNode && parent is SafNode) return child.documentId == parent.documentId ||
            child.documentId.startsWith(parent.documentId + "/")
        return false
    }

    /** Copies [src] into [destDir]. Returns the created node or null if skipped. */
    fun copy(src: Node, destDir: Node, policy: ConflictPolicy, sink: ProgressSink): Node? {
        sink.checkCancelled()
        if (src.isDirectory && isInside(destDir, src)) throw IOException("Ordner kann nicht in sich selbst kopiert werden")
        val existing = findChild(destDir, src.name)
        val targetName = when {
            existing == null -> src.name
            policy == ConflictPolicy.SKIP -> return null
            policy == ConflictPolicy.KEEP_BOTH || existing.id == src.id -> uniqueName(destDir, src.name)
            else -> {
                if (existing.isDirectory != src.isDirectory || !src.isDirectory) delete(existing)
                src.name
            }
        }
        return copyInto(src, destDir, targetName, sink)
    }

    private fun copyInto(src: Node, destDir: Node, name: String, sink: ProgressSink): Node {
        sink.checkCancelled()
        if (src.isDirectory) {
            val newDir = findChild(destDir, name)?.takeIf { it.isDirectory } ?: createDirectory(destDir, name)
            for (child in list(src)) {
                val existingChild = findChild(newDir, child.name)
                if (existingChild != null && !existingChild.isDirectory) delete(existingChild)
                copyInto(child, newDir, child.name, sink)
            }
            return newDir
        }
        sink.onFile(src.name)
        if (src is LocalNode && destDir is LocalNode) {
            val target = File(destDir.file, name)
            copyStream(FileInputStream(src.file), FileOutputStream(target), sink)
            target.setLastModified(src.lastModified)
            return LocalNode.of(target)
        }
        val target = createFile(destDir, name, src.mimeType)
        try {
            copyStream(openInput(src), openOutput(target), sink)
        } catch (e: Throwable) {
            runCatching { delete(target) }
            throw e
        }
        return target
    }

    fun copyStream(input: InputStream, output: OutputStream, sink: ProgressSink) {
        input.use { i ->
            output.use { o ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    sink.checkCancelled()
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    sink.onBytes(n.toLong())
                }
            }
        }
    }

    /** Moves [src] into [destDir]; uses a cheap rename when both are on the same local volume. */
    fun move(src: Node, destDir: Node, policy: ConflictPolicy, sink: ProgressSink): Node? {
        sink.checkCancelled()
        if (src.isDirectory && isInside(destDir, src)) throw IOException("Ordner kann nicht in sich selbst verschoben werden")
        if (src is LocalNode && destDir is LocalNode) {
            if (src.file.parentFile?.absolutePath == destDir.file.absolutePath) return src
            val existing = File(destDir.file, src.name)
            val target = when {
                !existing.exists() -> existing
                policy == ConflictPolicy.SKIP -> return null
                policy == ConflictPolicy.KEEP_BOTH -> File(destDir.file, uniqueName(destDir, src.name))
                else -> {
                    existing.deleteRecursively(); existing
                }
            }
            sink.onFile(src.name)
            if (src.file.renameTo(target)) {
                if (!src.isDirectory) sink.onBytes(src.size)
                return LocalNode.of(target)
            }
            val copied = copyInto(src, destDir, target.name, sink)
            delete(src)
            return copied
        }
        val copied = copy(src, destDir, policy, sink) ?: return null
        delete(src)
        return copied
    }
}
