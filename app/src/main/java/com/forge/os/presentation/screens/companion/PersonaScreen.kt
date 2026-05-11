package com.forge.os.presentation.screens.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.companion.PersonaVoice
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold

private val Accent = Color(0xFFf59e0b)

@Composable
fun PersonaScreen(
    onBack: () -> Unit,
    vm: CompanionViewModel = hiltViewModel(),
) {
    val persona by vm.personaManager.persona.collectAsState()

    var name by remember { mutableStateOf(persona.name) }
    var pronouns by remember { mutableStateOf(persona.pronouns) }
    var traits by remember { mutableStateOf(persona.coreTraits.joinToString(", ")) }
    var backstory by remember { mutableStateOf(persona.backstory) }
    var voice by remember { mutableStateOf(persona.voice) }

    ModuleScaffold(title = "PERSONA", onBack = onBack) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Phase I — your companion's identity. Injected into every COMPANION-mode reply.",
                color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )

            Field("Name", name) { name = it }
            Field("Pronouns", pronouns) { pronouns = it }
            Field("Core traits (comma-separated)", traits) { traits = it }
            Field("Backstory (optional)", backstory, multiline = true) { backstory = it }

            Text("Voice", color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PersonaVoice.values().forEach { v ->
                    val selected = v == voice
                    Box(
                        Modifier
                            .background(
                                if (selected) Accent else ForgeOsPalette.Surface,
                                RoundedCornerShape(6.dp),
                            )
                            .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
                            .clickable { voice = v }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            v.name.lowercase(),
                            color = if (selected) Color.Black else ForgeOsPalette.TextPrimary,
                            fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.background(Accent, RoundedCornerShape(6.dp))
                        .clickable {
                            val cleanedTraits = traits.split(",")
                                .map { it.trim() }.filter { it.isNotEmpty() }
                                .ifEmpty { listOf("curious", "warm", "candid") }
                            vm.personaManager.update {
                                it.copy(
                                    name = name.ifBlank { "Forge" },
                                    pronouns = pronouns.ifBlank { "they/them" },
                                    coreTraits = cleanedTraits.take(5),
                                    backstory = backstory.trim(),
                                    voice = voice,
                                )
                            }
                            onBack()
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text("save", color = Color.Black, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace)
                }
                Box(
                    Modifier.border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
                        .clickable {
                            vm.personaManager.reset()
                            val p = vm.personaManager.get()
                            name = p.name; pronouns = p.pronouns
                            traits = p.coreTraits.joinToString(", ")
                            backstory = p.backstory; voice = p.voice
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text("reset", color = ForgeOsPalette.TextMuted, fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("preview", color = ForgeOsPalette.TextMuted, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
            Box(
                Modifier.fillMaxWidth()
                    .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Text(
                    vm.personaManager.buildSystemPreamble(),
                    color = ForgeOsPalette.TextPrimary, fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    multiline: Boolean = false,
    onChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = ForgeOsPalette.TextMuted, fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
        Box(
            Modifier.fillMaxWidth()
                .background(ForgeOsPalette.Surface2, RoundedCornerShape(6.dp))
                .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value, onValueChange = onChange,
                textStyle = TextStyle(color = ForgeOsPalette.TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(Accent),
                singleLine = !multiline,
                modifier = Modifier.fillMaxWidth().let {
                    if (multiline) it.height(80.dp) else it
                },
            )
        }
    }
}
