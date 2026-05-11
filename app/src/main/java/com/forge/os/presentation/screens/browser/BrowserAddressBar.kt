package com.forge.os.presentation.screens.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Phase 3: Browser UI Redesign
 * 
 * Chrome-like address bar with:
 * - Full-width input field
 * - Lock icon for HTTPS
 * - Clear button for quick clearing
 * - Rounded corners and subtle shadow
 */
@Composable
fun BrowserAddressBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    isSecure: Boolean = false,
    modifier: Modifier = Modifier
) {
    var isEditing by remember { mutableStateOf(false) }
    var displayUrl by remember(url) { mutableStateOf(url) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(24.dp)
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Lock icon for HTTPS
        if (!isEditing && isSecure) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Secure connection",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(end = 8.dp)
            )
        }

        // URL input field
        BasicTextField(
            value = displayUrl,
            onValueChange = { newValue ->
                displayUrl = newValue
                onUrlChange(newValue)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = if (!isEditing && isSecure) 28.dp else 0.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    onNavigate(displayUrl)
                    isEditing = false
                }
            ),
            singleLine = true,
            decorationBox = { innerTextField ->
                if (displayUrl.isEmpty() && !isEditing) {
                    Text(
                        text = "Search or enter URL",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                }
                innerTextField()
            },
            onTextLayout = {
                if (!isEditing && displayUrl.isNotEmpty()) {
                    isEditing = true
                }
            }
        )

        // Clear button
        if (displayUrl.isNotEmpty() && isEditing) {
            IconButton(
                onClick = {
                    displayUrl = ""
                    onUrlChange("")
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Clear,
                    contentDescription = "Clear",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}
