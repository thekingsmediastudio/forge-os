package com.forge.os.presentation.screens.cost

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.data.api.CostMeter
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold

@Composable
fun CostStatsScreen(
    onBack: () -> Unit,
    viewModel: CostStatsViewModel = hiltViewModel(),
) {
    val snap by viewModel.snapshot.collectAsState()
    val prices by viewModel.prices.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var editing: Pair<String, CostMeter.PricePoint>? by remember { mutableStateOf(null) }
    var addingNew by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "COST + PRICES",
        onBack = onBack,
        actions = {
            TextButton(onClick = { addingNew = true }) {
                Text("+ PRICE", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            // Totals
            Section("TOTALS") {
                statRow("Lifetime", "$${"%.4f".format(snap.lifetimeUsd)} (${snap.callCount} calls)")
                statRow("Session", "$${"%.4f".format(snap.sessionUsd)} (${snap.sessionCalls} calls)")
                // Phase M-3 — agent vs companion split.
                statRow("Agent",     "$${"%.4f".format(snap.agentUsd)} (${snap.agentCalls} calls)")
                statRow("Companion", "$${"%.4f".format(snap.companionUsd)} (${snap.companionCalls} calls)")
                statRow("Last call", "$${"%.4f".format(snap.lastCallUsd)} (in ${snap.lastInputTokens}, out ${snap.lastOutputTokens} tok)")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("RESET SESSION", color = ForgeOsPalette.Orange,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.clickable { viewModel.resetSession() })
                    Text("RESET LIFETIME", color = ForgeOsPalette.Danger,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        modifier = Modifier.clickable { viewModel.resetLifetime() })
                }
            }

            Spacer(Modifier.height(16.dp))
            Section("BY MODEL") {
                if (snap.perModel.isEmpty()) {
                    Text("No usage yet.", color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                } else {
                    snap.perModel.entries.sortedByDescending { it.value.usd }.forEach { (model, st) ->
                        statRow(model, "$${"%.4f".format(st.usd)}  •  ${st.calls} calls  •  in ${st.inputTokens}/out ${st.outputTokens}")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Section("PRICES (USD per 1M tokens)") {
                Text("Tap a row to edit. Unknown models fall back to 1.00 / 3.00.",
                    color = ForgeOsPalette.TextMuted,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                Spacer(Modifier.height(8.dp))
                prices.entries.sortedBy { it.key }.forEach { (model, pp) ->
                    Row(Modifier.fillMaxWidth()
                        .clickable { editing = model to pp }
                        .padding(vertical = 4.dp)) {
                        Text(model, color = ForgeOsPalette.TextPrimary,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            modifier = Modifier.weight(1f))
                        Text("in ${"%.2f".format(pp.inputPerM)}  /  out ${"%.2f".format(pp.outputPerM)}",
                            color = ForgeOsPalette.TextMuted,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    }
                }
            }
            SnackbarHost(snackbar)
        }
    }

    editing?.let { (model, pp) ->
        PriceEditor(
            initialModel = model,
            initial = pp,
            allowNameEdit = false,
            onSave = { name, i, o -> viewModel.setPrice(name, i, o); editing = null },
            onRemove = { viewModel.removePrice(model); editing = null },
            onCancel = { editing = null },
        )
    }
    if (addingNew) {
        PriceEditor(
            initialModel = "",
            initial = CostMeter.PricePoint(1.0, 3.0),
            allowNameEdit = true,
            onSave = { name, i, o -> viewModel.setPrice(name, i, o); addingNew = false },
            onRemove = null,
            onCancel = { addingNew = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(12.dp),
    ) {
        Text(title, color = ForgeOsPalette.Orange, fontFamily = FontFamily.Monospace,
            fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun statRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            modifier = Modifier.weight(1f))
        Text(value, color = ForgeOsPalette.TextPrimary,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

@Composable
private fun PriceEditor(
    initialModel: String,
    initial: CostMeter.PricePoint,
    allowNameEdit: Boolean,
    onSave: (String, Double, Double) -> Unit,
    onRemove: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initialModel) }
    var inp by remember { mutableStateOf(initial.inputPerM.toString()) }
    var out by remember { mutableStateOf(initial.outputPerM.toString()) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (allowNameEdit) "Add price" else "Edit $initialModel") },
        text = {
            Column {
                if (allowNameEdit) {
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Model name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(value = inp, onValueChange = { inp = it },
                    label = { Text("Input USD / 1M tokens") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = out, onValueChange = { out = it },
                    label = { Text("Output USD / 1M tokens") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val i = inp.toDoubleOrNull() ?: 0.0
                val o = out.toDoubleOrNull() ?: 0.0
                onSave(if (allowNameEdit) name else initialModel, i, o)
            }) { Text("SAVE") }
        },
        dismissButton = {
            Row {
                if (onRemove != null) {
                    TextButton(onClick = onRemove) { Text("REMOVE") }
                }
                TextButton(onClick = onCancel) { Text("CANCEL") }
            }
        }
    )
}
