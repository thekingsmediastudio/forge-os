package com.forge.os.presentation.screens.governance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.components.ForgeButton
import com.forge.os.presentation.components.ForgeCard
import com.forge.os.presentation.components.ForgeScreenScaffold
import com.forge.os.presentation.theme.ForgeTokens.Colors

@Composable
fun AppGrantScreen(
    callerPackage: String = "Unknown Application",
    requestedTool: String = "Unknown Tool",
    onAccept: () -> Unit,
    onReject: () -> Unit,
    viewModel: GovernanceViewModel = hiltViewModel()
) {
    ForgeScreenScaffold {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                tint = Colors.Accent,
                modifier = Modifier
                    .size(64.dp)
                    .padding(bottom = 24.dp)
            )

            Text(
                "AUTHORIZATION PENDING",
                color = Colors.Warning,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Text(
                callerPackage,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            Text(
                "is requesting secure permission to execute the following internal feature:",
                color = Colors.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Permission chip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Colors.BgSurface, RoundedCornerShape(12.dp))
                    .border(1.dp, Colors.Border, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    requestedTool.uppercase(),
                    color = Colors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }

            Spacer(Modifier.height(48.dp))

            ForgeButton(
                text = "GRANT PERSISTENT ACCESS",
                onClick = {
                    viewModel.grantPermission(callerPackage, requestedTool)
                    onAccept()
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            ForgeButton(
                text = "DENY REQUEST",
                onClick = onReject,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
