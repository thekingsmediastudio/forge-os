package com.forge.os.presentation.screens.missingmode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.missingmode.MissingModeManager
import com.forge.os.presentation.components.ModernCard
import com.forge.os.presentation.components.SectionHeader
import com.forge.os.presentation.components.StatusBadge
import com.forge.os.presentation.theme.ModernAccent
import com.forge.os.presentation.theme.ModernTextPrimary
import com.forge.os.presentation.theme.ModernTextSecondary
import com.forge.os.presentation.theme.forgePalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissingModeScreen(
    onBack: () -> Unit,
    viewModel: MissingModeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()

    var newContact by remember { mutableStateOf("") }
    var showAddContact by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Missing Mode", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = forgePalette.bg,
                    titleContentColor = forgePalette.textPrimary,
                )
            )
        },
        containerColor = forgePalette.bg
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(Modifier.height(8.dp)) }

            // Status Card
            item {
                ModernCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Missing Mode",
                                color = ModernTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (state.enabled) "Active — auto-responding to trusted contacts"
                                else "Disabled — calls ring normally",
                                color = ModernTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        StatusBadge(
                            status = if (state.enabled) "Active" else "Off",
                            color = if (state.enabled) forgePalette.success else forgePalette.textMuted
                        )
                    }
                }
            }

            // Enable/Disable Toggle
            item {
                ModernCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Enable Missing Mode",
                                color = ModernTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Auto-respond to calls from trusted contacts",
                                color = ModernTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = { viewModel.setEnabled(it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = ModernAccent,
                                checkedThumbColor = forgePalette.onAccent
                            )
                        )
                    }
                }
            }

            // Response Type
            item { SectionHeader(title = "RESPONSE TYPE") }
            item {
                ModernCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ResponseTypeOption(
                            title = "SMS Only",
                            description = "Reject call and send SMS auto-reply",
                            selected = state.responseType == MissingModeManager.RESPONSE_SMS,
                            onClick = { viewModel.setResponseType(MissingModeManager.RESPONSE_SMS) }
                        )
                        HorizontalDivider(color = forgePalette.divider)
                        ResponseTypeOption(
                            title = "Voice Answer",
                            description = "Answer call and play TTS message",
                            selected = state.responseType == MissingModeManager.RESPONSE_TTS,
                            onClick = { viewModel.setResponseType(MissingModeManager.RESPONSE_TTS) }
                        )
                        HorizontalDivider(color = forgePalette.divider)
                        ResponseTypeOption(
                            title = "Both",
                            description = "Answer with TTS, then send SMS",
                            selected = state.responseType == MissingModeManager.RESPONSE_BOTH,
                            onClick = { viewModel.setResponseType(MissingModeManager.RESPONSE_BOTH) }
                        )
                    }
                }
            }

            // Message Templates
            item { SectionHeader(title = "MESSAGE TEMPLATES") }
            item {
                ModernCard {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        // SMS Template
                        Column {
                            Text(
                                "SMS Auto-Reply",
                                color = ModernTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = state.smsTemplate,
                                onValueChange = { viewModel.setSmsTemplate(it) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ModernAccent,
                                    unfocusedBorderColor = forgePalette.border
                                )
                            )
                        }

                        // TTS Message
                        Column {
                            Text(
                                "Voice Message (TTS)",
                                color = ModernTextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = state.ttsMessage,
                                onValueChange = { viewModel.setTtsMessage(it) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ModernAccent,
                                    unfocusedBorderColor = forgePalette.border
                                )
                            )
                        }
                    }
                }
            }

            // Trusted Contacts
            item { SectionHeader(title = "TRUSTED CONTACTS") }
            item {
                ModernCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Only calls from these numbers will trigger auto-response",
                            color = ModernTextSecondary,
                            fontSize = 12.sp
                        )

                        if (state.trustedContacts.isEmpty()) {
                            Text(
                                "No trusted contacts added",
                                color = forgePalette.textMuted,
                                fontSize = 13.sp
                            )
                        } else {
                            state.trustedContacts.forEach { contact ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Outlined.Phone,
                                            contentDescription = null,
                                            tint = ModernAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            contact,
                                            color = ModernTextPrimary,
                                            fontSize = 14.sp
                                        )
                                    }
                                    IconButton(
                                        onClick = { viewModel.removeTrustedContact(contact) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            "Remove",
                                            tint = forgePalette.danger,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Add Contact Button
                        if (showAddContact) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = newContact,
                                    onValueChange = { newContact = it },
                                    modifier = Modifier.weight(1f),
                                    placeholder = { Text("Phone number") },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ModernAccent,
                                        unfocusedBorderColor = forgePalette.border
                                    )
                                )
                                Button(
                                    onClick = {
                                        if (newContact.isNotBlank()) {
                                            viewModel.addTrustedContact(newContact)
                                            newContact = ""
                                            showAddContact = false
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ModernAccent)
                                ) {
                                    Text("Add", fontSize = 12.sp)
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { showAddContact = true },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ModernAccent)
                            ) {
                                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Add Trusted Contact", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Last Triggered
            if (state.lastTriggered > 0) {
                item { SectionHeader(title = "ACTIVITY") }
                item {
                    ModernCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "Last Auto-Response",
                                color = ModernTextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                "To: ${state.lastCaller}",
                                color = ModernTextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                formatTimestamp(state.lastTriggered),
                                color = forgePalette.textMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Test Button
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.testResponse() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = forgePalette.surface2),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Outlined.PlayArrow, null, tint = ModernAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("Test Response", color = ModernTextPrimary, fontSize = 14.sp)
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        // Snackbar
        message?.let { msg ->
            Box(modifier = Modifier.fillMaxSize()) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    containerColor = forgePalette.surface2
                ) {
                    Text(msg, color = ModernTextPrimary)
                }
            }
        }
    }
}

@Composable
private fun ResponseTypeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ModernTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(description, color = ModernTextSecondary, fontSize = 12.sp)
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = ModernAccent)
        )
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
