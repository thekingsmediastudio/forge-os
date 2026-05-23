package com.forge.os.presentation.screens.python

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.presentation.components.*
import com.forge.os.presentation.theme.ForgeTokens.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PythonPackageListScreen(
    onBack: () -> Unit,
    viewModel: PythonPackageListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredPackages = remember(state.packages, searchQuery) {
        if (searchQuery.isBlank()) state.packages
        else state.packages.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    ForgeScreenScaffold {
        Column(Modifier.fillMaxSize()) {
            ForgeTopBar(
                title = "PYTHON LAB",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Default.Refresh, "Refresh", tint = Colors.TextPrimary)
                    }
                }
            )

            // Search Bar
            Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Filter units...", color = Colors.TextMuted, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Colors.TextMuted, modifier = Modifier.size(18.dp)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Colors.Accent,
                        unfocusedBorderColor = Colors.Border,
                        focusedTextColor = Colors.TextPrimary,
                        unfocusedTextColor = Colors.TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                )
            }

            if (state.isLoading && state.packages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Colors.Accent)
                }
            } else if (state.error != null) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("❌ ENVIRONMENT ERROR", color = Colors.Error, fontWeight = FontWeight.Black, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(state.error!!, color = Colors.TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        ForgeButton(text = "RETRY SYNC", onClick = { viewModel.refresh() })
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "INSTALLED DISTRIBUTIONS",
                                color = Colors.TextTertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.5.sp
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${filteredPackages.size} UNITS",
                                color = Colors.Accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    items(filteredPackages) { pkg ->
                        PackageItem(pkg)
                    }

                    if (filteredPackages.isEmpty() && searchQuery.isNotBlank()) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                                Text("NO MATCHING UNITS FOUND", color = Colors.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun PackageItem(pkg: PythonPackage) {
    ForgeCard(padding = PaddingValues(12.dp, 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(Colors.Accent.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Inventory2,
                    null,
                    tint = Colors.Accent,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pkg.name,
                    color = Colors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Source: Chaquopy 3.11",
                    color = Colors.TextTertiary,
                    fontSize = 10.sp
                )
            }
            Box(
                modifier = Modifier
                    .background(Colors.Border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "v${pkg.version}",
                    color = Colors.TextSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
