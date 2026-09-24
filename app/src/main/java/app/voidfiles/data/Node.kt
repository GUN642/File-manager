package app.voidfiles.data

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File

/** A file or folder, either on local storage or inside a Storage Access Framework tree (e.g. Proton Drive). */
sealed class Node {
    abstract val id: String
    abstract val name: String
    abstract val isDirectory: Boolean
    abstract val size: Long
    abstract val lastModified: Long

    val isHidden: Boolean get() = name.startsWith(".")
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    val mimeType: String
        get() = when (this) {
            is SafNode -> mime.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: mimeFromName(name)
            is LocalNode -> mimeFromName(name)
        }

    companion object {
        fun mimeFromName(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext.isEmpty()) return "application/octet-stream"
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: when (ext) {
                    "apk" -> "application/vnd.android.package-archive"
                    "7z" -> "application/x-7z-compressed"
                    "rar" -> "application/vnd.rar"
                    "md", "log", "ini", "conf", "kt", "java", "py", "sh", "json", "yml", "yaml" -> "text/plain"
                    else -> "application/octet-stream"
                }
        }
    }
}

data class LocalNode(
    val file: File,
    override val name: String,
    override val isDirectory: Boolean,
    override val size: Long,
    override val lastModified: Long,
) : Node() {
    override val id: String get() = file.absolutePath

    companion object {
        fun of(file: File): LocalNode {
            val dir = file.isDirectory
            return LocalNode(
                file = file,
                name = file.name.ifEmpty { file.absolutePath },
                isDirectory = dir,
                size = if (dir) 0L else file.length(),
                lastModified = file.lastModified(),
            )
        }
    }
}

data class SafNode(
    val treeUri: Uri,
    val documentId: String,
    override val name: String,
    override val isDirectory: Boolean,
    override val size: Long,
    override val lastModified: Long,
    val mime: String,
    val flags: Int,
) : Node() {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
    override val id: String get() = uri.toString()

    val canRename: Boolean get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_RENAME != 0
    val canDelete: Boolean get() = flags and DocumentsContract.Document.FLAG_SUPPORTS_DELETE != 0
}
