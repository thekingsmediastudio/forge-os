package com.forge.os.presentation.screens.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.data.api.AiApiManager
import com.forge.os.presentation.theme.forgePalette

/**
 * Shared model picker component used by channels and cron jobs.
 * Surfaces both built-in providers and custom endpoints with their live catalogs.
 */

@Composable
fun ModelPickerRow(
    override: Pair<String, String>?,
    labelPrefix: String = "MODEL",
    defaultLabel: String = "(default route — auto-pick + fallback)",
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(forgePalette.surface, RoundedCornerShape(4.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(labelPrefix, color = forgePalette.textMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        val label = override?.let {
            val providerLabel = if (it.first.startsWith("custom:")) "custom" else it.first
            "$providerLabel · ${it.second}"
        } ?: defaultLabel
        Text(label, color = forgePalette.textPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.weight(1f))
        if (override != null && onClear != null) {
            TextButton(onClick = onClear) {
                Text("CLEAR", color = forgePalette.orange,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        Text("CHANGE ▸", color = forgePalette.orange,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerDialog(
    title: String = "Pick AI Model",
    availableModels: suspend () -> List<AiApiManager.Quad>,
    initial: Pair<String, String>?,
    onDismiss: () -> Unit,
    onSave: (providerKey: String, model: String) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<List<AiApiManager.Quad>>(emptyList()) }

    LaunchedEffect(Unit) {
        loading = true
        error = null
        try {
            catalog = availableModels()
        } catch (t: Throwable) {
            error = t.message ?: "could not load model list"
        } finally {
            loading = false
        }
    }

    var selectedProviderKey by remember { mutableStateOf(initial?.first.orEmpty()) }
    var selectedModel by remember { mutableStateOf(initial?.second.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = forgePalette.surface,
        title = { Text(title, color = forgePalette.textPrimary, fontFamily = FontFamily.Monospace, fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.heightIn(max = 480.dp)) {
                when {
                    loading -> {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 20.dp)) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = forgePalette.orange,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Probing catalogs…",
                                color = forgePalette.textMuted,
                                fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        }
                    }
                    catalog.isEmpty() -> {
                        Text(
                            error?.let { "Error: $it" } ?:
                                "No providers with keys found. Add keys in Settings first.",
                            color = forgePalette.textMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        )
                    }
                    else -> {
                        Text(
                            "Forge will try this model first, falling back to the global route on any error.",
                            color = forgePalette.textMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        val groups = remember(catalog) {
                            catalog.groupBy {
                                Triple(it.providerKey, it.providerLabel, it.kind)
                            }.toList().sortedWith(
                                compareBy(
                                    { it.first.third != "builtin" },
                                    { it.first.second.lowercase() },
                                )
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            groups.forEach { (header, models) ->
                                val (providerKey, providerLabel, kind) = header
                                item("h_${providerKey}") {
                                    Text(
                                        "${providerLabel.uppercase()} · $kind",
                                        color = forgePalette.orange,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                                    )
                                }
                                models.distinctBy { it.model }.forEach { m ->
                                    item("m_${providerKey}_${m.model}") {
                                        val selected = selectedProviderKey == providerKey && selectedModel == m.model
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .background(
                                                    if (selected) forgePalette.orange.copy(alpha = 0.15f)
                                                    else Color.Transparent,
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .border(
                                                    1.dp,
                                                    if (selected) forgePalette.orange else forgePalette.border,
                                                    RoundedCornerShape(4.dp),
                                                )
                                                .clickable {
                                                    selectedProviderKey = providerKey
                                                    selectedModel = m.model
                                                }
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                        ) {
                                            Text(
                                                m.model,
                                                color = if (selected) forgePalette.textPrimary else forgePalette.textMuted,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 12.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedProviderKey.isNotBlank() && selectedModel.isNotBlank()) {
                        onSave(selectedProviderKey, selectedModel)
                    }
                },
                enabled = selectedProviderKey.isNotBlank() && selectedModel.isNotBlank(),
            ) { Text("SELECT", color = forgePalette.success, fontFamily = FontFamily.Monospace) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = forgePalette.textMuted, fontFamily = FontFamily.Monospace)
            }
        },
    )
}
