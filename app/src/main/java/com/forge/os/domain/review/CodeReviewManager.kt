package com.forge.os.domain.review

import android.content.Context
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.projects.ProjectsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-powered code review system that analyzes code changes and suggests improvements.
 * 
 * Features:
 * - Automated code quality analysis
 * - Security vulnerability detection
 * - Performance optimization suggestions
 * - Best practices enforcement
 * - Integration with Git for diff analysis
 * - Learning from review patterns
 */
@Singleton
class CodeReviewManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiApiManager: AiApiManager,
    private val sandboxManager: SandboxManager,
    private val projectsRepository: ProjectsRepository,
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
) {

    data class CodeReviewResult(
        val overallScore: Int, // 0-100
        val issues: List<CodeIssue>,
        val suggestions: List<CodeSuggestion>,
        val summary: String,
        val filesReviewed: Int,
        val linesAnalyzed: Int
    )

    data class CodeIssue(
        val severity: IssueSeverity,
        val category: IssueCategory,
        val file: String,
        val line: Int?,
        val description: String,
        val suggestion: String,
        val ruleId: String
    )

    data class CodeSuggestion(
        val type: SuggestionType,
        val file: String,
        val line: Int?,
        val description: String,
        val before: String?,
        val after: String?,
        val impact: String
    )

    enum class IssueSeverity {
        CRITICAL, HIGH, MEDIUM, LOW, INFO
    }

    enum class IssueCategory {
        SECURITY, PERFORMANCE, MAINTAINABILITY, RELIABILITY, STYLE, DOCUMENTATION
    }

    enum class SuggestionType {
        REFACTOR, OPTIMIZE, SIMPLIFY, MODERNIZE, DOCUMENT, TEST
    }

    /**
     * Review all changes in a project.
     */
    suspend fun reviewProject(projectSlug: String): CodeReviewResult = withContext(Dispatchers.IO) {
        val project = projectsRepository.get(projectSlug)
            ?: return@withContext CodeReviewResult(0, emptyList(), emptyList(), "Project not found", 0, 0)

        val projectPath = "projects/$projectSlug"
        val files = sandboxManager.listFiles(projectPath).getOrElse { emptyList() }
        
        val codeFiles = files.filter { isCodeFile(it.name) }
        val issues = mutableListOf<CodeIssue>()
        val suggestions = mutableListOf<CodeSuggestion>()
        var totalLines = 0

        for (file in codeFiles) {
            val filePath = "$projectPath/${file.name}"
            val content = sandboxManager.readFile(filePath).getOrNull() ?: continue
            
            val lines = content.lines()
            totalLines += lines.size
            
            val fileReview = reviewFile(file.name, content, detectLanguage(file.name))
            issues.addAll(fileReview.issues)
            suggestions.addAll(fileReview.suggestions)
        }

        val overallScore = calculateOverallScore(issues)
        val summary = generateReviewSummary(issues, suggestions, codeFiles.size, totalLines)

        // Record code review patterns
        try {
            reflectionManager.recordPattern(
                pattern = "Code review: $projectSlug (Score: $overallScore)",
                description = "Reviewed ${codeFiles.size} files, found ${issues.size} issues, ${suggestions.size} suggestions",
                applicableTo = listOf("code_review", "quality_assurance", projectSlug),
                tags = listOf("code_review", "quality_check", projectSlug, "score_$overallScore")
            )

            // Record issues by category for learning
            issues.groupBy { it.category }.forEach { (category, categoryIssues) ->
                reflectionManager.recordPattern(
                    pattern = "Code issues: ${category.name.lowercase()}",
                    description = "Found ${categoryIssues.size} ${category.name.lowercase()} issues in $projectSlug",
                    applicableTo = listOf("code_quality", category.name.lowercase(), projectSlug),
                    tags = listOf("code_issues", category.name.lowercase(), projectSlug)
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to record code review patterns")
        }

        CodeReviewResult(overallScore, issues, suggestions, summary, codeFiles.size, totalLines)
    }

    /**
     * Review specific file changes.
     */
    suspend fun reviewFile(fileName: String, content: String, language: String): CodeReviewResult = withContext(Dispatchers.IO) {
        val issues = mutableListOf<CodeIssue>()
        val suggestions = mutableListOf<CodeSuggestion>()

        // Static analysis
        issues.addAll(performStaticAnalysis(fileName, content, language))
        
        // AI-powered analysis
        val aiReview = performAiAnalysis(fileName, content, language)
        issues.addAll(aiReview.issues)
        suggestions.addAll(aiReview.suggestions)

        val overallScore = calculateOverallScore(issues)
        val summary = "Reviewed $fileName: ${issues.size} issues found"

        CodeReviewResult(overallScore, issues, suggestions, summary, 1, content.lines().size)
    }

    /**
     * Review Git diff/changes.
     */
    suspend fun reviewChanges(projectSlug: String, diff: String): CodeReviewResult = withContext(Dispatchers.IO) {
        val issues = mutableListOf<CodeIssue>()
        val suggestions = mutableListOf<CodeSuggestion>()

        // Parse diff and analyze only changed lines
        val changedFiles = parseDiff(diff)
        var totalLines = 0

        for ((fileName, changes) in changedFiles) {
            val language = detectLanguage(fileName)
            totalLines += changes.size
            
            // Focus review on changed lines
            val changeReview = reviewChangedLines(fileName, changes, language)
            issues.addAll(changeReview.issues)
            suggestions.addAll(changeReview.suggestions)
        }

        val overallScore = calculateOverallScore(issues)
        val summary = generateChangeReviewSummary(issues, suggestions, changedFiles.size, totalLines)

        CodeReviewResult(overallScore, issues, suggestions, summary, changedFiles.size, totalLines)
    }

    /**
     * Get review suggestions for specific code snippet.
     */
    suspend fun getQuickSuggestions(code: String, language: String): List<CodeSuggestion> = withContext(Dispatchers.IO) {
        try {
            val prompt = buildString {
                appendLine("Review this $language code and provide improvement suggestions:")
                appendLine("```$language")
                appendLine(code)
                appendLine("```")
                appendLine()
                appendLine("Focus on:")
                appendLine("- Code quality and readability")
                appendLine("- Performance optimizations")
                appendLine("- Security considerations")
                appendLine("- Best practices")
                appendLine()
                appendLine("Provide specific, actionable suggestions with examples.")
            }

            val response = aiApiManager.chatCompletion(
                messages = listOf(
                    com.forge.os.data.api.ApiMessage("system", "You are an expert code reviewer. Provide concise, actionable feedback."),
                    com.forge.os.data.api.ApiMessage("user", prompt)
                )
            )

            parseAiSuggestions(response.content ?: "", "snippet", language)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get AI suggestions")
            emptyList()
        }
    }

    // Private implementation methods

    private fun isCodeFile(fileName: String): Boolean {
        val codeExtensions = setOf("py", "js", "ts", "kt", "java", "cpp", "c", "h", "cs", "go", "rs", "php", "rb", "swift")
        return codeExtensions.contains(fileName.substringAfterLast('.', ""))
    }

    private fun detectLanguage(fileName: String): String {
        return when (fileName.substringAfterLast('.', "")) {
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "kt" -> "kotlin"
            "java" -> "java"
            "cpp", "cc", "cxx" -> "cpp"
            "c" -> "c"
            "h", "hpp" -> "c++"
            "cs" -> "csharp"
            "go" -> "go"
            "rs" -> "rust"
            "php" -> "php"
            "rb" -> "ruby"
            "swift" -> "swift"
            else -> "text"
        }
    }

    private fun performStaticAnalysis(fileName: String, content: String, language: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = content.lines()

        // Common static analysis rules
        lines.forEachIndexed { index, line ->
            val lineNumber = index + 1

            // Long lines
            if (line.length > 120) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.LOW,
                    category = IssueCategory.STYLE,
                    file = fileName,
                    line = lineNumber,
                    description = "Line too long (${line.length} characters)",
                    suggestion = "Consider breaking this line into multiple lines",
                    ruleId = "line_length"
                ))
            }

            // TODO comments
            if (line.contains("TODO", ignoreCase = true)) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.INFO,
                    category = IssueCategory.MAINTAINABILITY,
                    file = fileName,
                    line = lineNumber,
                    description = "TODO comment found",
                    suggestion = "Consider implementing or creating a task for this TODO",
                    ruleId = "todo_comment"
                ))
            }

            // Language-specific rules
            when (language) {
                "python" -> issues.addAll(analyzePythonLine(fileName, lineNumber, line))
                "javascript", "typescript" -> issues.addAll(analyzeJavaScriptLine(fileName, lineNumber, line))
                "kotlin", "java" -> issues.addAll(analyzeKotlinJavaLine(fileName, lineNumber, line))
            }
        }

        return issues
    }

    private suspend fun performAiAnalysis(fileName: String, content: String, language: String): CodeReviewResult {
        return try {
            val prompt = buildString {
                appendLine("Perform a comprehensive code review of this $language file:")
                appendLine("File: $fileName")
                appendLine()
                appendLine("```$language")
                appendLine(content.take(4000)) // Limit content size for API
                appendLine("```")
                appendLine()
                appendLine("Analyze for:")
                appendLine("1. Security vulnerabilities")
                appendLine("2. Performance issues")
                appendLine("3. Code quality and maintainability")
                appendLine("4. Best practices adherence")
                appendLine("5. Potential bugs")
                appendLine()
                appendLine("Provide specific issues with line numbers and actionable suggestions.")
            }

            val response = aiApiManager.chatCompletion(
                messages = listOf(
                    com.forge.os.data.api.ApiMessage("system", "You are an expert code reviewer with deep knowledge of security, performance, and best practices."),
                    com.forge.os.data.api.ApiMessage("user", prompt)
                )
            )

            parseAiReviewResponse(response.content ?: "", fileName)
        } catch (e: Exception) {
            Timber.e(e, "AI analysis failed for $fileName")
            CodeReviewResult(100, emptyList(), emptyList(), "AI analysis unavailable", 0, 0)
        }
    }

    private fun analyzePythonLine(fileName: String, lineNumber: Int, line: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()

        // Python-specific rules
        if (line.trimStart().startsWith("print(") && !line.contains("debug")) {
            issues.add(CodeIssue(
                severity = IssueSeverity.LOW,
                category = IssueCategory.MAINTAINABILITY,
                file = fileName,
                line = lineNumber,
                description = "Print statement found",
                suggestion = "Consider using logging instead of print statements",
                ruleId = "python_print"
            ))
        }

        if (line.contains("eval(") || line.contains("exec(")) {
            issues.add(CodeIssue(
                severity = IssueSeverity.CRITICAL,
                category = IssueCategory.SECURITY,
                file = fileName,
                line = lineNumber,
                description = "Dangerous eval/exec usage",
                suggestion = "Avoid eval() and exec() as they can execute arbitrary code",
                ruleId = "python_eval_exec"
            ))
        }

        return issues
    }

    private fun analyzeJavaScriptLine(fileName: String, lineNumber: Int, line: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()

        // JavaScript-specific rules
        if (line.contains("console.log") && !fileName.contains("test")) {
            issues.add(CodeIssue(
                severity = IssueSeverity.LOW,
                category = IssueCategory.MAINTAINABILITY,
                file = fileName,
                line = lineNumber,
                description = "Console.log statement found",
                suggestion = "Remove console.log statements before production",
                ruleId = "js_console_log"
            ))
        }

        if (line.contains("eval(")) {
            issues.add(CodeIssue(
                severity = IssueSeverity.HIGH,
                category = IssueCategory.SECURITY,
                file = fileName,
                line = lineNumber,
                description = "eval() usage detected",
                suggestion = "Avoid eval() as it can execute arbitrary code",
                ruleId = "js_eval"
            ))
        }

        return issues
    }

    private fun analyzeKotlinJavaLine(fileName: String, lineNumber: Int, line: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()

        // Kotlin/Java-specific rules
        if (line.contains("System.out.print") && !fileName.contains("test")) {
            issues.add(CodeIssue(
                severity = IssueSeverity.LOW,
                category = IssueCategory.MAINTAINABILITY,
                file = fileName,
                line = lineNumber,
                description = "System.out.print usage found",
                suggestion = "Use proper logging framework instead of System.out",
                ruleId = "java_system_out"
            ))
        }

        return issues
    }

    private fun calculateOverallScore(issues: List<CodeIssue>): Int {
        var score = 100
        
        issues.forEach { issue ->
            score -= when (issue.severity) {
                IssueSeverity.CRITICAL -> 20
                IssueSeverity.HIGH -> 10
                IssueSeverity.MEDIUM -> 5
                IssueSeverity.LOW -> 2
                IssueSeverity.INFO -> 1
            }
        }
        
        return score.coerceAtLeast(0)
    }

    private fun generateReviewSummary(issues: List<CodeIssue>, suggestions: List<CodeSuggestion>, filesCount: Int, linesCount: Int): String {
        return buildString {
            appendLine("📋 Code Review Summary")
            appendLine("  • Files reviewed: $filesCount")
            appendLine("  • Lines analyzed: $linesCount")
            appendLine("  • Issues found: ${issues.size}")
            appendLine("  • Suggestions: ${suggestions.size}")
            appendLine()
            
            if (issues.isNotEmpty()) {
                val issuesBySeverity = issues.groupBy { it.severity }
                appendLine("Issues by severity:")
                IssueSeverity.values().forEach { severity ->
                    val count = issuesBySeverity[severity]?.size ?: 0
                    if (count > 0) {
                        val icon = when (severity) {
                            IssueSeverity.CRITICAL -> "🔴"
                            IssueSeverity.HIGH -> "🟠"
                            IssueSeverity.MEDIUM -> "🟡"
                            IssueSeverity.LOW -> "🔵"
                            IssueSeverity.INFO -> "ℹ️"
                        }
                        appendLine("  $icon ${severity.name}: $count")
                    }
                }
            }
        }
    }

    private fun generateChangeReviewSummary(issues: List<CodeIssue>, suggestions: List<CodeSuggestion>, filesCount: Int, linesCount: Int): String {
        return buildString {
            appendLine("📋 Change Review Summary")
            appendLine("  • Files changed: $filesCount")
            appendLine("  • Lines changed: $linesCount")
            appendLine("  • New issues: ${issues.size}")
            appendLine("  • Suggestions: ${suggestions.size}")
        }
    }

    private fun parseDiff(diff: String): Map<String, List<String>> {
        // Simple diff parser - in practice would be more sophisticated
        val files = mutableMapOf<String, MutableList<String>>()
        var currentFile = ""
        
        diff.lines().forEach { line ->
            when {
                line.startsWith("+++") -> {
                    currentFile = line.substringAfter("b/").trim()
                    files[currentFile] = mutableListOf()
                }
                line.startsWith("+") && !line.startsWith("+++") -> {
                    files[currentFile]?.add(line.substring(1))
                }
            }
        }
        
        return files
    }

    private suspend fun reviewChangedLines(fileName: String, changes: List<String>, language: String): CodeReviewResult {
        val issues = mutableListOf<CodeIssue>()
        val suggestions = mutableListOf<CodeSuggestion>()
        
        changes.forEachIndexed { index, line ->
            // Analyze each changed line
            issues.addAll(performStaticAnalysis(fileName, line, language))
        }
        
        return CodeReviewResult(
            overallScore = calculateOverallScore(issues),
            issues = issues,
            suggestions = suggestions,
            summary = "Reviewed ${changes.size} changed lines",
            filesReviewed = 1,
            linesAnalyzed = changes.size
        )
    }

    private fun parseAiReviewResponse(response: String, fileName: String): CodeReviewResult {
        // Parse AI response and extract issues/suggestions
        // This is a simplified parser - in practice would be more sophisticated
        val issues = mutableListOf<CodeIssue>()
        val suggestions = mutableListOf<CodeSuggestion>()
        
        // Simple parsing logic
        response.lines().forEach { line ->
            when {
                line.contains("CRITICAL", ignoreCase = true) -> {
                    issues.add(CodeIssue(
                        severity = IssueSeverity.CRITICAL,
                        category = IssueCategory.SECURITY,
                        file = fileName,
                        line = null,
                        description = line,
                        suggestion = "Review and fix this critical issue",
                        ruleId = "ai_critical"
                    ))
                }
                line.contains("SECURITY", ignoreCase = true) -> {
                    issues.add(CodeIssue(
                        severity = IssueSeverity.HIGH,
                        category = IssueCategory.SECURITY,
                        file = fileName,
                        line = null,
                        description = line,
                        suggestion = "Address this security concern",
                        ruleId = "ai_security"
                    ))
                }
            }
        }
        
        return CodeReviewResult(
            overallScore = calculateOverallScore(issues),
            issues = issues,
            suggestions = suggestions,
            summary = "AI analysis completed",
            filesReviewed = 1,
            linesAnalyzed = 0
        )
    }

    private fun parseAiSuggestions(response: String, fileName: String, language: String): List<CodeSuggestion> {
        // Parse AI suggestions from response
        val suggestions = mutableListOf<CodeSuggestion>()
        
        // Simple parsing - in practice would be more sophisticated
        response.lines().forEach { line ->
            if (line.contains("suggest", ignoreCase = true) || line.contains("improve", ignoreCase = true)) {
                suggestions.add(CodeSuggestion(
                    type = SuggestionType.REFACTOR,
                    file = fileName,
                    line = null,
                    description = line,
                    before = null,
                    after = null,
                    impact = "Code quality improvement"
                ))
            }
        }
        
        return suggestions
    }
}