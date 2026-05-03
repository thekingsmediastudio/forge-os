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
    @ApplicationContext private val context: Context
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
    }

    suspend fun evalJs(script: String, callbackId: String = "cb_${System.currentTimeMillis()}") {
        _commands.emit(NavigationCommand.EvalJs(script, callbackId))
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
