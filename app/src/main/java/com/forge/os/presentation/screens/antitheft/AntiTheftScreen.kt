package com.forge.os.presentation.screens.antitheft

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.forge.os.presentation.components.ModernCard
import com.forge.os.presentation.components.SectionHeader
import com.forge.os.presentation.components.StatusBadge
import com.forge.os.presentation.theme.ModernAccent
import com.forge.os.presentation.theme.ModernTextPrimary
import com.forge.os.presentation.theme.ModernTextSecondary
import com.forge.os.presentation.theme.forgePalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntiTheftScreen(
    onBack: () -> Unit,
    viewModel: AntiTheftViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val message by viewModel.message.collectAsState()

    var newContact by remember { mutableStateOf("") }
    var showAddContact by remember { mutableStateOf(false) }
    var showWipeConfirm by remember { mutableStateOf(false) }

    val deviceAdminLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.refreshState()
        if (viewModel.state.value.deviceAdminActive) {
            viewModel.showMessage("Device admin activated")
        }
    }

    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Anti-Theft", fontWeight = FontWeight.SemiBold) },
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
                                "Anti-Theft Protection",
                                color = ModernTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                when {
                                    state.triggered -> "⚠️ TRIGGERED — Device may be stolen"
                                    state.enabled -> "Active — monitoring for theft"
                                    else -> "Disabled"
                                },
                                color = if (state.triggered) forgePalette.danger else ModernTextSecondary,
                                fontSize = 13.sp
                            )
                        }
                        StatusBadge(
                            status = when {
                                state.triggered -> "Triggered"
                                state.enabled -> "Active"
                                else -> "Off"
                            },
                            color = when {
                                state.triggered -> forgePalette.danger
                                state.enabled -> forgePalette.success
                                else -> forgePalette.textMuted
                            }
                        )
                    }
                }
            }

            // Device Admin Status
            if (!state.deviceAdminActive) {
                item {
                    ModernCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Warning,
                                    contentDescription = null,
                                    tint = forgePalette.warning,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    "Device Admin Required",
                                    color = ModernTextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                "Anti-theft requires device admin permission to lock and wipe your phone remotely.",
                                color = ModernTextSecondary,
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { deviceAdminLauncher.launch(viewModel.getDeviceAdminIntent()) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = ModernAccent)
                            ) {
                                Text("Activate Device Admin", fontSize = 13.sp)
                            }
                        }
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
                                "Enable Anti-Theft",
                                color = ModernTextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "Monitor for theft and respond to SMS commands",
                                color = ModernTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            checked = state.enabled,
                            onCheckedChange = { viewModel.setEnabled(it) },
                            enabled = state.deviceAdminActive,
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = ModernAccent,
                                checkedThumbColor = forgePalette.onAccent
                            )
                        )
                    }
                }
            }

            // Clear Triggered State
            if (state.triggered) {
                item {
                    ModernCard {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Error,
                                    contentDescription = null,
                                    tint = forgePalette.danger,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    "Theft Alert Active",
                                    color = forgePalette.danger,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                "Theft was detected. Device is locked and alerts have been sent to trusted contacts.",
                                color = ModernTextSecondary,
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { viewModel.clearTriggered() },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = forgePalette.success)
                            ) {
                                Text("Clear Alert (I have my phone)", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            // Quick Actions
            item { SectionHeader(title = "QUICK ACTIONS") }
            item {
                ModernCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.lockDevice() },
                                modifier = Modifier.weight(1f),
                                enabled = state.deviceAdminActive,
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ModernTextPrimary)
                            ) {
                                Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Lock Now", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.updateLocation() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ModernTextPrimary)
                            ) {
                                Icon(Icons.Outlined.LocationOn, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Get Location", fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = { viewModel.sendTestAlert() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.trustedContacts.isNotEmpty(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ModernAccent)
                        ) {
                            Icon(Icons.Outlined.Send, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Send Test Alert to Contacts", fontSize = 12.sp)
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
                            "These contacts can send SMS commands (LOCK, LOCATE, WIPE, ALARM, STATUS)",
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
                                        Text(contact, color = ModernTextPrimary, fontSize = 14.sp)
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

            // Alert Message
            item { SectionHeader(title = "ALERT MESSAGE") }
            item {
                ModernCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Message sent to trusted contacts when theft is detected",
                            color = ModernTextSecondary,
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = state.alertMessage,
                            onValueChange = { viewModel.setAlertMessage(it) },
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

            // Last Location
            if (state.lastLocationTime > 0) {
                item { SectionHeader(title = "LAST KNOWN LOCATION") }
                item {
                    ModernCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${state.lastLatitude}, ${state.lastLongitude}",
                                color = ModernTextPrimary,
                                fontSize = 14.sp
                            )
                            Text(
                                formatTimestamp(state.lastLocationTime),
                                color = forgePalette.textMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Danger Zone
            item { SectionHeader(title = "DANGER ZONE") }
            item {
                ModernCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "These actions are irreversible. Use with caution.",
                            color = forgePalette.danger,
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = { showWipeConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = state.deviceAdminActive,
                            colors = ButtonDefaults.buttonColors(containerColor = forgePalette.danger)
                        ) {
                            Icon(Icons.Outlined.DeleteForever, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Wipe Device", fontSize = 13.sp)
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        // Wipe Confirmation Dialog
        if (showWipeConfirm) {
            AlertDialog(
                onDismissRequest = { showWipeConfirm = false },
                title = { Text("Wipe Device?") },
                text = { Text("This will erase ALL data on this device and cannot be undone. Are you absolutely sure?") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.wipeDevice()
                            showWipeConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = forgePalette.danger)
                    ) {
                        Text("Wipe Device")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showWipeConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
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

private fun formatTimestamp(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}
