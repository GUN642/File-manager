package app.voidfiles.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.exifinterface.media.ExifInterface
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale

/** Extra details for the properties dialog: EXIF, media metadata, APK info and checksums. */
object FileInfo {

    fun checksum(fs: FileSystem, node: Node, algorithm: String, sink: ProgressSink = NoProgress): String {
        val md = MessageDigest.getInstance(algorithm)
        fs.openInput(node).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                sink.checkCancelled()
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun details(context: Context, fs: FileSystem, node: Node): List<Pair<String, String>> {
        if (node.isDirectory) return emptyList()
        val mime = node.mimeType
        return runCatching {
            when {
                node.extension == "apk" && node is LocalNode -> apk(context, node)
                mime.startsWith("image/") -> image(fs, node)
                mime.startsWith("video/") || mime.startsWith("audio/") -> media(context, node)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
    }

    private fun image(fs: FileSystem, node: Node): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        fs.openInput(node).use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0) {
            val mp = opts.outWidth.toLong() * opts.outHeight / 1_000_000.0
            out += "Auflösung" to "${opts.outWidth} × ${opts.outHeight} (%.1f MP)".format(mp)
        }
        val exif = fs.openInput(node).use { ExifInterface(it) }
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
        val camera = listOfNotNull(make, model).distinct().joinToString(" ")
        if (camera.isNotBlank()) out += "Kamera" to camera
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { out += "Aufgenommen" to prettyExifDate(it) }
        val shot = listOfNotNull(
            exif.getAttribute(ExifInterface.TAG_F_NUMBER)?.toDoubleOrNull()?.let { "f/%.1f".format(it) },
            exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)?.toDoubleOrNull()?.let { exposure(it) },
            exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
            exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)?.let { rational(it) }?.let { "%.1f mm".format(it) },
        )
        if (shot.isNotEmpty()) out += "Belichtung" to shot.joinToString(" · ")
        exif.latLong?.let { (lat, lon) -> out += "Ort" to "%.5f, %.5f".format(Locale.US, lat, lon) }
        return out
    }

    /** EXIF (or file) date of a picture, used by batch rename. */
    fun captureDate(fs: FileSystem, node: Node): Long {
        if (node.mimeType.startsWith("image/")) {
            runCatching {
                val raw = fs.openInput(node).use { ExifInterface(it) }.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                if (raw != null) return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)!!.time
            }
        }
        return node.lastModified
    }

    private fun prettyExifDate(raw: String): String = runCatching {
        val d = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(raw)!!
        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMAN).format(d)
    }.getOrDefault(raw)

    private fun rational(v: String): Double? {
        val parts = v.split('/')
        return if (parts.size == 2) {
            val a = parts[0].toDoubleOrNull() ?: return null
            val b = parts[1].toDoubleOrNull() ?: return null
            if (b == 0.0) null else a / b
        } else v.toDoubleOrNull()
    }

    private fun exposure(seconds: Double): String =
        if (seconds >= 1) "%.1f s".format(seconds) else "1/${(1 / seconds).toInt()} s"

    private fun media(context: Context, node: Node): List<Pair<String, String>> {
        val r = MediaMetadataRetriever()
        try {
            when (node) {
                is LocalNode -> r.setDataSource(node.file.absolutePath)
                is SafNode -> r.setDataSource(context, node.uri)
            }
            val out = ArrayList<Pair<String, String>>()
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { out += "Dauer" to duration(it) }
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w != null && h != null) out += "Auflösung" to "$w × $h"
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { out += "Bitrate" to "${it / 1000} kbit/s" }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { out += "Titel" to it }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { out += "Interpret" to it }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { out += "Album" to it }
            return out
        } finally {
            runCatching { r.release() }
        }
    }

    private fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun apk(context: Context, node: LocalNode): List<Pair<String, String>> {
        val pm = context.packageManager
        val path = node.file.absolutePath
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(path, PackageManager.GET_PERMISSIONS) ?: return emptyList()
        val app = info.applicationInfo
        val out = ArrayList<Pair<String, String>>()
        if (app != null) {
            app.sourceDir = path
            app.publicSourceDir = path
            out += "App" to app.loadLabel(pm).toString()
        }
        out += "Paket" to info.packageName
        out += "Version" to "${info.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(info)})"
        if (app != null) {
            val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) app.minSdkVersion else 0
            out += "Android" to "min. API $min · Ziel API ${app.targetSdkVersion}"
        }
        val installed = runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull()
        out += "Installiert" to (installed?.let { "ja, Version ${it.versionName}" } ?: "nein")
        val perms = info.requestedPermissions?.map { it.substringAfterLast('.') }?.sorted().orEmpty()
        if (perms.isNotEmpty()) out += "Berechtigungen (${perms.size})" to perms.joinToString("\n")
        return out
    }
}
