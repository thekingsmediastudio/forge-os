package com.forge.os.presentation.screens.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.forgePalette

/**
 * Slim browser tab strip with ember accent for the active tab.
 * Tabs size to content (min 100dp, max 160dp) instead of fixed width.
 */
@Composable
fun BrowserTabStrip(
    tabs: List<BrowserTabUi>,
    onTabSelect: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(forgePalette.bg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back to chat
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to chat",
                tint = forgePalette.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }

        // Tab list
        LazyRow(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tabs, key = { it.id }) { tab ->
                BrowserTabItem(
                    tab = tab,
                    onSelect = { onTabSelect(tab.id) },
                    onClose = { onTabClose(tab.id) }
                )
            }
        }

        // Add new tab
        IconButton(
            onClick = onNewTab,
            modifier = Modifier
                .size(36.dp)
                .padding(end = 4.dp)
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "New tab",
                tint = forgePalette.textMuted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun BrowserTabItem(
    tab: BrowserTabUi,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (tab.isActive) forgePalette.surface else forgePalette.bg
    val textColor = if (tab.isActive) forgePalette.textPrimary else forgePalette.textMuted

    Row(
        modifier = modifier
            .height(32.dp)
            .widthIn(min = 100.dp, max = 160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Active indicator dot
        if (tab.isActive) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(forgePalette.orange, CircleShape)
            )
            Box(modifier = Modifier.width(6.dp))
        }

        Text(
            text = tab.title.ifEmpty { "New Tab" },
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = textColor
        )

        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(20.dp)
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close tab",
                tint = forgePalette.textMuted.copy(alpha = 0.6f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/** UI model for the tab strip — avoids duplicating the ViewModel's data class. */
data class BrowserTabUi(
    val id: String,
    val title: String,
    val url: String,
    val isActive: Boolean = false
)
