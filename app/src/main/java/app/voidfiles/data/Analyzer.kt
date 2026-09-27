package app.voidfiles.data

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.PriorityQueue

enum class Category(val label: String) {
    IMAGES("Bilder"), VIDEOS("Videos"), AUDIO("Audio"), DOCUMENTS("Dokumente"),
    ARCHIVES("Archive"), APPS("Apps (APK)"), OTHER("Andere"),
}

data class CategoryStat(val category: Category, val bytes: Long, val count: Int)

data class DuplicateGroup(val size: Long, val files: List<LocalNode>) {
    val wasted: Long get() = size * (files.size - 1)
}

data class AnalysisResult(
    val root: File,
    val total: Long,
    val free: Long,
    val scannedBytes: Long,
    val fileCount: Int,
    val categories: List<CategoryStat>,
    val largest: List<LocalNode>,
    val folders: List<Pair<LocalNode, Long>>,
    val duplicates: List<DuplicateGroup>,
)

/** Walks a storage volume and summarises what takes up the space. */
object Analyzer {

    private val docs = setOf("pdf", "doc", "docx", "odt", "rtf", "txt", "md", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp", "epub", "pages", "numbers", "key")

    fun categoryOf(name: String): Category {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext == "apk" || ext == "apks" || ext == "xapk") return Category.APPS
        if (Archives.isArchive(name)) return Category.ARCHIVES
        if (ext in docs) return Category.DOCUMENTS
        val mime = Node.mimeFromName(name)
        return when {
            mime.startsWith("image/") -> Category.IMAGES
            mime.startsWith("video/") -> Category.VIDEOS
            mime.startsWith("audio/") -> Category.AUDIO
            else -> Category.OTHER
        }
    }

    fun analyze(root: File, total: Long, free: Long, onProgress: (Int, String) -> Unit, cancelled: () -> Boolean): AnalysisResult {
        val catBytes = LongArray(Category.entries.size)
        val catCount = IntArray(Category.entries.size)
        val largest = PriorityQueue<Pair<File, Long>>(51, compareBy { it.second })
        val folderSizes = HashMap<String, Long>()
        val bySize = HashMap<Long, MutableList<File>>()
        var count = 0
        var bytes = 0L
        val rootPath = root.absolutePath

        root.walkTopDown()
            .onEnter { !cancelled() && it.name != ".VoidTrash" }
            .forEach { f ->
                if (!f.isFile) return@forEach
                val len = f.length()
                count++
                bytes += len
                val cat = categoryOf(f.name).ordinal
                catBytes[cat] += len
                catCount[cat]++
                largest.add(f to len)
                if (largest.size > 50) largest.poll()
                val rel = f.absolutePath.removePrefix("$rootPath/")
                val top = if ('/' in rel) rel.substringBefore('/') else ""
                folderSizes[top] = (folderSizes[top] ?: 0L) + len
                if (len >= 64 * 1024) bySize.getOrPut(len) { ArrayList(2) }.add(f)
                if (count % 400 == 0) onProgress(count, f.parentFile?.name ?: "")
            }

        onProgress(count, "Suche Duplikate …")
        val duplicates = ArrayList<DuplicateGroup>()
        for ((size, files) in bySize) {
            if (cancelled()) break
            if (files.size < 2) continue
            val byHash = files.groupBy { runCatching { fingerprint(it, size) }.getOrNull() }
            for ((hash, group) in byHash) {
                if (hash != null && group.size > 1) duplicates += DuplicateGroup(size, group.map { LocalNode.of(it) })
            }
        }

        return AnalysisResult(
            root = root,
            total = total,
            free = free,
            scannedBytes = bytes,
            fileCount = count,
            categories = Category.entries.map { CategoryStat(it, catBytes[it.ordinal], catCount[it.ordinal]) }
                .filter { it.count > 0 }.sortedByDescending { it.bytes },
            largest = largest.toList().sortedByDescending { it.second }.map { LocalNode.of(it.first) },
            folders = folderSizes.filterKeys { it.isNotEmpty() }.entries.sortedByDescending { it.value }.take(15)
                .map { LocalNode.of(File(root, it.key)) to it.value },
            duplicates = duplicates.sortedByDescending { it.wasted }.take(100),
        )
    }

    /**
     * Content fingerprint: MD5 of the whole file up to 64 MB; for bigger files the first, middle and last
     * 4 MB plus the size – fast and practically collision free for real duplicates.
     */
    private fun fingerprint(f: File, size: Long): String {
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(256 * 1024)
        RandomAccessFile(f, "r").use { raf ->
            fun hashRange(start: Long, length: Long) {
                raf.seek(start)
                var left = length
                while (left > 0) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    md.update(buf, 0, n)
                    left -= n
                }
            }
            val chunk = 4L * 1024 * 1024
            if (size <= 64L * 1024 * 1024) {
                hashRange(0, size)
            } else {
                hashRange(0, chunk)
                hashRange(size / 2 - chunk / 2, chunk)
                hashRange(size - chunk, chunk)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
