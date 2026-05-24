package com.forge.os.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.forge.os.presentation.theme.ForgeTokens.Colors
import com.forge.os.presentation.theme.ForgeTokens.Shape
import com.forge.os.presentation.theme.ForgeTokens.Spacing
import com.forge.os.presentation.theme.ForgeTokens.Type

// ─────────────────────────────────────────────────────────────────────────────
//  FORGE OS — DIALOG & SHEET SYSTEM
//  All popup, confirm, prompt, sheet, and context menu patterns consolidated.
// ─────────────────────────────────────────────────────────────────────────────

/* ══════════════════════════════════════════════════════════════════════════
   SHARED INTERNAL — Glass dialog container
   ══════════════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ForgeDialogContainer(
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Shape.xxl)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xF8131313), Color(0xF5141414))
                    )
                )
                .border(Dp.Hairline, Colors.BorderAccent, Shape.xxl)
                .padding(24.dp),
            content = content
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   1. FORGE ALERT  — simple info / error popup
       forge.showAlert(title, message)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeAlert(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmLabel: String = "OK"
) {
    ForgeDialogContainer(onDismiss = onDismiss) {
        Text(
            title,
            color = Colors.TextPrimary,
            fontSize = Type.body1,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.02).sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            message,
            color = Colors.TextSecondary,
            fontSize = Type.body3,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(20.dp))
        ForgeDialogButton(confirmLabel, primary = true, onClick = onDismiss)
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   2. FORGE CONFIRM  — binary choice dialog
       forge.confirm(title, message)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgeConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "CONFIRM",
    cancelLabel: String = "CANCEL",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    ForgeDialogContainer(onDismiss = onCancel) {
        Text(
            title,
            color = Colors.TextPrimary,
            fontSize = Type.body1,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(10.dp))
        Text(message, color = Colors.TextSecondary, fontSize = Type.body3, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeDialogButton(
                cancelLabel,
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = onCancel
            )
            ForgeDialogButton(
                confirmLabel,
                primary = true,
                destructive = destructive,
                modifier = Modifier.weight(1f),
                onClick = onConfirm
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   3. FORGE PROMPT  — single-field text input
       forge.prompt(title, placeholder)
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
fun ForgePromptDialog(
    title: String,
    placeholder: String = "",
    confirmLabel: String = "OK",
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    ForgeDialogContainer(onDismiss = onCancel) {
        Text(title, color = Colors.TextPrimary, fontSize = Type.body1, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(16.dp))

        // Input field
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black.copy(0.4f))
                .border(Dp.Hairline, Colors.BorderAccent, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (text.isEmpty()) {
                Text(placeholder, color = Colors.TextTertiary, fontSize = Type.body3)
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = LocalTextStyle.current.copy(
                    color = Colors.TextPrimary, fontSize = Type.body3
                ),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeDialogButton("CANCEL", primary = false, modifier = Modifier.weight(1f), onClick = onCancel)
            ForgeDialogButton(confirmLabel, primary = true, modifier = Modifier.weight(1f), onClick = { onConfirm(text) })
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   4. FORGE MULTI-STEP WIZARD  — step-by-step guided dialog
       forge.stepDialog(steps)
   ══════════════════════════════════════════════════════════════════════════ */

data class ForgeWizardStep(
    val title: String,
    val body: String,
    val inputPlaceholder: String? = null  // null = no input on this step
)

@Composable
fun ForgeWizardDialog(
    steps: List<ForgeWizardStep>,
    onComplete: (answers: List<String>) -> Unit,
    onCancel: () -> Unit
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val answers = remember { mutableStateListOf<String>().also { l -> repeat(steps.size) { l.add("") } } }
    val step = steps[currentStep]
    val isLast = currentStep == steps.lastIndex

    ForgeDialogContainer(onDismiss = onCancel) {
        // Step indicator
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.End)
        ) {
            steps.forEachIndexed { i, _ ->
                Box(
                    modifier = Modifier
                        .size(if (i == currentStep) 20.dp else 6.dp, 6.dp)
                        .clip(Shape.full)
                        .background(if (i == currentStep) Colors.Accent else Colors.Border)
                        .animateContentSize()
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        Text(step.title, color = Colors.TextPrimary, fontSize = Type.body1, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(step.body, color = Colors.TextSecondary, fontSize = Type.body3, lineHeight = 20.sp)

        if (step.inputPlaceholder != null) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(0.4f))
                    .border(Dp.Hairline, Colors.BorderAccent, RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                if (answers[currentStep].isEmpty())
                    Text(step.inputPlaceholder, color = Colors.TextTertiary, fontSize = Type.body3)
                BasicTextField(
                    value = answers[currentStep],
                    onValueChange = { answers[currentStep] = it },
                    textStyle = LocalTextStyle.current.copy(color = Colors.TextPrimary, fontSize = Type.body3),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ForgeDialogButton(
                if (currentStep == 0) "CANCEL" else "BACK",
                primary = false,
                modifier = Modifier.weight(1f),
                onClick = { if (currentStep == 0) onCancel() else currentStep-- }
            )
            ForgeDialogButton(
                if (isLast) "FINISH" else "NEXT",
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = { if (isLast) onComplete(answers.toList()) else currentStep++ }
            )
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   5. FORGE CUSTOM DIALOG  — fully custom buttons
       forge.showDialog(title, message, buttons[])
   ══════════════════════════════════════════════════════════════════════════ */

data class ForgeDialogAction(
    val label: String,
    val primary: Boolean = false,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun ForgeCustomDialog(
    title: String,
    message: String,
    actions: List<ForgeDialogAction>,
    onDismiss: () -> Unit
) {
    ForgeDialogContainer(onDismiss = onDismiss) {
        Text(title, color = Colors.TextPrimary, fontSize = Type.body1, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(10.dp))
        Text(message, color = Colors.TextSecondary, fontSize = Type.body3, lineHeight = 20.sp)
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                ForgeDialogButton(
                    action.label,
                    primary = action.primary,
                    destructive = action.destructive,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = action.onClick
                )
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   6. FORGE BOTTOM SHEET  — slides up from the bottom
       forge.sheet(title, content, actions)
   ══════════════════════════════════════════════════════════════════════════ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgeBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF131313),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .size(40.dp, 4.dp)
                    .clip(Shape.full)
                    .background(Colors.Border)
            )
        },
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    color = Colors.TextPrimary,
                    fontSize = Type.body2,
                    fontWeight = FontWeight.ExtraBold
                )
                ForgeIconButton(Icons.Default.Close, onDismiss, size = 36.dp, iconSize = 18.dp)
            }
            content()
            if (footer != null) {
                Spacer(Modifier.height(12.dp))
                Column(content = footer)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   7. FORGE ACTION SHEET  — icon + label option list (iOS-style)
       forge.actionSheet(title, options[])
   ══════════════════════════════════════════════════════════════════════════ */

data class ForgeAction(
    val icon: ImageVector,
    val label: String,
    val sublabel: String? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun ForgeActionSheet(
    title: String,
    actions: List<ForgeAction>,
    onDismiss: () -> Unit
) {
    ForgeBottomSheet(title = title, onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            actions.forEach { action ->
                val color = if (action.destructive) Colors.Error else Colors.TextPrimary
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(Shape.lg)
                        .clickable { action.onClick(); onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(Shape.md)
                            .background(color.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(action.icon, null, tint = color, modifier = Modifier.size(22.dp))
                    }
                    Column {
                        Text(action.label, color = color, fontSize = Type.body2, fontWeight = FontWeight.SemiBold)
                        if (action.sublabel != null) {
                            Text(action.sublabel, color = Colors.TextSecondary, fontSize = Type.label1)
                        }
                    }
                }
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   8. FORGE INPUT SHEET  — multi-field form in a bottom sheet
       forge.inputSheet(title, fields[])
   ══════════════════════════════════════════════════════════════════════════ */

data class ForgeFormField(
    val label: String,
    val placeholder: String = "",
    val isPassword: Boolean = false,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val multiline: Boolean = false
)

@Composable
fun ForgeInputSheet(
    title: String,
    fields: List<ForgeFormField>,
    confirmLabel: String = "SAVE",
    onConfirm: (values: Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val values = remember { mutableStateMapOf<String, String>() }

    ForgeBottomSheet(
        title = title,
        onDismiss = onDismiss,
        footer = {
            ForgeDialogButton(confirmLabel, primary = true, modifier = Modifier.fillMaxWidth()) {
                onConfirm(values.toMap())
                onDismiss()
            }
            Spacer(Modifier.height(8.dp))
            ForgeDialogButton("CANCEL", primary = false, modifier = Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEach { field ->
                val currentVal = values[field.label] ?: ""
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        field.label.uppercase(),
                        color = Colors.TextTertiary,
                        fontSize = Type.caption,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.Black.copy(0.4f))
                            .border(Dp.Hairline, Colors.BorderAccent, RoundedCornerShape(16.dp))
                            .padding(14.dp)
                    ) {
                        if (currentVal.isEmpty()) {
                            Text(field.placeholder, color = Colors.TextTertiary, fontSize = Type.body3)
                        }
                        BasicTextField(
                            value = currentVal,
                            onValueChange = { values[field.label] = it },
                            textStyle = LocalTextStyle.current.copy(
                                color = Colors.TextPrimary, fontSize = Type.body3
                            ),
                            visualTransformation = if (field.isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = field.keyboardType),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = !field.multiline,
                            maxLines = if (field.multiline) 4 else 1
                        )
                    }
                }
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   9. FORGE TOAST  — displayed via ForgeToastHost() at root level
   ══════════════════════════════════════════════════════════════════════════ */

enum class ForgeToastType { Default, Success, Error, Info }

data class ForgeToastData(
    val message: String,
    val type: ForgeToastType = ForgeToastType.Default,
    val action: Pair<String, () -> Unit>? = null  // label → callback
)

/** Place at the root of your screen/nav host — shows queued toasts. */
@Composable
fun ForgeToastHost(
    toastData: ForgeToastData?,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = toastData != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier
    ) {
        if (toastData != null) {
            val color = when (toastData.type) {
                ForgeToastType.Success -> Colors.Success
                ForgeToastType.Error   -> Colors.Error
                ForgeToastType.Info    -> Colors.Accent
                ForgeToastType.Default -> Colors.TextSecondary
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .clip(Shape.full)
                    .background(Color(0xFF1A1A1A))
                    .border(Dp.Hairline, color.copy(0.3f), Shape.full)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(toastData.message, color = color, fontSize = Type.body3, fontWeight = FontWeight.SemiBold)
                    if (toastData.action != null) {
                        Text(
                            toastData.action.first,
                            color = color,
                            fontSize = Type.label1,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.clickable(onClick = toastData.action.second)
                        )
                    }
                }
            }
        }
    }
}

/* ══════════════════════════════════════════════════════════════════════════
   INTERNAL — Shared button used by dialogs
   ══════════════════════════════════════════════════════════════════════════ */

@Composable
internal fun ForgeDialogButton(
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        destructive -> Colors.Error
        primary     -> Colors.Accent
        else        -> Color.White.copy(0.05f)
    }
    val border = when {
        destructive || primary -> Color.Transparent
        else                   -> Colors.BorderStrong
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(Shape.lg)
            .background(
                if (primary && !destructive) Brush.linearGradient(
                    listOf(Colors.Accent, Color(0xFFFF8C00))
                ) else Brush.linearGradient(listOf(bg, bg))
            )
            .border(Dp.Hairline, border, Shape.lg)
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (primary || destructive) Color.White else Colors.TextPrimary,
            fontSize = Type.body3,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            textAlign = TextAlign.Center
        )
    }
}
