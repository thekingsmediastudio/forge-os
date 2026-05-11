package com.forge.os.presentation.screens.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.theme.Glass
import com.forge.os.presentation.theme.VibrantBlue
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Restore: pick an existing backup file
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.restoreBackup(it) }
    }

    // Save: after backup is created, let the user pick where to save it
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { destUri: Uri? ->
        if (destUri != null) {
            viewModel.copyBackupTo(destUri)
        }
    }

    // When backup finishes, immediately open the save dialog
    val backupStatus = state.status
    LaunchedEffect(backupStatus) {
        if (backupStatus is BackupStatus.Done && backupStatus.path.isNotBlank()) {
            val filename = File(backupStatus.path).name
            saveLauncher.launch(filename)
        }
    }

    LaunchedEffect(state.lastMessage) {
        state.lastMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color.Transparent
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    MainActionsCard(
                        onBackup = { viewModel.createBackup() },
                        onRestore = { filePickerLauncher.launch(arrayOf("*/*")) },
                        status = state.status
                    )
                }

                item {
                    Text(
                        "Local Backups",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }

                if (state.backups.isEmpty()) {
                    item { EmptyBackupsCard() }
                } else {
                    items(state.backups) { backup ->
                        BackupItemCard(
                            backup = backup,
                            onShare = { viewModel.shareBackup(backup.path) },
                            onDelete = { viewModel.deleteBackup(backup.path) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(80.dp)) }
            }

            if (state.status is BackupStatus.Running) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VibrantBlue)
                }
            }
        }
    }
}

@Composable
fun MainActionsCard(
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    status: BackupStatus
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp)),
        color = Glass.copy(alpha = 0.1f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CloudUpload,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = VibrantBlue
            )
            Text(
                "Full System Snapshot",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "This creates a complete zip of your workspace, config, and memory. API keys are excluded for safety.",
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onBackup,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VibrantBlue)
                ) {
                    Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Backup Now")
                }

                OutlinedButton(
                    onClick = onRestore,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, VibrantBlue)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore", color = VibrantBlue)
                }
            }
        }
    }
}

@Composable
fun BackupItemCard(
    backup: com.forge.os.domain.backup.BackupManager.BackupInfo,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val date = remember(backup.timestamp) {
        SimpleDateFormat("MMM dd, yyyy · HH:mm", Locale.getDefault()).format(Date(backup.timestamp))
    }
    val size = remember(backup.sizeBytes) {
        val mb = backup.sizeBytes / (1024.0 * 1024.0)
        if (mb < 1) "${(backup.sizeBytes / 1024.0).toInt()} KB"
        else "${"%.1f".format(mb)} MB"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
        color = Color.White.copy(alpha = 0.05f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.FilePresent, contentDescription = null, tint = Color.LightGray)
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 16.dp)
            ) {
                Text(backup.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                Text("$date · $size", fontSize = 12.sp, color = Color.Gray)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = VibrantBlue)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun EmptyBackupsCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(Color.White.copy(alpha = 0.03f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("No local backups found", color = Color.Gray, fontSize = 14.sp)
    }
}
