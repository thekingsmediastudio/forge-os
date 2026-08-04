package com.forge.os.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.forgePalette

/**
 * Search bar matching chat input styling — pill shape, accent focus, clear button.
 */
@Composable
fun ForgeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search…",
    trailingContent: (@Composable () -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = forgePalette.surfaceSunken,
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = "Search",
                tint = forgePalette.textDim,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = forgePalette.textPrimary,
                    fontSize = 14.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(forgePalette.orange),
                decorationBox = { innerTextField ->
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            color = forgePalette.textDim,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            )
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear",
                    tint = forgePalette.textDim,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChange("") }
                )
            }
            if (trailingContent != null) {
                Spacer(Modifier.width(8.dp))
                trailingContent()
            }
        }
    }
}

/**
 * Horizontal scrolling row of filter chips.
 */
@Composable
fun FilterChipRow(
    chips: List<FilterChipData>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chips.forEach { chip ->
            FilterChip(
                data = chip,
                onClick = chip.onClick
            )
        }
    }
}

data class FilterChipData(
    val label: String,
    val icon: String? = null,
    val isActive: Boolean = false,
    val onClick: () -> Unit = {}
)

@Composable
private fun FilterChip(
    data: FilterChipData,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = if (data.isActive) forgePalette.orange else forgePalette.surface,
        shape = RoundedCornerShape(16.dp),
        border = if (!data.isActive) androidx.compose.foundation.BorderStroke(1.dp, forgePalette.border) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (data.icon != null) {
                Text(data.icon, fontSize = 12.sp)
                Spacer(Modifier.width(4.dp))
            }
            Text(
                data.label,
                color = if (data.isActive) forgePalette.onAccent else forgePalette.textMuted,
                fontSize = 12.sp,
                fontWeight = if (data.isActive) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/**
 * Budget/progress bar with warning threshold color change.
 */
@Composable
fun BudgetBar(
    current: Double,
    total: Double,
    modifier: Modifier = Modifier,
    warningThreshold: Float = 0.8f,
    height: androidx.compose.ui.unit.Dp = 6.dp
) {
    val progress = if (total > 0) (current / total).coerceIn(0.0, 1.0).toFloat() else 0f
    val barColor = when {
        progress >= 1f -> forgePalette.danger
        progress >= warningThreshold -> forgePalette.warning
        else -> forgePalette.success
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "Budget",
                color = forgePalette.textMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                "$${"%.2f".format(current)} / $${"%.2f".format(total)}",
                color = forgePalette.textDim,
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(forgePalette.border, RoundedCornerShape(height / 2))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(barColor, RoundedCornerShape(height / 2))
            )
        }
        if (progress >= warningThreshold) {
            Spacer(Modifier.height(6.dp))
            Text(
                if (progress >= 1f) "⚠️ Budget exceeded" else "⚠️ Approaching budget limit",
                color = forgePalette.warning,
                fontSize = 10.sp
            )
        }
    }
}

/**
 * Simple bar chart drawn with Canvas — for cost/spending visualization.
 */
@Composable
fun SparkBarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    barColor: Color = forgePalette.orange,
    maxHeight: androidx.compose.ui.unit.Dp = 80.dp
) {
    val maxVal = values.maxOrNull() ?: 1f

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxHeight),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEach { value ->
                val fraction = if (maxVal > 0) value / maxVal else 0f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(fraction.coerceAtLeast(0.05f))
                        .background(
                            barColor.copy(alpha = 0.7f),
                            RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)
                        )
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            labels.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    color = forgePalette.textDim,
                    fontSize = 9.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

/**
 * Status dot indicator — colored circle with optional pulse.
 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 8.dp,
    pulse: Boolean = false
) {
    if (pulse) {
        PulsingBadge(color = color, modifier = modifier, size = size)
    } else {
        Box(
            modifier = modifier
                .size(size)
                .background(color, androidx.compose.foundation.shape.CircleShape)
        )
    }
}
