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
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.browser.BrowserAddressBar
import com.forge.os.presentation.screens.browser.OmniboxSuggestion
import com.forge.os.presentation.screens.browser.BrowserTabStrip
import com.forge.os.presentation.screens.browser.BrowserTabUi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import androidx.compose.runtime.ReadOnlyComposable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Orange: Color
    @Composable @ReadOnlyComposable get() = forgePalette.orange
private val Bg: Color
    @Composable @ReadOnlyComposable get() = forgePalette.bg
private val Surface: Color
    @Composable @ReadOnlyComposable get() = forgePalette.surface
private val TextPrimary: Color
    @Composable @ReadOnlyComposable get() = forgePalette.textPrimary
private val TextMuted: Color
    @Composable @ReadOnlyComposable get() = forgePalette.textMuted

/**
 * In-app browser with persistent session (cookies/localStorage survive across
 * screen visits). The agent can control this browser via [BrowserSessionManager].
 *
 * Adds tabs, bookmarks, history sidebar, and find-in-page. Each tab owns a
 * dedicated WebView from a pool so switching tabs preserves back stack,
 * scroll position, DOM state, and form data without reloading.
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
    val loadProgress by viewModel.progress.collectAsState()
    val tabs by viewModel.tabs.collectAsState()
    val activeTabId by viewModel.activeTabId.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    val history by viewModel.history.collectAsState()
    val downloads by viewModel.downloads.collectAsState()
    val scope = rememberCoroutineScope()

    var addressBarText by remember { mutableStateOf(currentUrl) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showDownloads by remember { mutableStateOf(false) }
    // 0f–1f pull-to-refresh drag progress for the indicator overlay
    var pullProgress by remember { mutableStateOf(0f) }

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

    // Per-tab WebView pool — each tab keeps its own WebView alive so back
    // stack, scroll position, DOM state, and form data are all preserved.
    val webViewPool = remember { mutableMapOf<String, WebView>() }

    // The active WebView (for address bar commands, find-in-page, agent
    // dispatch). Reported by BrowserWebPanel via onActiveWebView so that
    // canGoBack/canGoForward recompose when the WebView is created/swapped.
    var activeWebView by remember { mutableStateOf<WebView?>(null) }
    // Bumped on every page-load event so nav-button state re-reads.
    var navVersion by remember { mutableStateOf(0) }
    // Per-tab favicons, keyed by tab id; fed by WebChromeClient.onReceivedIcon
    val tabFavicons = remember { mutableStateMapOf<String, android.graphics.Bitmap>() }

    // Clean up WebViews for closed tabs
    LaunchedEffect(tabs.map { it.id }) {
        val currentIds = tabs.map { it.id }.toSet()
        val iter = webViewPool.keys.iterator()
        while (iter.hasNext()) {
            val tabId = iter.next()
            if (tabId !in currentIds) {
                webViewPool[tabId]?.destroy()
                iter.remove()
                tabFavicons.remove(tabId)
            }
        }
    }

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
            })
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
            })
    }

    // Dispatch agent commands to the WebView
    LaunchedEffect(Unit) {
        sessionManager.commands.collectLatest { cmd ->
            val wv = activeWebView ?: return@collectLatest
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
        // ── Tab Strip ────────────────────────────────────────────────────
        BrowserTabStrip(
            tabs = tabs.map { tab ->
                BrowserTabUi(
                    id = tab.id,
                    title = tab.title.ifBlank { tab.url.removePrefix("https://").removePrefix("http://").take(24) },
                    url = tab.url,
                    isActive = tab.id == activeTabId,
                    favicon = tabFavicons[tab.id]
                )
            },
            onTabSelect = { tabId -> viewModel.switchTab(tabId) },
            onTabClose = { tabId -> viewModel.closeTab(tabId) },
            onNewTab = { viewModel.newTab() },
            onTabReload = { tabId ->
                if (tabId == activeTabId) activeWebView?.reload()
                else webViewPool[tabId]?.reload()
            },
            onTabDuplicate = { tabId ->
                tabs.firstOrNull { it.id == tabId }?.let { viewModel.newTab(it.url) }
            },
            onCloseOthers = { tabId -> viewModel.closeOtherTabs(tabId) },
            onBack = onNavigateBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
        )

        // ── Combined Address + Nav Bar ───────────────────────────────────
        BrowserAddressBar(
            url = addressBarText,
            onUrlChange = { addressBarText = it },
            onNavigate = { url -> activeWebView?.loadUrl(url) },
            isSecure = currentUrl.startsWith("https"),
            isLoading = isLoading,
            progress = loadProgress,
            // navVersion invalidates these reads on every page-load event
            canGoBack = navVersion.let { activeWebView?.canGoBack() == true },
            canGoForward = navVersion.let { activeWebView?.canGoForward() == true },
            isBookmarked = viewModel.isBookmarked(currentUrl),
            suggestions = remember(history, bookmarks) {
                history.map { OmniboxSuggestion(it.url, it.title.ifBlank { it.url }, isHistory = true) } +
                    bookmarks.map { OmniboxSuggestion(it.url, it.title.ifBlank { it.url }) }
            },
            onBackClick = { activeWebView?.goBack() },
            onForwardClick = { activeWebView?.goForward() },
            onRefreshClick = { activeWebView?.reload() },
            onStopClick = { activeWebView?.stopLoading() },
            onBookmarkClick = {
                val title = pageTitle.ifBlank { currentUrl }
                viewModel.toggleBookmark(currentUrl, title)
            },
            onMenuClick = { showMenu = true },
            modifier = Modifier.fillMaxWidth()
        )

        // ── Overflow menu ────────────────────────────────────────────────
        Box {
            if (showMenu) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(forgePalette.surface, RoundedCornerShape(12.dp))) {
                    DropdownMenuItem(
                        text = { Text("Find in page", color = forgePalette.textPrimary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = forgePalette.orange, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; findVisible = true })
                    DropdownMenuItem(
                        text = { Text("Bookmarks (${bookmarks.size})", color = forgePalette.textPrimary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Bookmark, contentDescription = null, tint = forgePalette.orange, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; showBookmarks = true })
                    DropdownMenuItem(
                        text = { Text("History", color = forgePalette.textPrimary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.History, contentDescription = null, tint = forgePalette.orange, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; showHistory = true })
                    DropdownMenuItem(
                        text = { Text("Downloads", color = forgePalette.textPrimary, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = forgePalette.orange, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; showDownloads = true })
                    DropdownMenuItem(
                        text = { Text("Clear session…", color = forgePalette.danger, fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null, tint = forgePalette.danger, modifier = Modifier.size(18.dp)) },
                        onClick = { showMenu = false; showClearDialog = true })
                }
            }
        }

        // ── Find-in-page bar ─────────────────────────────────────────────
        if (findVisible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(forgePalette.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = findQuery,
                    onValueChange = {
                        findQuery = it
                        if (it.isBlank()) {
                            activeWebView?.clearMatches()
                            findCounter = ""
                        } else {
                            activeWebView?.setFindListener { active, total, _ ->
                                findCounter = if (total == 0) "0 / 0" else "${active + 1} / $total"
                            }
                            activeWebView?.findAllAsync(it)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Find in page", color = forgePalette.textMuted, fontSize = 13.sp) },
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, color = forgePalette.textPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = forgePalette.orange,
                        unfocusedBorderColor = forgePalette.surface2,
                        focusedContainerColor = forgePalette.bg,
                        unfocusedContainerColor = forgePalette.bg)
                )
                Spacer(Modifier.width(6.dp))
                Text(findCounter, color = forgePalette.textMuted, fontSize = 11.sp)
                IconButton(onClick = { activeWebView?.findNext(false) }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous", tint = forgePalette.textPrimary)
                }
                IconButton(onClick = { activeWebView?.findNext(true) }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next", tint = forgePalette.textPrimary)
                }
                IconButton(onClick = {
                    findVisible = false
                    findQuery = ""
                    findCounter = ""
                    activeWebView?.clearMatches()
                }) {
                    Icon(Icons.Filled.Close, contentDescription = "Close find", tint = forgePalette.textMuted)
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
        // Per-tab WebView: only show the active tab's WebView, but keep all
        // tabs' WebViews alive in the pool so state is preserved.
        val activeTab = tabs.firstOrNull { it.id == activeTabId }
        if (activeTab != null) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                BrowserWebPanel(
                    tabId = activeTab.id,
                    initialUrl = activeTab.url,
                    webViewPool = webViewPool,
                    sessionManager = sessionManager,
                    onFileChooserRequested = { cb ->
                        pendingFileChooser = cb
                        showFileSourcePicker = true
                    },
                    onActiveWebView = { wv -> activeWebView = wv },
                    onNavEvent = { navVersion += 1 },
                    onDownload = { url, contentDisposition, mimeType ->
                        viewModel.downloadsStore.enqueue(url, contentDisposition, mimeType)
                        showDownloads = true
                    },
                    onPullProgress = { pullProgress = it },
                    onPullRefresh = { activeWebView?.reload() },
                    onFavicon = { icon -> tabFavicons[activeTab.id] = icon },
                    onPageFinished = { url, title -> viewModel.rememberActiveTabUrl(url, title) })

                // Home page overlay for fresh (about:blank) tabs — no HTML page,
                // so it never pollutes the WebView back stack
                if (currentUrl.isBlank() || currentUrl == "about:blank") {
                    BrowserHomePanel(
                        bookmarks = bookmarks,
                        recentHistory = history.asReversed().take(6),
                        onOpen = { url -> activeWebView?.loadUrl(url) })
                }

                // Pull-to-refresh indicator overlay
                if (pullProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = (8 + pullProgress * 40).dp)) {
                        CircularProgressIndicator(
                            progress = { pullProgress },
                            modifier = Modifier.size(28.dp),
                            color = Orange,
                            strokeWidth = 2.5.dp)
                    }
                }
            }
        }
    }

    if (showBookmarks) {
        BookmarksDialog(
            bookmarks = bookmarks,
            onDismiss = { showBookmarks = false },
            onOpen = { url ->
                showBookmarks = false
                activeWebView?.loadUrl(url)
            },
            onRemove = { url -> viewModel.bookmarksStore.remove(url) })
    }

    if (showHistory) {
        HistoryDialog(
            entries = history,
            onDismiss = { showHistory = false },
            onOpen = { url ->
                showHistory = false
                activeWebView?.loadUrl(url)
            },
            onClear = { viewModel.clearHistory() })
    }

    if (showDownloads) {
        DownloadsDialog(
            entries = downloads,
            onDismiss = { showDownloads = false },
            onRemove = { id -> viewModel.downloadsStore.remove(id) },
            onClear = { viewModel.downloadsStore.clear() })
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear browser session?", color = TextPrimary) },
            text = { Text("This clears cookies, localStorage and history for this tab.", color = TextMuted) },
            confirmButton = {
                TextButton(onClick = {
                    activeWebView?.apply {
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
            containerColor = Surface)
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
    onPicked: (String) -> Unit) {
    var files by remember { mutableStateOf<List<SandboxManager.FileInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        files = sandbox.listFiles("").getOrElse { emptyList() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Workspace files") },
        text = {
            Column {
                if (files.isEmpty()) {
                    Text("No files in workspace.", color = TextMuted)
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
        containerColor = Surface)
}

@Composable
private fun BookmarksDialog(
    bookmarks: List<BookmarkEntry>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks", color = TextPrimary) },
        text = {
            if (bookmarks.isEmpty()) {
                Text(
                    "No bookmarks yet — tap the star next to the URL bar to add one.",
                    color = TextMuted,
                    fontSize = 12.sp)
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
                            verticalAlignment = Alignment.CenterVertically) {
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
                                    maxLines = 1)
                                Text(
                                    b.url,
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1)
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
        containerColor = Surface)
}

@Composable
private fun HistoryDialog(
    entries: List<BrowserHistoryEntry>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onClear: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("History", color = TextPrimary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Clear all", color = TextMuted, fontSize = 12.sp) }
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "Browser history is empty.",
                    color = TextMuted,
                    fontSize = 12.sp)
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
                                .padding(vertical = 6.dp)) {
                            Text(
                                e.title.ifBlank { e.url },
                                color = TextPrimary,
                                fontSize = 13.sp,
                                maxLines = 1)
                            Text(
                                "${fmt.format(Date(e.ts))} · ${e.sessionId} · ${e.url}",
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Orange) }
        },
        containerColor = Surface)
}

/**
 * Home panel shown for fresh tabs (about:blank). Pure Compose — sits on top
 * of the pooled WebView, so it never enters the WebView's navigation history.
 */
@Composable
private fun BrowserHomePanel(
    bookmarks: List<BookmarkEntry>,
    recentHistory: List<BrowserHistoryEntry>,
    onOpen: (String) -> Unit) {
    val quickLinks = remember {
        listOf(
            "Google" to "https://www.google.com",
            "YouTube" to "https://m.youtube.com",
            "Wikipedia" to "https://en.wikipedia.org",
            "GitHub" to "https://github.com",
            "Reddit" to "https://www.reddit.com",
            "News" to "https://news.google.com")
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Spacer(Modifier.height(36.dp))
            Text(
                "FORGE",
                color = Orange,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp,
                fontFamily = FontFamily.Monospace)
            Text(
                "Search the web or tap a shortcut",
                color = TextMuted,
                fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
        }

        // Quick-link grid (2 per row)
        items(quickLinks.chunked(2).size) { rowIdx ->
            val pair = quickLinks.chunked(2)[rowIdx]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, url) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Surface)
                            .clickable { onOpen(url) }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center) {
                        Text(label, color = TextPrimary, fontSize = 13.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (bookmarks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(18.dp))
                Text("BOOKMARKS", color = TextMuted, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
            }
            items(bookmarks.take(5), key = { "bm-${it.url}" }) { b ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpen(b.url) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bookmark, contentDescription = null, tint = Orange, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(b.title.ifBlank { b.url }, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        if (recentHistory.isNotEmpty()) {
            item {
                Spacer(Modifier.height(18.dp))
                Text("RECENT", color = TextMuted, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(6.dp))
            }
            items(recentHistory, key = { "h-${it.ts}-${it.url}" }) { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpen(e.url) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.History, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(e.title.ifBlank { e.url }, color = TextPrimary, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun DownloadsDialog(
    entries: List<com.forge.os.data.browser.BrowserDownloadEntry>,
    onDismiss: () -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit) {
    val fmt = remember { SimpleDateFormat("MMM d HH:mm", Locale.getDefault()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Downloads", color = TextPrimary)
                Spacer(Modifier.weight(1f))
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClear) { Text("Clear all", color = TextMuted, fontSize = 12.sp) }
                }
            }
        },
        text = {
            if (entries.isEmpty()) {
                Text(
                    "No downloads yet. Files you download in the browser are saved to the device Downloads folder.",
                    color = TextMuted,
                    fontSize = 12.sp)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    items(entries, key = { it.id }) { d ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    d.fileName,
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1)
                                Text(
                                    "${fmt.format(Date(d.timestamp))} · ${d.url}",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1)
                            }
                            IconButton(onClick = { onRemove(d.id) }) {
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
        containerColor = Surface)
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
            .background(forgePalette.bg),
        contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "⚠ In-app browser unavailable",
                color = Orange,
                fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "Android couldn't start the system WebView on this device. " +
                    "This usually means the WebView component is updating, " +
                    "disabled, or another process is already using its data " +
                    "directory.",
                color = TextPrimary,
                fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                message,
                color = TextMuted,
                fontSize = 10.sp)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Orange)) { Text("RETRY") }
        }
    }
}

/**
 * Per-tab WebView panel. Each tab gets its own WebView from [webViewPool].
 * Switching tabs detaches the old WebView and attaches the new one — no
 * reload, no state loss. Back stack, scroll position, DOM, and form data
 * are all preserved per tab.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserWebPanel(
    tabId: String,
    initialUrl: String,
    webViewPool: MutableMap<String, WebView>,
    sessionManager: BrowserSessionManager,
    onFileChooserRequested: (ValueCallback<Array<Uri>>?) -> Unit,
    onActiveWebView: (WebView) -> Unit,
    onNavEvent: () -> Unit,
    onDownload: (url: String, contentDisposition: String?, mimeType: String?) -> Unit,
    onPullProgress: (Float) -> Unit,
    onPullRefresh: () -> Unit,
    onFavicon: (android.graphics.Bitmap) -> Unit,
    onPageFinished: (url: String, title: String) -> Unit) {
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
            onRetry = { retryNonce += 1 })
        return
    }

    // key(tabId) ensures each tab gets its own AndroidView/WebView instance
    androidx.compose.runtime.key(tabId) {
        AndroidView(
            factory = { ctx ->
                // Reuse existing WebView from pool, or create a new one
                val existing = webViewPool[tabId]
                if (existing != null) {
                    // Detach from old parent if needed
                    (existing.parent as? android.view.ViewGroup)?.removeView(existing)
                    // Post state writes — factory runs during layout pass
                    existing.post {
                        onActiveWebView(existing)
                        onNavEvent()
                    }
                    return@AndroidView existing
                }

                val wv = com.forge.os.presentation.screens.browser.PullRefreshWebView(
                    ctx,
                    onPullProgress = onPullProgress,
                    onPullRefresh = onPullRefresh)
                wv.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                    onDownload(url, contentDisposition, mimeType)
                }
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

                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        sessionManager.updateProgress(newProgress)
                        sessionManager.updateLoading(newProgress < 100)
                    }

                    override fun onReceivedIcon(view: WebView?, icon: android.graphics.Bitmap?) {
                        if (icon != null) onFavicon(icon)
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
                        onNavEvent()
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        sessionManager.updateUrl(url ?: "")
                        sessionManager.updateLoading(false)
                        onNavEvent()
                        onPageFinished(url ?: "", view?.title ?: "")
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?) {
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

                // Only load URL for brand-new tabs
                if (initialUrl.isNotBlank() && initialUrl != "about:blank") {
                    wv.loadUrl(initialUrl)
                }

                webViewPool[tabId] = wv
                // Post state write — factory runs during layout pass
                wv.post { onActiveWebView(wv) }
                wv
            },
            update = { /* no-op — each tab manages its own WebView state */ },
            modifier = Modifier.fillMaxSize()
        )
    }
}
