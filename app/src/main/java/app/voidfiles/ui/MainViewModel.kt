package app.voidfiles.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.voidfiles.data.StorageVolumeInfo
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.IntentCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.voidfiles.BuildConfig
import app.voidfiles.data.AnalysisResult
import app.voidfiles.data.Analyzer
import app.voidfiles.data.AppSettings
import app.voidfiles.data.ArchiveBrowser
import app.voidfiles.data.ArchiveItem
import app.voidfiles.data.ArchiveListing
import app.voidfiles.data.FileInfo
import app.voidfiles.data.ImageJob
import app.voidfiles.data.ImageTools
import app.voidfiles.data.Vault
import app.voidfiles.data.VaultEntry
import app.voidfiles.data.Archives
import app.voidfiles.data.CloudRoot
import app.voidfiles.data.ConflictPolicy
import app.voidfiles.data.DirIndex
import app.voidfiles.data.FileSystem
import app.voidfiles.data.LocalNode
import app.voidfiles.data.Node
import app.voidfiles.data.PasswordRequired
import app.voidfiles.data.ProgressSink
import app.voidfiles.data.Release
import app.voidfiles.data.SafNode
import app.voidfiles.data.SettingsRepository
import app.voidfiles.data.SortBy
import app.voidfiles.data.Storage
import app.voidfiles.data.Trash
import app.voidfiles.data.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

enum class Screen { FILES, SETTINGS, TRASH, ARCHIVE, VIEWER, ANALYSIS, VAULT }

/** An action offered in a message, e.g. "Rückgängig". */
class UndoAction(val label: String = "Rückgängig", val run: () -> Unit)

enum class PreviewKind { IMAGE, TEXT, PDF, MEDIA }

data class ViewerState(val files: List<Node>, val index: Int, val returnTo: Screen)

/** An archive opened for browsing: current folder inside it and the selected entries. */
data class ArchiveView(val listing: ArchiveListing, val dir: String = "", val selection: Set<String> = emptySet())

data class AnalysisUi(
    val running: Boolean = false,
    val count: Int = 0,
    val status: String = "",
    val volume: String = "",
    val result: AnalysisResult? = null,
)

/** Settings for renaming several files at once. */
data class RenameRule(
    val replaceMode: Boolean = false,
    val pattern: String = "{name}_{n}",
    val start: Int = 1,
    val find: String = "",
    val replace: String = "",
)

const val ACTION_OPEN_FOLDER = "app.voidfiles.OPEN_FOLDER"
const val EXTRA_PATH = "path"

data class SharedItem(val uri: Uri?, val name: String, val size: Long, val text: String? = null)

data class OpState(
    val title: String,
    val file: String = "",
    val done: Long = 0,
    val total: Long = 0,
    /** Bytes per second, 0 while unknown. */
    val speed: Long = 0,
    /** Seconds remaining, -1 while unknown. */
    val etaSeconds: Long = -1,
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

data class PendingPassword(
    val archive: Node,
    val dest: Node,
    val intoFolder: Boolean,
    val wrong: Boolean,
    val browse: Boolean = false,
    val browseDir: String = "",
)

data class Properties(
    val node: Node,
    val path: String,
    val size: Long?,
    val files: Int?,
    val details: List<Pair<String, String>> = emptyList(),
    val md5: String? = null,
    val sha256: String? = null,
    val hashing: Boolean = false,
)

sealed interface UiEvent {
    data class Message(val text: String, val undo: UndoAction? = null) : UiEvent
    data class Open(val node: Node) : UiEvent
    data class Install(val apk: File) : UiEvent
    data class Share(val nodes: List<Node>) : UiEvent
}

const val RECENT_QUERY = "Letzte Dateien"

private val HTML_EXTENSIONS = setOf("html", "htm", "xhtml", "shtml", "mht", "mhtml")

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "log", "json", "xml", "csv", "kt", "kts", "java", "py", "js", "ts", "css", "sh",
    "yml", "yaml", "ini", "conf", "cfg", "properties", "gradle", "c", "cpp", "h", "rs", "go", "sql", "toml", "srt", "nfo",
)

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

    /** Incremented after every finished operation – drives the Glyph flash animation. */
    var glyphTick by mutableStateOf(0)
        private set

    /** Undo for the operation that is currently running; picked up when its message is shown. */
    @Volatile
    private var nextUndo: UndoAction? = null

    var archiveView by mutableStateOf<ArchiveView?>(null)
    var viewer by mutableStateOf<ViewerState?>(null)
        private set
    var analysis by mutableStateOf(AnalysisUi())
        private set
    private var analysisJob: Job? = null

    private val vault = Vault(app)
    var vaultUnlocked by mutableStateOf(false)
        private set
    var vaultEntries by mutableStateOf<List<VaultEntry>>(emptyList())
        private set

    // ------------------------------------------------------------------ updates
    var releases by mutableStateOf<List<Release>>(emptyList())
        private set
    var availableUpdate by mutableStateOf<Release?>(null)
        private set
    var updateStatus by mutableStateOf<String?>(null)
        private set
    var checkingUpdate by mutableStateOf(false)
        private set
    var showUpdateDialog by mutableStateOf(false)

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
        val start = startStack()
        left.stack = start
        right.stack = start
        reload(left)
        reload(right)
        viewModelScope.launch {
            if (repo.settings.first().autoUpdateCheck) checkForUpdates(manual = false)
        }
    }

    // ------------------------------------------------------------------ storage volumes

    var volumes by mutableStateOf(Storage.volumes(app))
        private set

    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The volume list needs a moment to settle after the broadcast.
            viewModelScope.launch {
                kotlinx.coroutines.delay(600)
                refreshVolumes()
            }
        }
    }

    init {
        // Keep the storage list up to date when SD cards or USB drives come and go.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(app, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(mediaReceiver) }
        super.onCleared()
    }

    fun refreshVolumes() {
        val before = volumes
        val now = Storage.volumes(getApplication())
        volumes = now
        val gone = before.filter { b -> now.none { it.root.absolutePath == b.root.absolutePath } }
        val added = now.filter { n -> before.none { it.root.absolutePath == n.root.absolutePath } }
        added.forEach { message("${it.label} verbunden") }
        for (v in gone) {
            message("${v.label} getrennt")
            // Panes that were showing the removed drive fall back to the start folder.
            for (pane in listOf(left, right)) {
                val cur = pane.current
                if (cur is LocalNode && cur.file.absolutePath.startsWith(v.root.absolutePath)) {
                    pane.search = null
                    pane.selection = emptySet()
                    pane.stack = startStack()
                    reload(pane)
                }
            }
        }
    }

    /**
     * Android lets only system apps unmount a drive. We leave the drive, flush all pending writes to it and
     * then hand over to [then] (which opens Samsung's file manager, where "Trennen" is one tap away).
     */
    fun prepareEject(volume: StorageVolumeInfo, then: () -> Unit) {
        if (opJob?.isActive == true) {
            message("Es läuft noch ein Vorgang – bitte warten, bis er fertig ist")
            return
        }
        for (pane in listOf(left, right)) {
            val cur = pane.current
            if (cur is LocalNode && cur.file.absolutePath.startsWith(volume.root.absolutePath)) {
                pane.search = null
                pane.selection = emptySet()
                pane.stack = startStack()
                reload(pane)
            }
        }
        if (clipboard?.nodes?.any { it is LocalNode && it.file.absolutePath.startsWith(volume.root.absolutePath) } == true) {
            clipboard = null
        }
        viewModelScope.launch {
            // Force the kernel to write cached data to the drive (like "Hardware sicher entfernen").
            withContext(Dispatchers.IO) {
                runCatching {
                    val p = Runtime.getRuntime().exec(arrayOf("sync"))
                    p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                    p.destroy()
                }
            }
            message("Alle Daten geschrieben – jetzt ⋮ → \"Trennen\" tippen")
            then()
        }
    }

    /** The app opens in the Download folder (falls back to internal storage). */
    private fun startStack(): List<Node> {
        val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val target = if (download.isDirectory) download else Storage.primaryRoot
        return Storage.chain(getApplication(), target).map { LocalNode.of(it) }
    }

    fun checkForUpdates(manual: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        if (manual) updateStatus = "Suche nach Updates …"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Updater.fetchReleases() } }
            checkingUpdate = false
            result.onSuccess { list ->
                releases = list
                val newest = list.firstOrNull { it.apkUrl != null }
                if (newest != null && Updater.isNewer(newest.version, BuildConfig.VERSION_NAME)) {
                    availableUpdate = newest
                    updateStatus = "Version ${newest.version} ist verfügbar"
                    if (!manual) showUpdateDialog = true
                } else {
                    availableUpdate = null
                    updateStatus = "VOID Files ist aktuell"
                }
            }.onFailure {
                updateStatus = "Update-Prüfung fehlgeschlagen: ${it.message ?: "keine Verbindung"}"
                if (manual) message(updateStatus!!)
            }
        }
    }

    fun downloadUpdate(release: Release) {
        showUpdateDialog = false
        val url = release.apkUrl ?: return message("Für diese Version gibt es keine APK")
        if (opJob?.isActive == true) {
            message("Es läuft bereits ein Vorgang"); return
        }
        val title = "Update ${release.version} wird geladen"
        op = OpState(title, "VOID-Files-${release.version}.apk", 0, release.apkSize)
        opJob = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext.job
            val file = File(getApplication<Application>().cacheDir, "updates/VOID-Files-${release.version}.apk")
            var last = 0L
            try {
                getApplication<Application>().cacheDir.resolve("updates").listFiles()?.forEach { it.delete() }
                Updater.download(url, file, { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - last > 120) {
                        last = now
                        op = OpState(title, file.name, done, if (total > 0) total else release.apkSize)
                    }
                }, { !self.isActive })
                op = null
                events.trySend(UiEvent.Install(file))
            } catch (e: Throwable) {
                op = null
                message(if (!self.isActive) "Update abgebrochen" else "Update fehlgeschlagen: ${e.message}")
            }
        }
    }

    fun message(text: String, undo: UndoAction? = null) {
        events.trySend(UiEvent.Message(text, undo))
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
            if (current.internalViewer && !Archives.isArchive(node.name) && previewKindOf(node) != null) {
                openViewer(node, pane.visible)
            } else {
                events.trySend(UiEvent.Open(node))
            }
            return
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
        when (screen) {
            Screen.FILES -> Unit
            Screen.VIEWER -> {
                closeViewer(); return true
            }
            Screen.ARCHIVE -> {
                val v = archiveView
                when {
                    v == null -> screen = Screen.FILES
                    v.selection.isNotEmpty() -> archiveView = v.copy(selection = emptySet())
                    v.dir.isNotEmpty() -> archiveView = v.copy(dir = if ('/' in v.dir) v.dir.substringBeforeLast('/') else "")
                    else -> closeArchive()
                }
                return true
            }
            Screen.VAULT -> {
                lockVault(); return true
            }
            else -> {
                screen = Screen.FILES; return true
            }
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
                runTransfer(nodes, dest, move, if (sameDirCopy) ConflictPolicy.KEEP_BOTH else ConflictPolicy.OVERWRITE, allowUndo = true)
            }
        }
    }

    fun resolveConflict(policy: ConflictPolicy?) {
        val p = pendingTransfer ?: return
        pendingTransfer = null
        if (policy != null) runTransfer(p.nodes, p.dest, p.move, policy, allowUndo = policy != ConflictPolicy.OVERWRITE)
    }

    private fun parentOf(node: Node): Node? = (node as? LocalNode)?.file?.parentFile?.let { LocalNode.of(it) }

    private fun runTransfer(nodes: List<Node>, dest: Node, move: Boolean, policy: ConflictPolicy, allowUndo: Boolean) {
        val verb = if (move) "Verschiebe" else "Kopiere"
        runOp("$verb ${nodes.size} ${if (nodes.size == 1) "Element" else "Elemente"}", measure = nodes) { sink ->
            val done = ArrayList<Pair<Node, Node?>>()
            val index = DirIndex(fs)
            try {
                for (n in nodes) {
                    val origin = parentOf(n)
                    val result = if (move) fs.move(n, dest, policy, sink, index) else fs.copy(n, dest, policy, sink, index)
                    if (result != null) done += result to origin
                }
            } finally {
                if (allowUndo && done.isNotEmpty()) {
                    nextUndo = if (move) {
                        if (done.all { it.second != null }) UndoAction {
                            runOp("Verschiebe zurück", measure = null) { s2 ->
                                done.forEach { (node, origin) -> fs.move(node, origin!!, ConflictPolicy.KEEP_BOTH, s2) }
                                "Verschieben rückgängig gemacht"
                            }
                        } else null
                    } else UndoAction {
                        runOp("Entferne Kopien", measure = null) { s2 ->
                            done.forEach { (node, _) -> s2.checkCancelled(); fs.delete(node) }
                            "Kopieren rückgängig gemacht"
                        }
                    }
                }
            }
            "${if (move) "Verschoben" else "Kopiert"}: ${done.size}"
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
        simpleOp {
            val renamed = fs.rename(node, newName.trim())
            nextUndo = UndoAction { simpleOp { fs.rename(renamed, node.name); "Umbenennung rückgängig gemacht" } }
            "Umbenannt"
        }
    }

    fun delete(pane: Pane, nodes: List<Node>, permanent: Boolean) {
        pane.selection = emptySet()
        val toTrash = !permanent && current.useTrash
        runOp(if (toTrash) "In den Papierkorb" else "Lösche", measure = null) { sink ->
            val trashed = ArrayList<Trash.Entry>()
            try {
                for (n in nodes) {
                    sink.checkCancelled()
                    sink.onFile(n.name)
                    if (toTrash && n is LocalNode) trash.moveToTrash(n.file)?.let { trashed += it } else fs.delete(n)
                }
            } finally {
                if (trashed.isNotEmpty()) nextUndo = UndoAction {
                    runOp("Stelle wieder her", measure = null) { _ ->
                        trashed.forEach { trash.restore(it) }
                        "Wiederhergestellt: ${trashed.size}"
                    }
                }
            }
            if (toTrash && nodes.all { it is LocalNode }) "${nodes.size} in den Papierkorb verschoben" else "${nodes.size} gelöscht"
        }
    }

    /** Swipe-to-delete: goes straight to the trash (with undo) when possible. */
    fun quickDelete(pane: Pane, node: Node): Boolean {
        if (!current.useTrash || node !is LocalNode) return false
        delete(pane, listOf(node), permanent = false)
        return true
    }

    // ------------------------------------------------------------------ image tools

    private val imageWork: File get() = File(getApplication<Application>().cacheDir, "image_work")

    fun convertImages(pane: Pane, nodes: List<Node>, job: ImageJob) {
        val images = nodes.filter { ImageTools.isImage(it) }
        pane.selection = emptySet()
        runOp("Bearbeite ${images.size} ${if (images.size == 1) "Bild" else "Bilder"}", measure = null) { sink ->
            var count = 0
            for (img in images) {
                sink.checkCancelled()
                sink.onFile(img.name)
                ImageTools.convert(getApplication(), fs, img, parentOf(img) ?: pane.current, job, imageWork)
                count++
            }
            "$count ${if (count == 1) "Bild" else "Bilder"} gespeichert (${job.format.label})"
        }
    }

    fun stripLocation(pane: Pane, nodes: List<Node>) {
        val images = nodes.filter { ImageTools.isImage(it) }
        pane.selection = emptySet()
        runOp("Entferne Standortdaten", measure = null) { sink ->
            var done = 0
            var skipped = 0
            for (img in images) {
                sink.checkCancelled()
                sink.onFile(img.name)
                if (ImageTools.stripLocationInPlace(fs, img, imageWork)) done++ else skipped++
            }
            buildString {
                append("Standort entfernt: $done")
                if (skipped > 0) append(" · $skipped übersprungen (z. B. HEIC – \"Ohne Standort teilen\" oder Umwandeln nutzen)")
            }
        }
    }

    /** Shares copies of the pictures without location data; the originals stay untouched. */
    fun shareWithoutLocation(pane: Pane, nodes: List<Node>) {
        val images = nodes.filter { ImageTools.isImage(it) }
        if (images.isEmpty()) {
            message("Keine Bilder ausgewählt"); return
        }
        pane.selection = emptySet()
        val outDir = File(getApplication<Application>().cacheDir, "share_clean")
        runOp("Bereite Bilder vor", measure = null) { sink ->
            outDir.deleteRecursively()
            val files = images.map { img ->
                sink.checkCancelled()
                sink.onFile(img.name)
                LocalNode.of(ImageTools.withoutLocation(getApplication(), fs, img, outDir))
            }
            events.trySend(UiEvent.Share(files))
            null
        }
    }

    // ------------------------------------------------------------------ batch rename

    fun batchNames(nodes: List<Node>, rule: RenameRule, dates: List<Long> = nodes.map { it.lastModified }): List<String> {
        val digits = maxOf(2, (rule.start + nodes.size - 1).toString().length)
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.GERMAN)
        val used = HashSet<String>()
        return nodes.mapIndexed { i, n ->
            val dot = n.name.lastIndexOf('.')
            val hasExt = !n.isDirectory && dot > 0
            val base = if (hasExt) n.name.substring(0, dot) else n.name
            val ext = if (hasExt) n.name.substring(dot) else ""
            val newBase = if (rule.replaceMode) {
                if (rule.find.isEmpty()) base else base.replace(rule.find, rule.replace)
            } else {
                rule.pattern
                    .replace("{name}", base)
                    .replace("{n}", (rule.start + i).toString().padStart(digits, '0'))
                    .replace("{date}", fmt.format(java.util.Date(dates[i])))
            }.replace('/', '_').trim().ifEmpty { base }
            var candidate = newBase + ext
            var k = 1
            while (!used.add(candidate.lowercase())) candidate = "$newBase ($k)$ext".also { k++ }
            candidate
        }
    }

    fun batchRename(pane: Pane, nodes: List<Node>, rule: RenameRule) {
        pane.selection = emptySet()
        runOp("Benenne ${nodes.size} Elemente um", measure = null) { sink ->
            val dates = if (!rule.replaceMode && "{date}" in rule.pattern) nodes.map { FileInfo.captureDate(fs, it) } else nodes.map { it.lastModified }
            val names = batchNames(nodes, rule, dates)
            val stamp = System.currentTimeMillis()
            // Two phases so that swapping names inside the batch cannot collide.
            val temps = nodes.mapIndexed { i, n ->
                sink.checkCancelled()
                if (names[i] == n.name) n else fs.rename(n, ".voidtmp_${stamp}_$i")
            }
            val done = ArrayList<Pair<Node, String>>()
            temps.forEachIndexed { i, t ->
                sink.onFile(names[i])
                val renamed = if (t.name == names[i]) t else {
                    val parent = parentOf(t) ?: pane.current
                    val name = if (fs.findChild(parent, names[i]) != null) fs.uniqueName(parent, names[i]) else names[i]
                    fs.rename(t, name)
                }
                if (renamed.name != nodes[i].name) done += renamed to nodes[i].name
            }
            if (done.isNotEmpty()) nextUndo = UndoAction {
                runOp("Umbenennen rückgängig", measure = null) { _ ->
                    val back = done.mapIndexed { i, (n, _) -> fs.rename(n, ".voidtmp_undo_${stamp}_$i") }
                    back.forEachIndexed { i, t -> fs.rename(t, done[i].second) }
                    "Umbenennen rückgängig gemacht"
                }
            }
            "${done.size} umbenannt"
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
        pane.search = pane.search?.copy(running = true)
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
        if (!node.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                val details = FileInfo.details(getApplication(), fs, node)
                if (properties?.node?.id == node.id) properties = properties?.copy(details = details)
            }
        }
        if (node.isDirectory) {
            viewModelScope.launch(Dispatchers.IO) {
                val (bytes, count) = runCatching { fs.measure(node) }.getOrDefault(0L to 0)
                if (properties?.node?.id == node.id) properties = properties?.copy(size = bytes, files = count)
            }
        }
    }

    fun computeChecksums(node: Node) {
        properties = properties?.copy(hashing = true)
        viewModelScope.launch(Dispatchers.IO) {
            val md5 = runCatching { FileInfo.checksum(fs, node, "MD5") }.getOrElse { "Fehler: ${it.message}" }
            val sha = runCatching { FileInfo.checksum(fs, node, "SHA-256") }.getOrElse { "Fehler: ${it.message}" }
            if (properties?.node?.id == node.id) properties = properties?.copy(md5 = md5, sha256 = sha, hashing = false)
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
            var undo: UndoAction? = null
            val msg = withContext(Dispatchers.IO) {
                nextUndo = null
                runCatching { block().also { undo = nextUndo } }.getOrElse { it.message ?: "Fehler" }.also { nextUndo = null }
            }
            message(msg, undo)
            reloadAll()
        }
    }

    fun cancelOp() {
        opJob?.cancel()
    }

    // ------------------------------------------------------------------ intents (shortcuts)

    fun handleIntent(intent: Intent) {
        if (intent.action == ACTION_OPEN_FOLDER) {
            val path = intent.getStringExtra(EXTRA_PATH) ?: return
            val f = File(path)
            if (f.isDirectory) openLocal(active, f) else message("Ordner nicht gefunden: $path")
        } else {
            receiveShare(intent)
        }
    }

    // ------------------------------------------------------------------ viewer

    fun previewKindOf(node: Node): PreviewKind? {
        if (node.isDirectory) return null
        val mime = node.mimeType
        val ext = node.extension
        return when {
            mime.startsWith("image/") && ext != "svg" -> PreviewKind.IMAGE
            ext == "pdf" -> PreviewKind.PDF
            mime.startsWith("video/") || mime.startsWith("audio/") -> PreviewKind.MEDIA
            // Web pages belong in the browser, not in the code editor.
            ext in HTML_EXTENSIONS || mime == "text/html" -> null
            mime.startsWith("text/") || ext in TEXT_EXTENSIONS -> PreviewKind.TEXT
            else -> null
        }
    }

    fun openViewer(node: Node, siblings: List<Node>) {
        val kind = previewKindOf(node)
        if (kind == null) {
            events.trySend(UiEvent.Open(node)); return
        }
        var files = if (kind == PreviewKind.IMAGE) siblings.filter { previewKindOf(it) == PreviewKind.IMAGE } else listOf(node)
        var index = files.indexOfFirst { it.id == node.id }
        if (index < 0) {
            files = listOf(node); index = 0
        }
        val returnTo = if (screen == Screen.VIEWER) (viewer?.returnTo ?: Screen.FILES) else screen
        viewer = ViewerState(files, index, returnTo)
        screen = Screen.VIEWER
    }

    fun closeViewer() {
        screen = viewer?.returnTo ?: Screen.FILES
        viewer = null
    }

    fun openExternally(node: Node) {
        events.trySend(UiEvent.Open(node))
    }

    fun saveText(node: Node, text: String, onSaved: () -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { fs.openOutput(node).use { it.write(text.toByteArray()) } }
            }
            result.onSuccess {
                message("Gespeichert")
                onSaved()
            }.onFailure { message("Speichern fehlgeschlagen: ${it.message}") }
        }
    }

    // ------------------------------------------------------------------ archive browsing

    fun openArchive(node: Node, password: String? = null, dir: String = "") {
        runOp("Öffne ${node.name}", measure = null) { _ ->
            try {
                val listing = ArchiveBrowser.open(fs, node, getApplication<Application>().cacheDir, password)
                archiveView?.listing?.close()
                archiveView = ArchiveView(listing, dir = dir)
                screen = Screen.ARCHIVE
                null
            } catch (e: Exception) {
                if (e is PasswordRequired || e.message == "Falsches Passwort") {
                    pendingPassword = PendingPassword(node, node, false, wrong = password != null, browse = true, browseDir = dir)
                    null
                } else throw e
            }
        }
    }

    fun closeArchive() {
        archiveView?.listing?.close()
        archiveView = null
        screen = Screen.FILES
    }

    fun archiveEnter(item: ArchiveItem) {
        val v = archiveView ?: return
        if (v.selection.isNotEmpty()) {
            archiveToggle(item); return
        }
        if (item.isDirectory) archiveView = v.copy(dir = item.path) else previewArchiveItem(item)
    }

    fun archiveToggle(item: ArchiveItem) {
        val v = archiveView ?: return
        archiveView = v.copy(selection = if (item.path in v.selection) v.selection - item.path else v.selection + item.path)
    }

    fun archiveSelectAll() {
        val v = archiveView ?: return
        archiveView = v.copy(selection = v.listing.list(v.dir).mapTo(HashSet()) { it.path })
    }

    fun archiveGoTo(dir: String) {
        val v = archiveView ?: return
        archiveView = v.copy(dir = dir, selection = emptySet())
    }

    /** Extracts the selection (or everything in the current folder) into the active pane's folder. */
    fun extractFromArchive(all: Boolean) {
        val v = archiveView ?: return
        val items = if (all || v.selection.isEmpty()) v.listing.list(v.dir) else v.listing.list(v.dir).filter { it.path in v.selection }
        val dest = active.current
        archiveView = v.copy(selection = emptySet())
        runOp("Entpacke ${items.size} ${if (items.size == 1) "Element" else "Elemente"}", measure = null) { sink ->
            try {
                ArchiveBrowser.extract(fs, v.listing, items, v.dir, dest, sink)
            } catch (e: PasswordRequired) {
                pendingPassword = PendingPassword(v.listing.archive, dest, false, wrong = false, browse = true, browseDir = v.dir)
                return@runOp "Passwort erforderlich"
            }
            "Entpackt nach ${dest.name}"
        }
    }

    private fun previewArchiveItem(item: ArchiveItem) {
        val v = archiveView ?: return
        val dir = File(getApplication<Application>().cacheDir, "archive_preview")
        runOp("Öffne ${item.name}", measure = null) { sink ->
            dir.deleteRecursively()
            dir.mkdirs()
            val parent = item.parent
            ArchiveBrowser.extract(fs, v.listing, listOf(item), parent, LocalNode.of(dir), sink)
            val file = LocalNode.of(File(dir, item.name))
            viewModelScope.launch {
                if (current.internalViewer && previewKindOf(file) != null) openViewer(file, listOf(file)) else openExternally(file)
            }
            null
        }
    }

    // ------------------------------------------------------------------ storage analysis

    fun startAnalysis(volume: app.voidfiles.data.StorageVolumeInfo) {
        analysisJob?.cancel()
        analysis = AnalysisUi(running = true, status = "Starte …", volume = volume.label)
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            val self = coroutineContext.job
            val result = runCatching {
                Analyzer.analyze(volume.root, volume.total, volume.free, { count, where ->
                    analysis = analysis.copy(count = count, status = where)
                }, { !self.isActive })
            }
            analysis = if (result.isSuccess && self.isActive) {
                AnalysisUi(running = false, count = result.getOrThrow().fileCount, volume = volume.label, result = result.getOrThrow())
            } else {
                analysis.copy(running = false, status = result.exceptionOrNull()?.message ?: "Abgebrochen")
            }
        }
    }

    fun cancelAnalysis() {
        analysisJob?.cancel()
        analysis = analysis.copy(running = false)
    }

    /** Deletes a file listed in the analysis and removes it from the results. */
    fun analysisDelete(node: LocalNode) {
        val toTrash = current.useTrash
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { if (toTrash) trash.moveToTrash(node.file) else fs.delete(node) }.isSuccess
            }
            if (!ok) {
                message("\"${node.name}\" konnte nicht gelöscht werden"); return@launch
            }
            message(if (toTrash) "In den Papierkorb verschoben" else "Gelöscht")
            val r = analysis.result ?: return@launch
            analysis = analysis.copy(
                result = r.copy(
                    largest = r.largest.filterNot { it.id == node.id },
                    duplicates = r.duplicates.map { g -> g.copy(files = g.files.filterNot { it.id == node.id }) }.filter { it.files.size > 1 },
                ),
            )
        }
    }

    // ------------------------------------------------------------------ vault

    private val vaultTemp: File get() = File(getApplication<Application>().cacheDir, "vault_open")

    /** Called by the UI after a successful biometric / device credential check. */
    fun unlockVault() {
        vaultTemp.deleteRecursively()
        vaultUnlocked = true
        screen = Screen.VAULT
        loadVault()
    }

    fun lockVault() {
        if (!vaultUnlocked) return
        vaultUnlocked = false
        vaultEntries = emptyList()
        if (screen == Screen.VAULT) screen = Screen.FILES
        if (screen == Screen.VIEWER && viewer?.returnTo == Screen.VAULT) {
            viewer = null; screen = Screen.FILES
        }
    }

    private fun loadVault() {
        viewModelScope.launch(Dispatchers.IO) {
            vaultEntries = runCatching { vault.entries() }.getOrElse {
                message("Tresor konnte nicht gelesen werden: ${it.message}"); emptyList()
            }
        }
    }

    fun moveToVault(pane: Pane, nodes: List<Node>) {
        val files = nodes.filterNot { it.isDirectory }
        if (files.isEmpty()) {
            message("Ordner bitte zuerst als ZIP komprimieren"); return
        }
        pane.selection = emptySet()
        runOp("Verschlüssele ${files.size} ${if (files.size == 1) "Datei" else "Dateien"}", measure = files) { sink ->
            for (n in files) {
                val origin = (n as? LocalNode)?.file?.parent ?: pane.current.name
                vault.add(fs, n, origin, sink)
                fs.delete(n)
            }
            val skipped = nodes.size - files.size
            "${files.size} im Tresor" + if (skipped > 0) " · $skipped Ordner übersprungen" else ""
        }
    }

    fun vaultOpen(entry: VaultEntry) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { runCatching { vault.decryptToTemp(entry, vaultTemp) } }
            file.onSuccess {
                val node = LocalNode.of(it)
                if (current.internalViewer && previewKindOf(node) != null) openViewer(node, listOf(node)) else openExternally(node)
            }.onFailure { message("Entschlüsseln fehlgeschlagen: ${it.message}") }
        }
    }

    fun vaultRestore(entry: VaultEntry) {
        val dest = active.current
        runOp("Entschlüssele ${entry.name}", measure = null) { sink ->
            vault.export(fs, entry, dest, sink)
            vault.remove(entry)
            vaultEntries = vault.entries()
            "Wiederhergestellt nach ${dest.name}"
        }
    }

    fun vaultDelete(entry: VaultEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { vault.remove(entry) }
            vaultEntries = runCatching { vault.entries() }.getOrDefault(emptyList())
            message("Endgültig gelöscht")
        }
    }

    // ------------------------------------------------------------------ receiving shared files

    /** Files another app shared with us, waiting for the user to pick a folder. */
    var incomingShare by mutableStateOf<List<SharedItem>?>(null)

    fun receiveShare(intent: Intent) {
        val app = getApplication<Application>()
        val uris = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris += it }
            else -> return
        }
        if (uris.isEmpty()) intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris += it } }
        val items = uris.distinct().mapIndexed { i, uri -> describeShared(app, uri, i) }.toMutableList()
        if (items.isEmpty()) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (text.isNullOrBlank()) {
                message("Nichts zum Speichern erhalten"); return
            }
            val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.takeIf { it.isNotBlank() }
            val base = (subject ?: "Geteilter Text").replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
            items += SharedItem(null, "$base.txt", text.toByteArray().size.toLong(), text)
        }
        incomingShare = items
        screen = Screen.FILES
    }

    private fun describeShared(app: Application, uri: Uri, index: Int): SharedItem {
        var name: String? = null
        var size = 0L
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (name.isNullOrBlank()) {
            if (uri.scheme == "file") name = uri.lastPathSegment
        }
        if (name.isNullOrBlank()) {
            val ext = app.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            name = "Geteilt_${System.currentTimeMillis()}_${index + 1}" + (ext?.let { ".$it" } ?: "")
        }
        return SharedItem(uri, name!!.substringAfterLast('/'), size)
    }

    fun cancelShare() {
        incomingShare = null
    }

    /** Saves the shared files into the folder shown in [pane], then calls [onDone]. */
    fun saveShare(pane: Pane, onDone: () -> Unit) {
        val items = incomingShare ?: return
        val dest = pane.current
        if (pane.search != null) {
            message("Bitte zuerst einen Ordner öffnen"); return
        }
        val app = getApplication<Application>()
        runOp("Speichere ${items.size} ${if (items.size == 1) "Datei" else "Dateien"}", measure = null, onSuccess = {
            incomingShare = null
            onDone()
        }) { sink ->
            sink.checkCancelled()
            for (item in items) {
                sink.onFile(item.name)
                val target = fs.createFile(dest, fs.uniqueName(dest, item.name))
                try {
                    val input = if (item.text != null) item.text.byteInputStream()
                    else app.contentResolver.openInputStream(item.uri!!) ?: throw java.io.IOException("Kann ${item.name} nicht lesen")
                    fs.copyStream(input, fs.openOutput(target), sink)
                } catch (e: Throwable) {
                    runCatching { fs.delete(target) }
                    throw e
                }
            }
            "${items.size} ${if (items.size == 1) "Datei" else "Dateien"} gespeichert in ${dest.name}"
        }
    }

    private fun runOp(
        title: String,
        measure: List<Node>?,
        onSuccess: (() -> Unit)? = null,
        block: (ProgressSink) -> String?,
    ) {
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
                // Transfer speed: smoothed over samples taken at least every half second.
                var sampleTime = 0L
                var sampleDone = 0L
                var speed = 0.0
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
                    if (sampleTime == 0L && done > 0) {
                        sampleTime = now; sampleDone = done
                    } else if (sampleTime != 0L && now - sampleTime >= 500) {
                        val inst = (done - sampleDone) * 1000.0 / (now - sampleTime)
                        speed = if (speed == 0.0) inst else speed * 0.7 + inst * 0.3
                        sampleTime = now; sampleDone = done
                    }
                    if (force || now - last > 120) {
                        last = now
                        val eta = if (speed > 0 && total > done) ((total - done) / speed).toLong() else -1L
                        op = OpState(title, file, done, total, speed.toLong(), eta)
                    }
                }
            }
            var ok = false
            nextUndo = null
            val result = try {
                if (measure != null) {
                    sink.total = measure.sumOf { runCatching { fs.measure(it, sink).first }.getOrDefault(0L) }
                    sink.push(true)
                }
                block(sink).also { ok = true }
            } catch (e: CancellationException) {
                "Abgebrochen"
            } catch (e: Throwable) {
                e.message ?: e.javaClass.simpleName
            }
            op = null
            val undo = nextUndo.also { nextUndo = null }
            if (result != null) message(result, if (ok) undo else null)
            withContext(NonCancellable + Dispatchers.Main) {
                reloadAll()
                if (ok) {
                    if (result != null) glyphTick++
                    onSuccess?.invoke()
                }
            }
        }
        opJob = job
    }
}
