package app.voidfiles.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode(val label: String) {
    BLACK("Schwarz"),
    GRAPHITE("Graphit"),
    STEEL("Stahl"),
    PAPER("Papier"),
    WHITE("Weiß"),
    SYSTEM("System"),
    DYNAMIC("Material You"),
}

enum class Accent(val label: String, val argb: Long) {
    RED("Rot", 0xFFD71921),
    MONO("Mono", 0xFFFFFFFF),
    YELLOW("Gelb", 0xFFFFC400),
    ORANGE("Orange", 0xFFFF6A13),
    GREEN("Grün", 0xFF34C759),
    BLUE("Blau", 0xFF3D7BFF),
    PINK("Pink", 0xFFFF4F8B),
}

enum class SortBy(val label: String) { NAME("Name"), DATE("Datum"), SIZE("Größe"), TYPE("Typ") }

enum class ViewMode { LIST, GRID }

data class CloudRoot(val uri: String, val label: String) {
    fun encode() = "$label\u0000$uri"

    companion object {
        fun decode(s: String): CloudRoot? {
            val i = s.indexOf('\u0000')
            if (i < 0) return null
            return CloudRoot(uri = s.substring(i + 1), label = s.substring(0, i))
        }
    }
}

data class AppSettings(
    val theme: ThemeMode = ThemeMode.BLACK,
    val accent: Accent = Accent.RED,
    val dotHeadings: Boolean = true,
    val viewMode: ViewMode = ViewMode.LIST,
    val showHidden: Boolean = false,
    val foldersFirst: Boolean = true,
    val sortBy: SortBy = SortBy.NAME,
    val sortAscending: Boolean = true,
    val useTrash: Boolean = true,
    val confirmDelete: Boolean = true,
    val dualPaneLandscape: Boolean = true,
    val thumbnails: Boolean = true,
    val dotGrid: Boolean = true,
    val bookmarks: List<String> = emptyList(),
    val cloudRoots: List<CloudRoot> = emptyList(),
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val dotHeadings = booleanPreferencesKey("dot_headings")
        val viewMode = stringPreferencesKey("view_mode")
        val showHidden = booleanPreferencesKey("show_hidden")
        val foldersFirst = booleanPreferencesKey("folders_first")
        val sortBy = stringPreferencesKey("sort_by")
        val sortAscending = booleanPreferencesKey("sort_asc")
        val useTrash = booleanPreferencesKey("use_trash")
        val confirmDelete = booleanPreferencesKey("confirm_delete")
        val dualPane = booleanPreferencesKey("dual_pane")
        val thumbnails = booleanPreferencesKey("thumbnails")
        val dotGrid = booleanPreferencesKey("dot_grid")
        val bookmarks = stringSetPreferencesKey("bookmarks")
        val cloudRoots = stringSetPreferencesKey("cloud_roots")
    }

    private inline fun <reified T : Enum<T>> Preferences.enum(key: Preferences.Key<String>, default: T): T =
        this[key]?.let { v -> enumValues<T>().firstOrNull { it.name == v } } ?: default

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p.enum(K.theme, d.theme),
            accent = p.enum(K.accent, d.accent),
            dotHeadings = p[K.dotHeadings] ?: d.dotHeadings,
            viewMode = p.enum(K.viewMode, d.viewMode),
            showHidden = p[K.showHidden] ?: d.showHidden,
            foldersFirst = p[K.foldersFirst] ?: d.foldersFirst,
            sortBy = p.enum(K.sortBy, d.sortBy),
            sortAscending = p[K.sortAscending] ?: d.sortAscending,
            useTrash = p[K.useTrash] ?: d.useTrash,
            confirmDelete = p[K.confirmDelete] ?: d.confirmDelete,
            dualPaneLandscape = p[K.dualPane] ?: d.dualPaneLandscape,
            thumbnails = p[K.thumbnails] ?: d.thumbnails,
            dotGrid = p[K.dotGrid] ?: d.dotGrid,
            bookmarks = (p[K.bookmarks] ?: emptySet()).sorted(),
            cloudRoots = (p[K.cloudRoots] ?: emptySet()).mapNotNull { CloudRoot.decode(it) }.sortedBy { it.label.lowercase() },
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setAccent(v: Accent) = context.dataStore.edit { it[K.accent] = v.name }
    suspend fun setDotHeadings(v: Boolean) = context.dataStore.edit { it[K.dotHeadings] = v }
    suspend fun setViewMode(v: ViewMode) = context.dataStore.edit { it[K.viewMode] = v.name }
    suspend fun setShowHidden(v: Boolean) = context.dataStore.edit { it[K.showHidden] = v }
    suspend fun setFoldersFirst(v: Boolean) = context.dataStore.edit { it[K.foldersFirst] = v }
    suspend fun setSort(by: SortBy, ascending: Boolean) = context.dataStore.edit {
        it[K.sortBy] = by.name
        it[K.sortAscending] = ascending
    }
    suspend fun setUseTrash(v: Boolean) = context.dataStore.edit { it[K.useTrash] = v }
    suspend fun setConfirmDelete(v: Boolean) = context.dataStore.edit { it[K.confirmDelete] = v }
    suspend fun setDualPane(v: Boolean) = context.dataStore.edit { it[K.dualPane] = v }
    suspend fun setThumbnails(v: Boolean) = context.dataStore.edit { it[K.thumbnails] = v }
    suspend fun setDotGrid(v: Boolean) = context.dataStore.edit { it[K.dotGrid] = v }

    suspend fun toggleBookmark(path: String) = context.dataStore.edit {
        val cur = it[K.bookmarks] ?: emptySet()
        it[K.bookmarks] = if (path in cur) cur - path else cur + path
    }

    suspend fun addCloudRoot(root: CloudRoot) = context.dataStore.edit {
        val cur = (it[K.cloudRoots] ?: emptySet()).filterNot { s -> CloudRoot.decode(s)?.uri == root.uri }.toSet()
        it[K.cloudRoots] = cur + root.encode()
    }

    suspend fun removeCloudRoot(uri: String) = context.dataStore.edit {
        it[K.cloudRoots] = (it[K.cloudRoots] ?: emptySet()).filterNot { s -> CloudRoot.decode(s)?.uri == uri }.toSet()
    }
}
