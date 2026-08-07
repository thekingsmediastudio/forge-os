package com.forge.os.presentation.screens.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.forgePalette

/** A single omnibox suggestion row. */
data class OmniboxSuggestion(
    val text: String,   // URL or search query
    val label: String,  // display line 1 (title or query)
    val isHistory: Boolean = false)

/**
 * Omnibox: combined address + search bar with suggestions, stop/reload
 * toggle, and determinate load progress (bar + percentage).
 *
 * Non-URL input is treated as a web search. Suggestions come from local
 * history and bookmarks, filtered as the user types.
 */
@Composable
fun BrowserAddressBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    isSecure: Boolean = false,
    isLoading: Boolean = false,
    progress: Int = 100,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    isBookmarked: Boolean = false,
    suggestions: List<OmniboxSuggestion> = emptyList(),
    onSecurityClick: () -> Unit = {},
    onBackClick: () -> Unit = {},
    onForwardClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    onStopClick: () -> Unit = {},
    onBookmarkClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var displayUrl by remember(url) { mutableStateOf(url) }

    fun resolveAndNavigate(raw: String) {
        val input = raw.trim()
        if (input.isEmpty()) return
        val looksLikeUrl = input.startsWith("http://") ||
            input.startsWith("https://") ||
            (input.contains('.') && !input.contains(' ') &&
                input.substringAfterLast('.').length >= 2)
        val target = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            looksLikeUrl -> "https://$input"
            else -> "https://www.google.com/search?q=" +
                java.net.URLEncoder.encode(input, "UTF-8")
        }
        onNavigate(target)
        isEditing = false
    }

    Column(modifier = modifier) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(forgePalette.bg)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Nav buttons
                IconButton(
                    onClick = onBackClick,
                    enabled = canGoBack,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = if (canGoBack) forgePalette.textPrimary
                        else forgePalette.textMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = onForwardClick,
                    enabled = canGoForward,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Forward",
                        tint = if (canGoForward) forgePalette.textPrimary
                        else forgePalette.textMuted.copy(alpha = 0.3f),
                        modifier = Modifier.size(18.dp)
                    )
                }
                // Stop / reload toggle
                IconButton(
                    onClick = { if (isLoading) onStopClick() else onRefreshClick() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (isLoading) Icons.Filled.Close else Icons.Filled.Refresh,
                        contentDescription = if (isLoading) "Stop loading" else "Refresh",
                        tint = forgePalette.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // URL input
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(forgePalette.surface2)
                        .border(1.dp, forgePalette.border, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isEditing && isSecure) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Site security info",
                                tint = forgePalette.success,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onSecurityClick() }
                            )
                            Box(modifier = Modifier.size(6.dp))
                        } else if (!isEditing && url.startsWith("http://")) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = "Not secure",
                                tint = forgePalette.danger,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { onSecurityClick() }
                            )
                            Box(modifier = Modifier.size(6.dp))
                        }

                        BasicTextField(
                            value = displayUrl,
                            onValueChange = { newValue ->
                                displayUrl = newValue
                                onUrlChange(newValue)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { f -> isEditing = f.isFocused },
                            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                                color = forgePalette.textPrimary,
                                fontSize = 13.sp
                            ),
                            cursorBrush = SolidColor(forgePalette.orange),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = { resolveAndNavigate(displayUrl) }
                            ),
                            singleLine = true,
                            decorationBox = { innerTextField ->
                                if (displayUrl.isEmpty()) {
                                    Text(
                                        "Search or enter URL",
                                        color = forgePalette.textMuted.copy(alpha = 0.5f),
                                        fontSize = 13.sp
                                    )
                                }
                                innerTextField()
                            }
                        )

                        // Progress % while loading (when not editing)
                        if (isLoading && !isEditing) {
                            Text(
                                "$progress%",
                                color = forgePalette.orange,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }

                        if (displayUrl.isNotEmpty() && isEditing) {
                            IconButton(
                                onClick = {
                                    displayUrl = ""
                                    onUrlChange("")
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = "Clear",
                                    tint = forgePalette.textMuted,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // Bookmark
                IconButton(
                    onClick = onBookmarkClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (isBookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = "Bookmark",
                        tint = if (isBookmarked) forgePalette.orange else forgePalette.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Menu
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Menu",
                        tint = forgePalette.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Determinate loading progress bar at the bottom
            if (isLoading) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.BottomCenter),
                    color = forgePalette.orange,
                    trackColor = forgePalette.surface
                )
            }
        }

        // ── Suggestions dropdown ─────────────────────────────────────────
        val query = displayUrl.trim()
        if (isEditing && query.isNotEmpty()) {
            val filtered = suggestions
                .filter {
                    it.label.contains(query, ignoreCase = true) ||
                        it.text.contains(query, ignoreCase = true)
                }
                .distinctBy { it.text }
                .take(8)
            // Always offer a web search as the first row
            val rows = buildList {
                add(OmniboxSuggestion(text = query, label = "Search the web for \"$query\""))
                addAll(filtered)
            }
            if (rows.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(forgePalette.surface)
                ) {
                    items(rows) { suggestion ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { resolveAndNavigate(suggestion.text) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when {
                                    suggestion.isHistory -> Icons.Filled.History
                                    suggestion.text == query && suggestion.label.startsWith("Search") ->
                                        Icons.Filled.Search
                                    else -> Icons.Filled.Bookmark
                                },
                                contentDescription = null,
                                tint = forgePalette.textMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Box(modifier = Modifier.size(10.dp))
                            Column {
                                Text(
                                    suggestion.label,
                                    color = forgePalette.textPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1
                                )
                                if (suggestion.text != query) {
                                    Text(
                                        suggestion.text,
                                        color = forgePalette.textMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
