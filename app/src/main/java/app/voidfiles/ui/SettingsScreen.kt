package app.voidfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.voidfiles.BuildConfig
import app.voidfiles.data.Accent
import app.voidfiles.data.AppSettings
import app.voidfiles.data.ThemeMode
import app.voidfiles.data.ViewMode
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle

@Composable
private fun ScreenHeader(title: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    val c = VoidTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = c.text) }
            Spacer(Modifier.weight(1f))
            action()
        }
        Text(title.uppercase(), style = headingStyle(40), color = c.text, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(12.dp))
    }
}

private val themeSwatches = mapOf(
    ThemeMode.BLACK to Color(0xFF000000),
    ThemeMode.GRAPHITE to Color(0xFF1C1C1E),
    ThemeMode.STEEL to Color(0xFF12161B),
    ThemeMode.PAPER to Color(0xFFEFEEEA),
    ThemeMode.WHITE to Color(0xFFFFFFFF),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: MainViewModel, s: AppSettings, onBack: () -> Unit) {
    val c = VoidTheme.colors
    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader("Einstellungen", onBack)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
            Group("Hintergrund")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeMode.entries.forEach { t ->
                    ThemeTile(t, t == s.theme) { vm.update { setTheme(t) } }
                }
            }

            Group("Akzentfarbe")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Accent.entries.forEach { a ->
                    val color = if (a == Accent.MONO) c.text else Color(a.argb)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .border(2.dp, if (a == s.accent) c.text else Color.Transparent, CircleShape)
                                .padding(5.dp).clip(CircleShape).background(color)
                                .clickable { vm.update { setAccent(a) } },
                        )
                        Spacer(Modifier.height(4.dp))
                        Label(a.label, color = if (a == s.accent) c.text else c.textMuted)
                    }
                }
            }
            if (s.theme == ThemeMode.DYNAMIC) {
                Label("Bei Material You kommt die Akzentfarbe vom Hintergrundbild.", Modifier.padding(top = 8.dp))
            }

            Group("Design")
            Toggle("Dot-Matrix-Überschriften", "Pixelschrift im Nothing-Stil", s.dotHeadings) { v -> vm.update { setDotHeadings(v) } }
            Toggle("Punkteraster", "Dezentes Punktmuster in leeren Ordnern", s.dotGrid) { v -> vm.update { setDotGrid(v) } }
            Toggle("Vorschaubilder", "Miniaturen für Bilder und Videos", s.thumbnails) { v -> vm.update { setThumbnails(v) } }
            Toggle("Rasteransicht", "Kacheln statt Liste", s.viewMode == ViewMode.GRID) { v ->
                vm.update { setViewMode(if (v) ViewMode.GRID else ViewMode.LIST) }
            }

            Group("Dateien")
            Toggle("Versteckte Dateien", "Dateien mit Punkt am Anfang anzeigen", s.showHidden) { v -> vm.update { setShowHidden(v) } }
            Toggle("Ordner zuerst", null, s.foldersFirst) { v -> vm.update { setFoldersFirst(v) } }
            Toggle("Papierkorb verwenden", "Gelöschte Dateien zuerst in den Papierkorb", s.useTrash) { v -> vm.update { setUseTrash(v) } }

            Group("Querformat")
            Toggle("Zwei Fenster", "Im Querformat zwei Ordner nebeneinander mit Pfeil-Übertragung", s.dualPaneLandscape) { v ->
                vm.update { setDualPane(v) }
            }

            Group("Updates")
            UpdateSection(vm, s)

            Group("Über")
            Text("VOID Files ${BuildConfig.VERSION_NAME}", color = c.text, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Schriften: Doto & Space Mono (SIL Open Font License). Archive: zip4j, Apache Commons Compress, junrar.",
                color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun UpdateSection(vm: MainViewModel, s: AppSettings) {
    val c = VoidTheme.colors
    LaunchedEffect(Unit) { if (vm.releases.isEmpty()) vm.checkForUpdates(manual = false) }
    val update = vm.availableUpdate
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Installiert: ${BuildConfig.VERSION_NAME}", color = c.text, style = MaterialTheme.typography.bodyLarge)
            vm.updateStatus?.let {
                Text(it, color = if (update != null) c.accent else c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (vm.checkingUpdate) CircularProgressIndicator(Modifier.size(22.dp), color = c.accent, strokeWidth = 2.dp)
    }
    Spacer(Modifier.height(12.dp))
    val op = vm.op
    if (op != null && op.title.startsWith("Update")) {
        val f = if (op.total > 0) (op.done.toFloat() / op.total).coerceIn(0f, 1f) else 0f
        DotBar(f, Modifier.fillMaxWidth().height(10.dp), dots = 32)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("${formatSize(op.done)} / ${formatSize(op.total)}", Modifier.weight(1f))
            TextButton(onClick = { vm.cancelOp() }) { Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.accent) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (update != null) {
                Pill("Auf ${update.version} aktualisieren", selected = true, onClick = { vm.downloadUpdate(update) })
            }
            Pill("Nach Updates suchen", selected = update == null, onClick = { vm.checkForUpdates(manual = true) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Toggle("Beim Start prüfen", "Beim Öffnen der App nach neuen Versionen suchen", s.autoUpdateCheck) { v ->
        vm.update { setAutoUpdate(v) }
    }

    Group("Changelog")
    if (vm.releases.isEmpty()) {
        Text(
            if (vm.checkingUpdate) "Wird geladen …" else "Changelog nicht verfügbar – Internetverbindung prüfen.",
            color = c.textMuted, style = MaterialTheme.typography.bodyMedium,
        )
    }
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) vm.releases else vm.releases.take(5)
    shown.forEach { r ->
        val installed = r.version == BuildConfig.VERSION_NAME
        val newer = app.voidfiles.data.Updater.isNewer(r.version, BuildConfig.VERSION_NAME)
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.version, style = headingStyle(22), color = c.text)
                Spacer(Modifier.width(10.dp))
                Label(r.publishedAt.take(10).split('-').reversed().joinToString("."), Modifier.weight(1f))
                when {
                    installed -> Label("Installiert", color = c.text)
                    newer -> Label("Neu", color = c.accent)
                }
            }
            if (r.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(r.notes, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (vm.releases.size > 5) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "WENIGER" else "ALLE ${vm.releases.size} VERSIONEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
        }
    }
}

@Composable
private fun Group(title: String) {
    Spacer(Modifier.height(26.dp))
    Label(title)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ThemeTile(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val c = VoidTheme.colors
    val swatch = themeSwatches[mode]
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(18.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.divider, RoundedCornerShape(18.dp))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (swatch != null) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(swatch).border(1.dp, c.divider, RoundedCornerShape(12.dp)))
            } else {
                // System / Material You: split swatch.
                Row(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))) {
                    Box(Modifier.weight(1f).height(40.dp).background(Color.Black))
                    Box(Modifier.weight(1f).height(40.dp).background(if (mode == ThemeMode.DYNAMIC) Color(0xFFB4C8FF) else Color(0xFFEFEEEA)))
                }
            }
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
        }
        Spacer(Modifier.height(4.dp))
        Label(mode.label, color = if (selected) c.text else c.textMuted)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.textMuted, uncheckedTrackColor = c.surfaceHigh, uncheckedBorderColor = c.divider,
            ),
        )
    }
}

@Composable
fun TrashScreen(vm: MainViewModel, onBack: () -> Unit) {
    val c = VoidTheme.colors
    var confirmEmpty by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.background)) {
        ScreenHeader("Papierkorb", onBack) {
            if (vm.trashEntries.isNotEmpty()) {
                TextButton(onClick = { confirmEmpty = true }) {
                    Text("LEEREN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
        }
        Label("${vm.trashEntries.size} Elemente", Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        if (vm.trashEntries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (VoidTheme.dotGrid) DotGrid(Modifier.fillMaxSize())
                Text("LEER", style = headingStyle(34), color = c.textMuted)
            }
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                vm.trashEntries.forEach { e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Label("${formatDate(e.deletedAt)} · ${e.originalPath.substringBeforeLast('/')}")
                        }
                        IconButton(onClick = { vm.restore(e) }) { Icon(Icons.Outlined.Restore, "Wiederherstellen", tint = c.text) }
                        IconButton(onClick = { vm.deleteForever(e) }) { Icon(Icons.Outlined.DeleteForever, "Endgültig löschen", tint = c.accent) }
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        ConfirmDialog("Leeren", "Alle Elemente im Papierkorb endgültig löschen?", "Leeren", { confirmEmpty = false }) {
            confirmEmpty = false; vm.emptyTrash()
        }
    }
}
