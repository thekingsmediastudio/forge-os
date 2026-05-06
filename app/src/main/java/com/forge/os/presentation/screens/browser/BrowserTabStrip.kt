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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 3: Browser UI Redesign
 * 
 * Chrome-like tab strip with:
 * - Scrollable tab list
 * - Active tab highlighting
 * - Close button on each tab
 * - Add new tab button
 */
data class BrowserTab(
    val id: String,
    val title: String,
    val url: String,
    val isActive: Boolean = false
)

@Composable
fun BrowserTabStrip(
    tabs: List<BrowserTab>,
    onTabSelect: (String) -> Unit,
    onTabClose: (String) -> Unit,
    onNewTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab list
        LazyRow(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
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

        // Add new tab button
        IconButton(
            onClick = onNewTab,
            modifier = Modifier
                .size(40.dp)
                .padding(end = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "New tab",
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun BrowserTabItem(
    tab: BrowserTab,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .width(200.dp)
            .background(
                color = if (tab.isActive)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Tab title
        Text(
            text = tab.title.ifEmpty { "New Tab" },
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Close button
        IconButton(
            onClick = onClose,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Close tab",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
