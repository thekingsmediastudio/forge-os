package com.forge.os.external

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.forge.os.api.IForgeOsCallback
import com.forge.os.api.IForgeOsService
import com.forge.os.domain.notifications.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Bound service exposed at action `com.forge.os.api.IForgeOsService`.
 *
 * Manifest declares this with `android:permission="com.forge.os.permission.USE_API"`,
 * so only apps that requested the (signature|dangerous) permission can even bind.
 * On top of that, [ExternalApiBridge.authorize] enforces per-caller grants.
 */
@AndroidEntryPoint
class ForgeOsService : Service() {

    @Inject lateinit var bridge: ExternalApiBridge
    @Inject lateinit var registry: ExternalCallerRegistry
    @Inject lateinit var notifications: NotificationHelper

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder {
        // Surface a notification the very first time a new package tries to connect.
        val uid = Binder.getCallingUid()
        val caller = registry.observe(uid)
        if (caller != null && caller.status == GrantStatus.PENDING) {
            runCatching {
                notifications.notifyExternalApiRequest(caller.packageName, caller.displayName)
            }.onFailure { Timber.w(it, "notify external request failed") }
        }
        return binder
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private val binder = object : IForgeOsService.Stub() {

        override fun getApiVersion(): String = "1.0"

        override fun listTools(): String {
            val d = bridge.authorize(Binder.getCallingUid(), "listTools")
            return when (d) {
                is ExternalApiBridge.Decision.Allow -> bridge.listToolsFor(d.caller)
                is ExternalApiBridge.Decision.Deny -> errJson(d.code, d.reason)
            }
        }

        override fun invokeTool(toolName: String, jsonArgs: String): String {
            val d = bridge.authorize(Binder.getCallingUid(), "invokeTool", toolName)
            return when (d) {
                is ExternalApiBridge.Decision.Allow -> bridge.invokeToolSync(d.caller, toolName, jsonArgs)
                is ExternalApiBridge.Decision.Deny -> errJson(d.code, d.reason)
            }
        }

        override fun invokeToolAsync(toolName: String, jsonArgs: String, cb: IForgeOsCallback?) {
            cb ?: return
            val d = bridge.authorize(Binder.getCallingUid(), "invokeToolAsync", toolName)
            when (d) {
                is ExternalApiBridge.Decision.Deny -> safeError(cb, d.code, d.reason)
                is ExternalApiBridge.Decision.Allow -> scope.launch {
                    val out = bridge.invokeToolSync(d.caller, toolName, jsonArgs)
                    runCatching { cb.onResult(out) }
                }
            }
        }

        override fun askAgent(prompt: String, optsJson: String, cb: IForgeOsCallback?) {
            cb ?: return
            val d = bridge.authorize(Binder.getCallingUid(), "askAgent")
            when (d) {
                is ExternalApiBridge.Decision.Deny -> safeError(cb, d.code, d.reason)
                is ExternalApiBridge.Decision.Allow -> scope.launch {
                    bridge.askAgent(
                        caller = d.caller, prompt = prompt, optsJson = optsJson,
                        onChunk = { runCatching { cb.onChunk(it) } },
                        onResult = { runCatching { cb.onResult(it) } },
                        onError = { code, msg -> runCatching { cb.onError(code, msg) } },
                    )
                }
            }
        }

        override fun getMemory(key: String): String {
            val d = bridge.authorize(Binder.getCallingUid(), "getMemory", key)
            return when (d) {
                is ExternalApiBridge.Decision.Allow -> bridge.getMemory(d.caller, key)
                is ExternalApiBridge.Decision.Deny -> ""
            }
        }

        override fun putMemory(key: String, value: String, tagsCsv: String) {
            val d = bridge.authorize(Binder.getCallingUid(), "putMemory", key)
            if (d is ExternalApiBridge.Decision.Allow) {
                bridge.putMemory(d.caller, key, value, tagsCsv)
            }
        }

        override fun runSkill(skillId: String, jsonArgs: String): String {
            val d = bridge.authorize(Binder.getCallingUid(), "runSkill", skillId)
            return when (d) {
                is ExternalApiBridge.Decision.Allow -> bridge.runSkill(d.caller, skillId, jsonArgs)
                is ExternalApiBridge.Decision.Deny -> errJson(d.code, d.reason)
            }
        }
    }

    private fun safeError(cb: IForgeOsCallback, code: Int, msg: String) {
        runCatching { cb.onError(code, msg) }
    }

    private fun errJson(code: Int, msg: String) =
        """{"ok":false,"code":$code,"error":${'"'}${msg.replace("\"", "\\\"")}${'"'}}"""
}
