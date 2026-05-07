package com.forge.os.presentation.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
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
import com.forge.os.presentation.theme.forgePalette

@Composable
fun PersonalityScreen(
    onNavigateBack: () -> Unit,
    viewModel: PersonalityViewModel = hiltViewModel()
) {
    val palette = forgePalette
    val profiles by viewModel.profiles.collectAsState()
    val active by viewModel.activePersonality.collectAsState()
    val saveMessage by viewModel.saveMessage.collectAsState()

    // Edit state
    var editingName by remember(active) { mutableStateOf(active.name) }
    var editingDescription by remember(active) { mutableStateOf(active.description) }
    var editingSystemPrompt by remember(active) { mutableStateOf(active.systemPrompt) }
    var editingTraits by remember(active) { mutableStateOf(active.traits.joinToString("\n")) }
    var editingStyle by remember(active) { mutableStateOf(active.communicationStyle) }
    var editingInstructions by remember(active) { mutableStateOf(active.customInstructions) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var saveProfileName by remember { mutableStateOf("") }

    LaunchedEffect(saveMessage) {
        if (saveMessage != null) kotlinx.coroutines.delay(3000)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .padding(16.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack, "Back",
                    tint = palette.textMuted, modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "🎭  PERSONALITY",
                color = palette.orange, fontSize = 16.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 2.sp
            )
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Customize how Forge thinks, speaks, and behaves",
            color = palette.textMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace
        )

        // Save message banner
        AnimatedVisibility(visible = saveMessage != null) {
            saveMessage?.let {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF052e16), RoundedCornerShape(6.dp))
                        .padding(10.dp, 8.dp)
                ) {
                    Text(it, color = Color(0xFF4ade80), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ── Saved profiles ────────────────────────────────────────────
            item {
                SectionLabel("SAVED PROFILES")
            }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    profiles.forEach { name ->
                        val isActive = name == active.name
                        Box(
                            Modifier
                                .background(
                                    if (isActive) palette.orange.copy(alpha = 0.15f) else palette.surface,
                                    RoundedCornerShape(6.dp)
                                )
                                .border(
                                    1.dp,
                                    if (isActive) palette.orange else palette.border,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { viewModel.switchToProfile(name) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isActive) {
                                    Icon(
                                        Icons.Default.Check, null,
                                        tint = palette.orange,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    name,
                                    color = if (isActive) palette.orange else palette.textPrimary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            // ── Active personality editor ─────────────────────────────────
            item { Spacer(Modifier.height(4.dp)) }
            item { SectionLabel("ACTIVE PERSONALITY") }

            item {
                PersonalityField(
                    label = "Name",
                    value = editingName,
                    onValueChange = { editingName = it },
                    singleLine = true,
                    palette = palette
                )
            }
            item {
                PersonalityField(
                    label = "Description",
                    value = editingDescription,
                    onValueChange = { editingDescription = it },
                    singleLine = true,
                    palette = palette
                )
            }
            item {
                PersonalityField(
                    label = "System Prompt",
                    hint = "Core instructions for this personality (leave blank to use Forge defaults)",
                    value = editingSystemPrompt,
                    onValueChange = { editingSystemPrompt = it },
                    minLines = 4,
                    palette = palette
                )
            }
            item {
                PersonalityField(
                    label = "Traits",
                    hint = "One trait per line (e.g. Direct and concise)",
                    value = editingTraits,
                    onValueChange = { editingTraits = it },
                    minLines = 3,
                    palette = palette
                )
            }
            item {
                PersonalityField(
                    label = "Communication Style",
                    value = editingStyle,
                    onValueChange = { editingStyle = it },
                    minLines = 2,
                    palette = palette
                )
            }
            item {
                PersonalityField(
                    label = "Custom Instructions",
                    hint = "Extra rules appended to every system prompt",
                    value = editingInstructions,
                    onValueChange = { editingInstructions = it },
                    minLines = 3,
                    palette = palette
                )
            }

            // ── Action buttons ────────────────────────────────────────────
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Apply (update active without saving as profile)
                    Button(
                        onClick = {
                            viewModel.updatePersonality(
                                name = editingName,
                                description = editingDescription,
                                systemPrompt = editingSystemPrompt,
                                traits = editingTraits.lines().map { it.trim() }.filter { it.isNotBlank() },
                                communicationStyle = editingStyle,
                                customInstructions = editingInstructions
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.orange),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Apply", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = Color.Black)
                    }

                    // Save as named profile
                    OutlinedButton(
                        onClick = {
                            saveProfileName = editingName
                            showSaveDialog = true
                        },
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = androidx.compose.ui.graphics.SolidColor(palette.border)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Add, null, tint = palette.textMuted, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save Profile", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = palette.textMuted)
                    }
                }
            }

            // Reset to default — separate row, clearly destructive
            item {
                var showResetConfirm by remember { mutableStateOf(false) }

                OutlinedButton(
                    onClick = { showResetConfirm = true },
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(palette.danger.copy(alpha = 0.5f))
                    ),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Reset to Forge Defaults",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.danger.copy(alpha = 0.8f)
                    )
                }

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        containerColor = palette.surface,
                        title = {
                            Text(
                                "Reset personality?",
                                color = palette.textPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        text = {
                            Text(
                                "This restores the built-in Forge personality and discards any unsaved edits. " +
                                "Saved profiles are not affected — you can switch back to them at any time.",
                                color = palette.textMuted,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.resetToDefault()
                                showResetConfirm = false
                            }) {
                                Text("Reset", color = palette.danger, fontFamily = FontFamily.Monospace)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) {
                                Text("Cancel", color = palette.textMuted, fontFamily = FontFamily.Monospace)
                            }
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }

    // Save-as-profile dialog
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            containerColor = palette.surface,
            title = {
                Text("Save Profile", color = palette.textPrimary, fontFamily = FontFamily.Monospace)
            },
            text = {
                Column {
                    Text(
                        "Profile name:",
                        color = palette.textMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = saveProfileName,
                        onValueChange = { saveProfileName = it },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.orange,
                            unfocusedBorderColor = palette.border,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            cursorColor = palette.orange
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveProfile(saveProfileName)
                    showSaveDialog = false
                }) {
                    Text("Save", color = palette.orange, fontFamily = FontFamily.Monospace)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = palette.textMuted, fontFamily = FontFamily.Monospace)
                }
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    val palette = forgePalette
    Text(
        text,
        color = palette.textMuted, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
    )
}

@Composable
private fun PersonalityField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String = "",
    singleLine: Boolean = false,
    minLines: Int = 1,
    palette: com.forge.os.presentation.theme.ForgePalette
) {
    Column {
        Text(
            label.uppercase(),
            color = palette.textMuted, fontSize = 10.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp
        )
        if (hint.isNotBlank()) {
            Text(hint, color = palette.textMuted.copy(alpha = 0.6f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.orange,
                unfocusedBorderColor = palette.border,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                cursorColor = palette.orange
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
