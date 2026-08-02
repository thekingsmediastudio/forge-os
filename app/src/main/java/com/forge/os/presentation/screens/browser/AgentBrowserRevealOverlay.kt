package com.forge.os.presentation.screens.browser

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.forge.os.data.browser.BrowserRevealManager
import com.forge.os.data.web.HeadlessBrowser
import com.forge.os.presentation.theme.forgePalette
import kotlinx.coroutines.launch

/**
 * Full-screen overlay that shows the agent's headless WebView to the user.
 * The exact same page the agent was viewing is displayed — no reload, no
 * state loss. When the user taps "Done", the WebView is detached and
 * returned to headless mode so the agent can continue.
 */
@Composable
fun AgentBrowserRevealOverlay(
    request: BrowserRevealManager.BrowserRevealRequest,
    headlessBrowser: HeadlessBrowser,
    browserRevealManager: BrowserRevealManager,
) {
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = {
            scope.launch {
                headlessBrowser.detachFromVisibleContainer()
                browserRevealManager.dismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(forgePalette.bg)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ─── Top bar ────────────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = forgePalette.surface,
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Agent Browser",
                                color = forgePalette.textPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = request.url,
                                color = forgePalette.textMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = {
                                scope.launch {
                                    headlessBrowser.detachFromVisibleContainer()
                                    browserRevealManager.dismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = forgePalette.orange,
                                contentColor = forgePalette.bg,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Done", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // ─── Agent message banner ───────────────────────────────
                if (request.message.isNotBlank()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = forgePalette.orange.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = request.message,
                            color = forgePalette.orange,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }

                // ─── WebView container ──────────────────────────────────
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                scope.launch {
                                    headlessBrowser.attachTo(this@apply)
                                }
                            }
                        },
                        onRelease = {
                            scope.launch {
                                headlessBrowser.detachFromVisibleContainer()
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
