package com.forge.os.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.domain.channel.Channel
import com.forge.os.presentation.theme.forgePalette

/**
 * Channel switcher dropdown for chat header.
 * Shows current channel and allows switching between channels.
 */
@Composable
fun ChannelSwitcher(
    currentChannel: Channel,
    channels: List<Channel>,
    onChannelSelect: (Channel) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        // Current channel pill
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .clickable { expanded = true },
            color = forgePalette.surface,
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Channel icon
                Text(
                    currentChannel.icon,
                    fontSize = 14.sp
                )
                // Channel name
                Text(
                    currentChannel.name,
                    color = forgePalette.textPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                // Dropdown arrow
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Switch channel",
                    tint = forgePalette.textMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Dropdown menu
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(forgePalette.surface)
        ) {
            // All channels (General + user channels)
            val allChannels = listOf(Channel.GENERAL) + channels.filter { !it.isDefault }
            
            allChannels.forEach { channel ->
                DropdownMenuItem(
                    text = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Channel icon
                            Text(channel.icon, fontSize = 16.sp)

                            // Channel name
                            Text(
                                channel.name,
                                color = forgePalette.textPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f)
                            )

                            // Check mark for current
                            if (channel.id == currentChannel.id) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = forgePalette.orange,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClick = {
                        onChannelSelect(channel)
                        expanded = false
                    },
                    modifier = Modifier.background(
                        if (channel.id == currentChannel.id)
                            forgePalette.orange.copy(alpha = 0.1f)
                        else
                            Color.Transparent
                    )
                )
            }
        }
    }
}

/**
 * Compact channel indicator (no dropdown).
 * Used when channels are disabled or in compact layouts.
 */
@Composable
fun ChannelIndicator(
    channel: Channel,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(channel.icon, fontSize = 12.sp)
        Text(
            channel.name,
            color = forgePalette.textMuted,
            fontSize = 12.sp
        )
    }
}
