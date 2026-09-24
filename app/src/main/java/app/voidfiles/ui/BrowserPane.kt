package app.voidfiles.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voidfiles.data.AppSettings
import app.voidfiles.data.Archives
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.SafNode
import app.voidfiles.data.Storage
import app.voidfiles.data.ViewMode
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserPane(
    vm: MainViewModel,
    pane: Pane,
    settings: AppSettings,
    active: Boolean,
    compact: Boolean,
    launcher: Launcher,
    onActivate: () -> Unit,
    onMenu: (() -> Unit)?,
    onDialog: (Dlg) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = VoidTheme.colors
    Column(
        modifier
            .background(c.background)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    onActivate()
                }
            },
    ) {
        if (pane.selection.isNotEmpty()) {
            SelectionHeader(vm, pane, launcher, compact, onDialog)
        } else {
            PaneHeader(vm, pane, settings, active, compact, onMenu, onDialog)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (pane.loading || pane.search?.running == true) {
                LinearProgressIndicator(Modifier.fillMaxSize(), color = c.accent, trackColor = Color.Transparent)
            }
        }
        PullToRefreshBox(
            isRefreshing = false,
            onRefresh = { vm.reload(pane) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            PaneContent(vm, pane, settings, compact)
        }
    }
}

@Composable
private fun PaneHeader(
    vm: MainViewModel,
    pane: Pane,
    settings: AppSettings,
    active: Boolean,
    compact: Boolean,
    onMenu: (() -> Unit)?,
    onDialog: (Dlg) -> Unit,
) {
    val c = VoidTheme.colors
    val searchKind = pane.search?.kind
    var searchOpen by remember(pane.current.id, searchKind) { mutableStateOf(searchKind == ListKind.SEARCH) }
    var query by remember(pane.current.id, searchKind) {
        mutableStateOf(if (searchKind == ListKind.SEARCH) pane.search?.query.orEmpty() else "")
    }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onMenu != null) {
                IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Menü", tint = c.text) }
            }
            if (compact) {
                Box(
                    Modifier.padding(start = 12.dp).size(8.dp).clip(CircleShape)
                        .background(if (active) c.accent else c.divider),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = {
                searchOpen = !searchOpen
                if (!searchOpen) {
                    query = ""; vm.search(pane, "")
                }
            }) { Icon(if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search, "Suchen", tint = c.text) }
            IconButton(onClick = { onDialog(Dlg.Sort(pane)) }) { Icon(Icons.Outlined.Tune, "Ansicht", tint = c.text) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mehr", tint = c.text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surfaceHigh) {
                    MenuItem("Neuer Ordner", Icons.Outlined.CreateNewFolder) { menu = false; onDialog(Dlg.NewFolder(pane)) }
                    MenuItem("Neue Datei", Icons.Outlined.NoteAdd) { menu = false; onDialog(Dlg.NewFile(pane)) }
                    val cur = pane.current
                    if (cur is LocalNode) {
                        val marked = cur.file.absolutePath in settings.quickAccess
                        MenuItem(if (marked) "Aus Schnellzugriff entfernen" else "Zum Schnellzugriff",
                            if (marked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder) {
                            menu = false; vm.toggleQuickAccess(cur)
                        }
                    }
                    MenuItem("Eigenschaften", Icons.Outlined.Info) { menu = false; vm.showProperties(pane.current) }
                    MenuItem("Aktualisieren", Icons.Outlined.Refresh) { menu = false; vm.reload(pane) }
                }
            }
        }

        val title = when {
            pane.search?.kind == ListKind.RECENT -> "Zuletzt"
            pane.search != null -> "Suche"
            pane.stack.size == 1 && pane.current is LocalNode &&
                (pane.current as LocalNode).file.absolutePath == Storage.primaryRoot.absolutePath -> "Intern"
            else -> pane.current.name
        }
        Text(
            title.uppercase(),
            style = headingStyle(if (compact) 26 else 40),
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Breadcrumbs(vm, pane)

        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("In ${pane.current.name} suchen …") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(pane, query) }),
                shape = RoundedCornerShape(50),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = c.accent, unfocusedBorderColor = c.divider, cursorColor = c.accent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        val items = pane.visible
        val dirs = items.count { it.isDirectory }
        val info = when {
            pane.search?.kind == ListKind.RECENT -> "${items.size} zuletzt geänderte Dateien · neueste zuerst"
            pane.search != null -> "${items.size} Treffer für \"${pane.search?.query}\""
            else -> "$dirs Ordner · ${items.size - dirs} Dateien"
        }
        Label(info, Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 8.dp))
    }
}

@Composable
private fun Breadcrumbs(vm: MainViewModel, pane: Pane) {
    val c = VoidTheme.colors
    val scroll = rememberScrollState()
    LaunchedEffect(pane.stack) { scroll.scrollTo(scroll.maxValue) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 14.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pane.stack.forEachIndexed { i, node ->
            val label = if (i == 0 && node is LocalNode && node.file.absolutePath == Storage.primaryRoot.absolutePath) "Intern" else node.name
            val last = i == pane.stack.lastIndex
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (last) FontWeight.Bold else FontWeight.Normal),
                color = if (last) c.accent else c.accent.copy(alpha = 0.72f),
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { vm.navigateToCrumb(pane, i) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (!last) Text("/", style = MaterialTheme.typography.labelMedium, color = c.accent.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun SelectionHeader(vm: MainViewModel, pane: Pane, launcher: Launcher, compact: Boolean, onDialog: (Dlg) -> Unit) {
    val c = VoidTheme.colors
    val selected = pane.selectedNodes
    val single = selected.singleOrNull()
    var more by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 4.dp else 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.clearSelection(pane) }) { Icon(Icons.Outlined.Close, "Auswahl aufheben", tint = c.text) }
            Text(
                "${selected.size}",
                style = headingStyle(if (compact) 26 else 34),
                color = c.accent,
                modifier = Modifier.padding(start = 4.dp),
            )
            Label(" ausgewählt", Modifier.padding(start = 6.dp, top = 6.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.selectAll(pane) }) { Icon(Icons.Outlined.SelectAll, "Alle auswählen", tint = c.text) }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "Mehr", tint = c.text) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }, containerColor = c.surfaceHigh) {
                    if (single != null) {
                        MenuItem("Eigenschaften", Icons.Outlined.Info) { more = false; vm.showProperties(single) }
                        if (!single.isDirectory) {
                            MenuItem("Öffnen mit …", Icons.Outlined.OpenInNew) { more = false; launcher.open(single, chooser = true) }
                        }
                        if (Archives.isArchive(single.name)) {
                            MenuItem("Entpacken …", Icons.Outlined.Unarchive) { more = false; onDialog(Dlg.ArchiveAction(single)) }
                        }
                        if (single.isDirectory && single is LocalNode) {
                            MenuItem(if (vm.isQuickAccess(single)) "Aus Schnellzugriff entfernen" else "Zum Schnellzugriff",
                                Icons.Outlined.BookmarkBorder) { more = false; vm.toggleQuickAccess(single); vm.clearSelection(pane) }
                        }
                    }
                    MenuItem("An Proton Drive senden", Icons.Outlined.CloudUpload) {
                        more = false; launcher.sendToProton(selected); vm.clearSelection(pane)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ActionButton("Kopieren", Icons.Outlined.ContentCopy) { vm.copyToClipboard(pane, cut = false) }
            ActionButton("Ausschn.", Icons.Outlined.ContentCut) { vm.copyToClipboard(pane, cut = true) }
            ActionButton("Löschen", Icons.Outlined.Delete) { onDialog(Dlg.Delete(pane, selected)) }
            if (single != null) {
                ActionButton("Umbenennen", Icons.Outlined.DriveFileRenameOutline) { onDialog(Dlg.Rename(pane, single)) }
            }
            ActionButton("Teilen", Icons.Outlined.Share) { launcher.share(selected) }
            ActionButton("ZIP", Icons.Outlined.FolderZip) { onDialog(Dlg.Compress(pane, selected)) }
            ActionButton("Proton", Icons.Outlined.CloudUpload) { launcher.sendToProton(selected); vm.clearSelection(pane) }
        }
    }
}

@Composable
private fun ActionButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(c.surfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = c.text, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.height(4.dp))
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = c.textMuted)
    }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    val c = VoidTheme.colors
    DropdownMenuItem(
        text = { Text(text, color = c.text) },
        leadingIcon = { Icon(icon, null, tint = c.textMuted) },
        onClick = onClick,
    )
}

@Composable
private fun PaneContent(vm: MainViewModel, pane: Pane, settings: AppSettings, compact: Boolean) {
    val items = pane.visible
    val bottomPad = PaddingValues(bottom = 120.dp)
    when {
        pane.error != null && pane.search == null -> EmptyState("Kein Zugriff", pane.error ?: "")
        items.isEmpty() && !pane.loading && pane.search?.running != true ->
            when (pane.search?.kind) {
                ListKind.RECENT -> EmptyState("Leer", "Keine kürzlich geänderten Dateien gefunden")
                ListKind.SEARCH -> EmptyState("Nichts gefunden", "")
                null -> EmptyState("Leer", "Dieser Ordner ist leer")
            }
        settings.viewMode == ViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Adaptive(if (compact) 88.dp else 104.dp),
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 120.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.id }) { node ->
                GridItem(node, node.id in pane.selection, settings.thumbnails,
                    onClick = { vm.openNode(pane, node) }, onLongClick = { vm.toggleSelect(pane, node) })
            }
        }
        pane.search?.kind == ListKind.RECENT -> LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPad) {
            items.groupBy { dayLabel(it.lastModified) }.forEach { (day, files) ->
                item(key = "day:$day") {
                    Label(day, Modifier.padding(start = if (compact) 12.dp else 20.dp, top = 14.dp, bottom = 4.dp), color = VoidTheme.colors.accent)
                }
                items(files, key = { it.id }) { node ->
                    FileRow(
                        node, node.id in pane.selection, settings.thumbnails, compact,
                        subtitle = "${formatSize(node.size)}  ·  ${timeOf(node.lastModified)}  ·  ${parentHint(node)}",
                        onClick = { vm.openNode(pane, node) },
                        onLongClick = { vm.toggleSelect(pane, node) },
                    )
                }
            }
        }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = bottomPad) {
            items(items, key = { it.id }) { node ->
                FileRow(
                    node, node.id in pane.selection, settings.thumbnails, compact,
                    subtitle = if (pane.search != null) parentHint(node) else null,
                    onClick = { vm.openNode(pane, node) },
                    onLongClick = { vm.toggleSelect(pane, node) },
                )
            }
        }
    }
}

private fun dayLabel(ms: Long): String {
    val day = 24 * 60 * 60 * 1000L
    val cal = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val today = cal.timeInMillis
    return when {
        ms >= today -> "Heute"
        ms >= today - day -> "Gestern"
        ms >= today - 6 * day -> java.text.SimpleDateFormat("EEEE", java.util.Locale.GERMAN).format(java.util.Date(ms))
        else -> java.text.SimpleDateFormat("d. MMMM yyyy", java.util.Locale.GERMAN).format(java.util.Date(ms))
    }
}

private fun timeOf(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.GERMAN).format(java.util.Date(ms))

private fun parentHint(node: Node): String = when (node) {
    is LocalNode -> node.file.parentFile?.absolutePath?.removePrefix(Storage.primaryRoot.absolutePath)?.ifEmpty { "/" } ?: ""
    is SafNode -> android.net.Uri.decode(node.documentId).substringBeforeLast('/')
}

@Composable
private fun EmptyState(title: String, text: String) {
    val c = VoidTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (VoidTheme.dotGrid) DotGrid(Modifier.fillMaxSize())
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text(title.uppercase(), style = headingStyle(34), color = c.textMuted)
            if (text.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(text, color = c.textMuted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    node: Node,
    selected: Boolean,
    thumbnails: Boolean,
    compact: Boolean,
    subtitle: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = VoidTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) c.selection else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = if (compact) 12.dp else 20.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            NodeIcon(node, if (compact) 38.dp else 44.dp, thumbnails)
            if (selected) {
                Box(
                    Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(12.dp)) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                color = c.text,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (node.isDirectory) FontWeight.Medium else FontWeight.Normal),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = subtitle ?: if (node.isDirectory) formatDate(node.lastModified)
            else "${formatSize(node.size)}  ·  ${formatDate(node.lastModified)}"
            Text(meta, style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridItem(
    node: Node,
    selected: Boolean,
    thumbnails: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = VoidTheme.colors
    Column(
        Modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.selection else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            NodeIcon(node, 64.dp, thumbnails)
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(c.accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Check, null, tint = c.onAccent, modifier = Modifier.size(13.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            node.name, color = c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
        Text(
            if (node.isDirectory) "Ordner" else formatSize(node.size),
            style = MaterialTheme.typography.labelSmall, color = c.textMuted, maxLines = 1,
        )
    }
}
