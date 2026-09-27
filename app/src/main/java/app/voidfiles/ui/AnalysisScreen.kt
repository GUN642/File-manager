package app.voidfiles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Storage
import app.voidfiles.ui.theme.VoidTheme
import app.voidfiles.ui.theme.headingStyle

private enum class AnalysisTab(val label: String) { OVERVIEW("Übersicht"), LARGEST("Größte"), DUPLICATES("Duplikate") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnalysisScreen(vm: MainViewModel) {
    val c = VoidTheme.colors
    val context = LocalContext.current
    val volumes = remember { Storage.volumes(context) }
    var volumeIndex by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(AnalysisTab.OVERVIEW) }
    var confirm by remember { mutableStateOf<LocalNode?>(null) }
    val a = vm.analysis
    val result = a.result

    LaunchedEffect(Unit) {
        if (a.result == null && !a.running && volumes.isNotEmpty()) vm.startAnalysis(volumes[0])
    }

    Column(Modifier.fillMaxSize().background(c.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.cancelAnalysis(); vm.screen = Screen.FILES }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Zurück", tint = c.text)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { volumes.getOrNull(volumeIndex)?.let { vm.startAnalysis(it) } }, enabled = !a.running) {
                Icon(Icons.Outlined.Refresh, "Neu analysieren", tint = if (a.running) c.divider else c.text)
            }
        }
        Text("SPEICHER", style = headingStyle(40), color = c.text, modifier = Modifier.padding(horizontal = 20.dp))
        if (volumes.size > 1) {
            FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                volumes.forEachIndexed { i, v ->
                    Pill(v.label, i == volumeIndex, {
                        volumeIndex = i
                        vm.startAnalysis(v)
                    })
                }
            }
        }

        if (a.running) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                DotRing(null, Modifier.size(120.dp), dots = 30)
                Spacer(Modifier.height(20.dp))
                Text("${a.count}", style = headingStyle(34), color = c.text)
                Label("Dateien geprüft")
                Spacer(Modifier.height(6.dp))
                Label(a.status, color = c.textMuted)
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { vm.cancelAnalysis() }) {
                    Text("ABBRECHEN", style = MaterialTheme.typography.labelLarge, color = c.accent)
                }
            }
            return@Column
        }
        if (result == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(a.status.ifEmpty { "Keine Daten" }, color = c.textMuted)
            }
            return@Column
        }

        // Volume usage
        val used = result.total - result.free
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            DotBar(if (result.total > 0) used.toFloat() / result.total else 0f, Modifier.fillMaxWidth().height(10.dp), dots = 36)
            Spacer(Modifier.height(4.dp))
            Label("${formatSize(used)} belegt · ${formatSize(result.free)} frei · ${result.fileCount} Dateien")
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnalysisTab.entries.forEach { t -> Pill(t.label, t == tab, { tab = t }) }
        }

        when (tab) {
            AnalysisTab.OVERVIEW -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                item { SectionLabel("Nach Typ") }
                items(result.categories, key = { "cat:" + it.category.name }) { stat ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stat.category.label, color = c.text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Label("${formatSize(stat.bytes)} · ${stat.count}")
                        }
                        Spacer(Modifier.height(6.dp))
                        DotBar(
                            if (result.scannedBytes > 0) stat.bytes.toFloat() / result.scannedBytes else 0f,
                            Modifier.fillMaxWidth().height(8.dp), dots = 32,
                        )
                    }
                }
                item { SectionLabel("Größte Ordner") }
                items(result.folders, key = { "dir:" + it.first.id }) { (dir, bytes) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.openLocal(vm.active, dir.file) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NodeIcon(dir, 36.dp, false)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(dir.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            DotBar(
                                if (result.scannedBytes > 0) bytes.toFloat() / result.scannedBytes else 0f,
                                Modifier.fillMaxWidth().height(6.dp), dots = 28,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Label(formatSize(bytes))
                    }
                }
            }
            AnalysisTab.LARGEST -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(result.largest, key = { it.id }) { file ->
                    FileLine(vm, file, subtitle = file.file.parent?.removePrefix(result.root.absolutePath).orEmpty().ifEmpty { "/" }) {
                        confirm = file
                    }
                }
            }
            AnalysisTab.DUPLICATES -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
                item {
                    val wasted = result.duplicates.sumOf { it.wasted }
                    Label(
                        if (result.duplicates.isEmpty()) "Keine Duplikate gefunden"
                        else "${result.duplicates.size} Gruppen · ${formatSize(wasted)} doppelt belegt",
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                result.duplicates.forEachIndexed { gi, group ->
                    item(key = "dup:$gi:" + group.files.first().id) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 2.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                group.files.first().name, color = c.text, style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                            )
                            Label("${group.files.size}× ${formatSize(group.size)}", color = c.accent)
                        }
                    }
                    items(group.files, key = { "dupf:$gi:" + it.id }) { file ->
                        FileLine(vm, file, subtitle = file.file.parent?.removePrefix(result.root.absolutePath).orEmpty().ifEmpty { "/" }) {
                            confirm = file
                        }
                    }
                }
            }
        }
    }

    confirm?.let { f ->
        ConfirmDialog(
            "Löschen",
            "\"${f.name}\" (${formatSize(f.size)}) löschen?",
            "Löschen",
            onDismiss = { confirm = null },
        ) {
            confirm = null
            vm.analysisDelete(f)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Label(text, Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp))
}

@Composable
private fun FileLine(vm: MainViewModel, file: LocalNode, subtitle: String, onDelete: () -> Unit) {
    val c = VoidTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable { vm.openLocal(vm.active, file.file.parentFile ?: file.file) }
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NodeIcon(file, 40.dp, true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, color = c.text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatSize(file.size)}  ·  $subtitle", style = MaterialTheme.typography.labelSmall, color = c.textMuted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Löschen", tint = c.accent) }
    }
}
