package com.forge.os.presentation.screens.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.doctor.CheckStatus
import com.forge.os.domain.doctor.DoctorCheck
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold

@Composable
fun DoctorScreen(onBack: () -> Unit, viewModel: DoctorViewModel = hiltViewModel()) {
    val report by viewModel.report.collectAsState()
    val busy by viewModel.busy.collectAsState()

    ModuleScaffold(title = "DOCTOR", onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.runChecks() }, enabled = !busy,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ForgeOsPalette.Orange),
                ) {
                    Text(if (busy) "SCANNING..." else "RUN CHECKS",
                        fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(10.dp))

            val r = report
            when {
                r == null -> Text("Initialising...",
                    color = ForgeOsPalette.TextMuted, fontFamily = FontFamily.Monospace)
                else -> {
                    Text("${r.checks.size} checks  •  " +
                        "${r.checks.count { it.status == CheckStatus.OK }} ok  •  " +
                        "${r.checks.count { it.status == CheckStatus.WARN }} warn  •  " +
                        "${r.checks.count { it.status == CheckStatus.FAIL }} fail",
                        color = ForgeOsPalette.TextMuted,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(r.checks, key = { it.id }) { c -> CheckRow(c) { viewModel.fix(c.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(c: DoctorCheck, onFix: () -> Unit) {
    val (color, bg, symbol) = when (c.status) {
        CheckStatus.OK -> Triple(ForgeOsPalette.Success, ForgeOsPalette.SuccessBg, "✓")
        CheckStatus.WARN -> Triple(ForgeOsPalette.Orange, Color(0x22ff8800), "!")
        CheckStatus.FAIL -> Triple(ForgeOsPalette.Danger, ForgeOsPalette.DangerBg, "✗")
    }
    Column(
        Modifier.fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(bg, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(symbol, color = color, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(c.title, color = ForgeOsPalette.TextPrimary,
                fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                modifier = Modifier.weight(1f))
            if (c.fixable && c.status != CheckStatus.OK) {
                TextButton(onClick = onFix) {
                    Text("FIX", color = ForgeOsPalette.Orange,
                        fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
        Text(c.detail, color = ForgeOsPalette.TextMuted,
            fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}
