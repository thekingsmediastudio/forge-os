package com.forge.os.presentation.screens.governance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockClock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.components.ForgeButton
import com.forge.os.presentation.components.ForgeCard
import com.forge.os.presentation.components.ForgeListRow
import com.forge.os.presentation.components.ForgeScreenScaffold
import com.forge.os.presentation.components.ForgeTopBar
import com.forge.os.presentation.theme.ForgeTokens.Colors

@Composable
fun CallerManagementScreen(
    onBack: () -> Unit,
    viewModel: GovernanceViewModel = hiltViewModel()
) {
    val callers by viewModel.authorizedCallers.collectAsState()

    ForgeScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            ForgeTopBar(
                title = "APP GOVERNANCE",
                onBack = onBack
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text(
                        "EXTERNAL INTEGRATIONS",
                        color = Colors.TextTertiary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                if (callers.isEmpty()) {
                    item {
                        ForgeCard(padding = PaddingValues(20.dp)) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text("No external apps connected.", color = Colors.TextDim, fontSize = 13.sp)
                            }
                        }
                    }
                }

                items(callers, key = { it.packageName }) { caller ->
                    ForgeCard(padding = PaddingValues(16.dp)) {
                        Column {
                            ForgeListRow(
                                title = caller.packageName,
                                subtitle = "${caller.permissions.size} permissions granted",
                                icon = Icons.Outlined.LockClock,
                                iconColor = Colors.Accent,
                                onClick = {}
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                caller.permissions.forEach { p ->
                                    Box(
                                        modifier = Modifier
                                            .background(Colors.BgSurface, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(p, color = Colors.TextSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            ForgeButton(
                                text = "REVOKE ALL",
                                onClick = { viewModel.revokeCaller(caller.packageName) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
