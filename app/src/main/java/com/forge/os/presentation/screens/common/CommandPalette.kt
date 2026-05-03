package com.forge.os.presentation.screens.common

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.forge.os.presentation.theme.LocalForgePalette

data class CommandOption(
    val label: String,
    val description: String,
    val icon: String,
    val onSelect: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPalette(
    options: List<CommandOption>,
    onDismiss: () -> Unit
) {
    val palette = LocalForgePalette.current
    var query by remember { mutableStateOf("") }
    
    val filtered = remember(query) {
        if (query.isBlank()) options
        else options.filter { it.label.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        scrimColor = Color.Black.copy(alpha = 0.8f),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 500.dp)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Type a command or screen name...", color = palette.textDim,
                        fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = palette.orange) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.orange,
                    unfocusedBorderColor = palette.border,
                    focusedTextColor = palette.textPrimary,
                    unfocusedTextColor = palette.textPrimary
                ),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                shape = RoundedCornerShape(8.dp)
            )
            
            Spacer(Modifier.height(16.dp))
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(filtered) { opt ->
                    CommandItem(opt, palette, onDismiss)
                }
            }
            
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No commands found", color = palette.textDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CommandItem(opt: CommandOption, palette: com.forge.os.presentation.theme.ForgePalette, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { opt.onSelect(); onDismiss() }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(opt.icon, fontSize = 18.sp)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(
                opt.label.uppercase(),
                color = palette.textPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
            Text(
                opt.description,
                color = palette.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
