package app.voidfiles.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

enum class ImageFormat(val label: String, val ext: String, val mime: String) {
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    WEBP("WEBP", "webp", "image/webp"),
}

/** What to do with a picture: target format, longest side in px (0 = keep) and JPG/WEBP quality. */
data class ImageJob(
    val format: ImageFormat = ImageFormat.JPG,
    val maxSize: Int = 0,
    val quality: Int = 90,
    val keepDate: Boolean = true,
)

/** Converting, shrinking and removing location data from pictures. */
object ImageTools {
    /** Even "original size" is capped – a 200 MP photo would not fit into memory as a bitmap. */
    private const val MAX_DECODE = 8192

    private val GPS_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
    )

    /** Formats whose metadata can be edited without re-encoding the picture. */
    private val EXIF_WRITABLE = setOf("jpg", "jpeg", "png", "webp")

    fun isImage(node: Node): Boolean =
        !node.isDirectory && node.mimeType.startsWith("image/") && node.extension != "svg"

    private fun decode(context: Context, fs: FileSystem, node: Node, maxSize: Int): Bitmap {
        val limit = if (maxSize <= 0) MAX_DECODE else minOf(maxSize, MAX_DECODE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = when (node) {
                is LocalNode -> ImageDecoder.createSource(node.file)
                is SafNode -> ImageDecoder.createSource(context.contentResolver, node.uri)
            }
            // ImageDecoder also applies the EXIF rotation, so the result is upright.
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > limit) {
                    val scale = limit.toDouble() / longest
                    decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        fs.openInput(node).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= limit) sample *= 2
        val bmp = fs.openInput(node).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("${node.name} kann nicht gelesen werden")
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= limit) return bmp
        val scale = limit.toDouble() / longest
        return Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    }

    @Suppress("DEPRECATION")
    private fun compressFormat(format: ImageFormat): Bitmap.CompressFormat = when (format) {
        ImageFormat.JPG -> Bitmap.CompressFormat.JPEG
        ImageFormat.PNG -> Bitmap.CompressFormat.PNG
        ImageFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP
    }

    private fun readDates(fs: FileSystem, node: Node): Pair<String?, String?> = runCatching {
        val exif = fs.openInput(node).use { ExifInterface(it) }
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) to exif.getAttribute(ExifInterface.TAG_DATETIME)
    }.getOrDefault(null to null)

    /**
     * Saves a converted / resized copy of [node] into [destDir]. Re-encoding drops all metadata
     * (including location); with [ImageJob.keepDate] only the capture date is carried over.
     */
    fun convert(context: Context, fs: FileSystem, node: Node, destDir: Node, job: ImageJob, workDir: File): Node {
        val bitmap = decode(context, fs, node, job.maxSize)
        workDir.mkdirs()
        val tmp = File(workDir, "convert_${System.nanoTime()}.${job.format.ext}")
        try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(compressFormat(job.format), job.quality.coerceIn(1, 100), out)) {
                    throw IOException("${node.name} konnte nicht umgewandelt werden")
                }
            }
            bitmap.recycle()
            if (job.keepDate && job.format.ext in EXIF_WRITABLE) {
                val (original, plain) = readDates(fs, node)
                if (original != null || plain != null) runCatching {
                    val exif = ExifInterface(tmp)
                    original?.let { exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, it) }
                    (plain ?: original)?.let { exif.setAttribute(ExifInterface.TAG_DATETIME, it) }
                    exif.saveAttributes()
                }
            }
            val base = node.name.substringBeforeLast('.', node.name)
            val sameFormat = node.extension == job.format.ext || (node.extension == "jpeg" && job.format == ImageFormat.JPG)
            val wanted = if (sameFormat) "${base}_bearbeitet.${job.format.ext}" else "$base.${job.format.ext}"
            val target = fs.createFile(destDir, fs.uniqueName(destDir, wanted), job.format.mime)
            try {
                tmp.inputStream().use { input -> fs.openOutput(target).use { input.copyTo(it, 256 * 1024) } }
            } catch (e: Throwable) {
                runCatching { fs.delete(target) }
                throw e
            }
            if (target is LocalNode && node.lastModified > 0) target.file.setLastModified(node.lastModified)
            return target
        } finally {
            tmp.delete()
        }
    }

    /**
     * Copy of [node] without location data in [outDir]. JPG/PNG/WEBP keep their full quality (only the
     * metadata changes); other formats such as HEIC are re-encoded to JPG.
     */
    fun withoutLocation(context: Context, fs: FileSystem, node: Node, outDir: File): File {
        outDir.mkdirs()
        if (node.extension in EXIF_WRITABLE) {
            val out = File(outDir, uniqueFileName(outDir, node.name))
            fs.openInput(node).use { input -> FileOutputStream(out).use { input.copyTo(it, 256 * 1024) } }
            removeGps(out)
            return out
        }
        val converted = convert(context, fs, node, LocalNode.of(outDir), ImageJob(ImageFormat.JPG, 0, 95, true), outDir)
        return (converted as LocalNode).file
    }

    /** Removes location from [node] itself. Returns false when the format cannot be edited in place (e.g. HEIC). */
    fun stripLocationInPlace(fs: FileSystem, node: Node, workDir: File): Boolean {
        if (node.extension !in EXIF_WRITABLE) return false
        workDir.mkdirs()
        val tmp = File(workDir, "strip_${System.nanoTime()}.${node.extension}")
        try {
            fs.openInput(node).use { input -> FileOutputStream(tmp).use { input.copyTo(it, 256 * 1024) } }
            if (!removeGps(tmp)) return true // nothing to remove
            tmp.inputStream().use { input -> fs.openOutput(node).use { input.copyTo(it, 256 * 1024) } }
            if (node is LocalNode && node.lastModified > 0) node.file.setLastModified(node.lastModified)
            return true
        } finally {
            tmp.delete()
        }
    }

    /** Returns true if location data was found and removed. */
    private fun removeGps(file: File): Boolean {
        val exif = ExifInterface(file)
        val had = exif.latLong != null || GPS_TAGS.any { exif.getAttribute(it) != null }
        if (!had) return false
        GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
        return true
    }

    private fun uniqueFileName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (File(dir, "$base ($i)$ext").exists()) i++
        return "$base ($i)$ext"
    }
}
