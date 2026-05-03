package com.forge.os.domain.security

import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.model.TimeWindow
import com.forge.os.domain.model.UserRole
import kotlinx.serialization.Serializable
import timber.log.Timber
import java.io.File
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PermissionSet(
    val userId: String = "default",
    val role: UserRole = UserRole.ADMIN,
    val toolPermissions: Map<String, ToolPermission> = buildDefaultToolPermissions(),
    val filePermissions: FilePermissionRules = FilePermissionRules(),
    val networkPermissions: NetworkPermissions = NetworkPermissions(),
    val configPermissions: ConfigPermissions = ConfigPermissions(),
    val delegationPermissions: DelegationPermissions = DelegationPermissions()
)

@Serializable
data class ToolPermission(
    val toolName: String,
    val allowed: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val dailyQuota: Int = 500,
    val usedToday: Int = 0,
    val timeWindows: List<TimeWindow> = emptyList(),
    val allowedPatterns: List<String> = emptyList(),
    val blockedPatterns: List<String> = emptyList()
)

@Serializable
data class FilePermissionRules(
    // `**` matches any depth so the agent can read/write nested project files
    // like `projects/myproj/src/foo.kt`. Single `*` would only match one segment.
    val canRead: List<String> = listOf("projects/**", "memory/**", "workspace/**"),
    val canWrite: List<String> = listOf("projects/**", "workspace/**"),
    val canDelete: List<String> = listOf("projects/**"),
    val maxWriteSizeMb: Int = 10,
    val blockedExtensions: List<String> = listOf("exe", "bin", "so", "apk")
)

@Serializable
data class NetworkPermissions(
    val canAccessInternet: Boolean = false,
    val allowedHosts: List<String> = listOf(
        "api.openai.com", "api.anthropic.com", "api.groq.com"
    ),
    val blockedHosts: List<String> = listOf("localhost", "127.0.0.1")
)

@Serializable
data class ConfigPermissions(
    val canReadConfig: Boolean = true,
    val canModifyConfig: Boolean = true,
    val blockedConfigPaths: List<String> = listOf("sandboxLimits.blockedPaths")
)

@Serializable
data class DelegationPermissions(
    val canDelegate: Boolean = true,
    val maxSubAgents: Int = 5,
    val canCreateAgents: Boolean = true
)

data class PermissionCheckResult(
    val allowed: Boolean,
    val requiresConfirmation: Boolean = false,
    val reason: String = ""
)

@Singleton
class PermissionManager @Inject constructor(
    private val configRepository: ConfigRepository
) {
    private val permissionsFile by lazy {
        File(configRepository.get().let {
            // resolve workspace dir
            ""
        })
    }

    // In-memory permissions for Phase 1 (Phase 2 will persist to disk)
    private val permissions = mutableMapOf<String, PermissionSet>()

    fun getPermissions(userId: String = "default"): PermissionSet {
        val base = permissions.getOrPut(userId) { PermissionSet() }
        // Phase S — merge user-controlled overrides every read so toggles in
        // Tools → Advanced overrides take effect without an app restart.
        val o = configRepository.get().permissions.userOverrides
        if (o.extraBlockedHosts.isEmpty() &&
            o.extraBlockedExtensions.isEmpty() &&
            o.extraBlockedConfigPaths.isEmpty()
        ) return base
        return base.copy(
            filePermissions = base.filePermissions.copy(
                blockedExtensions = (base.filePermissions.blockedExtensions + o.extraBlockedExtensions).distinct()
            ),
            networkPermissions = base.networkPermissions.copy(
                blockedHosts = (base.networkPermissions.blockedHosts + o.extraBlockedHosts).distinct()
            ),
            configPermissions = base.configPermissions.copy(
                blockedConfigPaths = (base.configPermissions.blockedConfigPaths + o.extraBlockedConfigPaths).distinct()
            ),
        )
    }

    fun checkTool(
        toolName: String,
        arguments: Map<String, Any> = emptyMap(),
        userId: String = "default"
    ): PermissionCheckResult {
        val config = configRepository.get()

        // Check global disable list first
        if (toolName in config.toolRegistry.disabledTools) {
            return PermissionCheckResult(false, reason = "Tool '$toolName' is globally disabled")
        }

        val perms = getPermissions(userId)
        val toolPerm = perms.toolPermissions[toolName]
            ?: run {
                // The default permission map only covers core built-ins. Plugin tools,
                // MCP-prefixed tools, snapshot/* tools and any newer additions won't
                // have a per-user entry — but the global disabledTools list (checked
                // above) is the authoritative kill-switch. So fall through to "allowed",
                // surfacing only the destructive-confirmation flag from behaviorRules.
                val needsConfirm = toolName in config.behaviorRules.confirmDestructive &&
                    !config.behaviorRules.autoConfirmToolCalls
                return PermissionCheckResult(allowed = true, requiresConfirmation = needsConfirm)
            }

        if (!toolPerm.allowed) {
            return PermissionCheckResult(false, reason = "Tool '$toolName' not permitted for user")
        }

        if (toolPerm.usedToday >= toolPerm.dailyQuota) {
            return PermissionCheckResult(false, reason = "Daily quota for '$toolName' exceeded (${toolPerm.dailyQuota})")
        }

        if (toolPerm.timeWindows.isNotEmpty() && !isInTimeWindow(toolPerm.timeWindows)) {
            return PermissionCheckResult(false, reason = "Tool '$toolName' not available at this time")
        }

        val argString = arguments.toString()
        for (blocked in toolPerm.blockedPatterns) {
            if (Regex(blocked).containsMatchIn(argString)) {
                return PermissionCheckResult(false, reason = "Arguments match blocked pattern: $blocked")
            }
        }

        // Check if destructive and requires confirmation
        val needsConfirm = toolPerm.requiresConfirmation ||
            (toolName in config.behaviorRules.confirmDestructive && !config.behaviorRules.autoConfirmToolCalls)

        return PermissionCheckResult(allowed = true, requiresConfirmation = needsConfirm)
    }

    fun checkFileRead(path: String, userId: String = "default"): Boolean {
        val perms = getPermissions(userId)
        return perms.filePermissions.canRead.any { matchGlob(it, path) }
    }

    fun checkFileWrite(path: String, sizeBytes: Long, userId: String = "default"): PermissionCheckResult {
        val perms = getPermissions(userId)
        val ext = path.substringAfterLast('.', "")

        if (ext in perms.filePermissions.blockedExtensions) {
            return PermissionCheckResult(false, reason = "Extension '.$ext' is blocked")
        }

        if (sizeBytes > perms.filePermissions.maxWriteSizeMb * 1024 * 1024) {
            return PermissionCheckResult(false, reason = "File too large (max ${perms.filePermissions.maxWriteSizeMb}MB)")
        }

        val canWrite = perms.filePermissions.canWrite.any { matchGlob(it, path) }
        return PermissionCheckResult(allowed = canWrite, reason = if (!canWrite) "Path not in write-allowed list" else "")
    }

    fun checkFileDelete(path: String, userId: String = "default"): PermissionCheckResult {
        val perms = getPermissions(userId)
        val canDelete = perms.filePermissions.canDelete.any { matchGlob(it, path) }
        return PermissionCheckResult(
            allowed = canDelete,
            requiresConfirmation = canDelete, // Always confirm deletes
            reason = if (!canDelete) "Path not in delete-allowed list" else ""
        )
    }

    fun canModifyConfig(userId: String = "default", path: String? = null): Boolean {
        val perms = getPermissions(userId)
        if (!perms.configPermissions.canModifyConfig) return false
        // Phase S — when the user has locked the agent out of overrides, the
        // agent's `control_set` cannot rewrite anything under `permissions.*`.
        val overrides = configRepository.get().permissions.userOverrides
        if (overrides.lockAgentOut && path != null && path.startsWith("permissions")) return false
        if (path != null && perms.configPermissions.blockedConfigPaths.any { path.startsWith(it) }) return false
        return true
    }

    fun canCreateAgents(userId: String = "default"): Boolean {
        return getPermissions(userId).delegationPermissions.canCreateAgents
    }

    fun updateToolPermission(userId: String = "default", toolName: String, enabled: Boolean) {
        val current = getPermissions(userId)
        val updated = current.copy(
            toolPermissions = current.toolPermissions + (toolName to
                (current.toolPermissions[toolName] ?: ToolPermission(toolName)).copy(allowed = enabled))
        )
        permissions[userId] = updated
        Timber.i("Tool permission updated: $toolName = $enabled for $userId")
    }

    fun getPermissionSummary(userId: String = "default"): String {
        val perms = getPermissions(userId)
        val config = configRepository.get()
        val sb = StringBuilder("📋 Permission Profile (${perms.role}):\n\nTools:\n")

        val allTools = config.toolRegistry.enabledTools + config.toolRegistry.disabledTools
        for (tool in allTools.distinct().sorted()) {
            val disabled = tool in config.toolRegistry.disabledTools
            val perm = perms.toolPermissions[tool]
            val status = when {
                disabled -> "❌ disabled globally"
                perm?.allowed == false -> "⛔ blocked for user"
                perm?.requiresConfirmation == true -> "⚠️  allowed (confirm)"
                else -> "✅ allowed"
            }
            sb.appendLine("  $status  $tool")
        }

        sb.appendLine("\nConfig:")
        sb.appendLine("  ${if (perms.configPermissions.canReadConfig) "✅" else "❌"} Read config")
        sb.appendLine("  ${if (perms.configPermissions.canModifyConfig) "✅" else "❌"} Modify config")

        sb.appendLine("\nDelegation:")
        sb.appendLine("  ${if (perms.delegationPermissions.canCreateAgents) "✅" else "❌"} Create sub-agents (max ${perms.delegationPermissions.maxSubAgents})")

        return sb.toString()
    }

    private fun isInTimeWindow(windows: List<TimeWindow>): Boolean {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = now.get(Calendar.DAY_OF_WEEK)
        return windows.any { w ->
            hour in w.startHour..w.endHour && dayOfWeek in w.daysOfWeek
        }
    }

    private fun matchGlob(pattern: String, path: String): Boolean {
        // Order matters here. We must:
        //   1. Escape literal dots first.
        //   2. Replace `**` with a placeholder (because step 3 would corrupt the
        //      `*` characters inside the regex `.*` we'd otherwise emit).
        //   3. Replace single `*` with `[^/]*` (single path segment).
        //   4. Restore the `**` placeholder as `.*` (any depth).
        val placeholder = "\u0000DOUBLESTAR\u0000"
        val regex = pattern
            .replace(".", "\\.")
            .replace("**", placeholder)
            .replace("*", "[^/]*")
            .replace(placeholder, ".*")
        return Regex("^$regex$").matches(path)
    }
}

private fun buildDefaultToolPermissions(): Map<String, ToolPermission> {
    val defaults = listOf(
        "file_read", "file_write", "file_list", "file_delete",
        "shell_exec", "python_run", "workspace_info",
        "memory_store", "memory_recall", "config_read", "config_write",
        "heartbeat_check", "cron_add", "cron_list", "cron_remove"
    )
    return defaults.associateWith { name ->
        ToolPermission(
            toolName = name,
            allowed = true,
            requiresConfirmation = name in listOf("file_delete", "shell_exec")
        )
    }
}
