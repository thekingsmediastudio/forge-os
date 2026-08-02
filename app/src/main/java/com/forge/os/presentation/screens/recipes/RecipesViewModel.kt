package com.forge.os.presentation.screens.recipes

import androidx.lifecycle.ViewModel
import com.forge.os.domain.recipe.Recipe
import com.forge.os.domain.recipe.RecipeCategory
import com.forge.os.domain.recipe.RecipeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class RecipesUiState(
    val recipes: List<Recipe> = emptyList(),
    val query: String = "",
    val selectedCategory: RecipeCategory? = null,
    val message: String? = null)

@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val repository: RecipeRepository) : ViewModel() {

    private val _state = MutableStateFlow(RecipesUiState())
    val state: StateFlow<RecipesUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val q = _state.value.query
        val cat = _state.value.selectedCategory
        var list = if (q.isBlank()) repository.all() else repository.search(q)
        if (cat != null) list = list.filter { it.category == cat }
        _state.value = _state.value.copy(recipes = list)
    }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q); refresh() }

    fun setCategory(category: RecipeCategory?) {
        _state.value = _state.value.copy(selectedCategory = category); refresh()
    }

    fun create(title: String, description: String, prompt: String, category: RecipeCategory) {
        val recipe = Recipe(
            title = title.trim(),
            description = description.trim(),
            prompt = prompt,
            category = category,
            icon = category.icon,
            isBuiltIn = false)
        if (repository.add(recipe)) {
            _state.value = _state.value.copy(message = "Recipe created")
        } else {
            _state.value = _state.value.copy(message = "❌ could not create recipe")
        }
        refresh()
    }

    fun update(recipe: Recipe) {
        if (repository.update(recipe)) {
            _state.value = _state.value.copy(message = "Recipe updated")
        } else {
            _state.value = _state.value.copy(message = "❌ built-in recipes can't be edited")
        }
        refresh()
    }

    fun delete(id: String) {
        if (repository.remove(id)) {
            _state.value = _state.value.copy(message = "Recipe deleted")
        } else {
            _state.value = _state.value.copy(message = "❌ built-in recipes can't be deleted")
        }
        refresh()
    }

    fun dismissMessage() { _state.value = _state.value.copy(message = null) }
}
