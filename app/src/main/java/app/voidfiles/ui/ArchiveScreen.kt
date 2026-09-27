package app.voidfiles.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voidfiles.data.ArchiveItem
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle

/** Browse an archive like a folder and extract only what is needed. */
@Composable
fun ArchiveScreen(vm: MainViewModel) {
    val view = vm.archiveView ?: return
    val c = VoidTheme.colors
    val listing = view.listing
    val items = listing.list(view.dir)
    val totalSize = listing.entries.sumOf { it.size.coerceAtLeast(0) }

    Box(Modifier.fillMaxSize().background(c.background)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (view.selection.isEmpty()) {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = c.text) }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.archiveSelectAll() }) { Icon(Icons.Outlined.SelectAll, "Alle auswählen", tint = c.text) }
                } else {
                    IconButton(onClick = { vm.archiveView = view.copy(selection = emptySet()) }) {
                        Icon(Icons.Outlined.Close, "Auswahl aufheben", tint = c.text)
                    }
                    Text("${view.selection.size}", style = headingStyle(30), color = c.accent)
                    Label(" ausgewählt", Modifier.padding(start = 6.dp, top = 6.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { vm.archiveSelectAll() }) { Icon(Icons.Outlined.SelectAll, "Alle auswählen", tint = c.text) }
                }
            }
            Text(
                listing.archive.name.uppercase(), style = headingStyle(30), color = c.text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 20.dp),
            )
            // Path inside the archive, in accent colour like the folder breadcrumbs.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val parts = if (view.dir.isEmpty()) emptyList() else view.dir.split('/')
                Crumb("Archiv", parts.isEmpty()) { vm.archiveGoTo("") }
                parts.forEachIndexed { i, part ->
                    Text("/", style = MaterialTheme.typography.labelMedium, color = c.accent.copy(alpha = 0.4f))
                    Crumb(part, i == parts.lastIndex) { vm.archiveGoTo(parts.take(i + 1).joinToString("/")) }
                }
            }
            Label(
                "${listing.entries.count { !it.isDirectory }} Dateien · ${formatSize(totalSize)} entpackt · Ziel: ${vm.active.current.name}",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
            )
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("LEER", style = headingStyle(34), color = c.textMuted)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
                    items(items, key = { it.path }) { item ->
                        ArchiveRow(
                            item = item,
                            size = listing.sizeOf(item),
                            selected = item.path in view.selection,
                            onClick = { vm.archiveEnter(item) },
                            onLongClick = { vm.archiveToggle(item) },
                        )
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)
                .clip(RoundedCornerShape(50)).background(c.text).padding(start = 18.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (view.selection.isEmpty()) "ORDNERINHALT" else "${view.selection.size} AUSGEWÄHLT",
                style = MaterialTheme.typography.labelMedium, color = c.background, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.extractFromArchive(all = view.selection.isEmpty()) }) {
                Icon(Icons.Outlined.Unarchive, null, tint = c.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (view.selection.isEmpty()) "ALLES ENTPACKEN" else "ENTPACKEN",
                    style = MaterialTheme.typography.labelLarge, color = c.accent,
                )
            }
        }
    }
}

@Composable
private fun Crumb(text: String, last: Boolean, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (last) FontWeight.Bold else FontWeight.Normal),
        color = if (last) c.accent else c.accent.copy(alpha = 0.72f),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveRow(item: ArchiveItem, size: Long, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val c = VoidTheme.colors
    val kind = kindFor(item.name, item.isDirectory)
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.selection else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val shape = if (kind == Kind.FOLDER) RoundedCornerShape(14.dp) else CircleShape
        Box(
            Modifier.size(44.dp).clip(shape)
                .background(if (kind == Kind.FOLDER) c.surfaceHigh else Color.Transparent)
                .border(1.dp, if (kind == Kind.FOLDER) Color.Transparent else c.divider, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(24.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                }
            } else {
                Icon(iconFor(kind), null, tint = if (kind == Kind.FOLDER) c.text else c.textMuted, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                if (size >= 0) append(formatSize(size))
                if (item.time > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(formatDate(item.time))
                }
            }
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1)
        }
    }
}
