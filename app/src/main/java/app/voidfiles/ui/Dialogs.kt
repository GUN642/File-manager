package app.voidfiles.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import app.voidfiles.data.ConflictPolicy
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.SortBy
import app.voidfiles.data.ViewMode
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle

/** Dialogs that can be opened from anywhere in the file screen. */
sealed interface Dlg {
    data class NewFolder(val pane: Pane) : Dlg
    data class NewFile(val pane: Pane) : Dlg
    data class Rename(val pane: Pane, val node: Node) : Dlg
    data class Delete(val pane: Pane, val nodes: List<Node>) : Dlg
    data class Compress(val pane: Pane, val nodes: List<Node>) : Dlg
    data class ArchiveAction(val node: Node) : Dlg
    data class Sort(val pane: Pane) : Dlg
    data class BatchRename(val pane: Pane, val nodes: List<Node>) : Dlg
    data class MoveToVault(val pane: Pane, val nodes: List<Node>) : Dlg
}

@Composable
private fun VoidDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String?,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String = "Abbrechen",
    content: @Composable () -> Unit,
) {
    val c = VoidTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(title.uppercase(), style = headingStyle(24), color = c.text) },
        text = { content() },
        confirmButton = {
            if (confirmText != null) {
                TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                    Text(confirmText.uppercase(), style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText.uppercase(), style = MaterialTheme.typography.labelLarge, color = c.textMuted)
            }
        },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    selectBaseName: Boolean = false,
    onConfirm: (String) -> Unit,
) {
    val selEnd = if (selectBaseName && initial.contains('.')) initial.lastIndexOf('.').coerceAtLeast(1) else initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, selEnd))) }
    val focus = remember { FocusRequester() }
    val valid = value.text.isNotBlank() && !value.text.contains('/')
    VoidDialog(title, onDismiss, confirmText, { if (valid) onConfirm(value.text) }, confirmEnabled = valid) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
fun DeleteDialog(nodes: List<Node>, useTrash: Boolean, onDismiss: () -> Unit, onConfirm: (permanent: Boolean) -> Unit) {
    val trashable = useTrash && nodes.all { it is LocalNode }
    var permanent by remember { mutableStateOf(!trashable) }
    val what = if (nodes.size == 1) "\"${nodes.first().name}\"" else "${nodes.size} Elemente"
    VoidDialog("Löschen", onDismiss, if (permanent) "Endgültig löschen" else "Papierkorb", { onConfirm(permanent) }) {
        Column {
            Text(
                if (permanent) "$what endgültig löschen? Das kann nicht rückgängig gemacht werden."
                else "$what in den Papierkorb verschieben?",
                color = VoidTheme.colors.text,
            )
            if (trashable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = permanent, onCheckedChange = { permanent = it })
                    Text("Endgültig löschen", color = VoidTheme.colors.textMuted)
                }
            }
        }
    }
}

@Composable
fun CompressDialog(nodes: List<Node>, onDismiss: () -> Unit, onConfirm: (name: String, password: String?) -> Unit) {
    val suggested = if (nodes.size == 1) nodes.first().name.substringBeforeLast('.').ifEmpty { nodes.first().name } else "Archiv"
    var name by remember { mutableStateOf(suggested) }
    var protect by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && !name.contains('/') && (!protect || password.length >= 4)
    VoidDialog("ZIP erstellen", onDismiss, "Erstellen", { onConfirm(name.trim(), password.takeIf { protect }) }, confirmEnabled = valid) {
        Column {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") },
                suffix = { Text(".zip") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = protect, onCheckedChange = { protect = it })
                Text("Mit Passwort schützen (AES-256)", color = VoidTheme.colors.text)
            }
            if (protect) {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Passwort (min. 4 Zeichen)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun PasswordDialog(archiveName: String, wrong: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    VoidDialog("Passwort", onDismiss, "Entpacken", { onConfirm(password) }, confirmEnabled = password.isNotEmpty()) {
        Column {
            Text(
                if (wrong) "Falsches Passwort für \"$archiveName\". Bitte erneut versuchen." else "\"$archiveName\" ist verschlüsselt.",
                color = if (wrong) MaterialTheme.colorScheme.error else VoidTheme.colors.text,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password, onValueChange = { password = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun ConflictDialog(pending: PendingTransfer, onResult: (ConflictPolicy?) -> Unit) {
    val names = pending.conflicts
    VoidDialog("Bereits vorhanden", { onResult(null) }, null, {}) {
        Column {
            Text(
                if (names.size == 1) "\"${names.first()}\" existiert im Ziel bereits."
                else "${names.size} Elemente existieren im Ziel bereits.",
                color = VoidTheme.colors.text,
            )
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Ersetzen", selected = true, onClick = { onResult(ConflictPolicy.OVERWRITE) })
                Pill("Beide behalten", selected = false, onClick = { onResult(ConflictPolicy.KEEP_BOTH) })
                Pill("Überspringen", selected = false, onClick = { onResult(ConflictPolicy.SKIP) })
            }
        }
    }
}

@Composable
fun ArchiveDialog(
    node: Node,
    dualPane: Boolean,
    onDismiss: () -> Unit,
    onExtractHere: () -> Unit,
    onExtractFolder: () -> Unit,
    onExtractOther: () -> Unit,
    onOpenWith: () -> Unit,
    onBrowse: () -> Unit,
) {
    VoidDialog(node.name, onDismiss, null, {}) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Inhalt ansehen", selected = true, onClick = onBrowse)
            Pill("In Ordner entpacken", selected = false, onClick = onExtractFolder)
            Pill("Hier entpacken", selected = false, onClick = onExtractHere)
            if (dualPane) Pill("In anderes Fenster entpacken", selected = false, onClick = onExtractOther)
            Pill("Öffnen mit …", selected = false, onClick = onOpenWith)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SortDialog(
    sortBy: SortBy,
    ascending: Boolean,
    foldersFirst: Boolean,
    viewMode: ViewMode,
    showHidden: Boolean,
    onDismiss: () -> Unit,
    onSort: (SortBy, Boolean) -> Unit,
    onFoldersFirst: (Boolean) -> Unit,
    onViewMode: (ViewMode) -> Unit,
    onShowHidden: (Boolean) -> Unit,
) {
    VoidDialog("Ansicht", onDismiss, null, {}, dismissText = "Fertig") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label("Sortieren nach")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SortBy.entries.forEach { s -> Pill(s.label, s == sortBy, { onSort(s, ascending) }) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Aufsteigend", ascending, { onSort(sortBy, true) })
                Pill("Absteigend", !ascending, { onSort(sortBy, false) })
            }
            Label("Darstellung")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Liste", viewMode == ViewMode.LIST, { onViewMode(ViewMode.LIST) })
                Pill("Raster", viewMode == ViewMode.GRID, { onViewMode(ViewMode.GRID) })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("Ordner zuerst", foldersFirst, { onFoldersFirst(!foldersFirst) })
                Pill("Versteckte", showHidden, { onShowHidden(!showHidden) })
            }
        }
    }
}

@Composable
fun PropertiesDialog(p: Properties, onDismiss: () -> Unit, onChecksums: () -> Unit) {
    val c = VoidTheme.colors
    VoidDialog("Eigenschaften", onDismiss, null, {}, dismissText = "Schließen") {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PropRow("Name", p.node.name)
            PropRow("Pfad", p.path)
            PropRow("Typ", if (p.node.isDirectory) "Ordner" else p.node.mimeType)
            PropRow("Größe", p.size?.let { "${formatSize(it)} (${"%,d".format(it)} Bytes)" } ?: "wird berechnet …")
            if (p.node.isDirectory) PropRow("Inhalt", p.files?.let { "$it Dateien" } ?: "wird berechnet …")
            PropRow("Geändert", formatDate(p.node.lastModified))
            if (p.node is LocalNode) {
                val f = p.node.file
                PropRow("Rechte", buildString {
                    append(if (f.canRead()) "Lesen" else "–")
                    append(" · ")
                    append(if (f.canWrite()) "Schreiben" else "schreibgeschützt")
                    if (f.isHidden) append(" · versteckt")
                })
            }
            p.details.forEach { (label, value) -> PropRow(label, value) }
            if (!p.node.isDirectory) {
                Column {
                    Label("Prüfsummen")
                    when {
                        p.md5 != null -> {
                            SelectionContainer {
                                Column {
                                    Text("MD5  ${p.md5}", color = c.text, style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.height(4.dp))
                                    Text("SHA-256  ${p.sha256}", color = c.text, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        p.hashing -> DotLoader(Modifier.fillMaxWidth().height(12.dp))
                        else -> Pill("MD5 / SHA-256 berechnen", selected = false, onClick = onChecksums)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BatchRenameDialog(
    nodes: List<Node>,
    preview: (RenameRule) -> List<String>,
    onDismiss: () -> Unit,
    onConfirm: (RenameRule) -> Unit,
) {
    val c = VoidTheme.colors
    var rule by remember { mutableStateOf(RenameRule()) }
    var startText by remember { mutableStateOf("1") }
    val names = remember(rule) { preview(rule) }
    val valid = if (rule.replaceMode) rule.find.isNotEmpty() else rule.pattern.isNotBlank()
    VoidDialog("${nodes.size} umbenennen", onDismiss, "Umbenennen", { onConfirm(rule) }, confirmEnabled = valid) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Muster", !rule.replaceMode, { rule = rule.copy(replaceMode = false) })
                Pill("Ersetzen", rule.replaceMode, { rule = rule.copy(replaceMode = true) })
            }
            if (!rule.replaceMode) {
                OutlinedTextField(
                    value = rule.pattern, onValueChange = { rule = rule.copy(pattern = it) }, singleLine = true,
                    label = { Text("Muster") }, modifier = Modifier.fillMaxWidth(),
                )
                Label("{name} = alter Name · {n} = Nummer · {date} = Datum (Fotos: Aufnahme)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Urlaub_{n}", "{date}_{n}", "{name}_{n}").forEach { preset ->
                        Pill(preset, rule.pattern == preset, { rule = rule.copy(pattern = preset) })
                    }
                }
                OutlinedTextField(
                    value = startText,
                    onValueChange = { v ->
                        startText = v.filter { it.isDigit() }.take(6)
                        rule = rule.copy(start = startText.toIntOrNull() ?: 1)
                    },
                    singleLine = true, label = { Text("Startnummer") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                OutlinedTextField(
                    value = rule.find, onValueChange = { rule = rule.copy(find = it) }, singleLine = true,
                    label = { Text("Suchen") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = rule.replace, onValueChange = { rule = rule.copy(replace = it) }, singleLine = true,
                    label = { Text("Ersetzen durch") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            Label("Vorschau")
            nodes.take(4).forEachIndexed { i, n ->
                Column {
                    Text(n.name, color = c.textMuted, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    Text("→ ${names.getOrElse(i) { n.name }}", color = c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
            }
            if (nodes.size > 4) Label("… und ${nodes.size - 4} weitere")
        }
    }
}

@Composable
private fun PropRow(label: String, value: String) {
    Column {
        Label(label)
        Text(value, color = VoidTheme.colors.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    VoidDialog(title, onDismiss, confirm, onConfirm) {
        Text(text, color = VoidTheme.colors.text, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun UpdateDialog(release: app.voidfiles.data.Release, onDismiss: () -> Unit, onUpdate: () -> Unit) {
    val c = VoidTheme.colors
    VoidDialog("Update", onDismiss, "Aktualisieren", onUpdate, dismissText = "Später") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "Version ${release.version} ist verfügbar (installiert: ${app.voidfiles.BuildConfig.VERSION_NAME}).",
                color = c.text,
            )
            if (release.notes.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Label("Neu in dieser Version")
                Spacer(Modifier.height(6.dp))
                Text(release.notes, color = c.textMuted, style = MaterialTheme.typography.bodyMedium)
            }
            if (release.apkSize > 0) {
                Spacer(Modifier.height(10.dp))
                Label("Download: ${formatSize(release.apkSize)}")
            }
        }
    }
}
