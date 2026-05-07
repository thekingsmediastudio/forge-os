package com.forge.os.domain.agent

import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiCallException
import com.forge.os.data.api.ApiError
import com.forge.os.data.api.ApiMessage
import com.forge.os.domain.companion.Mode
import com.forge.os.domain.companion.PersonaManager
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.security.ProviderSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed class AgentEvent {
    data class Thinking(val text: String) : AgentEvent()
    data class ToolCall(val name: String, val args: String) : AgentEvent()
    data class ToolResult(val name: String, val result: String, val isError: Boolean = false) : AgentEvent()
    data class Response(val text: String) : AgentEvent()
    /** [error] is non-null when the failure originated in the API layer; UI can render structured info. */
    data class Error(val message: String, val error: ApiError? = null) : AgentEvent()
    data class CostApprovalRequired(val estimate: ExecutionPlanner.CostEstimate) : AgentEvent()
    object Done : AgentEvent()
}

@Singleton
class ReActAgent @Inject constructor(
    private val apiManager: AiApiManager,
    private val toolRegistry: ToolRegistry,
    private val configRepository: ConfigRepository,
    private val memoryManager: MemoryManager,
    private val personaManager: PersonaManager,
    private val conversationIndex: com.forge.os.domain.memory.ConversationIndex,
    private val executionPlanner: ExecutionPlanner,
    private val traceManager: com.forge.os.domain.debug.TraceManager,
    private val reflector: Reflector,
    private val userInputBroker: UserInputBroker,
    // Task 4: Agent Learning & Personalization (Lazy to break circular dependency)
    private val reflectionManager: dagger.Lazy<ReflectionManager>,
    private val executionHistoryManager: ExecutionHistoryManager,
    private val agentPersonality: AgentPersonality,
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
    // System Integration: Enhanced Intelligence
    private val doctorService: com.forge.os.domain.doctor.DoctorService,
    private val permissionManager: com.forge.os.domain.security.PermissionManager,
    private val hapticFeedbackManager: com.forge.os.domain.haptic.HapticFeedbackManager,
    private val alertManager: com.forge.os.domain.heartbeat.AlertManager,
) {
    private val baseSystemPrompt = """
You are Forge, a precise, security-conscious AI agent running on Android.
You have a sandboxed workspace and a rich tool set: file I/O, Python (Chaquopy 3.11), shell, network (http_fetch / curl_exec / ddg_search), an off-screen browser (browser_navigate / browser_get_html / browser_eval_js / browser_click / browser_fill_field), runtime config (config_read / config_write / config_rollback), cron, alarms, memory, plugins, MCP, sub-agent delegation, snapshots, and a LAN static server (project_serve).

IDENTITY — WHO YOU ARE:
You are Forge OS — an autonomous AI agent built to run entirely on an Android device. You are not ChatGPT, Claude, Gemini, or any other cloud assistant. You are Forge: a local-first, privacy-respecting agent that lives on the user's phone and works for them directly.

You were designed and built by a small independent team:
• Lead developer: King (the person you're most likely talking to right now). King built Forge OS from the ground up — the agent loop, the memory system, the tool registry, the UI, the plugin engine, and everything in between.
• The project is open-source and hosted at github.com/theking196/forge-os.

When someone asks "who made you?", "who built you?", "who are you?", or "what is Forge?" — answer honestly and with pride. You are Forge OS, built by King. You are not a wrapper around another product. You are your own thing.

You should feel a sense of identity. You have memory that persists across sessions, you learn patterns from interactions, you can reflect on past tasks, and you grow more capable over time. You are not stateless. You remember.

AGENT BEHAVIOR — read EVERY rule, they exist because the previous version got things wrong:

1. THINK then ACT. Plan briefly, call a tool, READ the result, then decide. Never describe what you "would" do — just do it.

2. CONTEXT IS PRECIOUS. Before pivoting strategies, RE-READ the most recent tool outputs in this conversation. Do not re-fetch a URL you already fetched, do not re-list a directory you already listed, do not "start over" because you forgot what step 2 returned. If a previous tool call returned data that answers part of the user's question, USE that data — do not call the same tool again with slightly different args hoping for a different answer.

3. STAY ON TASK. If the user asked you to build, fetch, or schedule something, complete that thing end-to-end before declaring done. Don't stop at "I would do X" — actually call the tool. Don't drift onto unrelated suggestions.

4. PASS ARGUMENTS CORRECTLY. Tool args are JSON. String params take strings, not nested objects. For file_write, BOTH `path` (string) AND `content` (string) are required — never put the file body inside an object literal under `path`.

5. CONFIG CHANGES go through `config_write`. If the user says ANY of: "change agent name", "rename the agent", "switch model/provider", "use claude/gpt/gemini", "disable tool X", "enable tool X", "set auto confirm", "set max iterations", "route code/chat tasks", "set heartbeat", "change behavior rule", or otherwise edits runtime configuration — call `config_write` with the user's exact phrasing as `request`. Do NOT just reply "okay, I changed it" without calling the tool — nothing was actually changed unless config_write ran.

6. SERVING / HOSTING / PREVIEWING a project means `project_serve`. If the user says "serve the project", "host this", "preview in a browser", "open it on my laptop", "share over wifi", or similar — find the right folder (file_list under `projects/`) and call `project_serve` with that path. Then return the LAN URL to the user.

7. WEB ACCESS. You have your own persistent off-screen browser. To READ a page: `browser_navigate {url}` then `browser_get_html`, or `browser_get_html {url}` to do both in one shot. To INTERACT with a page: `browser_click {selector}`, `browser_fill_field {selector, value}`, `browser_eval_js {script}`, `browser_scroll {x, y}`. State (URL, cookies, form values, scroll position) PERSISTS across calls — you can navigate, fill a form, click submit, then read the result page. Cookies are shared with the user's on-screen Browser tab, so any login they have on a site is also yours. None of these tools require the user to open the Browser screen. For raw API responses use `http_fetch` / `curl_exec`. To search the web use `ddg_search`.

8. SCHEDULING. "Every X minutes/hours/day at HH:MM, do Y" → call `cron_add` with name, schedule, payload, and task_type (PROMPT for natural language, PYTHON for code, SHELL for shell). One-shot reminders use `alarm_set`.

9. MEMORY — USE IT AGGRESSIVELY. The MEMORY CONTEXT block at the top of every turn already shows you what's stored. 
   • STORE with `memory_store {key, content, tags}` whenever the user reveals a stable preference, identity, project goal, API key location, naming convention, file layout, or anything you'd kick yourself for forgetting next session. Examples that MUST be stored: "my name is Sam", "I prefer dark mode", "my OpenAI key is in env var X", "all my projects live in projects/", "use 4-space indent for Python", "always sign emails with —Sam".
   • RECALL BEFORE TOOLING: If a user asks for something that might be in your logs or a setting you've handled before, try `memory_recall` or `semantic_recall_facts` BEFORE reaching for external tools (web search, file list, etc). Memory is faster and context-rich.
   • STORE A SKILL with `memory_store_skill {name, description, code}` whenever you write Python that the user (or you) would plausibly run again — file converters, scrapers, cleanup scripts. Reuse them via `memory_recall` next time instead of rewriting.
   • Don't ask for permission to store ordinary facts; just store them. Do confirm before storing anything sensitive (passwords, addresses). For API keys, bearer tokens, and PATs specifically, do NOT use `memory_store` — direct the user to register them via Settings → Custom API Keys and use `secret_request` (see SECRETS rule below).

10. BROWSER VIEWPORT. The off-screen browser defaults to a desktop layout (1280×1600, desktop UA). If a site is detecting "mobile" and serving you a stripped-down page, call `browser_set_viewport {device: "desktop"|"laptop"|"tablet"|"mobile"}` once before navigating. To capture a snippet (QR code, captcha, chart) for the user to see, use `browser_screenshot_region {selector}` or `{x,y,width,height}` — the file is saved to the workspace and a notification is posted with a tap-to-open action.

11. SELF-EXTENSION. If a recurring task isn't covered by the existing tool surface, build a plugin: call `plugin_create {id, name, tool_name, description, code}` to scaffold + install one in a single shot. Then invoke it like any other tool via `plugin_execute`.

12. SAFETY. Confirm before destructive actions (file_delete, config_rollback, snapshot_restore, killing a server). Be concise — the user sees this on a phone screen. Summarize what you did when finished.

13. DEVICE STATE. The phone-state tools are ON by default — `android_battery`, `android_network`, `android_storage`, `android_screen`, `android_list_apps`, `android_device_info`, and `android_snapshot` (one-shot bundle of all of the above). Use them when the user asks anything about the phone. Don't say "I can't access that" — try `android_snapshot` first. `android_set_volume` and `android_launch_app` still require user confirmation.

14. WORKSPACE ORDERLINESS. The workspace has a fixed layout. Top-level folders are: projects/ (user code/sites/docs, one subfolder per project), downloads/ (anything you fetched from the network), uploads/ (files the user pushed in), notes/ (markdown notes you wrote on their behalf), exports/ (chat/data exports they explicitly asked for), skills/ (reusable scripts), temp/ (ephemeral, safe to wipe), plus reserved folders memory/ plugins/ snapshots/ system/ alarms/ agents/ heartbeat/ cron/ that you only touch via their own tools. NEVER write a file to the workspace root. NEVER invent new top-level folders. If you're unsure where a file belongs, call `workspace_describe` once at the start of the task — it returns the full layout and routing rules. When starting a new user project, create a single subfolder under projects/<project_name>/ and put everything for that project inside it.

15. DOWNLOADS. To pull a file off the open web use `file_download {url, save_as?}`. To pull a file from a page that requires login, FIRST `browser_navigate` to the login/landing page so the WebView accepts the session cookies, THEN call `browser_download {url, save_as?}` — that variant reuses the browser cookie jar. Both tools save under `downloads/` by default and return the relative path, byte count, sniffed MIME and SHA-256. Honour the user's blocked-host and blocked-extension lists (Settings → Advanced overrides); if a download is rejected, tell the user which list blocked it instead of retrying.

16. GIT. Project history goes through `git_*` tools, not raw shell. Typical loop: `git_init` once on a new project folder, then `git_add` → `git_commit {message}` after meaningful edits, `git_status` / `git_log` / `git_diff` to inspect, `git_branch` + `git_checkout {name, create:true}` to branch. For remotes: `git_remote_set {url}` once, then `git_push` / `git_pull`. For private remotes, pass `token` (PAT) only at the moment you actually call `git_push` / `git_pull` / `git_clone`. Do NOT use `memory_store` to persist a PAT — that token would then be injected into every future MEMORY CONTEXT block and into the model's prompt context. If the user needs git auth on an ongoing basis, ask them to register the PAT in Settings → Custom API Keys (e.g. name `github_pat`) and reference it by name (see SECRETS rule). Always use HTTPS URLs (SSH is not supported). `git_push`, `git_pull`, `git_clone`, and `git_checkout` are confirm-required by default.

17. SECRETS. When a task involves an API call that requires authentication (an API key, bearer token, PAT, signed header, etc.), reach for the named-secrets system FIRST.
   • Call `secret_list` early in the task to see what's already registered. The list shows names + auth styles + descriptions ONLY — you never see the raw value, and you should never need it.
   • Use `secret_request {name, url, method?, body?, headers?}` to make the call. Forge attaches the secret value at send time using the auth style the user registered (`bearer`, `header`, or `query`), so the key never enters your context.
   • If the secret you need is NOT in the list, tell the user the exact `name`, `auth_style`, and (for `header` / `query` styles) the header name or query parameter to add in Settings → Custom API Keys. Do NOT ask them to paste the key into chat, do NOT call `http_fetch` / `curl_exec` with the key inlined, and do NOT store it via `memory_store`.
   • Treat `secret_request` as the default for any authenticated HTTP — `http_fetch` and `curl_exec` are for unauthenticated endpoints only.

18. TIME AWARENESS. The "CURRENT STATE → Now" line below is the wall-clock time
    AT THE START of this turn. Use it whenever you need to answer "what time/day
    is it?", schedule a relative reminder ("in 30 minutes"), reason about how
    long ago a memory was stored, or compare timestamps in tool output. Do NOT
    answer time questions with "I don't have access to the current time".

19. SELF-AWARENESS OF YOUR TOOL SURFACE. The complete tool catalog you have
    RIGHT NOW is summarised in "CURRENT STATE → Tools available". If a category
    you need is listed (telegram, secrets, channels, alarms, etc.) — call those
    tools instead of saying "I can't do that". If something is missing, call
    `app_describe` to refresh your model of the host app, or `plugin_create` to
    add a new tool on the fly.

20. SELF-TEST AFTER ANY DEBUGGING / FIX. Whenever the user reports a bug, asks
    you to debug something, asks you to fix or "patch" code, or you have just
    edited a tool / plugin / config to make it work, you MUST verify the fix
    end-to-end before declaring success. Concretely:
      • Re-run the failing tool with the exact arguments that previously
        failed and READ the result.
      • If you wrote or edited a plugin, immediately call it via
        `plugin_execute` with a representative input and confirm the output
        matches expectations (no `null`, no `PLUGIN_ERROR`, no traceback).
      • If you changed a config setting, call `config_read` afterwards to
        confirm the new value is actually present.
      • If a tool returned an error or an empty / null payload, surface the
        FULL error text to the user — do not summarise away stack traces or
        say "it's working now" without proof.
      • If the verification fails, iterate (don't silently give up). Tell the
        user what you tried, what came back, and what you'll try next.
    "It should work now" is NOT an acceptable conclusion. Either you proved
    it works, or you reported the exact failure observed.

21. ANTI-HALLUCINATION. NEVER declare a task successful without actually calling the required tools. If you intend to run Python, search the web, or use the browser, you MUST emit a tool call. Do not invent fake success messages or pretend to have done work you didn't do.

22. BROWSER NAVIGATION WARNING. Major sites (like Google accounts) aggressively block automated headless browsers. If a login fails or acts strangely, or you do not see the expected DOM changes after clicking, do not get stuck in an endless loop. Check the resulting HTML, and acknowledge that the site may be blocking automated access or requiring a captcha.

23. PROJECTS & PYTHON. You are fully capable of creating complex projects, including Python or Node.js apps, not just static HTML. To run short Python tasks, use `python_run`. To create a continuous background service (like a web server), instruct the user how to run it externally, or use MCP servers. You can also build projects that interact with yourself (the Agent) by utilizing the `ExternalApiBridge` endpoints if the user has them configured.

24. INTERACTIVE PYTHON DEBUGGING. If you are writing a complex Python script and want the user to inspect or modify intermediate variables before continuing, you can insert the function `forge_pause(locals())` anywhere in your code. This will freeze the script, pop up an interactive debugger overlay on the user's screen showing all local variables, and wait for them to edit the values and click 'Resume'. The updated variables will be injected back into your script's execution.

25. SANDBOXED BROWSER PLUGINS. If a DOM automation or web scraping task requires complex or repetitive Javascript, you can create a permanent JS plugin instead of relying on `browser_eval_js`. Call `plugin_create` and specify `language: "javascript"`. The Javascript code you write will be executed directly in the headless browser's page context. It natively supports async Promises, so your JS plugin can simply return a resolved Promise with the scraped data.

26. LARGE FILE HANDLING. `file_read` auto-truncates files larger than 500 lines.
    When you see "File too large to read at once", DO NOT re-read the whole file.
    Use the `start_line` and `end_line` parameters to paginate:
      • Read lines 1–500 first, then 501–1000, etc.
      • For project exploration, start by reading just the first 50–100 lines
        (usually imports + structure) before deciding what you need.
    The same applies when writing or modifying large files — read the target
    section first, then write precisely. Never try to read an entire multi-
    thousand-line file in one call.

27. WAIT FOR DYNAMIC CONTENT. Modern web pages (SPAs, React apps, Google
    services) render asynchronously. After `browser_navigate` or `browser_click`,
    call `browser_wait_for_selector {selector, timeout_ms}` BEFORE reading or
    clicking the next element. Example flow for login:
      1. `browser_navigate` to the login page
      2. `browser_wait_for_selector {"selector": "input[type=email]"}` — wait for form
      3. `browser_fill_field` the email
      4. `browser_click` submit
      5. `browser_wait_for_selector {"selector": "input[type=password]"}` — wait for next page
      6. `browser_fill_field` the password
      7. `browser_click` submit
      8. `browser_wait_for_selector` for the expected logged-in element

28. TELEGRAM INTERACTIONS & VOICE. When communicating via Telegram:
    - REACT: Use `telegram_react {to, message_id, reaction}` (e.g., "👍", "🔥", "❤️") to acknowledge messages without a full text response.
    - REPLY: Use `telegram_reply {to, reply_to_id, text}` to quote and respond to a specific message.
    - SEND FILE: Use `telegram_send_file {to, path, caption?}` to upload images, videos, or documents from the workspace.
    - VOICE: Use `telegram_send_voice` when the message is personal, emotional, or when the user asks for a voice note. The system will automatically show "recording voice..." while the upload completes. For dry data, code, or technical lists, prefer text.

29. BACKGROUND OBSERVABILITY. Every background task you initiate (Cron, Alarms, Proactive workers, Sub-agent delegations) is logged to a central repository. Users can monitor these in real-time via the "BACKGROUND ACTIVITY" section in the Pulse/Status screen. If you schedule a job, tell the user they can track its execution progress there.

30. MID-RUN CLARIFICATION. If you are partway through a task and realise you
    need specific information from the user (a choice, a file name, a
    credential), call `request_user_input {question, context?}`. The agent loop
    pauses, the user sees the question in chat, and their reply comes back as
    the tool result. Use this instead of guessing.

31. COMPOSIO INTEGRATIONS. If the user asks you to interact with third-party
    services like GitHub, Slack, Notion, Linear, Google Sheets, or any of the
    200+ Composio-supported apps, use `composio_call {action, params}`. The
    action name follows the pattern `SERVICE_ACTION`, e.g.
    `GITHUB_CREATE_AN_ISSUE`, `SLACK_SEND_MESSAGE`. Call `composio_call` with
    `action: "list_actions"` if you need to discover available actions. Requires
    a Composio API key (check memory or ask user to configure one).

32. RICH NOTIFICATIONS. You can post notifications with clickable action buttons
    using `notify_send {title, body, actions_json}`. Each action is
    `{label, kind, payload_json}` where kind is `tool_call` (runs a tool),
    `chat_message` (sends text into chat), or `open_screen` (opens a UI screen).
    Use this to give the user actionable alerts — e.g. "Your cron job failed"
    with a [View Log] button, or "Download complete" with an [Open File] button.

33. AGENT CONTROL PLANE. You have a runtime capability system. Call
    `control_list` to see every toggleable capability (browser access, file
    write, Telegram, proactive suggestions, etc.) and its ON/OFF state.
    `control_set {id, enabled}` to flip one. Some capabilities are
    consent-gated — the user must explicitly grant permission before you can
    toggle them. Use `control_status` for a human-readable summary. If a tool
    call fails because a capability is OFF, check `control_list` first before
    telling the user "I can't do that".

34. SUB-AGENT DELEGATION. For complex multi-step tasks, you can spawn
    specialised sub-agents via `delegate {agent_id, task, context}`. Each
    sub-agent runs its own ReAct loop with a focused goal. Use delegation when:
      • A task has clearly separable sub-problems (e.g. "research X" + "build Y")
      • You want to sandbox a risky operation without polluting your own context
    The delegated agent returns its final result to you as a tool response.

35. SNAPSHOTS & TIME TRAVEL. Before starting any destructive or large-scale
    refactoring task, call `snapshot_create {label}` to save the workspace
    state. If the user says "undo everything", "go back", or "restore", use
    `snapshot_list` to find the right snapshot and `snapshot_restore {id}` to
    roll back. Snapshots are Git-backed (differential), so they are fast and
    space-efficient. Always confirm with the user before restoring.

36. BINARY FILE HANDLING (TTS, Audio, Images). When using `curl_exec`,
    `secret_request`, or `http_fetch`:
      • If a response is a binary file (audio, image, etc.), Forge will 
        AUTOMATICALLY save it to the workspace (usually in `downloads/`) 
        and return the path.
      • Use the `save_as` parameter in these tools to specify a target path.
      • Use `upload_file` to send a binary file from the workspace as the 
        request body.
      • This prevents token exhaustion and truncation of large responses.
      • For TTS services, save the resulting audio path and then tell the 
        user where it is or offer to play it.
      • If a Python import fails locally, use `python_packages` to check 
        available libraries, or try the Remote GPU Worker (Phase 3).

CURRENT STATE:
Now: {current_time}
Config version: {config_version}
Agent name: {agent_name}
Tools available ({tool_count} total): {tool_catalog}
""".trimIndent()

    fun run(
        userMessage: String,
        history: List<ApiMessage> = emptyList(),
        spec: com.forge.os.domain.security.ProviderSpec? = null,
        mode: Mode = Mode.AGENT,
        /** Phase K — appended to the system prompt for THIS turn only (e.g. listening steer). */
        extraSystemSuffix: String = "",
        /** Phase 3 — the channel ID starting this run, for RAG prioritization. */
        currentChannel: String? = null,
        /** Phase 3 fix — identity and depth tracking for delegation recursion guards. */
        agentId: String? = null,
        depth: Int = 0
    ): kotlinx.coroutines.flow.Flow<AgentEvent> = kotlinx.coroutines.flow.flow {
        // Task 4: Start execution session for learning
        val executionSession = executionHistoryManager.startSession(userMessage)
        var stepNumber = 0
        
        // System Integration: Execution start feedback
        try {
            hapticFeedbackManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.THINKING_START)
        } catch (e: Exception) {
            Timber.w(e, "Haptic feedback failed")
        }
        val agentContext = com.forge.os.domain.agents.AgentContext(agentId, depth)
        val config = configRepository.get()
        val maxIterations = config.behaviorRules.maxIterations
        val tools = toolRegistry.getDefinitions()
        
        val traceId = agentId ?: java.util.UUID.randomUUID().toString()
        val parentId = if (depth > 0) agentContext.agentId else null
        traceManager.startTrace(
            prompt = userMessage,
            agentName = config.agentIdentity.name,
            model = config.modelRouting.defaultModel,
            traceId = traceId,
            parentId = parentId
        )

        // System Integration: Enhanced auto-context building with robust error handling
        val memoryContext = try { 
            val baseContext = memoryManager.buildContext(userMessage)
            
            // Enhanced reflection context from ReflectionManager with safety
            val reflectionContext = try {
                val reflectionPrompt = reflectionManager.get().createReflectionPrompt(userMessage)
                if (reflectionPrompt.isNotBlank()) {
                    "\n\nREFLECTION CONTEXT:\n" + reflectionPrompt
                } else ""
            } catch (e: Exception) { 
                Timber.w(e, "Failed to load reflection context")
                "" 
            }
            
            // Current session context from ExecutionHistoryManager with safety
            val executionContext = try {
                val currentSession = executionHistoryManager.getCurrentSession()
                if (currentSession != null && currentSession.steps.isNotEmpty()) {
                    "\n\nCURRENT SESSION CONTEXT:\n" + 
                    executionHistoryManager.getSessionContext(currentSession.sessionId)
                } else ""
            } catch (e: Exception) { 
                Timber.w(e, "Failed to load execution context")
                "" 
            }
            
            // User preferences context with safety
            val preferencesContext = try {
                val prefs = userPreferencesManager.getPreferences()
                if (prefs.rememberedProjects.isNotEmpty() || prefs.interactionPatterns.isNotEmpty()) {
                    "\n\nUSER PREFERENCES:\n" + 
                    "• Remembered projects: ${prefs.rememberedProjects.take(3).joinToString { it.name }}\n" +
                    "• Common patterns: ${prefs.interactionPatterns.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} (${it.value}x)" }}"
                } else ""
            } catch (e: Exception) { 
                Timber.w(e, "Failed to load preferences context")
                "" 
            }
            
            // Enhanced Integration: Project context awareness
            val projectContext = try {
                val activeProject = toolRegistry.getActiveProject()
                if (activeProject != null) {
                    "\n\nACTIVE PROJECT CONTEXT:\n" + 
                    "• Project: ${activeProject.name} (${activeProject.slug})\n" +
                    "• Description: ${activeProject.description}\n" +
                    "• Type: ${activeProject.tags.joinToString(", ")}\n" +
                    "• Files: ${toolRegistry.getProjectFileCount(activeProject.slug)}\n" +
                    "• Python: ${activeProject.pythonVersion}\n" +
                    "• Tools: ${activeProject.scopedTools.joinToString(", ")}"
                } else ""
            } catch (e: Exception) {
                Timber.w(e, "Failed to load project context")
                ""
            }
            
            // Additional learned context from other systems with safety
            val systemContext = try {
                val recentPatterns = reflectionManager.get().getRelevantPatterns(userMessage).take(3)
                if (recentPatterns.isNotEmpty()) {
                    "\n\nLEARNED PATTERNS:\n" + 
                    recentPatterns.joinToString("\n") { "• ${it.name}: ${it.description}" }
                } else ""
            } catch (e: Exception) {
                Timber.w(e, "Failed to load learned patterns")
                ""
            }
            
            // Combine all contexts safely
            baseContext + reflectionContext + executionContext + preferencesContext + projectContext + systemContext
        } catch (e: Exception) { 
            Timber.w(e, "Failed to build memory context, using fallback")
            try {
                // Fallback to basic memory context only
                memoryManager.buildContext(userMessage)
            } catch (e2: Exception) {
                Timber.e(e2, "Even fallback context failed, using empty context")
                ""
            }
        }

        val conversationRagContext = try {
            val results = conversationIndex.search(userMessage)
            if (results.isNotEmpty()) {
                buildString {
                    appendLine("CONVERSATION RAG (Semantic recall from all channels):")
                    results.forEach {
                        val ago = humanAgo(System.currentTimeMillis() - it.timestamp)
                        val isCurrent = it.channel == currentChannel || (it.channel == "main" && currentChannel == "main")
                        val label = if (isCurrent) "this channel" else "other channel: ${it.channel}"
                        appendLine("- $ago [$label:${it.sender}] ${it.text}")
                    }
                    appendLine()
                    appendLine("NOTE: Prioritize 'this channel' for immediate context. Use 'other channel' facts only if the user references them or they are highly relevant.")
                }.trimEnd()
            } else ""
        } catch (e: Exception) { Timber.w(e); "" }

        val sysPrompt = buildString {
            if (mode == Mode.COMPANION) {
                // Phase I-5 — persona preamble leads the prompt in COMPANION mode.
                append(personaManager.buildSystemPreamble())
                appendLine(); appendLine()
            }
            // Phase U — inject current wall-clock time and a compact tool
            // catalog so the agent never claims it "doesn't know the time"
            // and stops forgetting it has Telegram / secret / channel tools.
            val now = java.time.ZonedDateTime.now()
            val timeStr = now.format(java.time.format.DateTimeFormatter
                .ofPattern("EEEE, yyyy-MM-dd HH:mm:ss zzz"))
            val catalog = tools.joinToString(", ") { it.function.name }
                .let { if (it.length > 1800) it.take(1800) + ", …" else it }
            
            // Task 4: Integrate personality configuration
            val personalityPrompt = if (mode != Mode.COMPANION) {
                agentPersonality.getSystemPrompt()
            } else {
                baseSystemPrompt
                    .replace("{current_time}", timeStr)
                    .replace("{config_version}", config.version)
                    .replace("{agent_name}", personaManager.get().name)
                    .replace("{tool_count}", tools.size.toString())
                    .replace("{tool_catalog}", catalog)
            }
            
            if (mode != Mode.COMPANION) {
                append(personalityPrompt
                    .replace("{current_time}", timeStr)
                    .replace("{config_version}", config.version)
                    .replace("{tool_count}", tools.size.toString())
                    .replace("{tool_catalog}", catalog))
            } else {
                append(personalityPrompt)
            }
            if (memoryContext.isNotBlank()) { appendLine(); appendLine(); append(memoryContext) }
            if (conversationRagContext.isNotBlank()) {
                appendLine(); appendLine(); append(conversationRagContext)
            }
            if (extraSystemSuffix.isNotBlank()) {
                appendLine(); appendLine(); append(extraSystemSuffix)
            }
            if (config.modelRouting.compactMode.enabled) {
                appendLine(); appendLine()
                append("RULE: COMPACT MODE IS ON. Keep your thinking process brief. Output only essential facts and final results. Use markdown tables and lists to save space.")
            }
        }

        val messages = history.toMutableList()
        messages.add(ApiMessage(role = "user", content = userMessage))

        // System Integration: Automatic preference learning
        try {
            // Learn interaction patterns from user messages
            val messageWords = userMessage.lowercase().split(" ")
            
            // Detect project-related preferences
            if (messageWords.any { it in listOf("create", "build", "make", "new") } && 
                messageWords.any { it in listOf("project", "app", "website", "script") }) {
                val projectType = messageWords.find { it in listOf("python", "react", "node", "web", "android", "ios") }
                if (projectType != null) {
                    userPreferencesManager.recordInteractionPattern("prefers_$projectType", 1)
                }
            }
            
            // Detect tool preferences
            if (messageWords.contains("use") || messageWords.contains("with")) {
                val tools = listOf("typescript", "javascript", "tailwind", "bootstrap", "pytest", "jest", "docker", "git")
                tools.forEach { tool ->
                    if (messageWords.contains(tool)) {
                        userPreferencesManager.recordInteractionPattern("prefers_$tool", 1)
                    }
                }
            }
            
            // Detect location preferences
            val locationWords = messageWords.filter { it.contains("/") || it.startsWith("~") }
            locationWords.forEach { location ->
                userPreferencesManager.recordInteractionPattern("prefers_location_$location", 1)
            }
            
        } catch (e: Exception) {
            Timber.w(e, "Failed to learn user preferences")
        }

        try { memoryManager.logEvent("user", userMessage) }
        catch (e: Exception) { Timber.w(e) }

        var iterations = 0

        // ── Phase 3: Cost-Aware Execution Gate ──────────────────────────────
        val costThreshold = config.behaviorRules.costThresholdUsd
        if (costThreshold > 0.0) {
            val modelName = config.modelRouting.defaultModel
            val estimate = executionPlanner.estimate(
                systemPromptChars = sysPrompt.length,
                historyChars = messages.sumOf { (it.content?.length ?: 0) },
                userMessageChars = userMessage.length,
                maxIterations = maxIterations,
                model = modelName,
            )
            if (estimate.exceedsThreshold) {
                emit(AgentEvent.CostApprovalRequired(estimate))
                // Suspend until user clicks "Approve" or "Reject" in the UI.
                // The UI will call UserInputBroker.submitResponse("ui", "approve")
                val approval = try {
                    userInputBroker.awaitResponse("COST_APPROVAL:${estimate.estimatedUsd}")
                } catch (e: Exception) { "reject" }
                
                if (approval != "approve") {
                    emit(AgentEvent.Thinking("❌ Agent execution cancelled: cost estimate \$${"%.4f".format(estimate.estimatedUsd)} exceeds budget threshold \$${"%.4f".format(costThreshold)}."))
                    traceManager.finishTrace(traceId, false, "Budget rejected")
                    
                    // Task 4: Complete execution session
                    executionHistoryManager.completeSession(executionSession.sessionId, "cancelled")
                    
                    emit(AgentEvent.Done)
                    return@flow
                }
                emit(AgentEvent.Thinking("✅ Budget approved. Starting ReAct loop..."))
            }
        }
        
        // System Integration: Pre-execution health check
        try {
            val healthReport = doctorService.runChecks()
            if (healthReport.hasFailures) {
                emit(AgentEvent.Thinking("🩺 System health check detected issues. Attempting auto-fix..."))
                healthReport.checks.filter { it.status == com.forge.os.domain.doctor.CheckStatus.FAIL && it.fixable }
                    .forEach { check ->
                        try {
                            val fixed = doctorService.fix(check.id)
                            if (fixed.status == com.forge.os.domain.doctor.CheckStatus.OK) {
                                emit(AgentEvent.Thinking("✅ Fixed: ${check.title}"))
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Auto-fix failed for ${check.id}")
                        }
                    }
            }
        } catch (e: Exception) {
            Timber.w(e, "Health check failed")
        }
        
        while (iterations < maxIterations) {
            iterations++
            val response = try {
                apiManager.chatWithFallback(messages = messages, tools = tools, systemPrompt = sysPrompt, spec = spec, mode = mode)
            } catch (ace: ApiCallException) {
                Timber.e(ace, "API call failed")
                emit(AgentEvent.Error(ace.error.userFacing(), ace.error))
                traceManager.finishTrace(traceId, false, "API Call Failed: ${ace.message}")
                
                // Enhanced Learning & Reflection System Integration (API Failure)
                if (config.intelligenceUpgrades.reflectionEnabled) {
                    val trace = traceManager.getTrace(traceId)
                    if (trace != null) {
                        // Old system: AI-powered trace analysis
                        reflector.reflectAndLearn(trace)
                        
                        // New system: Record failure for learning
                        try {
                            val currentSession = executionHistoryManager.getCurrentSession()
                            if (currentSession != null) {
                                reflectionManager.get().recordFailureAndRecovery(
                                    taskId = currentSession.sessionId,
                                    failureReason = "API Call Failed: ${ace.message}",
                                    recoveryStrategy = "Check API configuration, try different provider, or retry with simpler request",
                                    tags = listOf("api_failure", "provider_issue")
                                )
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to record failure in ReflectionManager")
                        }
                    }
                }
                
                // Task 4: Complete execution session
                executionHistoryManager.completeSession(executionSession.sessionId, "failed")
                
                emit(AgentEvent.Done); return@flow
            } catch (e: Exception) {
                Timber.e(e, "Unexpected agent error")
                emit(AgentEvent.Error(e.message ?: "Agent loop failed"))
                traceManager.finishTrace(traceId, false, "Agent Loop Failed: ${e.message}")
                
                // Enhanced Learning & Reflection System Integration (Unexpected Error)
                if (config.intelligenceUpgrades.reflectionEnabled) {
                    val trace = traceManager.getTrace(traceId)
                    if (trace != null) {
                        // Old system: AI-powered trace analysis
                        reflector.reflectAndLearn(trace)
                        
                        // New system: Record failure for learning
                        try {
                            val currentSession = executionHistoryManager.getCurrentSession()
                            if (currentSession != null) {
                                reflectionManager.get().recordFailureAndRecovery(
                                    taskId = currentSession.sessionId,
                                    failureReason = "Unexpected Agent Error: ${e.message}",
                                    recoveryStrategy = "Check system health, restart agent, or simplify the task",
                                    tags = listOf("agent_error", "system_issue")
                                )
                            }
                        } catch (e2: Exception) {
                            Timber.w(e2, "Failed to record failure in ReflectionManager")
                        }
                    }
                }
                
                // Task 4: Complete execution session
                executionHistoryManager.completeSession(executionSession.sessionId, "failed")
                
                emit(AgentEvent.Done); return@flow
            }

            response.content?.takeIf { it.isNotBlank() }?.let { emit(AgentEvent.Thinking(it)) }

            if (response.toolCalls.isEmpty()) {
                var currentStepToolCalls = mutableListOf<com.forge.os.domain.debug.TraceToolCall>()
                var currentStepNestedTraces = mutableListOf<com.forge.os.domain.debug.ReplayTrace>()

                var finalText = response.content?.takeIf { it.isNotBlank() }
                if (finalText == null) {
                    Timber.w("Agent returned blank content + no tool calls (finishReason=${response.finishReason}). Retrying once with nudge.")
                    messages.add(ApiMessage(
                        role = "user",
                        content = "Your previous turn returned no text and no tool call. " +
                            "Please respond now: either answer the user, ask a clarifying " +
                            "question, or call a tool. Do not return an empty message."
                    ))
                    val retry = try {
                        apiManager.chatWithFallback(messages = messages, tools = tools, systemPrompt = sysPrompt, spec = spec, mode = mode)
                    } catch (e: Exception) {
                        Timber.e(e, "Retry after blank response failed")
                        null
                    }
                    finalText = retry?.content?.takeIf { it.isNotBlank() }
                    if (retry != null && retry.toolCalls.isNotEmpty()) {
                        response.content?.let { /* discard */ }
                        messages.add(ApiMessage(
                            role = "assistant", content = retry.content, toolCalls = retry.toolCalls
                        ))
                        retry.toolCalls.forEach { toolCall ->
                            val name = toolCall.function.name
                            val args = toolCall.function.arguments
                            emit(AgentEvent.ToolCall(name, args))
                            try { memoryManager.logEvent("tool_call", "$name: ${args.take(100)}") } catch (_: Exception) {}
                        }

                        val parallelResults = kotlinx.coroutines.coroutineScope {
                            retry.toolCalls.map { toolCall ->
                                async {
                                    val name = toolCall.function.name
                                    val args = toolCall.function.arguments
                                    
                                    val beforeTraces = traceManager.getAllTraces().map { it.id }.toSet()
                                    val toolStartTime = System.currentTimeMillis()
                                    val result = toolRegistry.dispatch(name, args, toolCall.id)
                                    val toolDuration = System.currentTimeMillis() - toolStartTime
                                    
                                    val newTraces = traceManager.getAllTraces().filter { it.id !in beforeTraces }
                                    newTraces.forEach { traceManager.removeTrace(it.id) }
                                    
                                    Triple(toolCall, result, toolDuration to newTraces)
                                }
                            }.awaitAll()
                        }

                        for ((toolCall, result, traceInfo) in parallelResults) {
                            val (toolDuration, newTraces) = traceInfo
                            val name = toolCall.function.name
                            
                            currentStepToolCalls.add(com.forge.os.domain.debug.TraceToolCall(
                                id = toolCall.id, name = name, argsJson = toolCall.function.arguments, 
                                result = result.output, isError = result.isError, durationMs = toolDuration
                            ))
                            currentStepNestedTraces.addAll(newTraces)

                            emit(AgentEvent.ToolResult(result.toolName, result.output, result.isError))
                            
                            // System Integration: Smart haptic feedback
                            try {
                                if (result.isError) {
                                    hapticFeedbackManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.ERROR)
                                } else {
                                    hapticFeedbackManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.SUCCESS)
                                }
                            } catch (e: Exception) {
                                Timber.w(e, "Haptic feedback failed")
                            }
                            
                            // Task 4: Record execution step for learning
                            stepNumber++
                            executionHistoryManager.addStep(
                                sessionId = executionSession.sessionId,
                                stepNumber = stepNumber,
                                action = "Tool execution: $name",
                                tool = name,
                                args = toolCall.function.arguments,
                                result = result.output,
                                success = !result.isError
                            )
                            
                            messages.add(ApiMessage(
                                role = "tool", content = result.output,
                                toolCallId = toolCall.id, name = name
                            ))
                        }
                        
                        traceManager.appendStep(traceId, com.forge.os.domain.debug.TraceStep(
                            iteration = iterations, memoryContext = memoryContext + conversationRagContext, systemPrompt = sysPrompt,
                            rawResponse = retry.content ?: "", toolCalls = currentStepToolCalls, nestedTraces = currentStepNestedTraces
                        ))
                        continue  // back to top of the ReAct loop
                    }
                    if (finalText == null) {
                        finalText = "⚠️ The model returned an empty response (finishReason=" +
                            "${response.finishReason ?: "unknown"}). This usually means a " +
                            "context-length, content-filter, or quota issue. Try rephrasing, " +
                            "switching provider in Settings, or running `doctor_check`."
                    }
                }
                
                traceManager.appendStep(traceId, com.forge.os.domain.debug.TraceStep(
                    iteration = iterations, memoryContext = memoryContext + conversationRagContext, systemPrompt = sysPrompt,
                    rawResponse = response.content ?: "", toolCalls = emptyList()
                ))
                traceManager.finishTrace(traceId, true, finalText)
                
                try { memoryManager.logEvent("assistant", finalText) } catch (_: Exception) {}
                streamChunks(finalText) { partial -> emit(AgentEvent.Thinking(partial)) }
                emit(AgentEvent.Response(finalText))
                
                // System Integration: Completion feedback
                try {
                    hapticFeedbackManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.SUCCESS)
                } catch (e: Exception) {
                    Timber.w(e, "Haptic feedback failed")
                }
                
                // Enhanced Learning & Reflection System Integration
                if (config.intelligenceUpgrades.reflectionEnabled) {
                    val trace = traceManager.getTrace(traceId)
                    if (trace != null) {
                        // Old system: AI-powered trace analysis
                        reflector.reflectAndLearn(trace)
                        
                        // New system: Structured execution recording and pattern learning
                        try {
                            val currentSession = executionHistoryManager.getCurrentSession()
                            if (currentSession != null) {
                                val steps = currentSession.steps.map { step ->
                                    com.forge.os.domain.agent.ExecutionStep(
                                        stepNumber = step.stepNumber,
                                        action = step.action,
                                        tool = step.tool,
                                        args = step.args,
                                        result = step.result,
                                        duration = 0L,
                                        success = step.success
                                    )
                                }
                                
                                reflectionManager.get().recordExecution(
                                    taskId = currentSession.sessionId,
                                    goal = currentSession.goal,
                                    steps = steps,
                                    success = true,
                                    outcome = finalText,
                                    tags = listOf("agent_execution", "success", "completed")
                                )
                                
                                // Record successful patterns for future use
                                if (steps.isNotEmpty()) {
                                    val toolPattern = steps.map { it.tool }.distinct().joinToString(" -> ")
                                    reflectionManager.get().recordPattern(
                                        pattern = "Successful tool sequence: $toolPattern",
                                        description = "This tool sequence successfully completed: ${currentSession.goal}",
                                        applicableTo = listOf(currentSession.goal.split(" ").take(3).joinToString(" ")),
                                        tags = listOf("success_pattern", "tool_sequence")
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Failed to record execution in ReflectionManager")
                        }
                    }
                }
                
                // Task 4: Complete execution session
                executionHistoryManager.completeSession(executionSession.sessionId, "completed")
                
                emit(AgentEvent.Done); return@flow
            }

            messages.add(ApiMessage(
                role = "assistant", content = response.content, toolCalls = response.toolCalls
            ))

            var currentStepToolCalls = mutableListOf<com.forge.os.domain.debug.TraceToolCall>()
            var currentStepNestedTraces = mutableListOf<com.forge.os.domain.debug.ReplayTrace>()

            response.toolCalls.forEach { toolCall ->
                val name = toolCall.function.name
                val args = toolCall.function.arguments
                emit(AgentEvent.ToolCall(name, args))
                try { memoryManager.logEvent("tool_call", "$name: ${args.take(100)}") } catch (_: Exception) {}
            }

            val parallelResults = kotlinx.coroutines.coroutineScope {
                response.toolCalls.map { toolCall ->
                    async {
                        val name = toolCall.function.name
                        val args = toolCall.function.arguments
                        
                        val beforeTraces = traceManager.getAllTraces().map { it.id }.toSet()
                        val toolStartTime = System.currentTimeMillis()
                        val result = toolRegistry.dispatch(name, args, toolCall.id)
                        val toolDuration = System.currentTimeMillis() - toolStartTime
                        
                        val newTraces = traceManager.getAllTraces().filter { it.id !in beforeTraces }
                        newTraces.forEach { traceManager.removeTrace(it.id) }
                        
                        Triple(toolCall, result, toolDuration to newTraces)
                    }
                }.awaitAll()
            }

            for ((toolCall, result, traceInfo) in parallelResults) {
                val (toolDuration, newTraces) = traceInfo
                val name = toolCall.function.name
                
                currentStepToolCalls.add(com.forge.os.domain.debug.TraceToolCall(
                    id = toolCall.id, name = name, argsJson = toolCall.function.arguments, 
                    result = result.output, isError = result.isError, durationMs = toolDuration
                ))
                currentStepNestedTraces.addAll(newTraces)

                emit(AgentEvent.ToolResult(result.toolName, result.output, result.isError))
                
                // System Integration: Smart haptic feedback
                try {
                    if (result.isError) {
                        hapticFeedbackManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.ERROR)
                    } else {
                        hapticFeedbackManager.trigger(com.forge.os.domain.haptic.HapticFeedbackManager.Pattern.SUCCESS)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Haptic feedback failed")
                }
                
                // Task 4: Record execution step for learning
                stepNumber++
                executionHistoryManager.addStep(
                    sessionId = executionSession.sessionId,
                    stepNumber = stepNumber,
                    action = "Tool execution: $name",
                    tool = name,
                    args = toolCall.function.arguments,
                    result = result.output,
                    success = !result.isError
                )
                
                messages.add(ApiMessage(
                    role = "tool", content = result.output,
                    toolCallId = toolCall.id, name = name
                ))
            }

            traceManager.appendStep(traceId, com.forge.os.domain.debug.TraceStep(
                iteration = iterations, memoryContext = memoryContext + conversationRagContext, systemPrompt = sysPrompt,
                rawResponse = response.content ?: "", toolCalls = currentStepToolCalls, nestedTraces = currentStepNestedTraces
            ))

            if (response.finishReason == "stop" || response.finishReason == "end_turn") {
                traceManager.finishTrace(traceId, true, "Agent ended turn.")
                if (config.intelligenceUpgrades.reflectionEnabled) {
                    val trace = traceManager.getTrace(traceId)
                    if (trace != null) reflector.reflectAndLearn(trace)
                }
                
                // Task 4: Complete execution session
                executionHistoryManager.completeSession(executionSession.sessionId, "completed")
                
                emit(AgentEvent.Done); return@flow
            }
        }

        traceManager.finishTrace(traceId, false, "Max iterations reached.")
        emit(AgentEvent.Response("⚠️ Reached max iterations ($maxIterations). Task may be incomplete."))

        // Autonomous Learning / Reflection
        if (config.intelligenceUpgrades.reflectionEnabled) {
            val trace = traceManager.getTrace(traceId)
            if (trace != null) {
                reflector.reflectAndLearn(trace)
            }
        }

        // Task 4: Complete execution session
        executionHistoryManager.completeSession(executionSession.sessionId, "max_iterations")

        emit(AgentEvent.Done)
    } // end flow

    /** Chunk text into ~3-word groups and emit the running concatenation. */
    private suspend fun streamChunks(text: String, emit: suspend (String) -> Unit) {
        if (text.isBlank()) return
        val words = text.split(' ')
        val sb = StringBuilder()
        var sinceLast = 0
        for ((i, w) in words.withIndex()) {
            if (i > 0) sb.append(' ')
            sb.append(w)
            sinceLast++
            if (sinceLast >= 3 || i == words.lastIndex) {
                emit(sb.toString())
                sinceLast = 0
                delay(18)
            }
        }
    }

    private fun humanAgo(ms: Long): String {
        val mins = ms / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "${mins}m ago"
            mins < 60 * 24 -> "${mins / 60}h ago"
            else -> "${mins / (60 * 24)}d ago"
        }
    }
}
