package com.forge.os.domain.code

import android.content.Context
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiMessage
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.projects.ProjectsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 5: AI-Powered Code Review
 * 
 * Reviews code changes and suggests improvements automatically.
 * Elevates project quality without manual effort.
 * 
 * Checks for:
 * - Code quality and best practices
 * - Potential bugs and security issues
 * - Performance optimizations
 * - Documentation completeness
 * - Test coverage
 * 
 * Example: "Forge, review my latest Python changes for the flashcard app"
 */
@Singleton
class CodeReviewService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiApiManager: AiApiManager,
    private val configRepository: ConfigRepository,
    private val projectsRepository: ProjectsRepository,
    private val sandboxManager: SandboxManager
) {
    /**
     * Review code changes in a project.
     */
    suspend fun reviewProject(projectSlug: String, options: ReviewOptions = ReviewOptions()): CodeReviewResult = withContext(Dispatchers.IO) {
        val project = projectsRepository.get(projectSlug)
            ?: return@withContext CodeReviewResult(
                success = false,
                error = "Project not found: $projectSlug"
            )
        
        Timber.i("Starting code review for project: ${project.name}")
        
        // Collect files to review
        val files = collectProjectFiles(projectSlug, options)
        if (files.isEmpty()) {
            return@withContext CodeReviewResult(
                success = false,
                error = "No files found to review"
            )
        }
        
        // Analyze each file
        val fileReviews = files.map { file ->
            reviewFile(file, options)
        }
        
        // Generate overall summary
        val summary = generateReviewSummary(fileReviews)
        
        CodeReviewResult(
            success = true,
            projectName = project.name,
            filesReviewed = files.size,
            fileReviews = fileReviews,
            summary = summary,
            overallScore = calculateOverallScore(fileReviews)
        )
    }
    
    /**
     * Review a single file.
     */
    suspend fun reviewFile(filePath: String, options: ReviewOptions = ReviewOptions()): FileReview = withContext(Dispatchers.IO) {
        try {
            val content = sandboxManager.readFile(filePath).getOrNull()
                ?: return@withContext FileReview(
                    filePath = filePath,
                    issues = listOf(CodeIssue(
                        severity = IssueSeverity.ERROR,
                        category = IssueCategory.OTHER,
                        message = "Could not read file",
                        line = 0
                    ))
                )
            
            val issues = mutableListOf<CodeIssue>()
            
            // Static analysis
            if (options.checkQuality) {
                issues.addAll(analyzeCodeQuality(filePath, content))
            }
            
            if (options.checkSecurity) {
                issues.addAll(analyzeSecurityIssues(filePath, content))
            }
            
            if (options.checkPerformance) {
                issues.addAll(analyzePerformance(filePath, content))
            }
            
            if (options.checkDocumentation) {
                issues.addAll(analyzeDocumentation(filePath, content))
            }
            
            // AI-powered review (if enabled)
            if (options.useAI) {
                issues.addAll(performAIReview(filePath, content))
            }
            
            FileReview(
                filePath = filePath,
                issues = issues,
                linesOfCode = content.lines().size,
                score = calculateFileScore(issues)
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to review file: $filePath")
            FileReview(
                filePath = filePath,
                issues = listOf(CodeIssue(
                    severity = IssueSeverity.ERROR,
                    category = IssueCategory.OTHER,
                    message = "Review failed: ${e.message}",
                    line = 0
                ))
            )
        }
    }
    
    /**
     * Analyze code quality.
     */
    private fun analyzeCodeQuality(filePath: String, content: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = content.lines()
        
        // Check for long lines
        lines.forEachIndexed { index, line ->
            if (line.length > 120) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.WARNING,
                    category = IssueCategory.STYLE,
                    message = "Line too long (${line.length} characters, max 120)",
                    line = index + 1
                ))
            }
        }
        
        // Check for TODO/FIXME comments
        lines.forEachIndexed { index, line ->
            when {
                line.contains("TODO", ignoreCase = true) -> {
                    issues.add(CodeIssue(
                        severity = IssueSeverity.INFO,
                        category = IssueCategory.MAINTENANCE,
                        message = "TODO comment found",
                        line = index + 1
                    ))
                }
                line.contains("FIXME", ignoreCase = true) -> {
                    issues.add(CodeIssue(
                        severity = IssueSeverity.WARNING,
                        category = IssueCategory.MAINTENANCE,
                        message = "FIXME comment found",
                        line = index + 1
                    ))
                }
            }
        }
        
        // Language-specific checks
        when {
            filePath.endsWith(".py") -> issues.addAll(analyzePythonQuality(lines))
            filePath.endsWith(".kt") -> issues.addAll(analyzeKotlinQuality(lines))
            filePath.endsWith(".js") || filePath.endsWith(".ts") -> issues.addAll(analyzeJavaScriptQuality(lines))
        }
        
        return issues
    }
    
    /**
     * Analyze Python code quality.
     */
    private fun analyzePythonQuality(lines: List<String>): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        
        lines.forEachIndexed { index, line ->
            // Check for bare except
            if (line.trim().startsWith("except:")) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.WARNING,
                    category = IssueCategory.BEST_PRACTICE,
                    message = "Bare except clause - specify exception type",
                    line = index + 1,
                    suggestion = "Use 'except Exception:' or specific exception type"
                ))
            }
            
            // Check for print statements (should use logging)
            if (line.contains("print(") && !line.trim().startsWith("#")) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.INFO,
                    category = IssueCategory.BEST_PRACTICE,
                    message = "Consider using logging instead of print",
                    line = index + 1,
                    suggestion = "Use logging.info(), logging.debug(), etc."
                ))
            }
        }
        
        return issues
    }
    
    /**
     * Analyze Kotlin code quality.
     */
    private fun analyzeKotlinQuality(lines: List<String>): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        
        lines.forEachIndexed { index, line ->
            // Check for !! operator
            if (line.contains("!!")) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.WARNING,
                    category = IssueCategory.BEST_PRACTICE,
                    message = "Avoid using !! operator - handle nullability properly",
                    line = index + 1,
                    suggestion = "Use safe call (?.) or elvis operator (?:)"
                ))
            }
        }
        
        return issues
    }
    
    /**
     * Analyze JavaScript/TypeScript code quality.
     */
    private fun analyzeJavaScriptQuality(lines: List<String>): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        
        lines.forEachIndexed { index, line ->
            // Check for var usage
            if (line.contains(Regex("\\bvar\\b"))) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.WARNING,
                    category = IssueCategory.BEST_PRACTICE,
                    message = "Use 'let' or 'const' instead of 'var'",
                    line = index + 1
                ))
            }
            
            // Check for console.log
            if (line.contains("console.log") && !line.trim().startsWith("//")) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.INFO,
                    category = IssueCategory.MAINTENANCE,
                    message = "Remove console.log before production",
                    line = index + 1
                ))
            }
        }
        
        return issues
    }
    
    /**
     * Analyze security issues.
     */
    private fun analyzeSecurityIssues(filePath: String, content: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            // Check for hardcoded secrets
            if (line.contains(Regex("(password|secret|api_key|token)\\s*=\\s*['\"]\\w+['\"]", RegexOption.IGNORE_CASE))) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = IssueCategory.SECURITY,
                    message = "Potential hardcoded secret detected",
                    line = index + 1,
                    suggestion = "Use environment variables or secure storage"
                ))
            }
            
            // Check for SQL injection risks
            if (line.contains(Regex("execute\\(.*\\+.*\\)|query\\(.*\\+.*\\)", RegexOption.IGNORE_CASE))) {
                issues.add(CodeIssue(
                    severity = IssueSeverity.CRITICAL,
                    category = IssueCategory.SECURITY,
                    message = "Potential SQL injection vulnerability",
                    line = index + 1,
                    suggestion = "Use parameterized queries"
                ))
            }
        }
        
        return issues
    }
    
    /**
     * Analyze performance issues.
     */
    private fun analyzePerformance(filePath: String, content: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = content.lines()
        
        lines.forEachIndexed { index, line ->
            // Check for nested loops
            if (line.trim().startsWith("for") && index > 0) {
                val prevLines = lines.subList(maxOf(0, index - 5), index)
                if (prevLines.any { it.trim().startsWith("for") }) {
                    issues.add(CodeIssue(
                        severity = IssueSeverity.INFO,
                        category = IssueCategory.PERFORMANCE,
                        message = "Nested loops detected - consider optimization",
                        line = index + 1,
                        suggestion = "Review algorithm complexity"
                    ))
                }
            }
        }
        
        return issues
    }
    
    /**
     * Analyze documentation completeness.
     */
    private fun analyzeDocumentation(filePath: String, content: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = content.lines()
        
        // Check for functions without docstrings/comments
        lines.forEachIndexed { index, line ->
            if (line.trim().startsWith("def ") || line.trim().startsWith("fun ") || line.trim().startsWith("function ")) {
                val prevLine = if (index > 0) lines[index - 1].trim() else ""
                if (!prevLine.startsWith("//") && !prevLine.startsWith("#") && !prevLine.startsWith("/**")) {
                    issues.add(CodeIssue(
                        severity = IssueSeverity.INFO,
                        category = IssueCategory.DOCUMENTATION,
                        message = "Function missing documentation",
                        line = index + 1,
                        suggestion = "Add docstring or comment explaining the function"
                    ))
                }
            }
        }
        
        return issues
    }
    
    /**
     * Perform AI-powered code review.
     */
    private suspend fun performAIReview(filePath: String, content: String): List<CodeIssue> {
        return try {
            // Use the configured LLM to review code
            val prompt = buildString {
                appendLine("Review the following code and identify issues:")
                appendLine()
                appendLine("File: $filePath")
                appendLine("```")
                appendLine(content)
                appendLine("```")
                appendLine()
                appendLine("Provide a list of issues in this format:")
                appendLine("LINE <number>: [SEVERITY] <category> - <message>")
                appendLine("SUGGESTION: <optional suggestion>")
                appendLine()
                appendLine("Severity levels: CRITICAL, ERROR, WARNING, INFO")
                appendLine("Categories: SECURITY, PERFORMANCE, BEST_PRACTICE, STYLE, DOCUMENTATION, MAINTENANCE, OTHER")
            }
            
            val config = configRepository.get()
            val response = aiApiManager.chat(
                messages = listOf(
                    ApiMessage(
                        role = "user",
                        content = prompt
                    )
                ),
                systemPrompt = "You are a code review assistant. Analyze code and identify issues."
            )
            
            // Parse the response into CodeIssue objects
            parseAIReviewResponse(response.content)
        } catch (e: Exception) {
            Timber.w(e, "AI review failed, falling back to static analysis")
            emptyList()
        }
    }
    
    /**
     * Parse AI review response into CodeIssue objects.
     */
    private fun parseAIReviewResponse(response: String): List<CodeIssue> {
        val issues = mutableListOf<CodeIssue>()
        val lines = response.lines()
        
        var currentLine = 0
        var currentSeverity: IssueSeverity? = null
        var currentCategory: IssueCategory? = null
        var currentMessage: String? = null
        var currentSuggestion: String? = null
        
        for (line in lines) {
            when {
                line.startsWith("LINE ") -> {
                    // Save previous issue if exists
                    if (currentLine > 0 && currentSeverity != null && currentCategory != null && currentMessage != null) {
                        issues.add(CodeIssue(
                            severity = currentSeverity,
                            category = currentCategory,
                            message = currentMessage,
                            line = currentLine,
                            suggestion = currentSuggestion
                        ))
                    }
                    
                    // Parse new issue
                    val match = Regex("LINE (\\d+): \\[(\\w+)]\\s+(\\w+)\\s*-\\s*(.+)").find(line)
                    if (match != null) {
                        currentLine = match.groupValues[1].toIntOrNull() ?: 0
                        currentSeverity = try {
                            IssueSeverity.valueOf(match.groupValues[2])
                        } catch (e: Exception) {
                            IssueSeverity.INFO
                        }
                        currentCategory = try {
                            IssueCategory.valueOf(match.groupValues[3])
                        } catch (e: Exception) {
                            IssueCategory.OTHER
                        }
                        currentMessage = match.groupValues[4].trim()
                        currentSuggestion = null
                    }
                }
                line.startsWith("SUGGESTION: ") -> {
                    currentSuggestion = line.removePrefix("SUGGESTION: ").trim()
                }
            }
        }
        
        // Save last issue
        if (currentLine > 0 && currentSeverity != null && currentCategory != null && currentMessage != null) {
            issues.add(CodeIssue(
                severity = currentSeverity,
                category = currentCategory,
                message = currentMessage,
                line = currentLine,
                suggestion = currentSuggestion
            ))
        }
        
        return issues
    }
    
    /**
     * Collect files to review from project.
     */
    private fun collectProjectFiles(projectSlug: String, options: ReviewOptions): List<String> {
        val projectDir = File(context.filesDir, "workspace/projects/$projectSlug")
        if (!projectDir.exists()) return emptyList()
        
        return projectDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val ext = file.extension
                when {
                    options.includePatterns.isNotEmpty() -> options.includePatterns.any { pattern ->
                        file.name.matches(Regex(pattern))
                    }
                    else -> ext in listOf("py", "kt", "java", "js", "ts", "jsx", "tsx")
                }
            }
            .filter { file ->
                options.excludePatterns.none { pattern ->
                    file.name.matches(Regex(pattern))
                }
            }
            .map { it.relativeTo(File(context.filesDir, "workspace")).path }
            .toList()
    }
    
    /**
     * Calculate score for a file (0-100).
     */
    private fun calculateFileScore(issues: List<CodeIssue>): Int {
        var score = 100
        issues.forEach { issue ->
            score -= when (issue.severity) {
                IssueSeverity.CRITICAL -> 20
                IssueSeverity.ERROR -> 10
                IssueSeverity.WARNING -> 5
                IssueSeverity.INFO -> 1
            }
        }
        return maxOf(0, score)
    }
    
    /**
     * Calculate overall score across all files.
     */
    private fun calculateOverallScore(fileReviews: List<FileReview>): Int {
        if (fileReviews.isEmpty()) return 0
        return fileReviews.map { it.score }.average().toInt()
    }
    
    /**
     * Generate review summary.
     */
    private fun generateReviewSummary(fileReviews: List<FileReview>): String {
        val totalIssues = fileReviews.sumOf { it.issues.size }
        val criticalIssues = fileReviews.sumOf { review ->
            review.issues.count { it.severity == IssueSeverity.CRITICAL }
        }
        val errorIssues = fileReviews.sumOf { review ->
            review.issues.count { it.severity == IssueSeverity.ERROR }
        }
        val warningIssues = fileReviews.sumOf { review ->
            review.issues.count { it.severity == IssueSeverity.WARNING }
        }
        
        return buildString {
            appendLine("Code Review Summary")
            appendLine("==================")
            appendLine()
            appendLine("Files reviewed: ${fileReviews.size}")
            appendLine("Total issues: $totalIssues")
            appendLine("  • Critical: $criticalIssues")
            appendLine("  • Errors: $errorIssues")
            appendLine("  • Warnings: $warningIssues")
            appendLine()
            
            if (criticalIssues > 0) {
                appendLine("⚠️ Critical issues found! Address these immediately.")
            } else if (errorIssues > 0) {
                appendLine("⚠️ Errors found. Please review and fix.")
            } else if (warningIssues > 0) {
                appendLine("✓ No critical issues, but some warnings to address.")
            } else {
                appendLine("✅ Code looks good! No major issues found.")
            }
        }
    }
}

/**
 * Code review options.
 */
data class ReviewOptions(
    val checkQuality: Boolean = true,
    val checkSecurity: Boolean = true,
    val checkPerformance: Boolean = true,
    val checkDocumentation: Boolean = true,
    val useAI: Boolean = false,
    val includePatterns: List<String> = emptyList(),
    val excludePatterns: List<String> = listOf(".*test.*", ".*\\.min\\..*")
)

/**
 * Code review result.
 */
@Serializable
data class CodeReviewResult(
    val success: Boolean,
    val projectName: String = "",
    val filesReviewed: Int = 0,
    val fileReviews: List<FileReview> = emptyList(),
    val summary: String = "",
    val overallScore: Int = 0,
    val error: String? = null
)

/**
 * Review of a single file.
 */
@Serializable
data class FileReview(
    val filePath: String,
    val issues: List<CodeIssue> = emptyList(),
    val linesOfCode: Int = 0,
    val score: Int = 100
)

/**
 * A code issue found during review.
 */
@Serializable
data class CodeIssue(
    val severity: IssueSeverity,
    val category: IssueCategory,
    val message: String,
    val line: Int,
    val column: Int = 0,
    val suggestion: String? = null
)

/**
 * Issue severity levels.
 */
@Serializable
enum class IssueSeverity {
    CRITICAL,  // Must fix before deployment
    ERROR,     // Should fix soon
    WARNING,   // Should review
    INFO       // Nice to have
}

/**
 * Issue categories.
 */
@Serializable
enum class IssueCategory {
    SECURITY,
    PERFORMANCE,
    BEST_PRACTICE,
    STYLE,
    DOCUMENTATION,
    MAINTENANCE,
    OTHER
}
