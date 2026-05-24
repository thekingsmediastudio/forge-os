package com.forge.os.presentation.screens.directives

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.components.*
import com.forge.os.presentation.theme.ForgeTokens.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectivesScreen(
    onBack: () -> Unit,
    viewModel: DirectivesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    ForgeScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            ForgeTopBar(
                title = "DIRECTIVES",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Add Rule", tint = Colors.TextPrimary)
                    }
                }
            )

            if (state.isLoading && state.rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Colors.Accent)
                }
            } else if (state.rules.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Rule,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = Colors.TextSecondary.copy(alpha = 0.2f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "NO ACTIVE PROTOCOLS",
                            color = Colors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 2.sp
                        )
                        Spacer(Modifier.height(24.dp))
                        ForgeButton(
                            text = "INITIALIZE DIRECTIVE",
                            onClick = { showAddDialog = true }
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            "CORE PROTOCOLS",
                            color = Colors.TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.5.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(state.rules) { rule ->
                        DirectiveItem(
                            rule = rule,
                            onToggle = { enabled -> viewModel.toggleRule(rule.id, enabled) },
                            onDelete = { viewModel.deleteRule(rule.id) }
                        )
                    }
                    item {
                        Spacer(Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = Colors.BgSurface,
            title = { 
                Text(
                    "NEW DIRECTIVE", 
                    color = Colors.Accent, 
                    fontSize = 14.sp, 
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                ) 
            },
            text = {
                Column {
                    Text(
                        "Input a mandatory protocol for the agent to follow.",
                        color = Colors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("e.g. Always respond in Dutch...", color = Colors.TextSecondary) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Colors.Accent,
                            unfocusedBorderColor = Colors.Border,
                            focusedTextColor = Colors.TextPrimary,
                            unfocusedTextColor = Colors.TextPrimary
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.addRule(text)
                    showAddDialog = false
                }) {
                    Text("INJECT", color = Colors.Accent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("CANCEL", color = Colors.TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun DirectiveItem(
    rule: com.forge.os.domain.directives.AgentDirective,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    ForgeCard(padding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "ID: ${rule.id.take(8).uppercase()}",
                    color = Colors.TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = rule.content,
                    color = if (rule.enabled) Colors.TextPrimary else Colors.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (rule.scope == "global") Colors.Accent.copy(alpha = 0.1f) 
                                else Color.Cyan.copy(alpha = 0.1f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            rule.scope.uppercase(),
                            color = if (rule.scope == "global") Colors.Accent else Color.Cyan,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
            
            Switch(
                checked = rule.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Colors.Accent,
                    checkedTrackColor = Colors.Accent.copy(alpha = 0.5f),
                    uncheckedThumbColor = Colors.TextSecondary,
                    uncheckedTrackColor = Colors.Border
                )
            )
            
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Colors.Error.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
