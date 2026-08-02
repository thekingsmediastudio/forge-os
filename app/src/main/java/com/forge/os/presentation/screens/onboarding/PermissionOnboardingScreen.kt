package com.forge.os.presentation.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.permissions.PermissionItem
import com.forge.os.presentation.components.ForgeLogo
import com.forge.os.presentation.theme.forgePalette

/**
 * Permission onboarding screen shown on first launch.
 * Explains why each permission is needed and requests them.
 */
@Composable
fun PermissionOnboardingScreen(
    onComplete: () -> Unit,
    viewModel: PermissionOnboardingViewModel = hiltViewModel()
) {
    val permissions by viewModel.permissions.collectAsState()
    val allRequiredGranted by viewModel.allRequiredGranted.collectAsState()

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.refreshPermissions()
        if (viewModel.areRequiredPermissionsGranted()) {
            viewModel.markComplete()
            onComplete()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(forgePalette.bg)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // Logo
            ForgeLogo(size = 80.dp)

            Spacer(Modifier.height(24.dp))

            // Title
            Text(
                "Permissions",
                color = forgePalette.textPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Forge OS needs a few permissions to work its magic",
                color = forgePalette.textMuted,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            // Permission cards
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                permissions.forEach { permission ->
                    PermissionCard(
                        permission = permission,
                        onRequest = {
                            permissionLauncher.launch(arrayOf(permission.permission))
                        }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Action buttons
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Grant all button
                Button(
                    onClick = {
                        val toRequest = viewModel.getPermissionsToRequest()
                        if (toRequest.isNotEmpty()) {
                            permissionLauncher.launch(toRequest)
                        } else {
                            viewModel.markComplete()
                            onComplete()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = forgePalette.orange
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (allRequiredGranted) "Continue" else "Grant Permissions",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }

                // Skip button
                TextButton(
                    onClick = {
                        viewModel.markComplete()
                        onComplete()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Skip for now",
                        color = forgePalette.textMuted,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    permission: PermissionItem,
    onRequest: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = forgePalette.surface,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (permission.isGranted)
                            forgePalette.success.copy(alpha = 0.15f)
                        else
                            forgePalette.surface2,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (permission.isGranted) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = forgePalette.success,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Text(
                        permission.icon,
                        fontSize = 24.sp
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            // Text
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        permission.name,
                        color = forgePalette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!permission.isRequired) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = forgePalette.surface2,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "Optional",
                                color = forgePalette.textMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    permission.description,
                    color = forgePalette.textMuted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }

            // Status
            if (permission.isGranted) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Granted",
                    tint = forgePalette.success,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
