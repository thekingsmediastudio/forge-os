package com.forge.os.presentation.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.sandbox.SandboxManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class WorkspaceSort(val label: String) {
    NAME("Name"),
    DATE("Date"),
    SIZE("Size"),
    TYPE("Type"),
}

data class WorkspaceUiState(
    val cwd: String = "",                       // sandbox-relative; "" means root
    val entries: List<WorkspaceFileItem> = emptyList(),
    val info: WorkspaceInfo = WorkspaceInfo(),
    val sort: WorkspaceSort = WorkspaceSort.NAME,
    val sortAscending: Boolean = true,
    val query: String = "",
    val recursiveSearch: Boolean = false,
    val selection: Set<String> = emptySet(),    // selected paths
    val message: String? = null,                // transient toast/snackbar text
    val error: String? = null,
)

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val sandboxManager: SandboxManager
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    // Compatibility shims so any existing callers keep working.
    val files: StateFlow<List<WorkspaceFileItem>> = MutableStateFlow<List<WorkspaceFileItem>>(emptyList()).also { mirror ->
        viewModelScope.launch { state.collect { mirror.value = it.entries } }
    }.asStateFlow()
    val workspaceInfo: StateFlow<WorkspaceInfo> = MutableStateFlow(WorkspaceInfo()).also { mirror ->
        viewModelScope.launch { state.collect { mirror.value = it.info } }
    }.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = reload(_state.value.cwd)

    fun openDirectory(path: String) {
        // Defensive: clear selection + search whenever the cwd changes.
        _state.value = _state.value.copy(selection = emptySet(), query = "", recursiveSearch = false)
        reload(path)
    }

    fun navigateUp() {
        val cwd = _state.value.cwd
        if (cwd.isBlank()) return
        val parent = cwd.substringBeforeLast('/', missingDelimiterValue = "")
        openDirectory(parent)
    }

    fun setSort(sort: WorkspaceSort) {
        val cur = _state.value
        val asc = if (cur.sort == sort) !cur.sortAscending else true
        _state.value = cur.copy(sort = sort, sortAscending = asc)
        reload(cur.cwd)
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        reload(_state.value.cwd)
    }

    fun setRecursiveSearch(on: Boolean) {
        _state.value = _state.value.copy(recursiveSearch = on)
        reload(_state.value.cwd)
    }

    // ---- Selection / multi-select ----

    fun toggleSelection(path: String) {
        val sel = _state.value.selection.toMutableSet()
        if (!sel.add(path)) sel.remove(path)
        _state.value = _state.value.copy(selection = sel)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selection = emptySet())
    }

    // ---- Mutations ----

    fun newFolder(name: String) = mutate("Folder created") {
        sandboxManager.mkdir(joinCwd(name)).getOrThrow()
    }

    fun newFile(name: String) = mutate("File created") {
        sandboxManager.createEmptyFile(joinCwd(name)).getOrThrow()
    }

    fun rename(item: WorkspaceFileItem, newName: String) = mutate("Renamed") {
        val parent = item.path.substringBeforeLast('/', missingDelimiterValue = "")
        val dst = if (parent.isEmpty()) newName else "$parent/$newName"
        sandboxManager.rename(item.path, dst).getOrThrow()
    }

    fun deleteSelectionToTrash() = mutate("Moved to .trash") {
        val sel = _state.value.selection.toList()
        sel.forEach { sandboxManager.moveToTrash(it).getOrThrow() }
        _state.value = _state.value.copy(selection = emptySet())
    }

    fun deleteToTrash(item: WorkspaceFileItem) = mutate("Moved to .trash") {
        sandboxManager.moveToTrash(item.path).getOrThrow()
    }

    /**
     * Phase R — import a file from the device (via SAF picker URI) into the
     * current workspace folder. Streams content; respects the sandbox file
     * size limit. Resolves a unique filename if one already exists.
     */
    fun importFromUri(context: Context, uri: Uri, suggestedName: String?) = mutate("Imported") {
        val resolver = context.contentResolver
        val baseName = suggestedName?.takeIf { it.isNotBlank() }
            ?: queryDisplayName(context, uri) ?: "import-${System.currentTimeMillis()}"
        val targetRel = uniqueChildName(_state.value.cwd, baseName)
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open URI: $uri" }
            sandboxManager.importStream(targetRel, input).getOrThrow()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Throwable) { null }

    private fun uniqueChildName(cwd: String, name: String): String {
        val existing = _state.value.entries.map { it.name }.toSet()
        if (name !in existing) return joinCwd(name)
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext  = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in existing) return joinCwd(candidate)
            i++
        }
    }

    fun emptyTrashEntry(item: WorkspaceFileItem) = mutate("Permanently deleted") {
        sandboxManager.deleteRecursive(item.path).getOrThrow()
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }

    // ---- Internals ----

    private fun mutate(success: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
                _state.value = _state.value.copy(message = success)
                reload(_state.value.cwd)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t.javaClass.simpleName)
            }
        }
    }

    private fun joinCwd(name: String): String {
        val cwd = _state.value.cwd
        return if (cwd.isBlank()) name else "$cwd/$name"
    }

    private fun reload(path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = sandboxManager.getWorkspaceInfo()
            val cur = _state.value
            val raw = if (cur.query.isBlank()) {
                sandboxManager.listFiles(path.ifBlank { "." })
            } else {
                sandboxManager.searchFiles(path.ifBlank { "." }, cur.query, cur.recursiveSearch)
            }.getOrDefault(emptyList())
            val mapped = raw.map {
                WorkspaceFileItem(
                    name = it.name,
                    path = it.path,
                    isDirectory = it.isDirectory,
                    size = it.size,
                    lastModified = it.lastModified,
                )
            }
            val sorted = sortEntries(mapped, cur.sort, cur.sortAscending)
            _state.value = cur.copy(
                cwd = path,
                entries = sorted,
                info = WorkspaceInfo(
                    totalFiles = info.totalFiles,
                    totalDirs = info.totalDirs,
                    totalSize = info.totalSize,
                    maxSize = info.maxSize,
                    usagePercent = info.usagePercent,
                ),
            )
        }
    }

    private fun sortEntries(items: List<WorkspaceFileItem>, sort: WorkspaceSort, asc: Boolean): List<WorkspaceFileItem> {
        // Always group directories first, then sort within each group.
        val cmp: Comparator<WorkspaceFileItem> = when (sort) {
            WorkspaceSort.NAME -> compareBy { it.name.lowercase() }
            WorkspaceSort.DATE -> compareBy { it.lastModified }
            WorkspaceSort.SIZE -> compareBy { it.size }
            WorkspaceSort.TYPE -> compareBy { it.name.substringAfterLast('.', missingDelimiterValue = "").lowercase() }
        }
        val ordered = if (asc) cmp else cmp.reversed()
        return items.sortedWith(compareByDescending<WorkspaceFileItem> { it.isDirectory }.then(ordered))
    }
}

data class WorkspaceFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0L,
)

data class WorkspaceInfo(
    val totalFiles: Int = 0,
    val totalDirs: Int = 0,
    val totalSize: Long = 0,
    val maxSize: Long = 500 * 1024 * 1024,
    val usagePercent: Float = 0f,
)
