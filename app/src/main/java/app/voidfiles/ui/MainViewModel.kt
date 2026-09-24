package app.voidfiles.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.voidfiles.data.AppSettings
import app.voidfiles.data.Archives
import app.voidfiles.data.CloudRoot
import app.voidfiles.data.ConflictPolicy
import app.voidfiles.data.FileSystem
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.PasswordRequired
import app.voidfiles.data.ProgressSink
import app.voidfiles.data.SafNode
import app.voidfiles.data.SettingsRepository
import app.voidfiles.data.SortBy
import app.voidfiles.data.Storage
import app.voidfiles.data.Trash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

enum class Screen { FILES, SETTINGS, TRASH }

data class OpState(
    val title: String,
    val file: String = "",
    val done: Long = 0,
    val total: Long = 0,
)

data class Clipboard(val nodes: List<Node>, val cut: Boolean)

enum class ListKind { SEARCH, RECENT }

data class SearchState(
    val query: String,
    val results: List<Node>,
    val running: Boolean,
    val kind: ListKind = ListKind.SEARCH,
)

/** A copy/move waiting for the user to decide what happens with existing names. */
data class PendingTransfer(val nodes: List<Node>, val dest: Node, val move: Boolean, val conflicts: List<String>)

data class PendingPassword(val archive: Node, val dest: Node, val intoFolder: Boolean, val wrong: Boolean)

data class Properties(
    val node: Node,
    val path: String,
    val size: Long?,
    val files: Int?,
)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class Open(val node: Node) : UiEvent
}

const val RECENT_QUERY = "Letzte Dateien"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val fs = FileSystem(app)
    private val repo = SettingsRepository(app)
    private val trash = Trash(app)

    val settings: StateFlow<AppSettings> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val current: AppSettings get() = settings.value

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    val left = Pane(LocalNode.of(Storage.primaryRoot))
    val right = Pane(LocalNode.of(Storage.primaryRoot))
    var activeIsLeft by mutableStateOf(true)
    val active: Pane get() = if (activeIsLeft) left else right
    val other: Pane get() = if (activeIsLeft) right else left

    var screen by mutableStateOf(Screen.FILES)
    var clipboard by mutableStateOf<Clipboard?>(null)
    var op by mutableStateOf<OpState?>(null)
        private set
    var pendingTransfer by mutableStateOf<PendingTransfer?>(null)
    var pendingPassword by mutableStateOf<PendingPassword?>(null)
    var properties by mutableStateOf<Properties?>(null)
    var trashEntries by mutableStateOf<List<Trash.Entry>>(emptyList())
        private set

    /** Dual-pane transfer direction mode: false = copy, true = move. */
    var transferMove by mutableStateOf(false)

    private var opJob: Job? = null

    init {
        viewModelScope.launch {
            var last: AppSettings? = null
            settings.collect { s ->
                val prev = last
                last = s
                if (prev == null || prev.showHidden != s.showHidden || prev.sortBy != s.sortBy ||
                    prev.sortAscending != s.sortAscending || prev.foldersFirst != s.foldersFirst
                ) {
                    left.shown = arrange(left.raw)
                    right.shown = arrange(right.raw)
                }
            }
        }
        reload(left)
        reload(right)
    }

    fun message(text: String) {
        events.trySend(UiEvent.Message(text))
    }

    // ------------------------------------------------------------------ listing & navigation

    private fun arrange(list: List<Node>): List<Node> {
        val s = current
        val filtered = if (s.showHidden) list else list.filterNot { it.isHidden }
        val cmp: Comparator<Node> = when (s.sortBy) {
            SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.DATE -> compareBy<Node> { it.lastModified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.SIZE -> compareBy<Node> { it.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortBy.TYPE -> compareBy<Node> { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        }
        val ordered = if (s.sortAscending) cmp else cmp.reversed()
        val finalCmp = if (s.foldersFirst) compareBy<Node> { !it.isDirectory }.then(ordered) else ordered
        return filtered.sortedWith(finalCmp)
    }

    fun reload(pane: Pane) {
        if (pane.search?.kind == ListKind.RECENT) loadRecent(pane)
        pane.loadJob?.cancel()
        val dir = pane.current
        pane.loading = true
        pane.loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fs.list(dir) } }
            if (pane.current.id != dir.id) return@launch
            result.onSuccess {
                pane.raw = it
                pane.shown = arrange(it)
                pane.error = null
                pane.selection = pane.selection.filterTo(HashSet()) { id -> it.any { n -> n.id == id } }
            }.onFailure {
                pane.raw = emptyList()
                pane.shown = emptyList()
                pane.error = it.message ?: "Ordner kann nicht gelesen werden"
            }
            pane.loading = false
        }
    }

    private fun reloadAll() {
        reload(left)
        reload(right)
    }

    fun openNode(pane: Pane, node: Node) {
        if (pane.selection.isNotEmpty()) {
            toggleSelect(pane, node); return
        }
        if (!node.isDirectory) {
            events.trySend(UiEvent.Open(node)); return
        }
        pane.search = null
        when (node) {
            is LocalNode -> pane.stack = Storage.chain(getApplication(), node.file).map { LocalNode.of(it) }
            is SafNode -> {
                val idx = pane.stack.indexOfFirst { it.id == node.id }
                pane.stack = if (idx >= 0) pane.stack.take(idx + 1) else pane.stack + node
            }
        }
        pane.selection = emptySet()
        reload(pane)
    }

    fun openLocal(pane: Pane, file: File) {
        screen = Screen.FILES
        openNode(pane.also { it.selection = emptySet() }, LocalNode.of(file))
    }

    fun openCloud(pane: Pane, root: CloudRoot) {
        screen = Screen.FILES
        viewModelScope.launch {
            val node = withContext(Dispatchers.IO) { runCatching { fs.safRoot(Uri.parse(root.uri)) }.getOrNull() }
            if (node == null) {
                message("${root.label} ist nicht erreichbar. Zugriff ggf. neu hinzufügen.")
                return@launch
            }
            pane.search = null
            pane.selection = emptySet()
            pane.stack = listOf(node.copy(name = root.label))
            reload(pane)
        }
    }

    fun navigateToCrumb(pane: Pane, index: Int) {
        if (index >= pane.stack.size - 1 && pane.search == null) return
        pane.search = null
        pane.selection = emptySet()
        pane.stack = pane.stack.take(index + 1)
        reload(pane)
    }

    /** Handles the system back gesture. Returns false when there is nothing left to go back to. */
    fun back(): Boolean {
        if (screen != Screen.FILES) {
            screen = Screen.FILES; return true
        }
        val pane = active
        if (pane.selection.isNotEmpty()) {
            pane.selection = emptySet(); return true
        }
        if (pane.search != null) {
            pane.search = null; return true
        }
        if (pane.stack.size > 1) {
            pane.stack = pane.stack.dropLast(1)
            reload(pane)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ selection

    fun toggleSelect(pane: Pane, node: Node) {
        pane.selection = if (node.id in pane.selection) pane.selection - node.id else pane.selection + node.id
    }

    fun selectAll(pane: Pane) {
        pane.selection = pane.visible.mapTo(HashSet()) { it.id }
    }

    fun clearSelection(pane: Pane) {
        pane.selection = emptySet()
    }

    // ------------------------------------------------------------------ clipboard & transfer

    fun copyToClipboard(pane: Pane, cut: Boolean) {
        val nodes = pane.selectedNodes
        if (nodes.isEmpty()) return
        clipboard = Clipboard(nodes, cut)
        pane.selection = emptySet()
        message("${nodes.size} ${if (nodes.size == 1) "Element" else "Elemente"} ${if (cut) "ausgeschnitten" else "kopiert"}")
    }

    fun paste(pane: Pane) {
        val clip = clipboard ?: return
        startTransfer(clip.nodes, pane.current, clip.cut)
        if (clip.cut) clipboard = null
    }

    /** Dual-pane arrow: send the selection of [from] into the folder shown in [to]. */
    fun transfer(from: Pane, to: Pane) {
        val nodes = from.selectedNodes
        if (nodes.isEmpty()) {
            message("Zuerst Dateien auswählen (lange drücken)")
            return
        }
        if (from.current.id == to.current.id) {
            message("Beide Seiten zeigen denselben Ordner")
            return
        }
        from.selection = emptySet()
        startTransfer(nodes, to.current, transferMove)
    }

    private fun startTransfer(nodes: List<Node>, dest: Node, move: Boolean) {
        viewModelScope.launch {
            val existing = withContext(Dispatchers.IO) {
                runCatching { fs.list(dest).mapTo(HashSet()) { it.name } }.getOrDefault(emptySet())
            }
            val conflicts = nodes.filter { n -> n.name in existing && !(n is LocalNode && dest is LocalNode && n.file.parentFile?.absolutePath == dest.file.absolutePath) }
                .map { it.name }
            val sameDirCopy = !move && nodes.any { n -> n is LocalNode && dest is LocalNode && n.file.parentFile?.absolutePath == dest.file.absolutePath }
            if (conflicts.isNotEmpty()) {
                pendingTransfer = PendingTransfer(nodes, dest, move, conflicts)
            } else {
                runTransfer(nodes, dest, move, if (sameDirCopy) ConflictPolicy.KEEP_BOTH else ConflictPolicy.OVERWRITE)
            }
        }
    }

    fun resolveConflict(policy: ConflictPolicy?) {
        val p = pendingTransfer ?: return
        pendingTransfer = null
        if (policy != null) runTransfer(p.nodes, p.dest, p.move, policy)
    }

    private fun runTransfer(nodes: List<Node>, dest: Node, move: Boolean, policy: ConflictPolicy) {
        val verb = if (move) "Verschiebe" else "Kopiere"
        runOp("$verb ${nodes.size} ${if (nodes.size == 1) "Element" else "Elemente"}", measure = nodes) { sink ->
            for (n in nodes) {
                if (move) fs.move(n, dest, policy, sink) else fs.copy(n, dest, policy, sink)
            }
            "${if (move) "Verschoben" else "Kopiert"}: ${nodes.size}"
        }
    }

    // ------------------------------------------------------------------ create / rename / delete

    fun createFolder(pane: Pane, name: String) = simpleOp {
        fs.createDirectory(pane.current, name.trim()); "Ordner erstellt"
    }

    fun createFile(pane: Pane, name: String) = simpleOp {
        if (fs.findChild(pane.current, name.trim()) != null) throw java.io.IOException("\"$name\" existiert bereits")
        fs.createFile(pane.current, name.trim()); "Datei erstellt"
    }

    fun rename(pane: Pane, node: Node, newName: String) {
        pane.selection = emptySet()
        simpleOp { fs.rename(node, newName.trim()); "Umbenannt" }
    }

    fun delete(pane: Pane, nodes: List<Node>, permanent: Boolean) {
        pane.selection = emptySet()
        val toTrash = !permanent && current.useTrash
        runOp(if (toTrash) "In den Papierkorb" else "Lösche", measure = null) { sink ->
            for (n in nodes) {
                sink.checkCancelled()
                sink.onFile(n.name)
                if (toTrash && n is LocalNode) trash.moveToTrash(n.file) else fs.delete(n)
            }
            if (toTrash && nodes.all { it is LocalNode }) "${nodes.size} in den Papierkorb verschoben" else "${nodes.size} gelöscht"
        }
    }

    // ------------------------------------------------------------------ archives

    fun compress(pane: Pane, nodes: List<Node>, name: String, password: String?) {
        pane.selection = emptySet()
        val zipName = if (name.lowercase().endsWith(".zip")) name else "$name.zip"
        runOp("Komprimiere $zipName", measure = nodes) { sink ->
            Archives.compressZip(fs, nodes, pane.current, zipName, password, sink)
            "Archiv erstellt: $zipName"
        }
    }

    fun extract(archive: Node, dest: Node, intoFolder: Boolean, password: String? = null) {
        runOp("Entpacke ${archive.name}", measure = null) { sink ->
            val target = if (intoFolder) {
                val folder = fs.uniqueName(dest, Archives.baseName(archive.name))
                fs.createDirectory(dest, folder)
            } else dest
            try {
                Archives.extract(fs, archive, target, password, getApplication<Application>().cacheDir, sink)
            } catch (e: Exception) {
                if (intoFolder) runCatching { fs.delete(target) }
                if (e is PasswordRequired || (password != null && e.message == "Falsches Passwort")) {
                    pendingPassword = PendingPassword(archive, dest, intoFolder, wrong = password != null)
                    return@runOp null
                }
                throw e
            }
            "Entpackt: ${archive.name}"
        }
    }

    // ------------------------------------------------------------------ search

    fun search(pane: Pane, query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            pane.search = null; return
        }
        pane.searchJob?.cancel()
        val root = pane.current
        val showHidden = current.showHidden
        pane.search = SearchState(q, emptyList(), running = true)
        pane.searchJob = viewModelScope.launch(Dispatchers.IO) {
            val results = ArrayList<Node>()
            fun publish(running: Boolean) {
                pane.search = SearchState(q, arrange(results.toList()), running)
            }
            var lastPublish = 0L
            fun walk(dir: Node, depth: Int) {
                if (results.size >= 1000 || depth > 32) return
                val children = runCatching { fs.list(dir) }.getOrDefault(emptyList())
                for (c in children) {
                    if (!pane.searchActive(q)) return
                    if (!showHidden && c.isHidden) continue
                    if (c.name.contains(q, ignoreCase = true)) results += c
                    if (c.isDirectory) walk(c, depth + 1)
                }
                val now = System.currentTimeMillis()
                if (now - lastPublish > 300) {
                    lastPublish = now; publish(true)
                }
            }
            walk(root, 0)
            if (pane.searchActive(q)) publish(false)
        }
    }

    /** Shows the most recently modified files (newest first) in [pane]. */
    fun showRecent(pane: Pane) {
        screen = Screen.FILES
        pane.selection = emptySet()
        pane.search = SearchState(RECENT_QUERY, pane.search?.takeIf { it.kind == ListKind.RECENT }?.results ?: emptyList(),
            running = true, kind = ListKind.RECENT)
        loadRecent(pane)
    }

    private fun loadRecent(pane: Pane) {
        pane.searchJob?.cancel()
        val showHidden = current.showHidden
        pane.searchJob = viewModelScope.launch(Dispatchers.IO) {
            val files = runCatching { Storage.recentFiles(getApplication(), showHidden = showHidden) }.getOrDefault(emptyList())
            if (pane.search?.kind == ListKind.RECENT) {
                pane.search = SearchState(RECENT_QUERY, files, running = false, kind = ListKind.RECENT)
            }
        }
    }

    // ------------------------------------------------------------------ misc

    fun showProperties(node: Node) {
        val path = when (node) {
            is LocalNode -> node.file.absolutePath
            is SafNode -> Uri.decode(node.documentId)
        }
        properties = Properties(node, path, if (node.isDirectory) null else node.size, if (node.isDirectory) null else 1)
        if (node.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                val (bytes, count) = runCatching { fs.measure(node) }.getOrDefault(0L to 0)
                if (properties?.node?.id == node.id) properties = properties?.copy(size = bytes, files = count)
            }
        }
    }

    fun isQuickAccess(node: Node) = node is LocalNode && node.file.absolutePath in current.quickAccess

    fun toggleQuickAccess(node: Node) {
        if (node !is LocalNode || !node.isDirectory) {
            message("Nur lokale Ordner können in den Schnellzugriff"); return
        }
        val path = node.file.absolutePath
        val had = path in current.quickAccess
        viewModelScope.launch { if (had) repo.removeQuickAccess(path) else repo.addQuickAccess(path) }
        message(if (had) "Aus Schnellzugriff entfernt" else "Zum Schnellzugriff hinzugefügt")
    }

    fun addCloudRoot(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { runCatching { fs.safRoot(uri) }.getOrNull() }
            val provider = uri.authority.orEmpty()
            val label = when {
                provider.contains("proton", ignoreCase = true) -> "Proton Drive" + (root?.name?.let { " · $it" } ?: "")
                else -> root?.name ?: "Cloud-Ordner"
            }
            val entry = CloudRoot(uri.toString(), label)
            repo.addCloudRoot(entry)
            message("$label hinzugefügt")
            openCloud(active, entry)
        }
    }

    fun removeCloudRoot(root: CloudRoot) {
        viewModelScope.launch {
            repo.removeCloudRoot(root.uri)
            runCatching {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(root.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    fun loadTrash() {
        viewModelScope.launch(Dispatchers.IO) { trashEntries = runCatching { trash.entries() }.getOrDefault(emptyList()) }
    }

    fun restore(entry: Trash.Entry) = trashOp { trash.restore(entry); "Wiederhergestellt: ${entry.name}" }
    fun deleteForever(entry: Trash.Entry) = trashOp { trash.deleteForever(entry); "Endgültig gelöscht" }
    fun emptyTrash() = trashOp { trash.empty(); "Papierkorb geleert" }

    private fun trashOp(block: () -> String) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg = runCatching { block() }.getOrElse { it.message ?: "Fehler" }
            trashEntries = runCatching { trash.entries() }.getOrDefault(emptyList())
            message(msg)
            withContext(Dispatchers.Main) { reloadAll() }
        }
    }

    // settings passthrough
    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    // ------------------------------------------------------------------ operation runner

    private fun simpleOp(block: () -> String) {
        viewModelScope.launch {
            val msg = withContext(Dispatchers.IO) { runCatching { block() }.getOrElse { it.message ?: "Fehler" } }
            message(msg)
            reloadAll()
        }
    }

    fun cancelOp() {
        opJob?.cancel()
    }

    private fun runOp(title: String, measure: List<Node>?, block: (ProgressSink) -> String?) {
        if (opJob?.isActive == true) {
            message("Es läuft bereits ein Vorgang")
            return
        }
        op = OpState(title)
        val job = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext.job
            val sink = object : ProgressSink {
                var done = 0L
                var total = 0L
                var file = ""
                var last = 0L
                override fun onFile(name: String) {
                    file = name; push(false)
                }
                override fun onBytes(bytes: Long) {
                    done += bytes; push(false)
                }
                override fun checkCancelled() {
                    if (!self.isActive) throw CancellationException()
                }
                fun push(force: Boolean) {
                    val now = System.currentTimeMillis()
                    if (force || now - last > 120) {
                        last = now
                        op = OpState(title, file, done, total)
                    }
                }
            }
            val result = try {
                if (measure != null) {
                    sink.total = measure.sumOf { runCatching { fs.measure(it, sink).first }.getOrDefault(0L) }
                    sink.push(true)
                }
                block(sink)
            } catch (e: CancellationException) {
                "Abgebrochen"
            } catch (e: Throwable) {
                e.message ?: e.javaClass.simpleName
            }
            op = null
            if (result != null) message(result)
            withContext(NonCancellable + Dispatchers.Main) { reloadAll() }
        }
        opJob = job
    }
}
