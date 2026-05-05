package com.forge.os.data.browser

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the persistent in-app browser session so the agent can navigate and
 * interact with web pages. Cookies and localStorage survive across screen
 * visits because we use Android's default WebView cookie store.
 *
 * The agent communicates with the live WebView via [NavigationCommand].
 */
sealed class NavigationCommand {
    data class OpenUrl(val url: String) : NavigationCommand()
    data class EvalJs(val script: String, val callbackId: String) : NavigationCommand()
    data class FillField(val selector: String, val value: String) : NavigationCommand()
    data class ClickElement(val selector: String) : NavigationCommand()
    data class ScrollTo(val x: Int, val y: Int) : NavigationCommand()
    object GetHtml : NavigationCommand()
    object GoBack : NavigationCommand()
    object GoForward : NavigationCommand()
    object Reload : NavigationCommand()
}

data class JsCallbackResult(val callbackId: String, val result: String)

@Singleton
class BrowserSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    // Enhanced Integration: Connect with learning systems
    private val userPreferencesManager: com.forge.os.domain.user.UserPreferencesManager,
    private val reflectionManager: com.forge.os.domain.agent.ReflectionManager,
) {
    private val _currentUrl = MutableStateFlow("about:blank")
    val currentUrl: StateFlow<String> = _currentUrl

    private val _pageTitle = MutableStateFlow("Browser")
    val pageTitle: StateFlow<String> = _pageTitle

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    /** Commands emitted to the active WebView composable. */
    private val _commands = MutableSharedFlow<NavigationCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<NavigationCommand> = _commands

    /** HTML snapshots and JS results streamed back to the agent. */
    private val _htmlSnapshots = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val htmlSnapshots: SharedFlow<String> = _htmlSnapshots

    private val _jsResults = MutableSharedFlow<JsCallbackResult>(extraBufferCapacity = 16)
    val jsResults: SharedFlow<JsCallbackResult> = _jsResults

    fun updateUrl(url: String) { _currentUrl.value = url }
    fun updateTitle(title: String) { _pageTitle.value = title }
    fun updateLoading(loading: Boolean) { _isLoading.value = loading }

    /** Called by the WebView JS bridge when getHtml() is invoked. */
    fun onHtmlSnapshot(html: String) { _htmlSnapshots.tryEmit(html) }
    fun onJsResult(callbackId: String, result: String) {
        _jsResults.tryEmit(JsCallbackResult(callbackId, result))
    }

    suspend fun navigate(url: String) {
        val resolved = if (url.startsWith("http://") || url.startsWith("https://")) url
                       else "https://$url"
        _commands.emit(NavigationCommand.OpenUrl(resolved))
        Timber.d("Browser navigate → $resolved")
        
        // Enhanced Integration: Learn browsing patterns
        try {
            val domain = java.net.URI(resolved).host?.lowercase() ?: ""
            if (domain.isNotBlank()) {
                userPreferencesManager.recordInteractionPattern("visits_domain_$domain", 1)
                
                // Learn browsing time patterns
                val hour = java.time.LocalDateTime.now().hour
                userPreferencesManager.recordInteractionPattern("browses_hour_$hour", 1)
                
                // Record navigation pattern
                reflectionManager.recordPattern(
                    pattern = "Browser navigation to $domain",
                    description = "User navigated to $domain via browser automation",
                    applicableTo = listOf("browser", "navigation", domain),
                    tags = listOf("browser_usage", "navigation_pattern", "web_interaction")
                )
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to learn browsing patterns")
        }
    }

    suspend fun evalJs(script: String, callbackId: String = "cb_${System.currentTimeMillis()}") {
        _commands.emit(NavigationCommand.EvalJs(script, callbackId))
        
        // Enhanced Integration: Learn JavaScript usage patterns
        try {
            userPreferencesManager.recordInteractionPattern("uses_browser_javascript", 1)
            
            // Learn common JS patterns
            val scriptType = when {
                script.contains("click") -> "dom_interaction"
                script.contains("querySelector") -> "element_selection"
                script.contains("fetch") || script.contains("XMLHttpRequest") -> "network_request"
                script.contains("localStorage") || script.contains("sessionStorage") -> "storage_access"
                else -> "custom_script"
            }
            userPreferencesManager.recordInteractionPattern("js_pattern_$scriptType", 1)
            
            reflectionManager.recordPattern(
                pattern = "JavaScript execution: $scriptType",
                description = "Executed JavaScript for $scriptType in browser automation",
                applicableTo = listOf("browser", "javascript", scriptType),
                tags = listOf("browser_automation", "javascript_usage", "web_interaction")
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to learn JavaScript patterns")
        }
    }

    suspend fun getHtml() { _commands.emit(NavigationCommand.GetHtml) }
    suspend fun goBack() { _commands.emit(NavigationCommand.GoBack) }
    suspend fun goForward() { _commands.emit(NavigationCommand.GoForward) }
    suspend fun reload() { _commands.emit(NavigationCommand.Reload) }

    suspend fun fillField(selector: String, value: String) {
        _commands.emit(NavigationCommand.FillField(selector, value))
    }

    suspend fun clickElement(selector: String) {
        _commands.emit(NavigationCommand.ClickElement(selector))
    }

    suspend fun scrollTo(x: Int, y: Int) {
        _commands.emit(NavigationCommand.ScrollTo(x, y))
    }

    /** Persist cookies so they survive the WebView being destroyed and recreated. */
    fun flushCookies() {
        runCatching { CookieManager.getInstance().flush() }
    }

    /** Clear all browsing data (opt-in, from the browser screen settings). */
    fun clearAll() {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
    }
}
