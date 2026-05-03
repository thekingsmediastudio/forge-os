package com.forge.os.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.compose.runtime.ReadOnlyComposable
import com.forge.os.data.api.ApiCallLog
import com.forge.os.data.api.ApiCallLogEntry
import com.forge.os.presentation.theme.forgePalette
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

// Theme-aware accessors
private val Orange: Color @Composable @ReadOnlyComposable get() = forgePalette.orange
private val Bg: Color @Composable @ReadOnlyComposable get() = forgePalette.bg
private val Surface: Color @Composable @ReadOnlyComposable get() = forgePalette.surface
private val Surface2: Color @Composable @ReadOnlyComposable get() = forgePalette.surface2
private val TextPrimary: Color @Composable @ReadOnlyComposable get() = forgePalette.textPrimary
private val TextMuted: Color @Composable @ReadOnlyComposable get() = forgePalette.textMuted

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val log: ApiCallLog
) : ViewModel() {
    val entries: StateFlow<List<ApiCallLogEntry>> = log.entries
    fun clear() = log.clear()
}

@Composable
fun DiagnosticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val entries by viewModel.entries.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onNavigateBack, modifier = Modifier.size(32.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back",
                     tint = TextMuted, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text("⚙  DIAGNOSTICS", color = Orange, fontSize = 16.sp,
                 fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = viewModel::clear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteSweep, "Clear",
                     tint = TextMuted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Last ${entries.size} API calls (in-memory)", color = TextMuted,
             fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(12.dp))

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No calls yet. Send a chat message to populate the log.",
                     color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(entries.reversed()) { e -> CallRow(e, fmt) }
            }
        }
    }
}

@Composable
private fun CallRow(e: ApiCallLogEntry, fmt: SimpleDateFormat) {
    val accent = when {
        e.errorMessage != null -> Color(0xFFef4444)
        e.httpCode in 200..299 -> Color(0xFF22c55e)
        else -> Color(0xFFf59e0b)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.background(Surface2, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("${e.httpCode} · ${e.durationMs}ms",
                         color = accent, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(6.dp))
                Text(e.provider, color = TextPrimary, fontSize = 11.sp,
                     fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(4.dp))
                Text("· ${e.model}", color = TextMuted, fontSize = 10.sp,
                     fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(fmt.format(Date(e.timestamp)), color = TextMuted,
                     fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            if (e.inputTokens + e.outputTokens > 0) {
                Spacer(Modifier.height(2.dp))
                Text("in=${e.inputTokens} out=${e.outputTokens} tokens" +
                        if (e.attempt > 1) " · attempt ${e.attempt}" else "",
                     color = TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            e.errorMessage?.let {
                Spacer(Modifier.height(4.dp))
                Text(it.take(200), color = Color(0xFFef4444), fontSize = 10.sp,
                     fontFamily = FontFamily.Monospace, lineHeight = 14.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(e.url, color = Color(0xFF404040), fontSize = 9.sp,
                 fontFamily = FontFamily.Monospace)
        }
    }
}
