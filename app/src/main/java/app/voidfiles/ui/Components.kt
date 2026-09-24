package app.voidfiles.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voidfiles.data.Archives
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.SafNode
import app.voidfiles.ui.theme.VoidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024; i++
    }
    return if (v >= 100) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}

private val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
fun formatDate(ms: Long): String = if (ms <= 0) "—" else dateFormat.format(Date(ms))

enum class Kind { FOLDER, IMAGE, VIDEO, AUDIO, ARCHIVE, APK, PDF, DOC, SHEET, SLIDES, CODE, TEXT, OTHER }

fun kindOf(node: Node): Kind {
    if (node.isDirectory) return Kind.FOLDER
    val ext = node.extension
    if (Archives.isArchive(node.name)) return Kind.ARCHIVE
    val mime = node.mimeType
    return when {
        ext == "apk" -> Kind.APK
        ext == "pdf" -> Kind.PDF
        mime.startsWith("image/") -> Kind.IMAGE
        mime.startsWith("video/") -> Kind.VIDEO
        mime.startsWith("audio/") -> Kind.AUDIO
        ext in setOf("doc", "docx", "odt", "rtf", "pages") -> Kind.DOC
        ext in setOf("xls", "xlsx", "ods", "csv", "numbers") -> Kind.SHEET
        ext in setOf("ppt", "pptx", "odp", "key") -> Kind.SLIDES
        ext in setOf("kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "sh", "c", "cpp", "h", "rs", "go", "yml", "yaml", "gradle", "kts") -> Kind.CODE
        mime.startsWith("text/") -> Kind.TEXT
        else -> Kind.OTHER
    }
}

fun iconFor(kind: Kind): ImageVector = when (kind) {
    Kind.FOLDER -> Icons.Rounded.Folder
    Kind.IMAGE -> Icons.Outlined.Image
    Kind.VIDEO -> Icons.Outlined.Movie
    Kind.AUDIO -> Icons.Outlined.AudioFile
    Kind.ARCHIVE -> Icons.Outlined.FolderZip
    Kind.APK -> Icons.Outlined.Android
    Kind.PDF -> Icons.Outlined.PictureAsPdf
    Kind.DOC -> Icons.Outlined.Description
    Kind.SHEET -> Icons.Outlined.TableChart
    Kind.SLIDES -> Icons.Outlined.Slideshow
    Kind.CODE -> Icons.Outlined.Code
    Kind.TEXT -> Icons.Outlined.Article
    Kind.OTHER -> Icons.Outlined.InsertDriveFile
}

private val thumbCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** Round file icon; shows a thumbnail for pictures and videos when enabled. */
@Composable
fun NodeIcon(node: Node, size: Dp, thumbnails: Boolean, modifier: Modifier = Modifier) {
    val c = VoidTheme.colors
    val kind = kindOf(node)
    val context = LocalContext.current
    val wantThumb = thumbnails && (kind == Kind.IMAGE || kind == Kind.VIDEO)
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }.coerceAtLeast(64)
    val key = "${node.id}:${node.lastModified}:$px"
    val thumb by produceState<Bitmap?>(null, key, wantThumb) {
        if (!wantThumb) {
            value = null
            return@produceState
        }
        thumbCache.get(key)?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bmp: Bitmap? = when (node) {
                    is LocalNode -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (kind == Kind.VIDEO) android.media.ThumbnailUtils.createVideoThumbnail(node.file, Size(px, px), null)
                        else android.media.ThumbnailUtils.createImageThumbnail(node.file, Size(px, px), null)
                    } else if (kind == Kind.IMAGE) {
                        decodeSampled(node.file.absolutePath, px)
                    } else null
                    is SafNode -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(node.uri, Size(px, px), null)
                    } else null
                }
                bmp?.also { thumbCache.put(key, it) }
            }.getOrNull()
        }
    }
    val shape = if (kind == Kind.FOLDER) RoundedCornerShape(size / 3.2f) else CircleShape
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(if (kind == Kind.FOLDER) c.surfaceHigh else Color.Transparent)
            .border(1.dp, if (kind == Kind.FOLDER) Color.Transparent else c.divider, shape),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumb
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                iconFor(kind), null,
                tint = when (kind) {
                    Kind.FOLDER -> c.text
                    Kind.ARCHIVE, Kind.APK -> c.accent
                    else -> c.textMuted
                },
                modifier = Modifier.size(size * 0.46f),
            )
        }
    }
}

private fun decodeSampled(path: String, target: Int): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, opts)
    var sample = 1
    while (opts.outWidth / (sample * 2) >= target && opts.outHeight / (sample * 2) >= target) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Nothing-style dot grid backdrop. */
@Composable
fun DotGrid(modifier: Modifier = Modifier, spacing: Dp = 18.dp, radius: Dp = 1.2.dp) {
    val color = VoidTheme.colors.dot
    Canvas(modifier) {
        val step = spacing.toPx()
        val r = radius.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawCircle(color, r, Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

/** Segmented dot bar (storage usage etc.). */
@Composable
fun DotBar(fraction: Float, modifier: Modifier = Modifier, dots: Int = 28) {
    val c = VoidTheme.colors
    Canvas(modifier) {
        val gap = size.width / dots
        val r = (gap * 0.32f).coerceAtMost(size.height / 2)
        val filled = (fraction.coerceIn(0f, 1f) * dots).toInt()
        for (i in 0 until dots) {
            drawCircle(
                if (i < filled) c.accent else c.textMuted.copy(alpha = 0.28f),
                r, Offset(gap * i + gap / 2, size.height / 2),
            )
        }
    }
}

/** Small uppercase mono label, Nothing style. */
@Composable
fun Label(text: String, modifier: Modifier = Modifier, color: Color = VoidTheme.colors.textMuted) {
    Text(text.uppercase(), modifier = modifier, style = MaterialTheme.typography.labelSmall, color = color)
}

/** Pill-shaped chip used for toggles in headers and dialogs. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = VoidTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.text else Color.Transparent)
            .border(1.dp, if (selected) c.text else c.divider, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
            color = if (selected) c.background else c.text,
        )
    }
}
