package com.forge.os.domain.projects

import android.content.Context
import com.forge.os.data.git.GitRunner
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.code.CodeReviewService
import com.forge.os.domain.code.ReviewOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feature 7: Project Health Monitor
 * 
 * Provides a UI panel showing project status including:
 * - Test passing/failing status
 * - Memory usage
 * - Recent commits
 * - Code quality score
 * - Build status
 * - Dependencies health
 * 
 * Quick overview of what's working and what's not.
 * 
 * Example: "show me the health of my flashcard project"
 */
@Singleton
class ProjectHealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectsRepository: ProjectsRepository,
    private val projectPythonRunner: ProjectPythonRunner,
    private val sandboxManager: SandboxManager,
    private val gitRunner: GitRunner,
    private val codeReviewService: CodeReviewService
) {
    private val _projectHealth = MutableStateFlow<Map<String, ProjectHealth>>(emptyMap())
    val projectHealth: StateFlow<Map<String, ProjectHealth>> = _projectHealth.asStateFlow()
    
    /**
     * Get health status for a specific project.
     */
    suspend fun getProjectHealth(projectSlug: String): ProjectHealth = withContext(Dispatchers.IO) {
        val project = projectsRepository.get(projectSlug)
            ?: return@withContext ProjectHealth(
                projectSlug = projectSlug,
                projectName = projectSlug,
                status = HealthStatus.UNKNOWN,
                message = "Project not found"
            )
        
        Timber.i("Checking health for project: ${project.name}")
        
        try {
            // Check various health indicators
            val testStatus = checkTestStatus(projectSlug)
            val buildStatus = checkBuildStatus(projectSlug)
            val gitStatus = checkGitStatus(projectSlug)
            val codeQuality = checkCodeQuality(projectSlug)
            val dependencies = checkDependencies(projectSlug)
            val memoryUsage = checkMemoryUsage(projectSlug)
            
            // Calculate overall status
            val overallStatus = calculateOverallStatus(
                testStatus, buildStatus, codeQuality
            )
            
            val health = ProjectHealth(
                projectSlug = projectSlug,
                projectName = project.name,
                status = overallStatus,
                testStatus = testStatus,
                buildStatus = buildStatus,
                gitStatus = gitStatus,
                codeQuality = codeQuality,
                dependencies = dependencies,
                memoryUsage = memoryUsage,
                lastChecked = System.currentTimeMillis()
            )
            
            // Update cache
            val currentHealth = _projectHealth.value.toMutableMap()
            currentHealth[projectSlug] = health
            _projectHealth.value = currentHealth
            
            health
        } catch (e: Exception) {
            Timber.e(e, "Failed to check project health")
            ProjectHealth(
                projectSlug = projectSlug,
                projectName = project.name,
                status = HealthStatus.ERROR,
                message = "Health check failed: ${e.message}"
            )
        }
    }
    
    /**
     * Get health status for all projects.
     */
    suspend fun getAllProjectsHealth(): Map<String, ProjectHealth> = withContext(Dispatchers.IO) {
        val projects = projectsRepository.list()
        val healthMap = mutableMapOf<String, ProjectHealth>()
        
        projects.forEach { project ->
            healthMap[project.slug] = getProjectHealth(project.slug)
        }
        
        healthMap
    }
    
    /**
     * Check test status.
     */
    private suspend fun checkTestStatus(projectSlug: String): TestStatus {
        val project = projectsRepository.get(projectSlug) ?: return TestStatus()
        
        // Look for test files
        val projectDir = File(context.filesDir, "workspace/projects/$projectSlug")
        val testFiles = projectDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                file.name.contains("test", ignoreCase = true) ||
                file.parent?.contains("test", ignoreCase = true) == true
            }
            .toList()
        
        if (testFiles.isEmpty()) {
            return TestStatus(
                hasTests = false,
                message = "No tests found"
            )
        }
        
        // Try to run tests
        val testResult = runTests(projectSlug)
        
        return TestStatus(
            hasTests = true,
            totalTests = testFiles.size,
            passingTests = if (testResult.success) testFiles.size else 0,
            failingTests = if (testResult.success) 0 else testFiles.size,
            lastRun = System.currentTimeMillis(),
            message = testResult.message
        )
    }
    
    /**
     * Run tests for a project.
     */
    private suspend fun runTests(projectSlug: String): TestResult {
        return try {
            // Try to find and run test file
            val projectDir = File(context.filesDir, "workspace/projects/$projectSlug")
            val testFile = projectDir.walkTopDown()
                .firstOrNull { it.name == "test.py" || it.name == "tests.py" }
            
            if (testFile != null) {
                val output = projectPythonRunner.executeFile(
                    projectSlug,
                    testFile.relativeTo(projectDir).path
                )
                TestResult(
                    success = !output.contains("FAILED") && !output.contains("ERROR"),
                    message = output
                )
            } else {
                TestResult(success = true, message = "No test runner found")
            }
        } catch (e: Exception) {
            TestResult(success = false, message = "Test execution failed: ${e.message}")
        }
    }
    
    /**
     * Check build status.
     */
    private suspend fun checkBuildStatus(projectSlug: String): BuildStatus {
        val project = projectsRepository.get(projectSlug) ?: return BuildStatus()
        
        // Check if main script exists and is valid
        val mainScript = project.mainScript
        if (mainScript != null) {
            val scriptPath = "projects/$projectSlug/$mainScript"
            val content = sandboxManager.readFile(scriptPath).getOrNull()
            
            if (content != null) {
                // Try to parse/validate the script
                val validation = validateScript(content)
                return BuildStatus(
                    canBuild = validation.valid,
                    lastBuild = System.currentTimeMillis(),
                    message = validation.message
                )
            }
        }
        
        return BuildStatus(
            canBuild = true,
            message = "No build configuration"
        )
    }
    
    /**
     * Validate script syntax.
     */
    private fun validateScript(content: String): ValidationResult {
        // Basic syntax validation
        val lines = content.lines()
        val errors = mutableListOf<String>()
        
        // Check for common syntax errors
        lines.forEachIndexed { index, line ->
            if (line.trim().startsWith("def ") && !line.trim().endsWith(":")) {
                errors.add("Line ${index + 1}: Missing colon after function definition")
            }
            if (line.trim().startsWith("class ") && !line.trim().endsWith(":")) {
                errors.add("Line ${index + 1}: Missing colon after class definition")
            }
        }
        
        return ValidationResult(
            valid = errors.isEmpty(),
            message = if (errors.isEmpty()) "Syntax OK" else errors.joinToString("\n")
        )
    }
    
    /**
     * Check Git status.
     */
    private suspend fun checkGitStatus(projectSlug: String): GitStatus {
        val projectDir = File(context.filesDir, "workspace/projects/$projectSlug")
        val gitDir = File(projectDir, ".git")
        
        if (!gitDir.exists()) {
            return GitStatus(
                isGitRepo = false,
                message = "Not a Git repository"
            )
        }
        
        try {
            // Get recent commits
            val logResult = gitRunner.execute(projectDir, listOf("log", "--oneline", "-5"))
            val commits = if (logResult.success) {
                logResult.output.lines().filter { it.isNotBlank() }
            } else emptyList()
            
            // Get status
            val statusResult = gitRunner.execute(projectDir, listOf("status", "--short"))
            val uncommittedChanges = if (statusResult.success) {
                statusResult.output.lines().filter { it.isNotBlank() }.size
            } else 0
            
            // Get current branch
            val branchResult = gitRunner.execute(projectDir, listOf("branch", "--show-current"))
            val currentBranch = if (branchResult.success) {
                branchResult.output.trim()
            } else "unknown"
            
            GitStatus(
                isGitRepo = true,
                currentBranch = currentBranch,
                recentCommits = commits,
                uncommittedChanges = uncommittedChanges,
                lastCommit = if (commits.isNotEmpty()) commits.first() else null
            )
        } catch (e: Exception) {
            GitStatus(
                isGitRepo = true,
                message = "Git status check failed: ${e.message}"
            )
        }
    }
    
    /**
     * Check code quality.
     */
    private suspend fun checkCodeQuality(projectSlug: String): CodeQualityStatus {
        return try {
            val reviewResult = codeReviewService.reviewProject(
                projectSlug,
                ReviewOptions(useAI = false)
            )
            
            if (reviewResult.success) {
                val criticalIssues = reviewResult.fileReviews.sumOf { review ->
                    review.issues.count { it.severity == com.forge.os.domain.code.IssueSeverity.CRITICAL }
                }
                val totalIssues = reviewResult.fileReviews.sumOf { it.issues.size }
                
                CodeQualityStatus(
                    score = reviewResult.overallScore,
                    totalIssues = totalIssues,
                    criticalIssues = criticalIssues,
                    lastChecked = System.currentTimeMillis()
                )
            } else {
                CodeQualityStatus(message = "Code review failed")
            }
        } catch (e: Exception) {
            CodeQualityStatus(message = "Quality check failed: ${e.message}")
        }
    }
    
    /**
     * Check dependencies health.
     */
    private suspend fun checkDependencies(projectSlug: String): DependenciesStatus {
        val project = projectsRepository.get(projectSlug) ?: return DependenciesStatus()
        
        val requirements = project.requirements
        if (requirements.isEmpty()) {
            return DependenciesStatus(
                totalDependencies = 0,
                message = "No dependencies"
            )
        }
        
        // Check which dependencies are available
        val available = requirements.count { req ->
            val packageName = req.split(Regex("[<>=!]")).first().trim()
            projectPythonRunner.isPackageAvailable(packageName)
        }
        
        DependenciesStatus(
            totalDependencies = requirements.size,
            availableDependencies = available,
            missingDependencies = requirements.size - available,
            lastChecked = System.currentTimeMillis()
        )
    }
    
    /**
     * Check memory usage.
     */
    private fun checkMemoryUsage(projectSlug: String): MemoryUsageStatus {
        val projectDir = File(context.filesDir, "workspace/projects/$projectSlug")
        
        if (!projectDir.exists()) {
            return MemoryUsageStatus()
        }
        
        val totalSize = projectDir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
        
        val fileCount = projectDir.walkTopDown()
            .filter { it.isFile }
            .count()
        
        MemoryUsageStatus(
            diskUsageBytes = totalSize,
            fileCount = fileCount
        )
    }
    
    /**
     * Calculate overall health status.
     */
    private fun calculateOverallStatus(
        testStatus: TestStatus,
        buildStatus: BuildStatus,
        codeQuality: CodeQualityStatus
    ): HealthStatus {
        // Critical issues = unhealthy
        if (codeQuality.criticalIssues > 0) {
            return HealthStatus.UNHEALTHY
        }
        
        // Build failures = unhealthy
        if (!buildStatus.canBuild) {
            return HealthStatus.UNHEALTHY
        }
        
        // Test failures = warning
        if (testStatus.hasTests && testStatus.failingTests > 0) {
            return HealthStatus.WARNING
        }
        
        // Low code quality = warning
        if (codeQuality.score < 70) {
            return HealthStatus.WARNING
        }
        
        return HealthStatus.HEALTHY
    }
    
    /**
     * Generate health report.
     */
    fun generateHealthReport(health: ProjectHealth): String {
        return buildString {
            appendLine("🏥 Project Health Report: ${health.projectName}")
            appendLine("=" .repeat(50))
            appendLine()
            
            appendLine("Overall Status: ${health.status.emoji} ${health.status.name}")
            appendLine()
            
            appendLine("📊 Tests:")
            if (health.testStatus.hasTests) {
                appendLine("  ✓ ${health.testStatus.passingTests} passing")
                appendLine("  ✗ ${health.testStatus.failingTests} failing")
            } else {
                appendLine("  ⚠️ No tests found")
            }
            appendLine()
            
            appendLine("🔨 Build:")
            appendLine("  ${if (health.buildStatus.canBuild) "✓" else "✗"} ${health.buildStatus.message}")
            appendLine()
            
            appendLine("📝 Git:")
            if (health.gitStatus.isGitRepo) {
                appendLine("  Branch: ${health.gitStatus.currentBranch}")
                appendLine("  Uncommitted changes: ${health.gitStatus.uncommittedChanges}")
                if (health.gitStatus.recentCommits.isNotEmpty()) {
                    appendLine("  Recent commits:")
                    health.gitStatus.recentCommits.take(3).forEach { commit ->
                        appendLine("    • $commit")
                    }
                }
            } else {
                appendLine("  ⚠️ Not a Git repository")
            }
            appendLine()
            
            appendLine("✨ Code Quality:")
            appendLine("  Score: ${health.codeQuality.score}/100")
            appendLine("  Issues: ${health.codeQuality.totalIssues} (${health.codeQuality.criticalIssues} critical)")
            appendLine()
            
            appendLine("📦 Dependencies:")
            appendLine("  Total: ${health.dependencies.totalDependencies}")
            appendLine("  Available: ${health.dependencies.availableDependencies}")
            if (health.dependencies.missingDependencies > 0) {
                appendLine("  ⚠️ Missing: ${health.dependencies.missingDependencies}")
            }
            appendLine()
            
            appendLine("💾 Storage:")
            val sizeMB = health.memoryUsage.diskUsageBytes / (1024.0 * 1024.0)
            appendLine("  Disk usage: ${"%.2f".format(sizeMB)} MB")
            appendLine("  Files: ${health.memoryUsage.fileCount}")
        }
    }
}

/**
 * Overall project health.
 */
@Serializable
data class ProjectHealth(
    val projectSlug: String,
    val projectName: String,
    val status: HealthStatus,
    val testStatus: TestStatus = TestStatus(),
    val buildStatus: BuildStatus = BuildStatus(),
    val gitStatus: GitStatus = GitStatus(),
    val codeQuality: CodeQualityStatus = CodeQualityStatus(),
    val dependencies: DependenciesStatus = DependenciesStatus(),
    val memoryUsage: MemoryUsageStatus = MemoryUsageStatus(),
    val lastChecked: Long = 0,
    val message: String = ""
)

@Serializable
enum class HealthStatus(val emoji: String) {
    HEALTHY("✅"),
    WARNING("⚠️"),
    UNHEALTHY("❌"),
    UNKNOWN("❓"),
    ERROR("💥")
}

@Serializable
data class TestStatus(
    val hasTests: Boolean = false,
    val totalTests: Int = 0,
    val passingTests: Int = 0,
    val failingTests: Int = 0,
    val lastRun: Long = 0,
    val message: String = ""
)

@Serializable
data class BuildStatus(
    val canBuild: Boolean = false,
    val lastBuild: Long = 0,
    val message: String = ""
)

@Serializable
data class GitStatus(
    val isGitRepo: Boolean = false,
    val currentBranch: String = "",
    val recentCommits: List<String> = emptyList(),
    val uncommittedChanges: Int = 0,
    val lastCommit: String? = null,
    val message: String = ""
)

@Serializable
data class CodeQualityStatus(
    val score: Int = 0,
    val totalIssues: Int = 0,
    val criticalIssues: Int = 0,
    val lastChecked: Long = 0,
    val message: String = ""
)

@Serializable
data class DependenciesStatus(
    val totalDependencies: Int = 0,
    val availableDependencies: Int = 0,
    val missingDependencies: Int = 0,
    val lastChecked: Long = 0,
    val message: String = ""
)

@Serializable
data class MemoryUsageStatus(
    val diskUsageBytes: Long = 0,
    val fileCount: Int = 0
)

data class TestResult(
    val success: Boolean,
    val message: String
)

data class ValidationResult(
    val valid: Boolean,
    val message: String
)
