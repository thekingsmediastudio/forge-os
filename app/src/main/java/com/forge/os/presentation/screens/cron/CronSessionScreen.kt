package com.forge.os.presentation.screens.cron

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.cron.CronExecution
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import com.forge.os.presentation.screens.common.StatusPill
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val tsFmt = SimpleDateFormat("MMM d HH:mm:ss", Locale.US)

/**
 * Live cron session viewer.
 *
 * Shows a streaming feed of every recent execution across all jobs, with:
 *   - Filters: all / success / failure
 *   - Search by job name or output text
 *   - Pick a single job (or "all")
 *   - Live tail toggle (auto-refresh every ~3 s)
 *   - Stats header (total runs, success rate, avg duration, last 24 h, due now)
 *   - Tap an entry to view full untruncated output, copy, or re-run the job
 *   - Clear all history (with confirmation) and export as JSON to workspace
 */
@Composable
fun CronSessionScreen(
    onBack: () -> Unit,
    viewModel: CronSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var inspecting: CronExecution? by remember { mutableStateOf(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    LaunchedEffect(state.liveTail) {
        while (state.liveTail) {
            delay(3_000)
            viewModel.refresh()
        }
    }

    ModuleScaffold(
        title = "CRON SESSION",
        onBack = onBack,
        actions = {
            IconButton(onClick = { viewModel.refresh() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Refresh, "Refresh",
                    tint = ForgeOsPalette.Orange, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { confirmClear = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Clear, "Clear history",
                    tint = ForgeOsPalette.Danger, modifier = Modifier.size(18.dp))
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item { StatsHeader(state) }
                item { ControlsBar(state, viewModel) }
                item {
                    Text("LIVE FEED (${state.filtered.size})",
                        color = ForgeOsPalette.Orange,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(top = 6.dp))
                }
                if (state.filtered.isEmpty()) {
                    item {
                        Text("No matching executions.",
                            color = ForgeOsPalette.TextMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.padding(8.dp))
                    }
                }
                items(state.filtered, key = { it.startedAt.toString() + it.jobId }) { exec ->
                    ExecutionRow(exec, onClick = { inspecting = exec })
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
            SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) {
                Snackbar(containerColor = ForgeOsPalette.Surface2,
                    contentColor = ForgeOsPalette.TextPrimary) { Text(it.visuals.message) }
            }
        }
    }

    val ins = inspecting
    if (ins != null) {
        OutputDialog(
            exec = ins,
            onRunAgain = { viewModel.runJobAgain(ins.jobId) },
            onDismiss = { inspecting = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = ForgeOsPalette.Surface,
            title = {
                Text("Clear all cron history?", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            },
            text = {
                Text("This permanently deletes every execution record on this device. " +
                     "Active scheduled jobs are NOT affected.",
                    color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); confirmClear = false }) {
                    Text("DELETE", color = ForgeOsPalette.Danger,
                        fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("CANCEL", color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace)
                }
            },
        )
    }
}

@Composable
private fun StatsHeader(state: CronSessionUiState) {
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Text("SESSION STATS", color = ForgeOsPalette.Orange,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("runs ${state.totalRuns}",
                ForgeOsPalette.TextPrimary, ForgeOsPalette.Surface2)
            StatusPill("ok ${state.successRatePct}%",
                ForgeOsPalette.Success, ForgeOsPalette.Surface2)
            StatusPill("avg ${state.avgDurationMs}ms",
                ForgeOsPalette.TextPrimary, ForgeOsPalette.Surface2)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("24h ${state.last24hCount}",
                ForgeOsPalette.TextPrimary, ForgeOsPalette.Surface2)
            StatusPill("fail ${state.failureCount}",
                ForgeOsPalette.Danger, ForgeOsPalette.DangerBg)
            StatusPill("due ${state.dueCount}",
                ForgeOsPalette.Orange, ForgeOsPalette.Surface2)
            StatusPill("active ${state.activeJobs}",
                ForgeOsPalette.TextPrimary, ForgeOsPalette.Surface2)
        }
    }
}

@Composable
private fun ControlsBar(state: CronSessionUiState, vm: CronSessionViewModel) {
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE TAIL",
                color = if (state.liveTail) ForgeOsPalette.Success else ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = state.liveTail,
                onCheckedChange = { vm.setLiveTail(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = ForgeOsPalette.Success,
                    checkedTrackColor = ForgeOsPalette.Success.copy(alpha = 0.3f),
                    uncheckedThumbColor = ForgeOsPalette.TextDim,
                    uncheckedTrackColor = ForgeOsPalette.Surface2,
                ),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.exportHistory() }) {
                Text("EXPORT", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Row {
            FilterChip("ALL", state.filter == SessionFilter.ALL) {
                vm.setFilter(SessionFilter.ALL)
            }
            Spacer(Modifier.width(4.dp))
            FilterChip("OK", state.filter == SessionFilter.SUCCESS) {
                vm.setFilter(SessionFilter.SUCCESS)
            }
            Spacer(Modifier.width(4.dp))
            FilterChip("FAIL", state.filter == SessionFilter.FAILURE) {
                vm.setFilter(SessionFilter.FAILURE)
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = state.search,
            onValueChange = { vm.setSearch(it) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            ),
        )
        if (state.jobNames.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("JOB FILTER", color = ForgeOsPalette.TextMuted,
                fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                FilterChip("all", state.selectedJobId == null) { vm.selectJob(null) }
                state.jobNames.forEach { (id, name) ->
                    Spacer(Modifier.width(4.dp))
                    FilterChip(name.take(14), state.selectedJobId == id) { vm.selectJob(id) }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (active) ForgeOsPalette.Orange.copy(alpha = 0.18f) else ForgeOsPalette.Surface2,
                RoundedCornerShape(4.dp))
            .border(1.dp,
                if (active) ForgeOsPalette.Orange else ForgeOsPalette.Border,
                RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label,
            color = if (active) ForgeOsPalette.Orange else ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}

@Composable
private fun ExecutionRow(exec: CronExecution, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(4.dp))
            .border(1.dp,
                if (exec.success) ForgeOsPalette.Border else ForgeOsPalette.Danger.copy(alpha = 0.5f),
                RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (exec.success) "✓" else "✗",
            color = if (exec.success) ForgeOsPalette.Success else ForgeOsPalette.Danger,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(exec.jobName, color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            Text(
                "${tsFmt.format(Date(exec.startedAt))} • ${exec.durationMs}ms" +
                    (exec.error?.let { " • $it" } ?: ""),
                color = if (exec.success) ForgeOsPalette.TextDim else ForgeOsPalette.Danger,
                fontFamily = FontFamily.Monospace, fontSize = 9.sp,
            )
        }
    }
}

@Composable
private fun OutputDialog(
    exec: CronExecution,
    onRunAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ForgeOsPalette.Surface,
        title = {
            Text("${if (exec.success) "✓" else "✗"} ${exec.jobName}",
                color = if (exec.success) ForgeOsPalette.Success else ForgeOsPalette.Danger,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())) {
                Text("${tsFmt.format(Date(exec.startedAt))} • ${exec.durationMs}ms",
                    color = ForgeOsPalette.TextDim,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                exec.error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text("ERROR: $it", color = ForgeOsPalette.Danger,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text("OUTPUT", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 1.sp)
                Box(Modifier.fillMaxWidth()
                    .background(ForgeOsPalette.Bg, RoundedCornerShape(4.dp))
                    .padding(8.dp)) {
                    Text(
                        if (exec.output.isBlank()) "(no output)" else exec.output,
                        color = ForgeOsPalette.TextPrimary,
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(exec.output))
                }) {
                    Text("COPY", color = ForgeOsPalette.Orange,
                        fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { onRunAgain(); onDismiss() }) {
                    Text("RUN AGAIN", color = ForgeOsPalette.Success,
                        fontFamily = FontFamily.Monospace)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace)
            }
        },
    )
}
