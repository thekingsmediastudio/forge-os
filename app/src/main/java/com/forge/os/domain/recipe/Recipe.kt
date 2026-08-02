package com.forge.os.domain.recipe

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * A pre-built prompt template (recipe) for common AI tasks.
 */
@Serializable
data class Recipe(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String,
    val prompt: String,
    val category: RecipeCategory,
    val icon: String = "📝",
    val isBuiltIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class RecipeCategory(val displayName: String, val icon: String) {
    WRITING("Writing", "✍️"),
    CODING("Coding", "💻"),
    ANALYSIS("Analysis", "📊"),
    CREATIVE("Creative", "🎨"),
    PRODUCTIVITY("Productivity", "⚡"),
    LEARNING("Learning", "📚"),
    CUSTOM("Custom", "✨")
}

/**
 * Built-in recipes that ship with the app.
 */
object BuiltInRecipes {
    val ALL = listOf(
        // ── Writing ─────────────────────────────────────────────────────────
        Recipe(
            id = "builtin-summarize",
            title = "Summarize Text",
            description = "Create a concise summary of any text",
            prompt = "Please summarize the following text in a clear and concise manner, highlighting the key points:\n\n",
            category = RecipeCategory.WRITING,
            icon = "📝",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-rewrite",
            title = "Rewrite & Improve",
            description = "Improve clarity and flow of writing",
            prompt = "Please rewrite the following text to improve its clarity, flow, and professionalism while maintaining the original meaning:\n\n",
            category = RecipeCategory.WRITING,
            icon = "✏️",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-email",
            title = "Draft Email",
            description = "Write a professional email",
            prompt = "Please draft a professional email for the following purpose. Include a subject line and appropriate greeting/closing:\n\n",
            category = RecipeCategory.WRITING,
            icon = "📧",
            isBuiltIn = true
        ),
        
        // ── Coding ──────────────────────────────────────────────────────────
        Recipe(
            id = "builtin-explain-code",
            title = "Explain Code",
            description = "Get a detailed explanation of code",
            prompt = "Please explain the following code in detail. Break down what each part does and explain the overall logic:\n\n```\n",
            category = RecipeCategory.CODING,
            icon = "🔍",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-debug",
            title = "Debug Code",
            description = "Find and fix bugs in code",
            prompt = "Please analyze the following code for bugs, errors, or potential issues. Suggest fixes:\n\n```\n",
            category = RecipeCategory.CODING,
            icon = "🐛",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-write-code",
            title = "Write Code",
            description = "Generate code from description",
            prompt = "Please write code for the following requirement. Include comments explaining key parts:\n\n",
            category = RecipeCategory.CODING,
            icon = "💻",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-optimize",
            title = "Optimize Code",
            description = "Improve code performance",
            prompt = "Please optimize the following code for better performance. Explain the improvements:\n\n```\n",
            category = RecipeCategory.CODING,
            icon = "⚡",
            isBuiltIn = true
        ),
        
        // ── Analysis ────────────────────────────────────────────────────────
        Recipe(
            id = "builtin-pros-cons",
            title = "Pros & Cons",
            description = "Analyze advantages and disadvantages",
            prompt = "Please analyze the following topic and list the pros and cons in a balanced way:\n\n",
            category = RecipeCategory.ANALYSIS,
            icon = "⚖️",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-compare",
            title = "Compare Options",
            description = "Compare multiple options side by side",
            prompt = "Please compare the following options across key criteria. Present in a clear comparison format:\n\n",
            category = RecipeCategory.ANALYSIS,
            icon = "📊",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-swot",
            title = "SWOT Analysis",
            description = "Strengths, Weaknesses, Opportunities, Threats",
            prompt = "Please perform a SWOT analysis (Strengths, Weaknesses, Opportunities, Threats) for the following:\n\n",
            category = RecipeCategory.ANALYSIS,
            icon = "🎯",
            isBuiltIn = true
        ),
        
        // ── Creative ────────────────────────────────────────────────────────
        Recipe(
            id = "builtin-brainstorm",
            title = "Brainstorm Ideas",
            description = "Generate creative ideas",
            prompt = "Please brainstorm creative ideas for the following. Generate at least 10 unique and diverse ideas:\n\n",
            category = RecipeCategory.CREATIVE,
            icon = "💡",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-story",
            title = "Write Story",
            description = "Create a short story",
            prompt = "Please write a short story based on the following premise. Make it engaging with vivid descriptions:\n\n",
            category = RecipeCategory.CREATIVE,
            icon = "📖",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-poem",
            title = "Write Poem",
            description = "Create a poem",
            prompt = "Please write a poem about the following theme. Use creative imagery and rhythm:\n\n",
            category = RecipeCategory.CREATIVE,
            icon = "🎭",
            isBuiltIn = true
        ),
        
        // ── Productivity ────────────────────────────────────────────────────
        Recipe(
            id = "builtin-todo",
            title = "Create To-Do List",
            description = "Break down tasks into actionable items",
            prompt = "Please break down the following goal/project into a detailed to-do list with actionable steps:\n\n",
            category = RecipeCategory.PRODUCTIVITY,
            icon = "✅",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-plan",
            title = "Create Plan",
            description = "Create a step-by-step plan",
            prompt = "Please create a detailed step-by-step plan to achieve the following goal. Include timeline suggestions:\n\n",
            category = RecipeCategory.PRODUCTIVITY,
            icon = "📋",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-meeting",
            title = "Meeting Notes",
            description = "Structure meeting notes",
            prompt = "Please organize the following meeting notes into a structured format with key decisions, action items, and next steps:\n\n",
            category = RecipeCategory.PRODUCTIVITY,
            icon = "📝",
            isBuiltIn = true
        ),
        
        // ── Learning ────────────────────────────────────────────────────────
        Recipe(
            id = "builtin-eli5",
            title = "Explain Like I'm 5",
            description = "Simple explanation of complex topics",
            prompt = "Please explain the following concept in the simplest possible terms, as if explaining to a 5-year-old:\n\n",
            category = RecipeCategory.LEARNING,
            icon = "👶",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-quiz",
            title = "Create Quiz",
            description = "Generate quiz questions",
            prompt = "Please create a quiz with 5 multiple-choice questions about the following topic. Include answers:\n\n",
            category = RecipeCategory.LEARNING,
            icon = "❓",
            isBuiltIn = true
        ),
        Recipe(
            id = "builtin-flashcards",
            title = "Create Flashcards",
            description = "Generate study flashcards",
            prompt = "Please create study flashcards for the following topic. Format as Q: [question] A: [answer] pairs:\n\n",
            category = RecipeCategory.LEARNING,
            icon = "🃏",
            isBuiltIn = true
        )
    )
    
    fun byCategory(category: RecipeCategory): List<Recipe> = ALL.filter { it.category == category }
}
