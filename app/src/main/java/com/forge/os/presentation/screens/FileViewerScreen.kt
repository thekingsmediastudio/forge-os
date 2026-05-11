package com.forge.os.presentation.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(
    path: String,
    onNavigateBack: () -> Unit,
    viewModel: FileViewerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val ctx = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(path) { viewModel.load(path) }
    LaunchedEffect(state.message, state.error) {
        val text = state.error ?: state.message
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.consumeMessage()
        }
    }

    var confirmOutside by remember { mutableStateOf(false) }
    val dirty = viewModel.isDirty

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(state.name.ifEmpty { "Viewer" }, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    if (state.kind == FileKind.TEXT) {
                        IconButton(onClick = {
                            if (state.outsideSandbox) confirmOutside = true else viewModel.save()
                        }, enabled = dirty) {
                            Icon(Icons.Default.Save, "Save")
                        }
                    }
                    if (state.kind == FileKind.BINARY || state.kind == FileKind.IMAGE) {
                        IconButton(onClick = { openWith(ctx, state) }) {
                            Icon(Icons.Default.OpenInNew, "Open with…")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.error != null && state.name.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(state.error!!) }
                state.kind == FileKind.TEXT -> TextEditor(state, viewModel::edit)
                state.kind == FileKind.IMAGE -> ImageViewer(state)
                state.kind == FileKind.BINARY -> BinaryViewer(state)
            }
        }
    }

    if (confirmOutside) {
        AlertDialog(
            onDismissRequest = { confirmOutside = false },
            title = { Text("Save outside sandbox?") },
            text = { Text("This file resolved outside the workspace root. Saving may be blocked by the security policy.") },
            confirmButton = {
                TextButton(onClick = { confirmOutside = false; viewModel.save(force = true) }) { Text("Save anyway") }
            },
            dismissButton = { TextButton(onClick = { confirmOutside = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextEditor(state: FileViewerState, onChange: (String) -> Unit) {
    val lang = remember(state.name) { languageFor(state.name) }
    val highlighted = rememberHighlighted(state.text, lang)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    Column(modifier = Modifier.fillMaxSize()) {
        // Read-only colored layer underneath; editable BasicTextField on top with transparent text
        // would be ideal, but for simplicity we fall back to the editable field with a visible
        // monospace style and surface the highlighted preview only when the file is unmodified.
        if (state.text == state.originalText && lang != "plain") {
            // Show coloured preview while idle.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                androidx.compose.material3.Text(text = highlighted, style = mono)
            }
        } else {
            BasicTextField(
                value = state.text,
                onValueChange = onChange,
                textStyle = mono.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun ImageViewer(state: FileViewerState) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offsetX += pan.x
                    offsetY += pan.y
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = state.absolutePath,
            contentDescription = state.name,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY,
                ),
        )
    }
}

@Composable
private fun BinaryViewer(state: FileViewerState) {
    Column(modifier = Modifier.fillMaxSize()) {
        Card(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(state.name, style = MaterialTheme.typography.titleMedium)
                Text("${state.size} bytes • ${state.mimeType}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(
            "Hex preview (first 4 KB)",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(
                state.hexPreview,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            )
        }
    }
}

private fun openWith(ctx: android.content.Context, state: FileViewerState) {
    if (state.absolutePath.isEmpty()) return
    val file = java.io.File(state.absolutePath)
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, state.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    ctx.startActivity(Intent.createChooser(intent, "Open ${file.name}").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
