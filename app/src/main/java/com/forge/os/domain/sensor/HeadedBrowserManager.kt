package com.forge.os.domain.sensor

import android.webkit.WebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the "Headed" (user-visible) browser session.
 * 
 * Unlike the HeadlessBrowser which is persistent and off-screen,
 * this manager coordinates with the BrowserHubScreen to provide
 * a bridge between the User's manual browsing and the Agent's
 * automated actions.
 */
@Singleton
class HeadedBrowserManager @Inject constructor() {

    private val _currentUrl = MutableStateFlow("about:blank")
    val currentUrl: StateFlow<String> = _currentUrl

    private val _isAgentActive = MutableStateFlow(false)
    val isAgentActive: StateFlow<Boolean> = _isAgentActive

    private val _agentThought = MutableStateFlow<String?>(null)
    val agentThought: StateFlow<String?> = _agentThought

    private var activeWebView: WebView? = null

    fun registerWebView(webView: WebView) {
        this.activeWebView = webView
        Timber.d("HeadedBrowser Hub Registered.")
    }

    fun unregisterWebView() {
        this.activeWebView = null
        _isAgentActive.value = false
        _agentThought.value = null
        Timber.d("HeadedBrowser Hub Unregistered.")
    }

    fun navigate(url: String) {
        val resolved = if (url.startsWith("http://") || url.startsWith("https://")) url
                       else "https://$url"
        _currentUrl.value = resolved
        activeWebView?.post { activeWebView?.loadUrl(resolved) }
    }

    fun setAgentActive(active: Boolean, thought: String? = null) {
        _isAgentActive.value = active
        _agentThought.value = thought
    }

    fun getWebView(): WebView? = activeWebView

    fun updateUrl(url: String) {
        _currentUrl.value = url
    }

    suspend fun evalJs(script: String): String? {
        val wv = activeWebView ?: return null
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            wv.post {
                wv.evaluateJavascript(script) { result ->
                    cont.resume(result, null)
                }
            }
        }
    }
}
