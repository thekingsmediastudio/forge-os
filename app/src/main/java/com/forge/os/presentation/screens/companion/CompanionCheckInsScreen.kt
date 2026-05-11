package com.forge.os.presentation.screens.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.config.FriendModeSettings
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

private val Accent = Color(0xFFf59e0b)

@HiltViewModel
class CompanionCheckInsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {
    private val _settings = MutableStateFlow(configRepository.get().friendMode)
    val settings: StateFlow<FriendModeSettings> = _settings

    fun update(transform: (FriendModeSettings) -> FriendModeSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                configRepository.update { it.copy(friendMode = next) }
            }
        }
    }
}

@Composable
fun CompanionCheckInsScreen(
    onBack: () -> Unit,
    vm: CompanionCheckInsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsState()

    ModuleScaffold(title = "COMPANION · CHECK-INS", onBack = onBack) {
        Column(
            Modifier.fillMaxSize().background(ForgeOsPalette.Bg).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header()

            SectionCard("MASTER SWITCHES") {
                ToggleRow(
                    label = "Friend mode enabled",
                    sub = "Required before any companion check-in can fire.",
                    value = s.enabled,
                    onChange = { vm.update { o -> o.copy(enabled = it) } },
                )
                ToggleRow(
                    label = "Proactive check-ins",
                    sub = "Allow the companion to reach out unprompted.",
                    value = s.proactiveCheckInsEnabled,
                    onChange = { vm.update { o -> o.copy(proactiveCheckInsEnabled = it) } },
                )
            }

            SectionCard("MORNING CHECK-IN") {
                ToggleRow(
                    label = "Enabled",
                    sub = "Once per day, within 15 min of the time below.",
                    value = s.morningCheckInEnabled,
                    onChange = { vm.update { o -> o.copy(morningCheckInEnabled = it) } },
                )
                TimeRow(
                    label = "Time",
                    value = s.morningCheckInTime,
                    onChange = { vm.update { o -> o.copy(morningCheckInTime = it) } },
                )
            }

            SectionCard("FOLLOW-UPS & ANNIVERSARIES") {
                ToggleRow(
                    label = "Follow-ups",
                    sub = "Re-surface a question the companion saved during a past chat.",
                    value = s.followUpsEnabled,
                    onChange = { vm.update { o -> o.copy(followUpsEnabled = it) } },
                )
                ToggleRow(
                    label = "Anniversaries",
                    sub = "“A year ago today we talked about…”.",
                    value = s.anniversariesEnabled,
                    onChange = { vm.update { o -> o.copy(anniversariesEnabled = it) } },
                )
            }

            SectionCard("LIMITS & QUIET HOURS") {
                IntRow(
                    label = "Max per day",
                    sub = "Hard cap. The default of 2 is intentional.",
                    value = s.maxProactivePerDay,
                    onChange = {
                        vm.update { o -> o.copy(maxProactivePerDay = it.coerceIn(0, 6)) }
                    },
                )
                TimeRow(
                    label = "Quiet hours start",
                    value = s.quietHoursStart,
                    onChange = { vm.update { o -> o.copy(quietHoursStart = it) } },
                )
                TimeRow(
                    label = "Quiet hours end",
                    value = s.quietHoursEnd,
                    onChange = { vm.update { o -> o.copy(quietHoursEnd = it) } },
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Text(
        "Notifications fire only while friend mode + proactive check-ins are both on, " +
            "outside quiet hours, and within the daily cap.",
        color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
    )
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(ForgeOsPalette.Surface, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, color = Accent, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        content()
    }
}

@Composable
private fun ToggleRow(
    label: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = ForgeOsPalette.TextPrimary,
                fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(sub, color = ForgeOsPalette.TextMuted,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Accent,
                checkedTrackColor = Color(0xFF3a2a13),
            )
        )
    }
}

@Composable
private fun TimeRow(label: String, value: String, onChange: (String) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ForgeOsPalette.TextPrimary,
            fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f))
        Box(
            Modifier.width(96.dp).height(36.dp)
                .background(ForgeOsPalette.Surface2, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = local,
                onValueChange = {
                    local = it
                    if (isValidHm(it)) onChange(it)
                },
                singleLine = true,
                textStyle = TextStyle(color = ForgeOsPalette.TextPrimary,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun IntRow(label: String, sub: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = ForgeOsPalette.TextPrimary,
                fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Text(sub, color = ForgeOsPalette.TextMuted,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−") { onChange((value - 1).coerceAtLeast(0)) }
            Text(value.toString(),
                color = ForgeOsPalette.TextPrimary,
                fontSize = 14.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 12.dp))
            StepperButton("+") { onChange((value + 1).coerceAtMost(6)) }
        }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(28.dp)
            .background(ForgeOsPalette.Surface2, RoundedCornerShape(6.dp))
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        TextButton(
            onClick = onClick,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Text(label, color = Accent, fontSize = 14.sp,
                fontFamily = FontFamily.Monospace)
        }
    }
}

private fun isValidHm(s: String): Boolean {
    val parts = s.split(':')
    if (parts.size != 2) return false
    val h = parts[0].toIntOrNull() ?: return false
    val m = parts[1].toIntOrNull() ?: return false
    return h in 0..23 && m in 0..59
}
