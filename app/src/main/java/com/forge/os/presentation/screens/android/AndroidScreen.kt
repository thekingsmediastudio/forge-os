package com.forge.os.presentation.screens.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.forge.os.data.android.AndroidController
import com.forge.os.presentation.screens.common.ForgeOsPalette
import com.forge.os.presentation.screens.common.ModuleScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AndroidScreenViewModel @Inject constructor(
    private val controller: AndroidController,
) : ViewModel() {
    private val _snapshot = MutableStateFlow<String>("Tap REFRESH to load device snapshot.")
    val snapshot: StateFlow<String> = _snapshot

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _snapshot.value = runCatching {
                controller.snapshot().toString().chunked(100).joinToString("\n")
            }.getOrElse { "Error: ${it.message}" }
        }
    }
}

@Composable
fun AndroidScreen(onBack: () -> Unit, viewModel: AndroidScreenViewModel = hiltViewModel()) {
    val snapshot by viewModel.snapshot.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }

    ModuleScaffold(title = "ANDROID", onBack = onBack) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
            Button(
                onClick = { viewModel.refresh() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = ForgeOsPalette.Orange),
            ) { Text("REFRESH", fontFamily = FontFamily.Monospace) }
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(ForgeOsPalette.Surface, RoundedCornerShape(6.dp))
                    .border(1.dp, ForgeOsPalette.Border, RoundedCornerShape(6.dp))
                    .padding(10.dp)
            ) {
                Text("DEVICE SNAPSHOT", color = ForgeOsPalette.Orange,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(snapshot, color = ForgeOsPalette.TextPrimary,
                    fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}
