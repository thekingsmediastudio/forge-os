package com.forge.os.presentation.screens
 
import kotlinx.coroutines.launch

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.data.sandbox.SandboxManager
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onNavigateBack: () -> Unit,
    onOpenFile: (String) -> Unit = {},
    sandboxManager: SandboxManager? = null,
    viewModel: WorkspaceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Surface VM messages.
    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    // Dialogs
    var showSort by remember { mutableStateOf(false) }
    var showNewMenu by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf<NewKind?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceFileItem?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceFileItem?>(null) }

    val multiSelect = state.selection.isNotEmpty()

    // SAF picker: import a file from the device into the current workspace folder.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFromUri(ctx, uri, suggestedName = null)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (multiSelect) {
                TopAppBar(
                    title = { Text("${state.selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, "Clear selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.deleteSelectionToTrash() }) {
                            Icon(Icons.Default.Delete, "Delete selected")
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text("Workspace") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSort = true }) {
                            Icon(Icons.Default.Sort, "Sort")
                        }
                        Box {
                            IconButton(onClick = { showNewMenu = true }) {
                                Icon(Icons.Default.Add, "New")
                            }
                            DropdownMenu(expanded = showNewMenu, onDismissRequest = { showNewMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("New file") },
                                    onClick = { showNewMenu = false; newDialog = NewKind.FILE },
                                    leadingIcon = { Icon(Icons.Default.InsertDriveFile, null) },
                                )
                                DropdownMenuItem(
                                    text = { Text("New folder") },
                                    onClick = { showNewMenu = false; newDialog = NewKind.FOLDER },
                                    leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Upload from device") },
                                    onClick = {
                                        showNewMenu = false
                                        importLauncher.launch(arrayOf("*/*"))
                                    },
                                    leadingIcon = { Icon(Icons.Default.Upload, null) },
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StorageCard(state.info)
            BreadcrumbBar(cwd = state.cwd, onNavigate = viewModel::openDirectory, onUp = viewModel::navigateUp)
            SearchBar(
                query = state.query,
                recursive = state.recursiveSearch,
                onQuery = viewModel::setQuery,
                onRecursiveToggle = viewModel::setRecursiveSearch,
            )

            if (state.entries.isEmpty()) {
                EmptyState(query = state.query)
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.entries, key = { it.path.ifEmpty { it.name } }) { file ->
                        FileRow(
                            file = file,
                            isSelected = file.path in state.selection,
                            multiSelectActive = multiSelect,
                            onTap = {
                                if (multiSelect) {
                                    viewModel.toggleSelection(file.path)
                                } else if (file.isDirectory) {
                                    viewModel.openDirectory(file.path)
                                } else {
                                    onOpenFile(file.path)
                                }
                            },
                            onLongPress = { viewModel.toggleSelection(file.path) },
                            onShare = {
                                if (sandboxManager != null && !file.isDirectory) {
                                    scope.launch { shareFile(ctx, sandboxManager, file.path) }
                                }
                            },
                            onRename = { renameTarget = file },
                            onDelete = { deleteTarget = file },
                        )
                    }
                }
            }
        }
    }

    if (showSort) {
        SortSheet(
            current = state.sort,
            ascending = state.sortAscending,
            onPick = { viewModel.setSort(it); showSort = false },
            onDismiss = { showSort = false },
        )
    }

    newDialog?.let { kind ->
        TextPromptDialog(
            title = if (kind == NewKind.FILE) "New file" else "New folder",
            label = "Name",
            confirm = "Create",
            onDismiss = { newDialog = null },
            onConfirm = { name ->
                newDialog = null
                if (name.isNotBlank()) {
                    if (kind == NewKind.FILE) viewModel.newFile(name) else viewModel.newFolder(name)
                }
            },
        )
    }

    renameTarget?.let { item ->
        TextPromptDialog(
            title = "Rename",
            label = "New name",
            initial = item.name,
            confirm = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                if (name.isNotBlank() && name != item.name) viewModel.rename(item, name)
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Move to .trash?") },
            text = { Text("\"${item.name}\" will be moved to workspace/.trash and can be restored from there.") },
            confirmButton = {
                TextButton(onClick = {
                    val t = item; deleteTarget = null
                    viewModel.deleteToTrash(t)
                }) { Text("Move to trash") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

private enum class NewKind { FILE, FOLDER }

@Composable
private fun StorageCard(info: WorkspaceInfo) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { (info.usagePercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${info.totalSize / 1024}KB / ${info.maxSize / 1024 / 1024}MB (${"%.1f".format(info.usagePercent)}%)",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${info.totalFiles} files, ${info.totalDirs} directories",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun BreadcrumbBar(cwd: String, onNavigate: (String) -> Unit, onUp: () -> Unit) {
    val parts = if (cwd.isBlank()) emptyList() else cwd.split('/').filter { it.isNotEmpty() }
    // The Up arrow stays pinned on the left; the breadcrumb chips scroll
    // horizontally so deeply nested paths remain fully tappable instead of
    // being squashed/clipped on narrow screens.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp, enabled = parts.isNotEmpty()) {
            Icon(Icons.Default.ArrowUpward, "Up")
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(onClick = { onNavigate("") }, label = { Text("workspace") })
            var acc = ""
            parts.forEach { p ->
                acc = if (acc.isEmpty()) p else "$acc/$p"
                Text("/", modifier = Modifier.padding(horizontal = 4.dp))
                val target = acc
                AssistChip(
                    onClick = { onNavigate(target) },
                    label = { Text(p, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String,
    recursive: Boolean,
    onQuery: (String) -> Unit,
    onRecursiveToggle: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = recursive, onCheckedChange = onRecursiveToggle, enabled = query.isNotBlank())
            Spacer(Modifier.width(8.dp))
            Text("Recursive", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EmptyState(query: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            if (query.isBlank()) "Empty folder" else "No matches for \"$query\"",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    file: WorkspaceFileItem,
    isSelected: Boolean,
    multiSelectActive: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Surface(
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap),
    ) {
        ListItem(
            headlineContent = {
                Text(file.name, fontWeight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Normal)
            },
            supportingContent = {
                val time = if (file.lastModified > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.lastModified)) else ""
                val size = if (file.isDirectory) "Directory" else humanSize(file.size)
                Text(if (time.isEmpty()) size else "$size • $time", style = MaterialTheme.typography.bodySmall)
            },
            leadingContent = {
                if (multiSelectActive) {
                    Checkbox(checked = isSelected, onCheckedChange = { onLongPress() })
                } else {
                    Icon(
                        imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                        contentDescription = null,
                        tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            trailingContent = {
                Row {
                    if (!multiSelectActive) {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                            DropdownMenuItem(text = { Text("Move to trash") }, onClick = { menuOpen = false; onDelete() })
                            if (!file.isDirectory) {
                                DropdownMenuItem(text = { Text("Share") }, onClick = { menuOpen = false; onShare() })
                            }
                            DropdownMenuItem(text = { Text("Select") }, onClick = { menuOpen = false; onLongPress() })
                        }
                    }
                }
            },
            modifier = Modifier.background(bg),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(
    current: WorkspaceSort,
    ascending: Boolean,
    onPick: (WorkspaceSort) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
            WorkspaceSort.values().forEach { opt ->
                ListItem(
                    headlineContent = { Text(opt.label) },
                    leadingContent = { RadioButton(selected = opt == current, onClick = { onPick(opt) }) },
                    trailingContent = {
                        if (opt == current) Icon(
                            if (ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { onPick(opt) },
                )
            }
            Text(
                "Tap the active option again to flip direction.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var i = 0
    var v = bytes.toDouble()
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}

private suspend fun shareFile(ctx: android.content.Context, sandbox: SandboxManager, relPath: String) {
    val file = sandbox.resolveSafe(relPath)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = ctx.contentResolver.getType(uri) ?: "*/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Share ${file.name}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
