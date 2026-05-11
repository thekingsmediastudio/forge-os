package com.forge.os.presentation.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Lightweight, regex-based syntax colouring for the in-app file viewer. Intentionally
 * simple — this is not a real lexer. It paints comments, strings, numbers, and a small
 * list of keywords for json / md / py / kt / yaml. Anything else returns plain text.
 */
@Composable
fun rememberHighlighted(text: String, language: String): AnnotatedString {
    val colors = highlightColors()
    return remember(text, language, colors.hashCode()) { highlight(text, language, colors) }
}

private data class HiColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val punct: Color,
)

@Composable
private fun highlightColors(): HiColors {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return HiColors(
        keyword = primary,
        string = tertiary,
        number = onSurface.copy(alpha = 0.85f),
        comment = muted,
        punct = onSurface,
    )
}

private fun highlight(text: String, lang: String, c: HiColors): AnnotatedString {
    val rules = rulesFor(lang) ?: return AnnotatedString(text)
    val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()
    rules.forEach { rule ->
        rule.regex.findAll(text).forEach { m ->
            val color = when (rule.kind) {
                Kind.KEYWORD -> c.keyword
                Kind.STRING -> c.string
                Kind.NUMBER -> c.number
                Kind.COMMENT -> c.comment
            }
            spans += Triple(m.range.first, m.range.last + 1, SpanStyle(color = color))
        }
    }
    spans.sortBy { it.first }
    return buildAnnotatedString {
        var cursor = 0
        // Greedy non-overlapping pass: skip spans that start inside an already-applied span.
        val applied = mutableListOf<IntRange>()
        for ((start, end, style) in spans) {
            if (applied.any { start in it }) continue
            if (start > cursor) append(text.substring(cursor, start))
            withStyle(style) { append(text.substring(start, end)) }
            cursor = end
            applied += start until end
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

private enum class Kind { KEYWORD, STRING, NUMBER, COMMENT }
private data class Rule(val regex: Regex, val kind: Kind)

private fun rulesFor(lang: String): List<Rule>? {
    val ml = setOf(RegexOption.MULTILINE)
    return when (lang) {
        "json" -> listOf(
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), Kind.STRING),
            Rule(Regex("\\b-?\\d+(?:\\.\\d+)?\\b"), Kind.NUMBER),
            Rule(Regex("\\b(?:true|false|null)\\b"), Kind.KEYWORD),
        )
        "py" -> listOf(
            Rule(Regex("#.*", ml), Kind.COMMENT),
            Rule(Regex("(?:'''[\\s\\S]*?'''|\"\"\"[\\s\\S]*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"), Kind.STRING),
            Rule(Regex("\\b\\d+(?:\\.\\d+)?\\b"), Kind.NUMBER),
            Rule(Regex("\\b(?:def|class|if|elif|else|for|while|return|import|from|as|with|try|except|finally|raise|yield|in|not|and|or|is|None|True|False|lambda|pass|break|continue|global|nonlocal|async|await)\\b"), Kind.KEYWORD),
        )
        "kt" -> listOf(
            Rule(Regex("//.*", ml), Kind.COMMENT),
            Rule(Regex("/\\*[\\s\\S]*?\\*/"), Kind.COMMENT),
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\""), Kind.STRING),
            Rule(Regex("\\b\\d+(?:\\.\\d+)?[fLuU]?\\b"), Kind.NUMBER),
            Rule(Regex("\\b(?:fun|val|var|class|object|interface|package|import|return|if|else|when|for|while|do|try|catch|finally|throw|in|is|as|by|out|in|null|true|false|this|super|sealed|data|enum|companion|override|open|abstract|private|public|protected|internal|suspend|inline|operator|infix|tailrec|const|lateinit|init|typealias|where)\\b"), Kind.KEYWORD),
        )
        "md" -> listOf(
            Rule(Regex("^#{1,6} .*", ml), Kind.KEYWORD),
            Rule(Regex("\\*\\*[^*]+\\*\\*"), Kind.KEYWORD),
            Rule(Regex("`[^`]+`"), Kind.STRING),
            Rule(Regex("^>.*", ml), Kind.COMMENT),
            Rule(Regex("\\[[^\\]]+]\\([^)]+\\)"), Kind.STRING),
        )
        "yaml" -> listOf(
            Rule(Regex("#.*", ml), Kind.COMMENT),
            Rule(Regex("^[\\s-]*[A-Za-z_][\\w-]*(?=\\s*:)", ml), Kind.KEYWORD),
            Rule(Regex("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'"), Kind.STRING),
            Rule(Regex("\\b-?\\d+(?:\\.\\d+)?\\b"), Kind.NUMBER),
        )
        else -> null
    }
}

fun languageFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "json" -> "json"
    "py" -> "py"
    "kt", "kts" -> "kt"
    "md", "markdown" -> "md"
    "yaml", "yml" -> "yaml"
    else -> "plain"
}
