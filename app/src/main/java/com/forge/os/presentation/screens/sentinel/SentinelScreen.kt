package com.forge.os.presentation.screens.sentinel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.forge.os.domain.cron.TaskType
import com.forge.os.domain.sentinel.SentinelEventType
import com.forge.os.domain.sentinel.SentinelTrigger
import java.text.SimpleDateFormat
import java.util.*

private val Orange = Color(0xFFFF4500)
private val Surface = Color(0xFF121212)
private val TextMuted = Color(0xFF737373)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelScreen(
    onBack: () -> Unit,
    viewModel: SentinelViewModel = hiltViewModel()
) {
    val sentinels by viewModel.sentinels.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text("SENTINEL VANGUARD", color = Color.White, 
                         fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.Black)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Add Sentinel", tint = Orange)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Black)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
            Text("Reactive Automation Console", color = TextMuted, fontSize = 12.sp, 
                 fontFamily = FontFamily.Monospace, modifier = Modifier.padding(bottom = 16.dp))

            if (sentinels.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No Sentinels Active", color = Color.DarkGray, fontFamily = FontFamily.Monospace)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(sentinels) { sentinel ->
                        SentinelCard(
                            sentinel = sentinel,
                            onToggle = { viewModel.toggleSentinel(sentinel.id, it) },
                            onDelete = { viewModel.deleteSentinel(sentinel.id) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddSentinelDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, event, cond, type, payload ->
                viewModel.addSentinel(name, event, cond, type, payload)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun SentinelCard(
    sentinel: SentinelTrigger,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when(sentinel.eventType) {
                        SentinelEventType.WIFI_CONNECTED -> Icons.Default.Wifi
                        SentinelEventType.BATTERY_CHANGED -> Icons.Default.BatteryChargingFull
                        SentinelEventType.POWER_CONNECTED -> Icons.Default.Power
                        SentinelEventType.BLUETOOTH_CONNECTED -> Icons.Default.Bluetooth
                        else -> Icons.Default.Snooze
                    },
                    contentDescription = null, tint = Orange, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sentinel.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                    Text(
                        "${sentinel.eventType.name}${sentinel.condition?.let { " ($it)" } ?: ""}",
                        color = Orange, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = sentinel.enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Orange)
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                sentinel.payload.take(150),
                color = Color.LightGray, fontSize = 11.sp, 
                fontFamily = FontFamily.Monospace, maxLines = 3
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Fired: ${sentinel.fireCount} | Last: ${sentinel.lastFiredAt?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) } ?: "Never"}",
                    color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Default.Delete, null, tint = Color.Red.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AddSentinelDialog(
    onDismiss: () -> Unit,
    onAdd: (String, SentinelEventType, String?, TaskType, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var eventType by remember { mutableStateOf(SentinelEventType.WIFI_CONNECTED) }
    var condition by remember { mutableStateOf("") }
    var taskType by remember { mutableStateOf(TaskType.PROMPT) }
    var payload by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("New Sentinel", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (e.g. WiFi Automator)") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                // Event Type Selector
                Column {
                    Text("Trigger Event", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        SentinelEventType.entries.take(4).forEach { ev ->
                            FilterChip(
                                selected = eventType == ev,
                                onClick = { eventType = ev },
                                label = { Text(ev.name.split("_").first(), fontSize = 10.sp) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = condition, onValueChange = { condition = it },
                    label = { Text("Condition (e.g. WiFi Name)") },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                )

                Row {
                    TaskType.entries.forEach { tt ->
                        FilterChip(
                            selected = taskType == tt,
                            onClick = { taskType = tt },
                            label = { Text(tt.name, fontSize = 10.sp) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = payload, onValueChange = { payload = it },
                    label = { Text("Payload (Prompt/Command/Python)") },
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name, eventType, condition, taskType, payload) },
                colors = ButtonDefaults.buttonColors(containerColor = Orange),
                enabled = name.isNotBlank() && payload.isNotBlank()
            ) {
                Text("Deploy Sentinel", fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextMuted) }
        }
    )
}
