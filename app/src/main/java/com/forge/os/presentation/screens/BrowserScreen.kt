package com.forge.os.presentation.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import timber.log.Timber
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.platform.LocalContext
import com.forge.os.data.sandbox.SandboxManager
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.data.browser.Bookmark as BookmarkEntry
import com.forge.os.data.browser.BrowserHistoryEntry
import com.forge.os.data.browser.BrowserSessionManager
import com.forge.os.data.browser.NavigationCommand
import com.forge.os.presentation.theme.LocalForgePalette
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.ReadOnlyComposable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Orange: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.orange
private val Bg: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.bg
private val Surface: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.surface
private val Surface2: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.surface2
private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textPrimary
private val TextMuted: Color
    @Composable @ReadOnlyComposable get() = LocalForgePalette.current.textMuted

/**
 * In-app browser with persistent session (cookies/localStorage survive across
 * screen visits). The agent can control this browser via [BrowserSessionManager].
 *
 * Adds tabs, bookmarks, history sidebar, and find-in-page on top of the
 * existing single-WebView model. The WebView itself is reused across tabs;
 * switching tabs just reloads the tab's URL.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onNavigateBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val currentUrl by viewModel.currentUrl.collectAsState()
    val pageTitle by viewModel.pageTitle.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.history.collectAsState()
    val scope = rememberCoroutineScope()

    var addressBarText by remember { mutableStateOf(currentUrl) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    // Find-in-page state.
    var findVisible by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findCounter by remember { mutableStateOf("") } // e.g. "1 / 5"

    // Keep address bar in sync when agent navigates
    LaunchedEffect(currentUrl) {
        if (currentUrl != "about:blank") addressBarText = currentUrl
        viewModel.rememberActiveTabUrl(currentUrl, pageTitle)
    }

    val sessionManager = viewModel.sessionManager

    // Reference to the live WebView so we can dispatch commands from the agent
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // ─── Phase R: file-input upload bridge ──────────────────────────────────
    val ctxLocal = LocalContext.current
    val sandbox = remember {
        EntryPointAccessors.fromApplication(ctxLocal.applicationContext, BrowserSandboxEntryPoint::class.java).sandbox()
    }
    var pendingFileChooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var showFileSourcePicker by remember { mutableStateOf(false) }
    var showWorkspacePicker by remember { mutableStateOf(false) }
    val deviceFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingFileChooser?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        pendingFileChooser = null
    }

    if (showFileSourcePicker) {
        AlertDialog(
            onDismissRequest = {
                showFileSourcePicker = false
                pendingFileChooser?.onReceiveValue(null); pendingFileChooser = null
            },
            title = { Text("Upload file") },
            text = { Text("Pick a file from your Forge workspace or from the device.") },
            confirmButton = {
                TextButton(onClick = {
                    showFileSourcePicker = false
                    showWorkspacePicker = true
                }) { Text("From workspace") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showFileSourcePicker = false
                    deviceFileLauncher.launch(arrayOf("*/*"))
                }) { Text("From device") }
            },
        )
    }

    if (showWorkspacePicker) {
        WorkspaceFilePickerDialog(
            sandbox = sandbox,
            onDismiss = {
                showWorkspacePicker = false
                pendingFileChooser?.onReceiveValue(null); pendingFileChooser = null
            },
            onPicked = { relPath ->
                showWorkspacePicker = false
                scope.launch {
                    val abs = sandbox.absolutePathFor(relPath)
                    val authority = "${ctxLocal.packageName}.fileprovider"
                    val uri = runCatching { FileProvider.getUriForFile(ctxLocal, authority, java.io.File(abs)) }.getOrNull()
                    pendingFileChooser?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
                    pendingFileChooser = null
                }
            },
        )
    }

    // Dispatch agent commands to the WebView
    LaunchedEffect(Unit) {
        sessionManager.commands.collectLatest { cmd ->
            val wv = webViewRef ?: return@collectLatest
            when (cmd) {
                is NavigationCommand.OpenUrl -> wv.post { wv.loadUrl(cmd.url) }
                is NavigationCommand.Reload -> wv.post { wv.reload() }
                is NavigationCommand.GetHtml -> wv.post {
                    wv.evaluateJavascript(
                        "(function(){ return document.documentElement.outerHTML; })()"
                    ) { html -> sessionManager.onHtmlSnapshot(html ?: "") }
                }
                is NavigationCommand.EvalJs -> wv.post {
                    wv.evaluateJavascript(cmd.script) { result ->
                        sessionManager.onJsResult(cmd.callbackId, result ?: "null")
                    }
                }
                is NavigationCommand.FillField -> wv.post {
                    val js = """
                        (function(){
                          var el = document.querySelector('${cmd.selector.replace("'", "\\'")}');
                          if(el){ el.value='${cmd.value.replace("'", "\\'")}'; el.dispatchEvent(new Event('input',{bubbles:true})); return 'ok'; }
                          return 'not found';
                        })()
                    """.trimIndent()
                    wv.evaluateJavascript(js) { r -> sessionManager.onJsResult("fill_${System.currentTimeMillis()}", r ?: "null") }
                }
                is NavigationCommand.ClickElement -> wv.post {
                    val js = """
                        (function(){
                          var el = document.querySelector('${cmd.selector.replace("'", "\\'")}');
                          if(el){ el.click(); return 'clicked'; }
                          return 'not found';
                        })()
                    """.trimIndent()
                    wv.evaluateJavascript(js) { r -> sessionManager.onJsResult("click_${System.currentTimeMillis()}", r ?: "null") }
                }
                is NavigationCommand.ScrollTo -> wv.post { wv.scrollTo(cmd.x, cmd.y) }
                is NavigationCommand.GoBack -> wv.post { if (wv.canGoBack()) wv.goBack() }
                is NavigationCommand.GoForward -> wv.post { if (wv.canGoForward()) wv.goForward() }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        // ── Address bar ─────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            IconButton(onClick = { webViewRef?.goBack() }, enabled = webViewRef?.canGoBack() == true) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back", tint = if (webViewRef?.canGoBack() == true) TextPrimary else TextMuted)
            }
            IconButton(onClick = { webViewRef?.goForward() }, enabled = webViewRef?.canGoForward() == true) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Go forward", tint = if (webViewRef?.canGoForward() == true) TextPrimary else TextMuted)
            }
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Reload", tint = TextPrimary)
            }

            OutlinedTextField(
                value = addressBarText,
                onValueChange = { addressBarText = it },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = TextPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onGo = {
                    val url = addressBarText.trim()
                    if (url.isNotBlank()) {
                        val target = if (url.startsWith("http")) url else "https://$url"
                        webViewRef?.loadUrl(target)
                    }
                }),
                trailingIcon = {
                    Row {
                        // Bookmark toggle.
                        val starred = viewModel.isBookmarked(currentUrl)
                        IconButton(onClick = {
                            val title = pageTitle.ifBlank { currentUrl }
                            viewModel.toggleBookmark(currentUrl, title)
                        }) {
                            Icon(
                                if (starred) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                contentDescription = if (starred) "Remove bookmark" else "Add bookmark",
                                tint = if (starred) Orange else TextMuted,
                            )
                        }
                        if (addressBarText.isNotEmpty()) {
                            IconButton(onClick = { addressBarText = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextMuted)
                            }
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Orange,
                    unfocusedBorderColor = Surface2,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                )
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp).padding(start = 6.dp),
                    color = Orange,
                    strokeWidth = 2.dp
                )
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Menu", tint = TextPrimary)
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(Surface),
                ) {
                    DropdownMenuItem(
                        text = { Text("Find in page", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Orange) },
                        onClick = { showMenu = false; findVisible = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Bookmarks (${bookmarks.size})", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Orange) },
                        onClick = { showMenu = false; showBookmarks = true },
                    )
                    DropdownMenuItem(
                        text = { Text("History", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, tint = Orange) },
                        onClick = { showMenu = false; showHistory = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear session…", color = TextPrimary) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted) },
                        onClick = { showMenu = false; showClearDialog = true },
                    )
                }
            }
        }

        // ── Tab strip ────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Surface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LazyRow(modifier = Modifier.weight(1f)) {
                items(tabs, key = { it.id }) { tab ->
                    val isActive = tab.id == activeTabId
                    Row(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(
                                if (isActive) Surface2 else Bg,
                                RoundedCornerShape(8.dp),
                            )
                            .border(
                                width = 1.dp,
                                color = if (isActive) Orange else Surface2,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { viewModel.switchTab(tab.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val label = tab.title.ifBlank {
                            tab.url.removePrefix("https://").removePrefix("http://").take(24)
                                .ifBlank { "New tab" }
                        }
                        Text(
                            label,
                            color = if (isActive) TextPrimary else TextMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close tab",
                            tint = TextMuted,
                            modifier = Modifier
                                .size(14.dp)
                                .clickable { viewModel.closeTab(tab.id) },
                        )
                    }
                }
            }
            IconButton(onClick = { viewModel.newTab() }) {
                Icon(Icons.Filled.Add, contentDescription = "New tab", tint = Orange)
            }
        }

        // ── Find-in-page bar ─────────────────────────────────────────────
        if (findVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        if (it.isBlank()) {
                            webViewRef?.clearMatches()
                            findCounter = ""
                        } else {
                            webViewRef?.setFindListener { active, total, _ ->
                                findCounter = if (total == 0) "0 / 0" else "${active + 1} / $total"
                            }
                            webViewRef?.findAllAsync(it)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Find in page", color = TextMuted, fontSize = 13.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Orange,
                        unfocusedBorderColor = Surface2,
                        focusedContainerColor = Bg,
                        unfocusedContainerColor = Bg,
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(findCounter, color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                IconButton(onClick = { webViewRef?.findNext(false) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous", tint = TextPrimary)
                }
                IconButton(onClick = { webViewRef?.findNext(true) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next", tint = TextPrimary)
                }
                IconButton(onClick = {
                    findVisible = false
                    findQuery = ""
                    findCounter = ""
                    webViewRef?.clearMatches()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close find", tint = TextMuted)
                }
            }
        }

        // ── WebView ─────────────────────────────────────────────────────
        // Phase V — the entire WebView surface lives in its own composable
        // (`BrowserWebPanel`). Inlining the preflight + the giant AndroidView
        // factory directly inside `BrowserScreen` produced a method so large
        // (hundreds of locals across many nested lambdas) that ART's bytecode
        // verifier rejected it on some devices with
        //   VerifyError: copy1 v13<-v281 type=Precise Reference: BrowserViewModel
        // Splitting the WebView block into its own function keeps both
        // methods comfortably below the verifier's register-merge limits and
        // is the canonical Compose-on-Android workaround for this class of
        // crash.
        BrowserWebPanel(
            currentUrl = currentUrl,
            sessionManager = sessionManager,
            onWebViewReady = { webViewRef = it },
            onWebViewReleased = { if (webViewRef === it) webViewRef = null },
            onFileChooserRequested = { cb ->
                pendingFileChooser = cb
                showFileSourcePicker = true
            },
            onPageFinished = { url, title -> viewModel.rememberActiveTabUrl(url, title) },
        )
    }

    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = bookmarks,
            onDismiss = { showBookmarks = false },
            onOpen = { url ->
                showBookmarks = false
                webViewRef?.loadUrl(url)
            },
            onRemove = { url -> viewModel.bookmarksStore.remove(url) },
        )
    }

    if (showHistory) {
        HistoryDialog(
            entries = history,
            onDismiss = { showHistory = false },
            onOpen = { url ->
                showHistory = false
                webViewRef?.loadUrl(url)
            },
            onClear = { viewModel.clearHistory() },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear browser session?", color = TextPrimary) },
            text = { Text("This clears cookies, localStorage and history for this tab.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    webViewRef?.apply {
                        clearCache(true)
                        clearHistory()
                        clearFormData()
                        CookieManager.getInstance().removeAllCookies(null)
                    }
                    viewModel.clearAll()
                    showClearDialog = false
                }) { Text("Clear", color = Orange) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel", color = TextMuted) }
            },
            containerColor = Surface,
        )
    }
}

// ─── Hilt entry point so the sandbox can be fetched inside a @Composable ───
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface BrowserSandboxEntryPoint {
    fun sandbox(): SandboxManager
}

@Composable
private fun WorkspaceFilePickerDialog(
    sandbox: SandboxManager,
    onDismiss: () -> Unit,
    onPicked: (String) -> Unit,
) {
    var files by remember { mutableStateOf<List<SandboxManager.FileInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        files = sandbox.listFiles("").getOrElse { emptyList() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspace files", fontFamily = FontFamily.Monospace) },
        text = {
            Column {
                if (files.isEmpty()) {
                    Text("No files in workspace.", color = TextMuted, fontFamily = FontFamily.Monospace)
                } else {
                    LazyColumn {
                        items(files) { f ->
                            Text(
                                f.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPicked(f.path) }
                                    .padding(vertical = 6.dp),
                                color = Orange,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        },
        containerColor = Surface,
    )
}

@Composable
private fun BookmarksDialog(
    bookmarks: List<BookmarkEntry>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks", color = TextPrimary, fontFamily = FontFamily.Monospace) },
        text = {
            if (bookmarks.isEmpty()) {
                Text(
                    "No bookmarks yet — tap the star next to the URL bar to add one.",
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    items(bookmarks, key = { it.url }) { b ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpen(b.url) }
                            ) {
                                Text(
                                    b.title.ifBlank { b.url },
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                )
                                Text(
                                    b.url,
                                    color = TextMuted,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                            IconButton(onClick = { onRemove(b.url) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = TextMuted)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Orange) }
        },
        containerColor = Surface,
    )
}

@Composable
private fun HistoryDialog(
    entries: List<BrowserHistoryEntry>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
) {
    val fmt = remember { SimpleDateFormat("MMM d HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", color = TextPrimary, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear all", color = TextMuted, fontSize = 12.sp) }
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "Browser history is empty.",
                    color = TextMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    itemsIndexed(entries.asReversed().take(200)) { _, e ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(e.url) }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                e.title.ifBlank { e.url },
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1,
                            )
                            Text(
                                "${fmt.format(Date(e.ts))} · ${e.sessionId} · ${e.url}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Orange) }
        },
        containerColor = Surface,
    )
}

/**
 * Phase U2 — Shown instead of the WebView when system construction
 * throws (WebView system package missing/disabled, multi-process data dir
 * conflict, etc.). Lets the user retry without restarting the activity.
 */
@Composable
private fun WebViewUnavailablePanel(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "⚠ In-app browser unavailable",
                color = Orange,
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Android couldn't start the system WebView on this device. " +
                    "This usually means the WebView component is updating, " +
                    "disabled, or another process is already using its data " +
                    "directory.",
                color = TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = TextMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
            ) { Text("RETRY", fontFamily = FontFamily.Monospace) }
        }
    }
}

/**
 * Phase V — the WebView surface, extracted from `BrowserScreen` so that the
 * outer composable's bytecode stays small enough for ART's verifier on every
 * device. The contents are intentionally identical to the previous inline
 * block; the split is purely structural.
 *
 * The preflight checks `WebView.getCurrentWebViewPackage()` and bails out
 * with `WebViewUnavailablePanel` if the system WebView is missing / being
 * updated. When the preflight succeeds, a single AndroidView builds the
 * WebView, wires the JS bridge, and forwards file-chooser requests + page
 * lifecycle events back to the host screen via callbacks.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebPanel(
    currentUrl: String,
    sessionManager: BrowserSessionManager,
    onWebViewReady: (WebView) -> Unit,
    onWebViewReleased: (WebView) -> Unit,
    onFileChooserRequested: (ValueCallback<Array<Uri>>?) -> Unit,
    onPageFinished: (url: String, title: String) -> Unit,
) {
    val ctxForPreflight = LocalContext.current
    var webViewFatal by remember { mutableStateOf<String?>(null) }
    var retryNonce by remember { mutableStateOf(0) }

    LaunchedEffect(retryNonce, ctxForPreflight) {
        webViewFatal = runCatching {
            if (WebView.getCurrentWebViewPackage() == null) {
                error("Android System WebView is missing or being updated. " +
                    "Open Play Store and update Android System WebView, then retry.")
            }
            null as String?
        }.getOrElse { t ->
            Timber.e(t, "BrowserWebPanel: WebView preflight failed")
            t.message ?: t::class.java.simpleName
        }
    }

    val fatal = webViewFatal
    if (fatal != null) {
        WebViewUnavailablePanel(
            message = fatal,
            onRetry = { retryNonce += 1 },
        )
        return
    }

    AndroidView(
        factory = { ctx ->
            val wv = WebView(ctx)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
            }

            wv.webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    onFileChooserRequested(filePathCallback)
                    return true
                }
            }

            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    return when {
                        url.startsWith("http://") || url.startsWith("https://") -> false
                        url.startsWith("intent://") -> {
                            runCatching {
                                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                                ctx.startActivity(intent)
                            }
                            true
                        }
                        else -> {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            true
                        }
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    sessionManager.updateUrl(url ?: "")
                    sessionManager.updateLoading(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    sessionManager.updateUrl(url ?: "")
                    sessionManager.updateLoading(false)
                    onPageFinished(url ?: "", view?.title ?: "")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    val isMainFrame = request?.isForMainFrame == true
                    if (!isMainFrame || view == null) return
                    val failedUrl = request?.url?.toString() ?: ""
                    val desc = error?.description?.toString() ?: "Unknown error"
                    Timber.w("BrowserWebPanel: load error $desc for $failedUrl")
                    val safeUrl = failedUrl.replace("'", "\\'")
                    val safeDesc = desc.replace("<", "&lt;")
                    val html = """
                        <html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                        <style>
                          body{background:#0a0a0a;color:#e5e5e5;font-family:-apple-system,system-ui,sans-serif;padding:32px;line-height:1.5}
                          h1{color:#f97316;font-size:18px;margin:0 0 16px}
                          code{background:#1a1a1a;padding:2px 6px;border-radius:4px;font-size:12px}
                          button{background:#f97316;color:#000;border:0;border-radius:6px;padding:10px 16px;font-weight:600;margin-top:16px;cursor:pointer}
                          .muted{color:#888;font-size:12px;margin-top:24px}
                        </style></head><body>
                        <h1>⚠ Couldn't load this page</h1>
                        <p>$safeDesc</p>
                        <code>$safeUrl</code><br>
                        <button onclick="history.back()">← Go back</button>
                        <p class="muted">Forge OS Browser</p>
                        </body></html>
                    """.trimIndent()
                    view.loadDataWithBaseURL(failedUrl, html, "text/html", "UTF-8", failedUrl)
                }
            }

            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun postMessage(json: String) {
                    sessionManager.onHtmlSnapshot(json)
                }
            }, "ForgeBridge")

            wv.loadUrl(currentUrl.takeIf { it.isNotBlank() } ?: "about:blank")
            onWebViewReady(wv)
            wv
        },
        update = { wv ->
            if (currentUrl != wv.url && currentUrl.isNotBlank() && currentUrl != "about:blank") {
                wv.loadUrl(currentUrl)
            }
        },
        onRelease = { wv -> onWebViewReleased(wv) },
        modifier = Modifier.fillMaxSize()
    )
}
