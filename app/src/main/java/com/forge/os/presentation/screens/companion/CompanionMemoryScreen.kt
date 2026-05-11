package com.forge.os.presentation.screens.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Phase O-5 — Companion Memory Transparency screen.
 *
 * Shows the user every piece of companion data the app holds:
 *  - Episodic memory (conversation summaries)
 *  - Long-term / semantic facts
 *
 * Every item is individually deletable. A "Forget everything" button with a
 * two-step confirm wipes all companion data immediately and permanently.
 *
 * The screen also displays a "Local only — never synced" notice (Phase O-7).
 */

private val Bg        = Color(0xFF0d0d0d)
private val Surface   = Color(0xFF141414)
private val Border    = Color(0xFF2a2a2a)
private val AccentRed = Color(0xFFef4444)
private val TextPri   = Color(0xFFf5f5f5)
private val TextSec   = Color(0xFF888888)
private val Amber     = Color(0xFFf59e0b)

@Composable
fun CompanionMemoryScreen(
    onBack: () -> Unit,
    vm: CompanionMemoryViewModel = hiltViewModel(),
) {
    val episodes by vm.episodes.collectAsState()
    val facts by vm.facts.collectAsState()
    val summary by vm.summary.collectAsState()
    val wipeStep by vm.wipeConfirmStep.collectAsState()

    LaunchedEffect(Unit) { vm.refreshSummary() }

    // Wipe confirmation dialogs
    if (wipeStep == 1) {
        AlertDialog(
            onDismissRequest = { vm.cancelWipe() },
            title = { Text("Delete all companion memory?", color = TextPri) },
            text = {
                Text(
                    "This will permanently delete ${summary.episodeCount} episode(s) and " +
                            "${summary.factCount} fact(s). This cannot be undone.",
                    color = TextSec,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmWipe() }) {
                    Text("Yes, delete everything", color = AccentRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelWipe() }) { Text("Cancel", color = TextSec) }
            },
            containerColor = Color(0xFF1a1a1a),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "← Back",
                color = TextSec,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onBack() }.padding(end = 12.dp),
            )
            Text(
                text = "Companion Memory",
                color = TextPri,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(Modifier.height(8.dp))

        // Phase O-7 — Local-only notice
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Amber.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .background(Amber.copy(alpha = 0.06f), RoundedCornerShape(6.dp))
                .padding(10.dp),
        ) {
            Text(
                text = "🔒  Local only · Never synced · Stored on this device only",
                color = Amber,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }

        Spacer(Modifier.height(12.dp))

        // Summary row
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatChip("${summary.episodeCount} episodes")
            StatChip("${summary.factCount} facts")
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Episodes ─────────────────────────────────────────────────────
            if (episodes.isNotEmpty()) {
                item {
                    SectionHeader("Conversation Episodes")
                }
                items(episodes, key = { it.id }) { ep ->
                    EpisodeCard(
                        summary = ep.summary,
                        timestamp = ep.timestamp,
                        topics = ep.keyTopics,
                        onDelete = { vm.deleteEpisode(ep.id) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // ── Facts ─────────────────────────────────────────────────────────
            if (facts.isNotEmpty()) {
                item {
                    SectionHeader("Stored Facts")
                }
                items(facts, key = { it.first }) { (key, value) ->
                    FactRow(
                        factKey = key,
                        value = value,
                        onDelete = { vm.deleteFact(key) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            if (episodes.isEmpty() && facts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No companion memory stored yet.",
                            color = TextSec,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Forget-everything button
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, AccentRed.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .background(AccentRed.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .clickable { vm.requestWipe() }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Forget Everything",
                color = AccentRed,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        color = TextSec,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun StatChip(label: String) {
    Box(
        modifier = Modifier
            .border(1.dp, Border, RoundedCornerShape(4.dp))
            .background(Surface, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(label, color = TextSec, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EpisodeCard(
    summary: String,
    timestamp: Long,
    topics: List<String>,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border, RoundedCornerShape(8.dp))
            .background(Surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = java.text.SimpleDateFormat("MMM d, yyyy · HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(timestamp)),
            color = TextSec,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(summary, color = TextPri, fontSize = 13.sp)
        if (topics.isNotEmpty()) {
            Text(
                text = topics.joinToString(" · "),
                color = TextSec,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "Delete",
                color = AccentRed,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable { onDelete() },
            )
        }
    }
}

@Composable
private fun FactRow(factKey: String, value: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Border, RoundedCornerShape(6.dp))
            .background(Surface, RoundedCornerShape(6.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(factKey, color = TextSec, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(value, color = TextPri, fontSize = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "×",
            color = AccentRed,
            fontSize = 16.sp,
            modifier = Modifier.clickable { onDelete() },
        )
    }
}
