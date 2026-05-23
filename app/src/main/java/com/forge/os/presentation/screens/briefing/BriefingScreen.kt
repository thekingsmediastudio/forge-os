package com.forge.os.presentation.screens.briefing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.components.ForgeScreenScaffold
import com.forge.os.presentation.components.ForgeTopBar
import com.forge.os.presentation.components.ForgeCard
import com.forge.os.presentation.screens.pulse.PulseViewModel
import com.forge.os.presentation.theme.ForgeTokens.Colors

@Composable
fun BriefingScreen(
    onBack: () -> Unit,
    viewModel: PulseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    ForgeScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            ForgeTopBar(
                title = "NEURAL BRIEFING",
                onBack = onBack
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    // Hero Card matching briefing.html
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Colors.Accent.copy(alpha = 0.15f), RoundedCornerShape(32.dp))
                            .border(1.dp, Colors.Accent.copy(alpha = 0.2f), RoundedCornerShape(32.dp))
                            .padding(24.dp)
                    ) {
                        Column {
                            Text(
                                "SYSTEM NOMINAL",
                                color = Colors.Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                "Neural Intelligence is primed.",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black,
                                lineHeight = 32.sp,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )
                            Text(
                                "Projects: ${state.projectCount}   |   Cron Jobs: ${state.activeCronCount}   |   Sub-Agents: ${state.activeAgentCount}",
                                color = Colors.TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(bottom = 24.dp)
                            )
                            
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Column {
                                    Text(
                                        "\$${"%.2f".format(state.dailySpend)}",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        "DAILY SPEND",
                                        color = Colors.TextTertiary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                                Box(
                                    Modifier
                                        .width(1.dp)
                                        .height(30.dp)
                                        .background(Color.White.copy(alpha = 0.1f))
                                )
                                Column {
                                    Text(
                                        state.system.overallHealth.name,
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                    Text(
                                        "AGENT STATE",
                                        color = Colors.TextTertiary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }

                val neuralEvents = (state.recentAgentActions.map { "AGENT" to it } + 
                                   state.backgroundLogs.map { "SYSTEM" to it.label })
                                   .take(10)

                if (neuralEvents.isEmpty()) {
                    item {
                        ForgeCard(padding = PaddingValues(16.dp)) {
                            Text(
                                "No neural activity monitored.",
                                color = Colors.TextDim,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    items(neuralEvents.size) { idx ->
                        val (type, label) = neuralEvents[idx]
                        Row(modifier = Modifier.padding(start = 12.dp)) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .size(8.dp)
                                    .background(if (type == "AGENT") Colors.Accent else Colors.Success, CircleShape)
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    label,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Type: $type",
                                    color = Colors.TextTertiary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
