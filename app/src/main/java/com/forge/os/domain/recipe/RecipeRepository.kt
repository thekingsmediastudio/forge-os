package com.forge.os.domain.recipe

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for user-created recipes.
 *
 * Built-in recipes ship with the app (see [BuiltInRecipes]) and are read-only;
 * custom recipes are stored at workspace/recipes/recipes.json.
 */
@Singleton
class RecipeRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; prettyPrint = true }

    private val recipesDir: File get() = context.filesDir.resolve("workspace/recipes").apply { mkdirs() }
    private val recipesFile: File get() = recipesDir.resolve("recipes.json")

    private var cache: MutableList<Recipe> = mutableListOf()

    init { load() }

    private fun load() {
        cache = try {
            if (recipesFile.exists()) {
                json.decodeFromString<List<Recipe>>(recipesFile.readText()).toMutableList()
            } else mutableListOf()
        } catch (e: Exception) {
            Timber.e(e, "RecipeRepository: load failed")
            mutableListOf()
        }
    }

    /** All recipes: built-ins first, then custom (newest first). */
    @Synchronized
    fun all(): List<Recipe> = BuiltInRecipes.ALL + cache.sortedByDescending { it.createdAt }

    @Synchronized
    fun byId(id: String): Recipe? =
        BuiltInRecipes.ALL.find { it.id == id } ?: cache.find { it.id == id }

    @Synchronized
    fun byCategory(category: RecipeCategory): List<Recipe> =
        all().filter { it.category == category }

    @Synchronized
    fun search(query: String): List<Recipe> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return all()
        return all().filter {
            it.title.lowercase().contains(q) ||
                it.description.lowercase().contains(q) ||
                it.prompt.lowercase().contains(q)
        }
    }

    /** Adds a custom recipe. Built-in IDs are reserved and rejected. */
    @Synchronized
    fun add(recipe: Recipe): Boolean {
        if (recipe.isBuiltIn || BuiltInRecipes.ALL.any { it.id == recipe.id }) return false
        cache.removeIf { it.id == recipe.id }
        cache.add(recipe)
        persist()
        return true
    }

    /** Updates an existing custom recipe. Built-ins cannot be modified. */
    @Synchronized
    fun update(recipe: Recipe): Boolean {
        val idx = cache.indexOfFirst { it.id == recipe.id }
        if (idx < 0) return false
        cache[idx] = recipe.copy(isBuiltIn = false)
        persist()
        return true
    }

    /** Removes a custom recipe. Built-ins cannot be deleted. */
    @Synchronized
    fun remove(id: String): Boolean {
        val removed = cache.removeIf { it.id == id }
        if (removed) persist()
        return removed
    }

    @Synchronized
    private fun persist() {
        try {
            recipesFile.writeText(json.encodeToString(cache as List<Recipe>))
        } catch (e: Exception) {
            Timber.e(e, "RecipeRepository: persist failed")
        }
    }
}
