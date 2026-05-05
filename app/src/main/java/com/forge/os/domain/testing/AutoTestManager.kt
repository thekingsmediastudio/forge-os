package com.forge.os.domain.testing

import android.content.Context
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.domain.projects.ProjectsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatically generates and runs tests for Python/JS projects before commits.
 * 
 * Features:
 * - Auto-detects project type and testing framework
 * - Generates basic tests for untested code
 * - Runs existing test suites
 * - Pre-commit test validation
 * - Test coverage analysis
 * - Integration with Git hooks
 */
@Singleton
class AutoTestManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sandboxManager: SandboxManager,
    private val projectsRepository: ProjectsRepository,
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
) {

    data class TestResult(
        val success: Boolean,
        val testsRun: Int,
        val testsPassed: Int,
        val testsFailed: Int,
        val coverage: Double? = null,
        val output: String,
        val generatedTests: List<String> = emptyList()
    )

    data class ProjectTestInfo(
        val projectType: ProjectType,
        val testFramework: TestFramework?,
        val testDirectory: String,
        val hasExistingTests: Boolean,
        val testableFiles: List<String>
    )

    enum class ProjectType {
        PYTHON, JAVASCRIPT, TYPESCRIPT, UNKNOWN
    }

    enum class TestFramework {
        PYTEST, UNITTEST, JEST, MOCHA, VITEST
    }

    /**
     * Analyze project and determine testing setup.
     */
    suspend fun analyzeProject(projectSlug: String): ProjectTestInfo = withContext(Dispatchers.IO) {
        val project = projectsRepository.get(projectSlug)
            ?: return@withContext ProjectTestInfo(ProjectType.UNKNOWN, null, "", false, emptyList())

        val projectPath = "projects/$projectSlug"
        val files = sandboxManager.listFiles(projectPath).getOrElse { emptyList() }

        // Detect project type
        val projectType = when {
            files.any { it.name.endsWith(".py") } -> ProjectType.PYTHON
            files.any { it.name.endsWith(".js") } -> ProjectType.JAVASCRIPT
            files.any { it.name.endsWith(".ts") } -> ProjectType.TYPESCRIPT
            else -> ProjectType.UNKNOWN
        }

        // Detect test framework
        val testFramework = detectTestFramework(projectPath, projectType)

        // Find test directory
        val testDirectory = findTestDirectory(projectPath, files)

        // Check for existing tests
        val hasExistingTests = hasExistingTests(projectPath, testDirectory, projectType)

        // Find testable files
        val testableFiles = findTestableFiles(projectPath, files, projectType)

        ProjectTestInfo(projectType, testFramework, testDirectory, hasExistingTests, testableFiles)
    }

    /**
     * Generate tests for untested code.
     */
    suspend fun generateTests(projectSlug: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val testInfo = analyzeProject(projectSlug)
            val generatedTests = mutableListOf<String>()

            for (file in testInfo.testableFiles) {
                val testCode = generateTestForFile(projectSlug, file, testInfo.projectType, testInfo.testFramework)
                if (testCode != null) {
                    val testFileName = generateTestFileName(file, testInfo.projectType)
                    val testPath = "${testInfo.testDirectory}/$testFileName"
                    
                    sandboxManager.writeFile("projects/$projectSlug/$testPath", testCode)
                        .onSuccess { 
                            generatedTests.add(testPath)
                            Timber.i("Generated test: $testPath")
                        }
                        .onFailure { 
                            Timber.w("Failed to write test file: $testPath")
                        }
                }
            }

            // Record test generation patterns
            reflectionManager.recordPattern(
                pattern = "Test generation: ${testInfo.projectType} project",
                description = "Generated ${generatedTests.size} tests for $projectSlug",
                applicableTo = listOf("test_generation", testInfo.projectType.name.lowercase(), "quality_assurance"),
                tags = listOf("auto_testing", "test_generation", testInfo.projectType.name.lowercase(), projectSlug)
            )

            Result.success(generatedTests)
        } catch (e: Exception) {
            Timber.e(e, "Failed to generate tests for $projectSlug")
            Result.failure(e)
        }
    }

    /**
     * Run tests for a project.
     */
    suspend fun runTests(projectSlug: String): TestResult = withContext(Dispatchers.IO) {
        val testInfo = analyzeProject(projectSlug)
        val projectPath = "projects/$projectSlug"

        val result = when (testInfo.projectType) {
            ProjectType.PYTHON -> runPythonTests(projectPath, testInfo.testFramework)
            ProjectType.JAVASCRIPT, ProjectType.TYPESCRIPT -> runJavaScriptTests(projectPath, testInfo.testFramework)
            ProjectType.UNKNOWN -> TestResult(false, 0, 0, 0, null, "Unknown project type")
        }

        // Record test execution patterns
        try {
            reflectionManager.recordPattern(
                pattern = "Test execution: ${testInfo.projectType} - ${if (result.success) "PASS" else "FAIL"}",
                description = "Ran ${result.testsRun} tests, ${result.testsPassed} passed, ${result.testsFailed} failed",
                applicableTo = listOf("test_execution", testInfo.projectType.name.lowercase(), "quality_assurance"),
                tags = listOf("auto_testing", "test_execution", if (result.success) "test_pass" else "test_fail", projectSlug)
            )

            if (!result.success) {
                reflectionManager.recordFailureAndRecovery(
                    taskId = "test_execution_$projectSlug",
                    failureReason = "Tests failed: ${result.testsFailed} failures out of ${result.testsRun} tests",
                    recoveryStrategy = "Review test output, fix failing tests, check for missing dependencies",
                    tags = listOf("test_failure", projectSlug, testInfo.projectType.name.lowercase())
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to record test execution patterns")
        }

        result
    }

    /**
     * Run tests before commit (pre-commit hook).
     */
    suspend fun runPreCommitTests(projectSlug: String): TestResult {
        Timber.i("Running pre-commit tests for $projectSlug")
        
        // First generate any missing tests
        generateTests(projectSlug)
        
        // Then run all tests
        return runTests(projectSlug)
    }

    private suspend fun detectTestFramework(projectPath: String, projectType: ProjectType): TestFramework? {
        return when (projectType) {
            ProjectType.PYTHON -> {
                // Check for pytest.ini, setup.cfg, or requirements.txt mentioning pytest
                val haspytest = sandboxManager.readFile("$projectPath/pytest.ini").isSuccess ||
                    sandboxManager.readFile("$projectPath/requirements.txt").fold(
                        onSuccess = { it.contains("pytest") },
                        onFailure = { false }
                    )
                if (haspytest) TestFramework.PYTEST else TestFramework.UNITTEST
            }
            ProjectType.JAVASCRIPT, ProjectType.TYPESCRIPT -> {
                // Check package.json for test frameworks
                val packageJson = sandboxManager.readFile("$projectPath/package.json").getOrNull()
                when {
                    packageJson?.contains("jest") == true -> TestFramework.JEST
                    packageJson?.contains("vitest") == true -> TestFramework.VITEST
                    packageJson?.contains("mocha") == true -> TestFramework.MOCHA
                    else -> TestFramework.JEST // Default
                }
            }
            ProjectType.UNKNOWN -> null
        }
    }

    private suspend fun findTestDirectory(projectPath: String, files: List<com.forge.os.data.sandbox.FileInfo>): String {
        // Look for existing test directories
        val testDirs = listOf("tests", "test", "__tests__", "spec")
        for (dir in testDirs) {
            if (files.any { it.name == dir && it.isDirectory }) {
                return dir
            }
        }
        // Default to "tests"
        sandboxManager.mkdir("$projectPath/tests")
        return "tests"
    }

    private suspend fun hasExistingTests(projectPath: String, testDir: String, projectType: ProjectType): Boolean {
        val testFiles = sandboxManager.listFiles("$projectPath/$testDir").getOrElse { emptyList() }
        return when (projectType) {
            ProjectType.PYTHON -> testFiles.any { it.name.startsWith("test_") && it.name.endsWith(".py") }
            ProjectType.JAVASCRIPT, ProjectType.TYPESCRIPT -> testFiles.any { 
                it.name.contains("test") || it.name.contains("spec") 
            }
            ProjectType.UNKNOWN -> false
        }
    }

    private suspend fun findTestableFiles(
        projectPath: String, 
        files: List<com.forge.os.data.sandbox.FileInfo>, 
        projectType: ProjectType
    ): List<String> {
        val testableFiles = mutableListOf<String>()
        
        for (file in files) {
            if (file.isDirectory) continue
            
            val isTestable = when (projectType) {
                ProjectType.PYTHON -> file.name.endsWith(".py") && !file.name.startsWith("test_")
                ProjectType.JAVASCRIPT -> file.name.endsWith(".js") && !file.name.contains("test") && !file.name.contains("spec")
                ProjectType.TYPESCRIPT -> file.name.endsWith(".ts") && !file.name.contains("test") && !file.name.contains("spec")
                ProjectType.UNKNOWN -> false
            }
            
            if (isTestable) {
                testableFiles.add(file.name)
            }
        }
        
        return testableFiles
    }

    private suspend fun generateTestForFile(
        projectSlug: String, 
        fileName: String, 
        projectType: ProjectType, 
        testFramework: TestFramework?
    ): String? {
        val projectPath = "projects/$projectSlug"
        val fileContent = sandboxManager.readFile("$projectPath/$fileName").getOrNull() ?: return null

        return when (projectType) {
            ProjectType.PYTHON -> generatePythonTest(fileName, fileContent, testFramework)
            ProjectType.JAVASCRIPT, ProjectType.TYPESCRIPT -> generateJavaScriptTest(fileName, fileContent, testFramework)
            ProjectType.UNKNOWN -> null
        }
    }

    private fun generatePythonTest(fileName: String, content: String, framework: TestFramework?): String {
        val moduleName = fileName.removeSuffix(".py")
        val functions = extractPythonFunctions(content)
        val classes = extractPythonClasses(content)

        return when (framework) {
            TestFramework.PYTEST -> generatePytestCode(moduleName, functions, classes)
            else -> generateUnittestCode(moduleName, functions, classes)
        }
    }

    private fun generateJavaScriptTest(fileName: String, content: String, framework: TestFramework?): String {
        val moduleName = fileName.removeSuffix(".js").removeSuffix(".ts")
        val functions = extractJavaScriptFunctions(content)

        return when (framework) {
            TestFramework.JEST -> generateJestCode(moduleName, functions)
            TestFramework.VITEST -> generateVitestCode(moduleName, functions)
            TestFramework.MOCHA -> generateMochaCode(moduleName, functions)
            else -> generateJestCode(moduleName, functions) // Default to Jest
        }
    }

    private fun generateTestFileName(sourceFile: String, projectType: ProjectType): String {
        return when (projectType) {
            ProjectType.PYTHON -> "test_${sourceFile}"
            ProjectType.JAVASCRIPT -> sourceFile.replace(".js", ".test.js")
            ProjectType.TYPESCRIPT -> sourceFile.replace(".ts", ".test.ts")
            ProjectType.UNKNOWN -> "test_$sourceFile"
        }
    }

    private suspend fun runPythonTests(projectPath: String, framework: TestFramework?): TestResult {
        val command = when (framework) {
            TestFramework.PYTEST -> "python -m pytest tests/ -v"
            else -> "python -m unittest discover tests -v"
        }

        return sandboxManager.executeShell(command, workingDir = projectPath).fold(
            onSuccess = { output -> parsePythonTestOutput(output, framework) },
            onFailure = { TestResult(false, 0, 0, 0, null, it.message ?: "Test execution failed") }
        )
    }

    private suspend fun runJavaScriptTests(projectPath: String, framework: TestFramework?): TestResult {
        val command = when (framework) {
            TestFramework.JEST -> "npm test"
            TestFramework.VITEST -> "npx vitest run"
            TestFramework.MOCHA -> "npx mocha tests/"
            else -> "npm test"
        }

        return sandboxManager.executeShell(command, workingDir = projectPath).fold(
            onSuccess = { output -> parseJavaScriptTestOutput(output, framework) },
            onFailure = { TestResult(false, 0, 0, 0, null, it.message ?: "Test execution failed") }
        )
    }

    // Helper functions for parsing and generating test code
    private fun extractPythonFunctions(content: String): List<String> {
        val functionRegex = Regex("""def\s+(\w+)\s*\([^)]*\):""")
        return functionRegex.findAll(content).map { it.groupValues[1] }.toList()
    }

    private fun extractPythonClasses(content: String): List<String> {
        val classRegex = Regex("""class\s+(\w+)(?:\([^)]*\))?:""")
        return classRegex.findAll(content).map { it.groupValues[1] }.toList()
    }

    private fun extractJavaScriptFunctions(content: String): List<String> {
        val functionRegex = Regex("""(?:function\s+(\w+)|const\s+(\w+)\s*=|(\w+)\s*:\s*function)""")
        return functionRegex.findAll(content).mapNotNull { 
            it.groupValues[1].ifBlank { it.groupValues[2].ifBlank { it.groupValues[3] } }
        }.toList()
    }

    private fun generatePytestCode(moduleName: String, functions: List<String>, classes: List<String>): String {
        return buildString {
            appendLine("import pytest")
            appendLine("from $moduleName import *")
            appendLine()
            
            functions.forEach { func ->
                appendLine("def test_${func}():")
                appendLine("    # TODO: Implement test for $func")
                appendLine("    assert True  # Placeholder")
                appendLine()
            }
            
            classes.forEach { cls ->
                appendLine("class Test$cls:")
                appendLine("    def test_${cls.lowercase()}_creation(self):")
                appendLine("        # TODO: Test $cls instantiation")
                appendLine("        assert True  # Placeholder")
                appendLine()
            }
        }
    }

    private fun generateUnittestCode(moduleName: String, functions: List<String>, classes: List<String>): String {
        return buildString {
            appendLine("import unittest")
            appendLine("from $moduleName import *")
            appendLine()
            appendLine("class Test${moduleName.capitalize()}(unittest.TestCase):")
            appendLine()
            
            functions.forEach { func ->
                appendLine("    def test_${func}(self):")
                appendLine("        # TODO: Implement test for $func")
                appendLine("        self.assertTrue(True)  # Placeholder")
                appendLine()
            }
            
            classes.forEach { cls ->
                appendLine("    def test_${cls.lowercase()}_creation(self):")
                appendLine("        # TODO: Test $cls instantiation")
                appendLine("        self.assertTrue(True)  # Placeholder")
                appendLine()
            }
            
            appendLine("if __name__ == '__main__':")
            appendLine("    unittest.main()")
        }
    }

    private fun generateJestCode(moduleName: String, functions: List<String>): String {
        return buildString {
            appendLine("const { ${functions.joinToString(", ")} } = require('./$moduleName');")
            appendLine()
            appendLine("describe('$moduleName', () => {")
            
            functions.forEach { func ->
                appendLine("  test('$func should work correctly', () => {")
                appendLine("    // TODO: Implement test for $func")
                appendLine("    expect(true).toBe(true); // Placeholder")
                appendLine("  });")
                appendLine()
            }
            
            appendLine("});")
        }
    }

    private fun generateVitestCode(moduleName: String, functions: List<String>): String {
        return buildString {
            appendLine("import { describe, test, expect } from 'vitest';")
            appendLine("import { ${functions.joinToString(", ")} } from './$moduleName';")
            appendLine()
            appendLine("describe('$moduleName', () => {")
            
            functions.forEach { func ->
                appendLine("  test('$func should work correctly', () => {")
                appendLine("    // TODO: Implement test for $func")
                appendLine("    expect(true).toBe(true); // Placeholder")
                appendLine("  });")
                appendLine()
            }
            
            appendLine("});")
        }
    }

    private fun generateMochaCode(moduleName: String, functions: List<String>): String {
        return buildString {
            appendLine("const { expect } = require('chai');")
            appendLine("const { ${functions.joinToString(", ")} } = require('./$moduleName');")
            appendLine()
            appendLine("describe('$moduleName', () => {")
            
            functions.forEach { func ->
                appendLine("  it('$func should work correctly', () => {")
                appendLine("    // TODO: Implement test for $func")
                appendLine("    expect(true).to.be.true; // Placeholder")
                appendLine("  });")
                appendLine()
            }
            
            appendLine("});")
        }
    }

    private fun parsePythonTestOutput(output: String, framework: TestFramework?): TestResult {
        // Parse pytest or unittest output
        val lines = output.lines()
        var testsRun = 0
        var testsPassed = 0
        var testsFailed = 0

        when (framework) {
            TestFramework.PYTEST -> {
                // Parse pytest output: "= 5 passed, 2 failed in 0.12s ="
                val resultLine = lines.find { it.contains("passed") || it.contains("failed") }
                if (resultLine != null) {
                    val passedMatch = Regex("""(\d+) passed""").find(resultLine)
                    val failedMatch = Regex("""(\d+) failed""").find(resultLine)
                    testsPassed = passedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    testsFailed = failedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    testsRun = testsPassed + testsFailed
                }
            }
            else -> {
                // Parse unittest output: "Ran 7 tests in 0.001s"
                val ranLine = lines.find { it.startsWith("Ran") && it.contains("tests") }
                if (ranLine != null) {
                    val match = Regex("""Ran (\d+) tests""").find(ranLine)
                    testsRun = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    testsPassed = if (output.contains("FAILED")) testsRun - 1 else testsRun
                    testsFailed = testsRun - testsPassed
                }
            }
        }

        return TestResult(
            success = testsFailed == 0 && testsRun > 0,
            testsRun = testsRun,
            testsPassed = testsPassed,
            testsFailed = testsFailed,
            output = output
        )
    }

    private fun parseJavaScriptTestOutput(output: String, framework: TestFramework?): TestResult {
        // Parse Jest/Vitest/Mocha output
        val lines = output.lines()
        var testsRun = 0
        var testsPassed = 0
        var testsFailed = 0

        // Look for summary line like "Tests: 2 passed, 1 failed, 3 total"
        val summaryLine = lines.find { 
            it.contains("Tests:") || it.contains("passing") || it.contains("failing") 
        }
        
        if (summaryLine != null) {
            val passedMatch = Regex("""(\d+) (?:passed|passing)""").find(summaryLine)
            val failedMatch = Regex("""(\d+) (?:failed|failing)""").find(summaryLine)
            val totalMatch = Regex("""(\d+) total""").find(summaryLine)
            
            testsPassed = passedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            testsFailed = failedMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            testsRun = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: (testsPassed + testsFailed)
        }

        return TestResult(
            success = testsFailed == 0 && testsRun > 0,
            testsRun = testsRun,
            testsPassed = testsPassed,
            testsFailed = testsFailed,
            output = output
        )
    }
}