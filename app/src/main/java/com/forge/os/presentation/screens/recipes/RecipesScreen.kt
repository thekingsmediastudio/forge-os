package com.forge.os.presentation.screens.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.forge.os.domain.recipe.Recipe
import com.forge.os.domain.recipe.RecipeCategory
import com.forge.os.presentation.theme.forgePalette
import com.forge.os.presentation.screens.common.ModuleScaffold

@Composable
fun RecipesScreen(
    onBack: () -> Unit,
    onUseInChat: (String) -> Unit,
    viewModel: RecipesViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var selected: Recipe? by remember { mutableStateOf(null) }
    var editing: Recipe? by remember { mutableStateOf(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.dismissMessage() }
    }

    ModuleScaffold(
        title = "RECIPES",
        onBack = onBack,
        actions = {
            IconButton(onClick = { creating = true }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "New",
                    tint = forgePalette.orange, modifier = Modifier.size(20.dp))
            }
        }) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = state.query, onValueChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                placeholder = { Text("search recipes…", color = forgePalette.textDim, fontSize = 12.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = forgePalette.textPrimary, fontSize = 12.sp))
            CategoryFilterRow(
                selected = state.selectedCategory,
                onSelect = viewModel::setCategory)
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.recipes.isEmpty()) item {
                        Text("No recipes match.\nRecipes are ready-made prompt templates for common tasks.",
                            color = forgePalette.textMuted, fontSize = 11.sp)
                    }
                    items(state.recipes, key = { it.id }) { r ->
                        RecipeCard(r = r, onClick = { selected = r })
                    }
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter)) {
                    Snackbar(containerColor = forgePalette.surface2,
                        contentColor = forgePalette.textPrimary) { Text(it.visuals.message) }
                }
            }
        }
    }

    val sel = selected
    if (sel != null) RecipeDetailDialog(
        recipe = sel,
        onUse = { onUseInChat(sel.prompt); selected = null },
        onEdit = if (sel.isBuiltIn) null else ({ editing = sel; selected = null }),
        onDelete = if (sel.isBuiltIn) null else ({ viewModel.delete(sel.id); selected = null }),
        onDismiss = { selected = null })

    if (creating) RecipeEditDialog(
        initial = null,
        onSave = { t, d, p, c -> viewModel.create(t, d, p, c); creating = false },
        onDismiss = { creating = false })
    val ed = editing
    if (ed != null) RecipeEditDialog(
        initial = ed,
        onSave = { t, d, p, c ->
            viewModel.update(ed.copy(title = t, description = d, prompt = p, category = c, icon = c.icon))
            editing = null
        },
        onDismiss = { editing = null })
}

@Composable
private fun CategoryFilterRow(
    selected: RecipeCategory?,
    onSelect: (RecipeCategory?) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            FilterChip(label = "All", active = selected == null) { onSelect(null) }
        }
        items(RecipeCategory.entries.toList(), key = { it.name }) { cat ->
            FilterChip(label = "${cat.icon} ${cat.displayName}", active = selected == cat) {
                onSelect(if (selected == cat) null else cat)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.background(if (active) forgePalette.orange else forgePalette.surface,
                RoundedCornerShape(12.dp))
            .border(1.dp, if (active) forgePalette.orange else forgePalette.border,
                RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(label,
            color = if (active) forgePalette.bg else forgePalette.textMuted,
            fontSize = 10.sp)
    }
}

@Composable
private fun RecipeCard(r: Recipe, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(forgePalette.surface, RoundedCornerShape(6.dp))
            .border(1.dp, forgePalette.border, RoundedCornerShape(6.dp))
            .clickable { onClick() }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.icon, fontSize = 16.sp)
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(r.title, color = forgePalette.orange, fontSize = 13.sp)
                Text(r.description, color = forgePalette.textPrimary, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("${r.category.displayName}${if (r.isBuiltIn) " • built-in" else " • custom"}",
            color = forgePalette.textDim, fontSize = 10.sp)
    }
}

@Composable
private fun RecipeDetailDialog(
    recipe: Recipe,
    onUse: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = forgePalette.surface,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(recipe.icon, fontSize = 16.sp)
                Spacer(Modifier.size(8.dp))
                Text(recipe.title, color = forgePalette.orange, fontSize = 14.sp)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(recipe.description, color = forgePalette.textMuted, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth()
                    .background(forgePalette.bg, RoundedCornerShape(4.dp)).padding(8.dp)) {
                    Text(recipe.prompt, color = forgePalette.textPrimary, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("DELETE", color = forgePalette.danger)
                }
                if (onEdit != null) TextButton(onClick = onEdit) {
                    Text("EDIT", color = forgePalette.textMuted)
                }
                TextButton(onClick = onUse) {
                    Text("USE IN CHAT", color = forgePalette.success)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = forgePalette.textMuted)
            }
        })
}

@Composable
private fun RecipeEditDialog(
    initial: Recipe?,
    onSave: (String, String, String, RecipeCategory) -> Unit,
    onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var desc by remember { mutableStateOf(initial?.description ?: "") }
    var prompt by remember { mutableStateOf(initial?.prompt ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: RecipeCategory.CUSTOM) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = forgePalette.surface,
        title = { Text(if (initial == null) "New recipe" else "Edit recipe",
            color = forgePalette.orange, fontSize = 14.sp) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text("title", color = forgePalette.textMuted, fontSize = 10.sp)
                OutlinedTextField(value = title, onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = forgePalette.textPrimary, fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                Text("description", color = forgePalette.textMuted, fontSize = 10.sp)
                OutlinedTextField(value = desc, onValueChange = { desc = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = forgePalette.textPrimary, fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                Text("prompt", color = forgePalette.textMuted, fontSize = 10.sp)
                OutlinedTextField(value = prompt, onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = forgePalette.textPrimary, fontSize = 11.sp))
                Spacer(Modifier.height(6.dp))
                Text("category", color = forgePalette.textMuted, fontSize = 10.sp)
                Spacer(Modifier.height(4.dp))
                CategoryFilterRow(
                    selected = category,
                    onSelect = { category = it ?: RecipeCategory.CUSTOM })
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && prompt.isNotBlank(),
                onClick = { onSave(title.trim(), desc.trim(), prompt, category) }) {
                Text("SAVE", color = forgePalette.success)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = forgePalette.textMuted)
            }
        })
}
