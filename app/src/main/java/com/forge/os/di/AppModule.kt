package com.forge.os.di

import android.content.Context
import androidx.work.WorkManager
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.forge.os.data.api.AiApiManager
import com.forge.os.data.api.ApiCallLog
import com.forge.os.data.api.CostMeter
import com.forge.os.data.api.ForgeBridgeDiscovery
import com.forge.os.data.conversations.ConversationRepository
import com.forge.os.data.mcp.McpClient
import com.forge.os.data.mcp.McpServerRepository
import com.forge.os.domain.snapshots.SnapshotManager
import com.forge.os.domain.agent.SkillRecorder
import com.forge.os.data.sandbox.PythonRunner
import com.forge.os.data.sandbox.SandboxManager
import com.forge.os.data.sandbox.SecurityPolicy
import com.forge.os.data.sandbox.ShellExecutor
import com.forge.os.domain.agent.ReActAgent
import com.forge.os.domain.agent.ToolRegistry
import com.forge.os.domain.agents.DelegationManager
import com.forge.os.domain.agents.SubAgentRepository
import com.forge.os.domain.companion.DependencyMonitor
import com.forge.os.domain.companion.SafetyFilter
import com.forge.os.domain.config.ConfigMutationEngine
import com.forge.os.domain.config.ConfigRepository
import com.forge.os.domain.cron.CronManager
import com.forge.os.domain.cron.CronRepository
import com.forge.os.domain.heartbeat.AlertManager
import com.forge.os.domain.heartbeat.HeartbeatMonitor
import com.forge.os.domain.memory.DailyMemory
import com.forge.os.domain.memory.LongtermMemory
import com.forge.os.domain.memory.MemoryManager
import com.forge.os.domain.memory.SkillMemory
import com.forge.os.domain.notifications.AgentNotifier
import com.forge.os.domain.notifications.NotificationHelper
import com.forge.os.domain.plugins.BuiltInPlugins
import com.forge.os.domain.plugins.PluginManager
import com.forge.os.domain.plugins.PluginRepository
import com.forge.os.domain.plugins.PluginValidator
import com.forge.os.domain.projects.ProjectScopeManager
import com.forge.os.domain.projects.ProjectsRepository
import com.forge.os.domain.security.CustomEndpointRepository
import com.forge.os.domain.security.PermissionManager
import com.forge.os.domain.security.SecureKeyStore
import com.forge.os.domain.security.ToolAuditLog
import com.forge.os.external.ExternalApiBridge
import com.forge.os.external.ExternalAuditLog
import com.forge.os.external.ExternalCallerRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // Phase 0 — Sandbox
    @Provides @Singleton
    fun providePythonInstance(@ApplicationContext ctx: Context): Python {
        if (!Python.isStarted()) Python.start(AndroidPlatform(ctx))
        return Python.getInstance()
    }

    @Provides @Singleton fun provideSecurityPolicy(): SecurityPolicy = SecurityPolicy()

    // Phase Q — Agent control plane and consent ledger. The control plane is
    // wired into SecurityPolicy via setFlagsProvider so existing security
    // guards consult the live capability state rather than constants.
    @Provides @Singleton
    fun provideUserConsentLedger(@ApplicationContext ctx: Context) =
        com.forge.os.domain.control.UserConsentLedger(ctx)
    @Provides @Singleton
    fun provideAgentControlPlane(
        @ApplicationContext ctx: Context,
        consent: com.forge.os.domain.control.UserConsentLedger,
        sp: SecurityPolicy,
    ): com.forge.os.domain.control.AgentControlPlane {
        val plane = com.forge.os.domain.control.AgentControlPlane(ctx, consent)
        sp.setFlagsProvider(object : SecurityPolicy.Flags {
            override fun shellBlocklistEnabled() =
                plane.isEnabled(com.forge.os.domain.control.AgentControlPlane.SHELL_BLOCKLIST)
            override fun pythonImportGuardEnabled() =
                plane.isEnabled(com.forge.os.domain.control.AgentControlPlane.PYTHON_IMPORT_GUARD)
            override fun fileProtectionEnabled() =
                plane.isEnabled(com.forge.os.domain.control.AgentControlPlane.FILE_PROTECTION)
            override fun fileSizeLimitEnabled() =
                plane.isEnabled(com.forge.os.domain.control.AgentControlPlane.FILE_SIZE_LIMIT)
        })
        return plane
    }
    @Provides @Singleton fun provideShellExecutor(): ShellExecutor = ShellExecutor()
    @Provides @Singleton fun providePythonRunner(p: Python): PythonRunner = PythonRunner(p)
    @Provides @Singleton fun provideSandboxManager(
        @ApplicationContext ctx: Context, sp: SecurityPolicy, se: ShellExecutor, pr: PythonRunner,
        wl: com.forge.os.domain.workspace.WorkspaceLock
    ): SandboxManager = SandboxManager(ctx, sp, se, pr, wl)

    // Phase 1 — Kernel
    @Provides @Singleton fun provideConfigRepository(@ApplicationContext ctx: Context) = ConfigRepository(ctx)
    @Provides @Singleton fun providePermissionManager(r: ConfigRepository) = PermissionManager(r)
    @Provides @Singleton fun provideAlertManager() = AlertManager()
    @Provides @Singleton fun provideHeartbeatMonitor(
        @ApplicationContext ctx: Context, r: ConfigRepository, a: AlertManager
    ) = HeartbeatMonitor(ctx, r, a)
    @Provides @Singleton fun provideConfigMutationEngine(r: ConfigRepository) = ConfigMutationEngine(r)
    @Provides @Singleton fun provideWorkManager(@ApplicationContext ctx: Context): WorkManager =
        WorkManager.getInstance(ctx)

    // Phase 2 — API + Agent
    @Provides @Singleton fun provideSecureKeyStore(@ApplicationContext ctx: Context) = SecureKeyStore(ctx)
    @Provides @Singleton fun provideCustomEndpointRepository(@ApplicationContext ctx: Context) =
        CustomEndpointRepository(ctx)
    @Provides @Singleton fun provideApiCallLog() = ApiCallLog()
    @Provides @Singleton fun provideCostMeter(@ApplicationContext ctx: Context) = CostMeter(ctx)
    @Provides @Singleton fun provideAiApiManager(
        @ApplicationContext ctx: Context,
        ks: SecureKeyStore, cr: ConfigRepository,
        cer: CustomEndpointRepository, log: ApiCallLog, cm: CostMeter,
        bridgeDiscovery: ForgeBridgeDiscovery,
    ) = AiApiManager(ks, cr, cer, log, cm, ctx, bridgeDiscovery)
    @Provides @Singleton fun provideConversationRepository(@ApplicationContext ctx: Context) =
        ConversationRepository(ctx)
    @Provides @Singleton fun provideSkillRecorder(sm: SkillMemory) = SkillRecorder(sm)

    // Phase 3 — Memory
    @Provides @Singleton fun provideDailyMemory(@ApplicationContext ctx: Context) = DailyMemory(ctx)
    @Provides @Singleton fun provideLongtermMemory(@ApplicationContext ctx: Context) = LongtermMemory(ctx)
    @Provides @Singleton fun provideSkillMemory(@ApplicationContext ctx: Context) = SkillMemory(ctx)
    @Provides @Singleton fun provideSemanticFactIndex(
        @ApplicationContext ctx: Context, api: AiApiManager,
    ) = com.forge.os.domain.memory.SemanticFactIndex(ctx, api)
    @Provides @Singleton fun provideMemoryManager(
        daily: DailyMemory, longterm: LongtermMemory, skill: SkillMemory,
        sem: com.forge.os.domain.memory.SemanticFactIndex,
        reranker: com.forge.os.domain.memory.ContextReranker,
    ) = MemoryManager(daily, longterm, skill, sem, reranker)

    // Phase 4 — Cron + Notifications
    @Provides @Singleton fun provideNotificationHelper(@ApplicationContext ctx: Context) =
        NotificationHelper(ctx)
    @Provides @Singleton fun provideCronRepository(@ApplicationContext ctx: Context) =
        CronRepository(ctx)
    @Provides @Singleton fun provideCronManager(
        repo: CronRepository, sm: SandboxManager, cr: ConfigRepository,
        mm: MemoryManager, nh: NotificationHelper,
        aiApiManager: dagger.Lazy<AiApiManager>,
        reActAgent: dagger.Lazy<ReActAgent>,
        backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager,
    ) = CronManager(repo, sm, cr, mm, nh, aiApiManager, reActAgent, backgroundLog)

    // Phase 5 — Plugins
    @Provides @Singleton fun providePluginRepository(@ApplicationContext ctx: Context) =
        PluginRepository(ctx)
    @Provides @Singleton fun providePluginValidator(cr: ConfigRepository) =
        PluginValidator(cr)
    @Provides @Singleton fun providePluginExporter(
        @ApplicationContext ctx: Context,
        plane: com.forge.os.domain.control.AgentControlPlane,
    ) = com.forge.os.domain.plugins.PluginExporter(ctx, plane)
    @Provides @Singleton fun providePluginManager(
        repo: PluginRepository, v: PluginValidator, sm: SandboxManager,
        cr: ConfigRepository, mm: MemoryManager,
        exporter: com.forge.os.domain.plugins.PluginExporter,
        headlessBrowser: com.forge.os.data.web.HeadlessBrowser,
    ) = PluginManager(repo, v, sm, cr, mm, exporter, headlessBrowser)
    @Provides @Singleton fun provideBuiltInPlugins(pm: PluginManager) = BuiltInPlugins(pm)

    // Phase 6 — Sub-Agent Delegation
    @Provides @Singleton fun provideSubAgentRepository(@ApplicationContext ctx: Context) =
        SubAgentRepository(ctx)
    @Provides @Singleton fun provideAgentNotifier(@ApplicationContext ctx: Context) =
        AgentNotifier(ctx)
    @Provides @Singleton fun provideDelegationManager(
        repo: SubAgentRepository, cr: ConfigRepository, mm: MemoryManager,
        agentProvider: javax.inject.Provider<ReActAgent>, notifier: AgentNotifier,
        aiApiManager: AiApiManager,
        ghostWorkspaceProvider: com.forge.os.domain.workspace.GhostWorkspaceProvider,
        backgroundLog: com.forge.os.domain.debug.BackgroundTaskLogManager,
    ) = DelegationManager(repo, cr, mm, agentProvider, notifier, aiApiManager, ghostWorkspaceProvider, backgroundLog)

    // Phase D — Module UI shared services
    @Provides @Singleton fun provideToolAuditLog(@ApplicationContext ctx: Context) = ToolAuditLog(ctx)
    @Provides @Singleton fun provideProjectsRepository(@ApplicationContext ctx: Context) =
        ProjectsRepository(ctx)
    @Provides @Singleton fun provideProjectScopeManager(
        @ApplicationContext ctx: Context, repo: ProjectsRepository
    ) = ProjectScopeManager(ctx, repo)

    // Phase F — Snapshots + MCP
    @Provides @Singleton fun provideSnapshotManager(
        @ApplicationContext ctx: Context,
        workspaceLock: com.forge.os.domain.workspace.WorkspaceLock
    ) = SnapshotManager(ctx, workspaceLock)
    @Provides @Singleton fun provideMcpServerRepository(@ApplicationContext ctx: Context) =
        McpServerRepository(ctx)
    @Provides @Singleton fun provideMcpClient(repo: McpServerRepository) = McpClient(repo)

    // ToolRegistry is auto-provided from its @Inject constructor — all deps
    // (including BrowserSessionManager, UserInputBroker, AlarmRepository,
    // ForgeAlarmScheduler, AndroidController, ForgeHttpServer, DoctorService,
    // ChannelManager) are @Singleton with @Inject constructors, so Hilt wires
    // the graph without a manual @Provides here.

    @Provides @Singleton fun provideReActAgent(
        api: AiApiManager, tr: ToolRegistry, cr: ConfigRepository, mm: MemoryManager,
        pm: com.forge.os.domain.companion.PersonaManager,
        conversationIndex: com.forge.os.domain.memory.ConversationIndex,
        executionPlanner: com.forge.os.domain.agent.ExecutionPlanner,
        traceManager: com.forge.os.domain.debug.TraceManager,
        reflector: com.forge.os.domain.agent.Reflector,
        userInputBroker: com.forge.os.domain.agent.UserInputBroker,
    ) = ReActAgent(api, tr, cr, mm, pm, conversationIndex, executionPlanner, traceManager, reflector, userInputBroker)

    // Phase H/I — Companion (Friend Mode)
    @Provides @Singleton fun providePersonaManager(@ApplicationContext ctx: Context) =
        com.forge.os.domain.companion.PersonaManager(ctx)

    // Phase K — emotional context classifier
    @Provides @Singleton fun provideEmotionalContext(
        api: AiApiManager,
        @ApplicationContext ctx: Context,
    ) = com.forge.os.domain.companion.EmotionalContext(api, ctx)

    // Phase J1 — episodic memory + summariser
    @Provides @Singleton fun provideEpisodicMemoryStore(@ApplicationContext ctx: Context) =
        com.forge.os.domain.companion.EpisodicMemoryStore(ctx)
    @Provides @Singleton fun provideConversationSummarizer(api: AiApiManager) =
        com.forge.os.domain.companion.ConversationSummarizer(api)

    // Phase L — proactive companion check-ins
    @Provides @Singleton fun provideCheckInState(@ApplicationContext ctx: Context) =
        com.forge.os.domain.companion.CheckInState(ctx)
    @Provides @Singleton fun provideCheckInScheduler(
        cr: ConfigRepository,
        store: com.forge.os.domain.companion.EpisodicMemoryStore,
        pm: com.forge.os.domain.companion.PersonaManager,
        nh: NotificationHelper,
        st: com.forge.os.domain.companion.CheckInState,
    ) = com.forge.os.domain.companion.CheckInScheduler(cr, store, pm, nh, st)

    // Phase N — relationship counters + Phase P — voice
    @Provides @Singleton fun provideRelationshipState(
        @ApplicationContext ctx: Context,
        store: com.forge.os.domain.companion.EpisodicMemoryStore,
    ) = com.forge.os.domain.companion.RelationshipState(ctx, store)
    @Provides @Singleton fun provideCompanionVoice(@ApplicationContext ctx: Context) =
        com.forge.os.domain.companion.CompanionVoice(ctx)

    // Phase O — Safety rails
    @Provides @Singleton fun provideSafetyFilter(): SafetyFilter = SafetyFilter()
    @Provides @Singleton fun provideDependencyMonitor(
        @ApplicationContext ctx: Context,
        cr: ConfigRepository,
        nh: NotificationHelper,
        pm: com.forge.os.domain.companion.PersonaManager,
    ) = DependencyMonitor(ctx, cr, nh, pm)

    // Phase G — External API
    @Provides @Singleton fun provideExternalCallerRegistry(@ApplicationContext ctx: Context) =
        ExternalCallerRegistry(ctx)
    @Provides @Singleton fun provideExternalAuditLog(@ApplicationContext ctx: Context) =
        ExternalAuditLog(ctx)
    @Provides @Singleton fun provideExternalApiBridge(
        tr: ToolRegistry, pm: PluginManager, mm: MemoryManager,
        agentProvider: javax.inject.Provider<ReActAgent>,
        registry: ExternalCallerRegistry, audit: ExternalAuditLog, cr: ConfigRepository,
    ) = ExternalApiBridge(tr, pm, mm, agentProvider, registry, audit, cr)
}
