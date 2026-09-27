package app.voidfiles.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.SafNode
import app.voidfiles.ui.theme.MonoFont
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(vm: MainViewModel, launcher: Launcher) {
    val state = vm.viewer ?: return
    val c = VoidTheme.colors
    var chrome by remember { mutableStateOf(true) }
    val pager = rememberPagerState(initialPage = state.index) { state.files.size }
    val currentNode = state.files.getOrElse(pager.currentPage) { state.files.first() }
    val kind = vm.previewKindOf(currentNode)

    Box(Modifier.fillMaxSize().background(if (kind == PreviewKind.IMAGE || kind == PreviewKind.MEDIA) Color.Black else c.background)) {
        when (kind) {
            PreviewKind.IMAGE -> HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                ZoomableImage(vm, state.files[page], onTap = { chrome = !chrome })
            }
            PreviewKind.TEXT -> TextViewer(vm, currentNode)
            PreviewKind.PDF -> PdfViewer(vm, currentNode)
            PreviewKind.MEDIA -> MediaViewer(currentNode)
            null -> Unit
        }

        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut()) {
            val dark = kind == PreviewKind.IMAGE || kind == PreviewKind.MEDIA
            val fg = if (dark) Color.White else c.text
            Row(
                Modifier.fillMaxWidth()
                    .background(if (dark) Color.Black.copy(alpha = 0.55f) else c.background)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { vm.closeViewer() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = fg) }
                Column(Modifier.weight(1f)) {
                    Text(currentNode.name, color = fg, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.files.size > 1) Label("${pager.currentPage + 1} / ${state.files.size}", color = fg.copy(alpha = 0.7f))
                }
                IconButton(onClick = { launcher.share(listOf(currentNode)) }) { Icon(Icons.Outlined.Share, "Teilen", tint = fg) }
                IconButton(onClick = { launcher.open(currentNode, chooser = true) }) { Icon(Icons.Outlined.OpenInNew, "Öffnen mit", tint = fg) }
                IconButton(onClick = { vm.showProperties(currentNode) }) { Icon(Icons.Outlined.Info, "Eigenschaften", tint = fg) }
            }
        }
    }
}

// ---------------------------------------------------------------------- images

private suspend fun loadImage(vm: MainViewModel, context: android.content.Context, node: Node, maxPx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = when (node) {
                    is LocalNode -> ImageDecoder.createSource(node.file)
                    is SafNode -> ImageDecoder.createSource(context.contentResolver, node.uri)
                }
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val scale = maxOf(1, maxOf(w, h) / maxPx)
                    decoder.setTargetSize(maxOf(1, w / scale), maxOf(1, h / scale))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                vm.fs.openInput(node).use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                vm.fs.openInput(node).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            }
        }.getOrNull()
    }

@Composable
private fun ZoomableImage(vm: MainViewModel, node: Node, onTap: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, node.id) { value = loadImage(vm, context, node, 2560) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(node.id) {
                // Only consume gestures while zooming, so the pager can still swipe between pictures.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 8f)
                            offset = if (scale <= 1f) Offset.Zero else {
                                val o = offset + event.calculatePan()
                                val maxX = size.width * (scale - 1) / 2
                                val maxY = size.height * (scale - 1) / 2
                                Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(node.id) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f; offset = Offset.Zero
                        } else scale = 2.5f
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp == null) {
            DotLoader(Modifier.fillMaxWidth().height(12.dp))
        } else {
            Image(
                bmp.asImageBitmap(), node.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale
                    translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

// ---------------------------------------------------------------------- text

@Composable
private fun TextViewer(vm: MainViewModel, node: Node) {
    val c = VoidTheme.colors
    var original by remember(node.id) { mutableStateOf<String?>(null) }
    var text by remember(node.id) { mutableStateOf("") }
    var error by remember(node.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(node.id) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                if (node.size > 2L * 1024 * 1024) throw java.io.IOException("Datei ist zu groß für die Vorschau (max. 2 MB)")
                vm.fs.openInput(node).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }
        result.onSuccess { original = it; text = it }.onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp).imePadding()) {
        val err = error
        when {
            err != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(err, color = c.textMuted, modifier = Modifier.padding(32.dp))
            }
            original == null -> DotLoader(Modifier.fillMaxWidth().height(12.dp).padding(top = 24.dp))
            else -> {
                if (text != original) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Label("Geändert", Modifier.weight(1f), color = c.accent)
                        TextButton(onClick = { text = original ?: text }) {
                            Text("VERWERFEN", style = MaterialTheme.typography.labelLarge, color = c.textMuted)
                        }
                        TextButton(onClick = { vm.saveText(node, text) { original = text } }) {
                            Text("SPEICHERN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                        }
                    }
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = TextStyle(fontFamily = MonoFont, fontSize = 13.sp, lineHeight = 19.sp, color = c.text),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- pdf

private class PdfDoc(val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
    val mutex = Mutex()
    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }
}

@Composable
private fun PdfViewer(vm: MainViewModel, node: Node) {
    val c = VoidTheme.colors
    val context = LocalContext.current
    val widthPx = context.resources.displayMetrics.widthPixels.coerceAtMost(2000)
    val doc by produceState<Result<PdfDoc>?>(null, node.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val fd = when (node) {
                    is LocalNode -> ParcelFileDescriptor.open(node.file, ParcelFileDescriptor.MODE_READ_ONLY)
                    is SafNode -> context.contentResolver.openFileDescriptor(node.uri, "r") ?: throw java.io.IOException("PDF nicht lesbar")
                }
                PdfDoc(fd, PdfRenderer(fd))
            }
        }
    }
    DisposableEffect(doc) {
        val d = doc?.getOrNull()
        onDispose { d?.close() }
    }
    val d = doc
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = 56.dp)) {
        when {
            d == null -> DotLoader(Modifier.fillMaxWidth().height(12.dp).padding(top = 24.dp))
            d.isFailure -> Text(
                "PDF kann nicht angezeigt werden: ${d.exceptionOrNull()?.message}", color = c.textMuted,
                modifier = Modifier.padding(32.dp),
            )
            else -> {
                val pdf = d.getOrThrow()
                LazyColumn(
                    Modifier.fillMaxSize().background(c.surface),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(pdf.renderer.pageCount) { index ->
                        PdfPage(pdf, index, widthPx)
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(pdf: PdfDoc, index: Int, widthPx: Int) {
    val page by produceState<Bitmap?>(null, pdf, index) {
        value = withContext(Dispatchers.IO) {
            pdf.mutex.withLock {
                runCatching {
                    pdf.renderer.openPage(index).use { p ->
                        val w = widthPx
                        val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }.getOrNull()
            }
        }
    }
    val bmp = page
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), "Seite ${index + 1}", Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height))
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(0.707f).background(Color.White.copy(alpha = 0.06f)))
        }
        Spacer(Modifier.height(4.dp))
        Label("Seite ${index + 1} / ${pdf.renderer.pageCount}")
    }
}

// ---------------------------------------------------------------------- audio & video

@Composable
private fun MediaViewer(node: Node) {
    val uri: Uri = when (node) {
        is LocalNode -> Uri.fromFile(node.file)
        is SafNode -> node.uri
    }
    val isAudio = node.mimeType.startsWith("audio/")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (isAudio) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 160.dp)) {
                DotRing(null, Modifier.size(140.dp), dots = 32)
                Spacer(Modifier.height(24.dp))
                Text(node.name.substringBeforeLast('.').uppercase(), style = headingStyle(24), color = Color.White, maxLines = 2)
            }
        }
        AndroidView(
            factory = {
                VideoView(it).apply {
                    val controller = MediaController(it)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setVideoURI(uri)
                    setOnPreparedListener { mp ->
                        start()
                        controller.show(0)
                        mp.isLooping = false
                    }
                }
            },
            onRelease = { it.stopPlayback() },
            modifier = if (isAudio) Modifier.fillMaxWidth().height(1.dp) else Modifier.fillMaxSize(),
        )
    }
}
