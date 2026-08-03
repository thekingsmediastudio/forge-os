package com.forge.os.presentation.screens.findmyphone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.forge.os.domain.findmyphone.FindMyPhoneManager
import com.forge.os.presentation.theme.forgePalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindMyPhoneScreen(
    navController: NavController,
    viewModel: FindMyPhoneViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find My Phone") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Outlined.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = forgePalette.surface,
                    titleContentColor = forgePalette.textPrimary,
                ),
            )
        },
        containerColor = forgePalette.bg,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Enable toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = forgePalette.surface),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            "Enable Find My Phone",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = forgePalette.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Answer calls automatically to help locate your phone",
                            fontSize = 13.sp,
                            color = forgePalette.textMuted,
                        )
                    }
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { viewModel.setEnabled(it) },
                    )
                }
            }

            // Response type
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = forgePalette.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Response Type",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = forgePalette.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    listOf(
                        FindMyPhoneManager.RESPONSE_RING_LOUD to "Ring Loudly",
                        FindMyPhoneManager.RESPONSE_SPEAK_LOCATION to "Speak Message",
                        FindMyPhoneManager.RESPONSE_FLASH_LED to "Flash LED",
                        FindMyPhoneManager.RESPONSE_ALL to "All of the Above",
                    ).forEach { (type, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = state.responseType == type,
                                onClick = { viewModel.setResponseType(type) },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, color = forgePalette.textPrimary)
                        }
                    }
                }
            }

            // TTS message
            if (state.responseType == FindMyPhoneManager.RESPONSE_SPEAK_LOCATION ||
                state.responseType == FindMyPhoneManager.RESPONSE_ALL
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = forgePalette.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "TTS Message",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = forgePalette.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.ttsMessage,
                            onValueChange = { viewModel.setTtsMessage(it) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("I'm here! You found me!") },
                        )
                    }
                }
            }

            // Duration
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = forgePalette.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Duration: ${state.durationSeconds} seconds",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = forgePalette.textPrimary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = state.durationSeconds.toFloat(),
                        onValueChange = { viewModel.setDurationSeconds(it.toInt()) },
                        valueRange = 10f..60f,
                        steps = 9,
                    )
                }
            }

            // Test button
            Button(
                onClick = { viewModel.testResponse() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = forgePalette.orange,
                ),
            ) {
                Text("Test Response")
            }

            // Last triggered
            if (state.lastTriggered > 0) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = forgePalette.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "Last Triggered",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = forgePalette.textPrimary,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "From: ${state.lastCaller}",
                            fontSize = 13.sp,
                            color = forgePalette.textMuted,
                        )
                        Text(
                            "At: ${java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault()).format(state.lastTriggered)}",
                            fontSize = 13.sp,
                            color = forgePalette.textMuted,
                        )
                    }
                }
            }
        }
    }
}
