package com.forge.os.domain.workspace

/**
 * Phase S — Canonical workspace layout.
 *
 * Single source of truth for "where does X belong inside the workspace".
 * SandboxManager creates these folders on init; the agent's system prompt
 * teaches the model to route writes here; `workspace_describe` returns the
 * human-readable form below so the agent can re-orient itself mid-task.
 *
 * Adding a folder here is a three-step change:
 *   1) add the entry to [Folders] with a one-line purpose,
 *   2) add the folder name to SandboxManager.init's mkdir list,
 *   3) (optional) add a routing hint in [routingHint] so the agent picks
 *      this folder by default for that kind of write.
 */
object WorkspaceLayout {

    data class Folder(val name: String, val purpose: String)

    val Folders: List<Folder> = listOf(
        Folder("projects",  "User-owned code, sites, docs. One subfolder per project (e.g. projects/blog/, projects/scraper/)."),
        Folder("downloads", "Anything pulled from the network (file_download, browser_download). Never put user-authored files here."),
        Folder("uploads",   "Files the user pushed from their device into the workspace."),
        Folder("memory",    "Long-term memory shards. Managed by MemoryManager — don't write here directly."),
        Folder("skills",    "Reusable Python/shell scripts the agent saved with memory_store_skill."),
        Folder("plugins",   "Installed plugin manifests + code. Managed by PluginManager."),
        Folder("cron",      "Scheduled task definitions and run logs."),
        Folder("alarms",    "One-shot alarm payloads and post-fire snapshots."),
        Folder("agents",    "Sub-agent transcripts and delegation artifacts."),
        Folder("heartbeat", "Self-check reports written by HeartbeatMonitor."),
        Folder("snapshots", "Config and data snapshots for restore."),
        Folder("system",    "App-internal scratch (audit logs, doctor reports). Read-mostly."),
        Folder("temp",      "Ephemeral. Safe to wipe via temp_clear at any time."),
        Folder("notes",     "Markdown notes the agent wrote on the user's behalf."),
        Folder("exports",   "Chat / data exports the user explicitly asked for."),
    )

    /**
     * Returns the recommended folder for a given high-level intent. Used by
     * the agent's planning step before issuing a `file_write` so files don't
     * end up at the workspace root.
     */
    fun routingHint(intent: Intent): String = when (intent) {
        Intent.UserProject       -> "projects/<project>/"
        Intent.NetworkDownload   -> "downloads/"
        Intent.UserUpload        -> "uploads/"
        Intent.SkillScript       -> "skills/"
        Intent.NoteOrSummary     -> "notes/"
        Intent.ChatOrDataExport  -> "exports/"
        Intent.PluginCode        -> "plugins/<plugin_id>/"
        Intent.OneShotScratch    -> "temp/"
        Intent.AlarmArtifact     -> "alarms/<alarm_id>/"
        Intent.SubAgentArtifact  -> "agents/<task_id>/"
    }

    enum class Intent {
        UserProject,
        NetworkDownload,
        UserUpload,
        SkillScript,
        NoteOrSummary,
        ChatOrDataExport,
        PluginCode,
        OneShotScratch,
        AlarmArtifact,
        SubAgentArtifact,
    }

    /**
     * Multi-line human-readable summary returned by the `workspace_describe`
     * tool. The agent reads this whenever it's unsure where to place a file.
     */
    fun describe(): String = buildString {
        appendLine("Workspace layout (Phase S):")
        for (f in Folders) appendLine("  • ${f.name.padEnd(10)} — ${f.purpose}")
        appendLine()
        appendLine("Routing rules of thumb:")
        appendLine("  • Code/docs/sites the user is building → projects/<project>/")
        appendLine("  • Files you pulled from the web        → downloads/")
        appendLine("  • Notes/summaries you wrote           → notes/")
        appendLine("  • Reusable scripts                    → skills/  (also memory_store_skill)")
        appendLine("  • Anything you'll throw away          → temp/")
        appendLine("  • Never write to: memory/, plugins/, snapshots/, system/ directly — use the matching tool.")
        appendLine("  • Never dump files at the workspace root.")
    }
}
