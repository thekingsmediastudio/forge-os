package com.forge.os.presentation.screens.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.forgePalette

/**
 * Combined address bar + navigation bar in a single row.
 * Back/forward/refresh on the left, URL input center, bookmark/menu on right.
 * Shows a thin loading progress bar at the bottom when loading.
 */
@Composable
fun BrowserAddressBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    isSecure: Boolean = false,
    isLoading: Boolean = false,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    isBookmarked: Boolean = false,
    onBackClick: () -> Unit = {},
    onForwardClick: () -> Unit = {},
    onRefreshClick: () -> Unit = {},
    onBookmarkClick: () -> Unit = {},
    onMenuClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var displayUrl by remember(url) { mutableStateOf(url) }

    Box(modifier = modifier) {
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
            IconButton(
                onClick = onRefreshClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Refresh",
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
                    .background(forgePalette.surface)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isEditing && isSecure) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "Secure",
                            tint = forgePalette.success,
                            modifier = Modifier.size(14.dp)
                        )
                        Box(modifier = Modifier.size(6.dp))
                    }

                    BasicTextField(
                        value = displayUrl,
                        onValueChange = { newValue ->
                            displayUrl = newValue
                            onUrlChange(newValue)
                        },
                        modifier = Modifier.weight(1f),
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
                            onGo = {
                                onNavigate(displayUrl)
                                isEditing = false
                            }
                        ),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            if (displayUrl.isEmpty() && !isEditing) {
                                Text(
                                    "Search or enter URL",
                                    color = forgePalette.textMuted.copy(alpha = 0.5f),
                                    fontSize = 13.sp
                                )
                            }
                            innerTextField()
                        }
                    )

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

        // Loading progress bar at the bottom
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter),
                color = forgePalette.orange,
                trackColor = forgePalette.surface
            )
        }
    }
}
