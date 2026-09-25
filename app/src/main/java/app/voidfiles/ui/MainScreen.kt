package app.voidfiles.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.voidfiles.data.AppSettings
import app.voidfiles.data.Archives
import app.voidfiles.ui.theme.VoidTheme
import kotlinx.coroutines.launch

@Composable
fun MainScreen(vm: MainViewModel, settings: AppSettings) {
    val context = LocalContext.current
    val launcher = remember { Launcher(context.applicationContext) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var protonHint by remember { mutableStateOf(false) }
    val c = VoidTheme.colors

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dual = landscape && settings.dualPaneLandscape

    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addCloudRoot(uri)
    }

    LaunchedEffect(Unit) {
        vm.uiEvents.collect { e ->
            when (e) {
                is UiEvent.Message -> {
                    snackbar.currentSnackbarData?.dismiss()
                    launch { snackbar.showSnackbar(e.text) }
                }
                is UiEvent.Open -> if (Archives.isArchive(e.node.name)) dlg = Dlg.ArchiveAction(e.node) else launcher.open(e.node)
                is UiEvent.Install -> launcher.installApk(e.apk)
            }
        }
    }

    BackHandler {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            !vm.back() -> (context as? Activity)?.moveTaskToBack(true)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = vm.screen == Screen.FILES,
        drawerContent = {
            Drawer(
                vm = vm,
                settings = settings,
                launcher = launcher,
                close = { scope.launch { drawer.close() } },
                onAddCloud = { protonHint = true },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(containerColor = c.background) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (vm.screen) {
                        Screen.SETTINGS -> SettingsScreen(vm, settings) { vm.screen = Screen.FILES }
                        Screen.TRASH -> TrashScreen(vm) { vm.screen = Screen.FILES }
                        Screen.FILES -> FilesLayout(
                            vm = vm,
                            settings = settings,
                            dual = dual,
                            launcher = launcher,
                            onMenu = { scope.launch { drawer.open() } },
                            onDialog = { dlg = it },
                        )
                    }
                }
            }
            // Messages appear at the top so they never cover the paste bar or buttons at the bottom.
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp),
            ) { data ->
                Snackbar(
                    data,
                    containerColor = c.text,
                    contentColor = c.background,
                    shape = RoundedCornerShape(50),
                )
            }
        }
    }

    // ---------------------------------------------------------------- dialogs
    when (val d = dlg) {
        null -> Unit
        is Dlg.NewFolder -> TextInputDialog("Neuer Ordner", "Neuer Ordner", "Erstellen", { dlg = null }) {
            dlg = null; vm.createFolder(d.pane, it)
        }
        is Dlg.NewFile -> TextInputDialog("Neue Datei", "Neue Datei.txt", "Erstellen", { dlg = null }, selectBaseName = true) {
            dlg = null; vm.createFile(d.pane, it)
        }
        is Dlg.Rename -> TextInputDialog("Umbenennen", d.node.name, "Umbenennen", { dlg = null }, selectBaseName = !d.node.isDirectory) {
            dlg = null; vm.rename(d.pane, d.node, it)
        }
        is Dlg.Delete -> DeleteDialog(d.nodes, settings.useTrash, { dlg = null }) { permanent ->
            dlg = null; vm.delete(d.pane, d.nodes, permanent)
        }
        is Dlg.Compress -> CompressDialog(d.nodes, { dlg = null }) { name, pw ->
            dlg = null; vm.compress(d.pane, d.nodes, name, pw)
        }
        is Dlg.ArchiveAction -> ArchiveDialog(
            node = d.node,
            dualPane = dual,
            onDismiss = { dlg = null },
            onExtractHere = { dlg = null; vm.extract(d.node, vm.active.current, intoFolder = false) },
            onExtractFolder = { dlg = null; vm.extract(d.node, vm.active.current, intoFolder = true) },
            onExtractOther = { dlg = null; vm.extract(d.node, vm.other.current, intoFolder = true) },
            onOpenWith = { dlg = null; launcher.open(d.node, chooser = true) },
        )
        is Dlg.Sort -> SortDialog(
            sortBy = settings.sortBy,
            ascending = settings.sortAscending,
            foldersFirst = settings.foldersFirst,
            viewMode = settings.viewMode,
            showHidden = settings.showHidden,
            onDismiss = { dlg = null },
            onSort = { by, asc -> vm.update { setSort(by, asc) } },
            onFoldersFirst = { v -> vm.update { setFoldersFirst(v) } },
            onViewMode = { v -> vm.update { setViewMode(v) } },
            onShowHidden = { v -> vm.update { setShowHidden(v) } },
        )
    }
    vm.pendingTransfer?.let { ConflictDialog(it) { policy -> vm.resolveConflict(policy) } }
    vm.pendingPassword?.let { p ->
        PasswordDialog(p.archive.name, p.wrong, { vm.pendingPassword = null }) { pw ->
            vm.pendingPassword = null
            vm.extract(p.archive, p.dest, p.intoFolder, pw)
        }
    }
    vm.properties?.let { PropertiesDialog(it) { vm.properties = null } }
    val update = vm.availableUpdate
    if (vm.showUpdateDialog && update != null) {
        UpdateDialog(update, onDismiss = { vm.showUpdateDialog = false }) { vm.downloadUpdate(update) }
    }
    if (protonHint) {
        ConfirmDialog(
            title = "Cloud verbinden",
            text = "Im nächsten Dialog oben links das Menü (☰) öffnen und \"Proton Drive\" wählen, dann einen Ordner " +
                "freigeben.\n\nErscheint Proton Drive dort nicht, stellt deine Proton-App (noch) keinen Speicherzugriff " +
                "bereit. Dateien kannst du dann weiterhin über \"An Proton Drive senden\" hochladen.",
            confirm = "Weiter",
            onDismiss = { protonHint = false },
        ) {
            protonHint = false
            runCatching { treePicker.launch(null) }.onFailure { vm.message("Ordnerauswahl nicht verfügbar") }
        }
    }
}

@Composable
private fun FilesLayout(
    vm: MainViewModel,
    settings: AppSettings,
    dual: Boolean,
    launcher: Launcher,
    onMenu: () -> Unit,
    onDialog: (Dlg) -> Unit,
) {
    val c = VoidTheme.colors
    Box(Modifier.fillMaxSize()) {
        if (dual) {
            Row(Modifier.fillMaxSize()) {
                BrowserPane(
                    vm, vm.left, settings, active = vm.activeIsLeft, compact = true, launcher = launcher,
                    onActivate = { vm.activeIsLeft = true }, onMenu = onMenu, onDialog = onDialog,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                TransferStrip(vm)
                BrowserPane(
                    vm, vm.right, settings, active = !vm.activeIsLeft, compact = true, launcher = launcher,
                    onActivate = { vm.activeIsLeft = false }, onMenu = null, onDialog = onDialog,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            BrowserPane(
                vm, vm.active, settings, active = true, compact = false, launcher = launcher,
                onActivate = {}, onMenu = onMenu, onDialog = onDialog,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(vm.op != null, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }) {
                vm.op?.let { OpCard(it) { vm.cancelOp() } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val clip = vm.clipboard
                if (clip != null) {
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(50)).background(c.text).padding(start = 18.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${clip.nodes.size} ${if (clip.cut) "AUSGESCHNITTEN" else "KOPIERT"}",
                            style = MaterialTheme.typography.labelMedium, color = c.background, modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.paste(vm.active) }) {
                            Icon(Icons.Outlined.ContentPaste, null, tint = c.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("EINFÜGEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                        }
                        IconButton(onClick = { vm.clipboard = null }) { Icon(Icons.Outlined.Close, "Leeren", tint = c.background) }
                    }
                    Spacer(Modifier.width(12.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (vm.active.selection.isEmpty()) NewFab(vm, onDialog)
            }
        }
    }
}

@Composable
private fun NewFab(vm: MainViewModel, onDialog: (Dlg) -> Unit) {
    val c = VoidTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(58.dp).clip(CircleShape).background(c.accent).clickable { open = true },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.Add, "Neu", tint = c.onAccent, modifier = Modifier.size(28.dp)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surfaceHigh) {
            MenuItem("Neuer Ordner", Icons.Outlined.CreateNewFolder) { open = false; onDialog(Dlg.NewFolder(vm.active)) }
            MenuItem("Neue Datei", Icons.Outlined.NoteAdd) { open = false; onDialog(Dlg.NewFile(vm.active)) }
        }
    }
}

@Composable
private fun OpCard(op: OpState, onCancel: () -> Unit) {
    val c = VoidTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfaceHigh)
            .border(1.dp, c.divider, RoundedCornerShape(24.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(op.title, color = c.text, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                if (op.file.isNotEmpty()) Label(op.file, color = c.textMuted)
            }
            TextButton(onClick = onCancel) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
        }
        Spacer(Modifier.height(8.dp))
        if (op.total > 0) {
            val f = (op.done.toFloat() / op.total).coerceIn(0f, 1f)
            DotBar(f, Modifier.fillMaxWidth().height(10.dp), dots = 36)
            Spacer(Modifier.height(4.dp))
            Label("${formatSize(op.done)} / ${formatSize(op.total)} · ${(f * 100).toInt()} %")
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.divider)
        }
    }
}

/** Centre column in dual-pane mode: arrows send the selection to the other side. */
@Composable
private fun TransferStrip(vm: MainViewModel) {
    val c = VoidTheme.colors
    Column(
        Modifier.width(72.dp).fillMaxHeight().background(c.surface).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        val leftSel = vm.left.selection.size
        val rightSel = vm.right.selection.size
        ArrowButton(Icons.AutoMirrored.Outlined.ArrowForward, leftSel, "Nach rechts") { vm.transfer(vm.left, vm.right) }
        ArrowButton(Icons.AutoMirrored.Outlined.ArrowBack, rightSel, "Nach links") { vm.transfer(vm.right, vm.left) }
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).border(1.dp, c.divider, RoundedCornerShape(16.dp))
                .clickable { vm.transferMove = !vm.transferMove }.padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                if (vm.transferMove) Icons.Outlined.DriveFileMove else Icons.Outlined.ContentCopy, null,
                tint = c.text, modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (vm.transferMove) "MOVE" else "KOPIE",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                color = c.text,
            )
        }
        IconButton(onClick = {
            val target = vm.active.stack
            vm.other.stack = target
            vm.reload(vm.other)
        }) { Icon(Icons.Outlined.SyncAlt, "Gleicher Ordner", tint = c.textMuted) }
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, count: Int, description: String, onClick: () -> Unit) {
    val c = VoidTheme.colors
    val enabled = count > 0
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(if (enabled) c.accent else c.surfaceHigh)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, description, tint = if (enabled) c.onAccent else c.textMuted, modifier = Modifier.size(26.dp)) }
        if (enabled) {
            Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(c.text),
                contentAlignment = Alignment.Center,
            ) { Text("$count", color = c.background, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)) }
        }
    }
}
