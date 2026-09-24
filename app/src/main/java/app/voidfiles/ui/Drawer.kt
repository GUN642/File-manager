package app.voidfiles.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voidfiles.BuildConfig
import app.voidfiles.data.AppSettings
import app.voidfiles.data.CloudRoot
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Storage
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle
import java.io.File

@Composable
fun Drawer(
    vm: MainViewModel,
    settings: AppSettings,
    launcher: Launcher,
    close: () -> Unit,
    onAddCloud: () -> Unit,
) {
    val c = VoidTheme.colors
    val context = LocalContext.current
    val volumes = remember { Storage.volumes(context) }
    var removeRoot by remember { mutableStateOf<CloudRoot?>(null) }
    var editQuick by remember { mutableStateOf(false) }

    ModalDrawerSheet(drawerContainerColor = c.background, drawerContentColor = c.text) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 20.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.Bottom) {
                Text("VOID", style = headingStyle(46), color = c.text)
                Text(".", style = headingStyle(46), color = c.accent)
            }
            Label("Files · ${BuildConfig.VERSION_NAME}", Modifier.padding(horizontal = 24.dp))
            Spacer(Modifier.height(20.dp))

            Section("Speicher")
            volumes.forEach { v ->
                val total = v.total
                val used = total - v.free
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp))
                        .combinedClickableCompat { vm.openLocal(vm.active, v.root); close() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (v.isPrimary) Icons.Outlined.PhoneAndroid else Icons.Outlined.SdCard, null, tint = c.text)
                        Spacer(Modifier.width(14.dp))
                        Text(v.label, color = c.text, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (total > 0) {
                        Spacer(Modifier.height(8.dp))
                        DotBar(used.toFloat() / total, Modifier.fillMaxWidth().height(8.dp))
                        Spacer(Modifier.height(4.dp))
                        Label("${formatSize(v.free)} frei von ${formatSize(total)}")
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, start = 24.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("Schnellzugriff", Modifier.weight(1f))
                Text(
                    if (editQuick) "FERTIG" else "BEARBEITEN",
                    style = MaterialTheme.typography.labelSmall,
                    color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { editQuick = !editQuick }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            DrawerItem(RECENT_QUERY, Icons.Outlined.History) { vm.showRecent(vm.active); close() }
            settings.quickAccess.forEachIndexed { i, path ->
                val label = Storage.labelFor(path)
                if (editQuick) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(quickIcon(label), null, tint = c.textMuted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(label, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.update { moveQuickAccess(path, -1) } }, enabled = i > 0) {
                            Icon(Icons.Outlined.KeyboardArrowUp, "Nach oben", tint = if (i > 0) c.text else c.divider)
                        }
                        IconButton(onClick = { vm.update { moveQuickAccess(path, 1) } }, enabled = i < settings.quickAccess.lastIndex) {
                            Icon(Icons.Outlined.KeyboardArrowDown, "Nach unten",
                                tint = if (i < settings.quickAccess.lastIndex) c.text else c.divider)
                        }
                        IconButton(onClick = { vm.update { removeQuickAccess(path) } }) {
                            Icon(Icons.Outlined.RemoveCircleOutline, "Entfernen", tint = c.accent)
                        }
                    }
                } else {
                    DrawerItem(label, quickIcon(label), onLongClick = { editQuick = true }) {
                        vm.openLocal(vm.active, File(path)); close()
                    }
                }
            }
            if (editQuick) {
                val cur = vm.active.current
                if (cur is LocalNode && cur.file.absolutePath !in settings.quickAccess) {
                    DrawerItem("\"${Storage.labelFor(cur.file.absolutePath)}\" hinzufügen", Icons.Outlined.Add) {
                        vm.update { addQuickAccess(cur.file.absolutePath) }
                    }
                }
                Label(
                    "Tipp: Ordner öffnen und im ⋮-Menü \"Zum Schnellzugriff\" wählen.",
                    Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
                DrawerItem("Standard wiederherstellen", Icons.Outlined.Restore) { vm.update { resetQuickAccess() } }
            }

            Section("Cloud")
            settings.cloudRoots.forEach { root ->
                DrawerItem(root.label, Icons.Outlined.Cloud, onLongClick = { removeRoot = root }) {
                    vm.openCloud(vm.active, root); close()
                }
            }
            DrawerItem("Cloud-Ordner verbinden", Icons.Outlined.Add) { close(); onAddCloud() }
            DrawerItem("Proton Drive öffnen", Icons.Outlined.OpenInNew) { close(); launcher.openProtonApp() }

            Section("")
            DrawerItem("Papierkorb", Icons.Outlined.Delete) { vm.loadTrash(); vm.screen = Screen.TRASH; close() }
            DrawerItem("Einstellungen", Icons.Outlined.Settings) { vm.screen = Screen.SETTINGS; close() }
        }
    }

    removeRoot?.let { r ->
        ConfirmDialog("Trennen", "\"${r.label}\" aus der Liste entfernen? Deine Dateien bleiben erhalten.", "Entfernen",
            onDismiss = { removeRoot = null }) {
            vm.removeCloudRoot(r); removeRoot = null
        }
    }
}

private fun quickIcon(label: String): ImageVector = when (label) {
    "Interner Speicher" -> Icons.Outlined.PhoneAndroid
    "Downloads" -> Icons.Outlined.Download
    "Kamera" -> Icons.Outlined.CameraAlt
    "Bilder" -> Icons.Outlined.Image
    "Dokumente" -> Icons.Outlined.Description
    "Musik" -> Icons.Outlined.MusicNote
    "Videos" -> Icons.Outlined.Movie
    else -> Icons.Outlined.Folder
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(14.dp))
    if (title.isNotEmpty()) Label(title, Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)

@Composable
private fun DrawerItem(text: String, icon: ImageVector, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(50))
            .combinedClickableCompat(onLongClick, onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = c.textMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, color = c.text, style = MaterialTheme.typography.bodyLarge)
    }
}
