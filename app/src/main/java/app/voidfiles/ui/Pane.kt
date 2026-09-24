package app.voidfiles.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.voidfiles.data.Node
import kotlinx.coroutines.Job

/** State of one file browser pane (the dual-pane layout has two of these). */
class Pane(initial: Node) {
    var stack by mutableStateOf(listOf(initial))
    val current: Node get() = stack.last()

    var raw by mutableStateOf<List<Node>>(emptyList())
    var shown by mutableStateOf<List<Node>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var selection by mutableStateOf<Set<String>>(emptySet())
    var search by mutableStateOf<SearchState?>(null)

    var loadJob: Job? = null
    var searchJob: Job? = null

    /** What the pane currently lists: search results or the folder content. */
    val visible: List<Node> get() = search?.results ?: shown

    val selectedNodes: List<Node> get() = visible.filter { it.id in selection }

    fun searchActive(query: String) = search?.query == query
}
