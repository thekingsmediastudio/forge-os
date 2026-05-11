package com.forge.os.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * A **persistent, stateful, off-screen WebView** owned by the agent.
 *
 * Why this exists: the on-screen [com.forge.os.data.browser.BrowserSessionManager]
 * lives inside the BrowserScreen composable. As soon as the user leaves
 * that screen the WebView is gone, and every browser_* tool the agent
 * tries to use silently times out. That meant the user effectively had
 * to babysit the Browser tab while the agent worked.
 *
 * This class fixes that by giving the agent its own WebView that:
 *   - Is created once and reused across calls (so login state, scroll
 *     position, and any client-side JS state PERSIST across tool calls).
 *   - Lives on the main thread (Android requires it) but exposes
 *     suspend functions that any coroutine can call from anywhere.
 *   - Shares the application's global cookie jar with the on-screen
 *     browser, so logging in once on the user-visible Browser tab
 *     automatically gives the agent the same session.
 *
 * Capabilities exposed:
 *   - [navigate] / [getHtml] — load a URL and read the rendered DOM.
 *   - [evalJs] — run arbitrary JavaScript and return its value.
 *   - [fillField] — set the value of an input via CSS selector,
 *     dispatching React-friendly input/change events.
 *   - [click] — click any element by CSS selector.
 *   - [scroll] — scroll the page.
 *   - [reset] — discard the WebView (next call will create a new one).
 */
@Singleton
class HeadlessBrowser @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var wv: WebView? = null

    // Phase R — configurable viewport + UA so the agent can pose as desktop /
    // laptop / tablet / mobile per site. Defaults to a desktop layout because
    // the headless WebView's actual screen size is undefined and many sites
    // gate features on viewport width.
    @Volatile var viewportWidth: Int = 1280
        private set
    @Volatile var viewportHeight: Int = 1600
        private set
    @Volatile private var userAgentOverride: String? = null

    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 ForgeOS/Headless"
    private val LAPTOP_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 ForgeOS/Headless"
    private val TABLET_UA = "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1 ForgeOS/Headless"
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 ForgeOS/Headless"

    /** URL of the most recently loaded page (or "about:blank"). */
    @Volatile var currentUrl: String = "about:blank"
        private set

    /** Resolved by webViewClient.onPageFinished for the in-flight navigation. */
    @Volatile private var pendingNav: CompletableDeferred<NavResult>? = null

    private data class NavResult(val url: String, val error: String?)

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /** Lazily create and configure the persistent WebView. Main-thread only. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureOnMain(): WebView {
        wv?.let { return it }
        val w = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = userAgentOverride ?: DESKTOP_UA
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
        }
        // Off-screen rendering still needs a non-zero layout box so that
        // selectors like `:visible`, layout-dependent JS, and clicks at
        // computed coordinates work the same way they would on-screen.
        w.layout(0, 0, viewportWidth, viewportHeight)

        // Cookie jar is global per app, but we explicitly enable it
        // so login sessions from the on-screen browser flow through.
        with(CookieManager.getInstance()) {
            setAcceptCookie(true)
            runCatching { setAcceptThirdPartyCookies(w, true) }
        }

        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url
                pendingNav?.also { pendingNav = null }?.complete(NavResult(url, null))
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                // Only fail the pending navigation when the failing request is the
                // top-level page itself (sub-resource errors are normal).
                if (request.isForMainFrame) {
                    pendingNav?.also { pendingNav = null }
                        ?.complete(NavResult(request.url.toString(), "${error.errorCode}: ${error.description}"))
                }
            }
        }
        wv = w
        return w
    }

    /**
     * Phase S — return the cookie header for [url] from the shared WebView jar.
     * Used by `DownloadManager.downloadWithBrowserCookies` so authenticated
     * downloads work after the agent navigates the page in this browser.
     */
    fun getCookieHeader(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (e: Exception) {
        Timber.w(e, "getCookieHeader failed for $url")
        null
    }

    /** Tear down the WebView. Next operation will create a new one. */
    suspend fun reset() = withContext(Dispatchers.Main) {
        runCatching {
            wv?.stopLoading()
            wv?.clearHistory()
            wv?.destroy()
        }
        wv = null
        currentUrl = "about:blank"
        pendingNav?.complete(NavResult("about:blank", "reset"))
        pendingNav = null
    }

    // ─── Public ops (all safe to call from any coroutine) ───────────────────

    /**
     * Load [url] in the persistent WebView, wait for `onPageFinished` plus
     * a short settling delay (so client-rendered apps can hydrate), then
     * return a result string.
     */
    suspend fun navigate(
        url: String,
        waitMs: Long = 1200L,
        timeoutMs: Long = 15_000L,
    ): String = withContext(Dispatchers.Main) {
        val resolved = if (url.startsWith("http://") || url.startsWith("https://")) url
                       else "https://$url"

        val deferred = CompletableDeferred<NavResult>()
        // Cancel any prior pending nav — we're navigating away from it.
        pendingNav?.complete(NavResult(currentUrl, "superseded"))
        pendingNav = deferred

        val w = ensureOnMain()
        runCatching { w.stopLoading() }
        try {
            w.loadUrl(resolved)
        } catch (t: Throwable) {
            pendingNav = null
            return@withContext "❌ loadUrl failed: ${t.message}"
        }

        val res = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (res == null) {
            pendingNav = null
            return@withContext "⚠️ Navigation to $resolved timed out after ${timeoutMs / 1000}s (page may still be partially loaded)."
        }
        // Let async rendering / JS settle.
        if (waitMs > 0) delay(waitMs)
        if (res.error != null) "⚠️ Loaded with error: ${res.error}" else "✅ Loaded ${res.url}"
    }

    /**
     * Return the visible text of the current page (scripts/styles stripped).
     * If [maxChars] is exceeded, the result is truncated.
     */
    suspend fun getReadableText(maxChars: Int = 6000): String {
        val html = getRawHtml()
        return RenderedPage(currentUrl, html, null).toReadableText(maxChars)
    }

    /** Return the current page's outer HTML (full markup, untruncated). */
    suspend fun getRawHtml(): String = withContext(Dispatchers.Main) {
        val w = wv ?: return@withContext ""
        evalRaw(w, "document.documentElement.outerHTML")
    }

    /** Run [script] in the page and return whatever it evaluates to. */
    suspend fun evalJs(script: String): String = withContext(Dispatchers.Main) {
        val w = ensureOnMain()
        evalRaw(w, script)
    }

    /** 
     * Run [script] that may return a Promise, and wait for it to resolve/reject. 
     * The result is JSON-stringified.
     */
    suspend fun evalJsAsync(script: String, timeoutMs: Long = 30_000): String = withContext(Dispatchers.Main) {
        val w = ensureOnMain()
        val bridgeId = "forge_bridge_${System.currentTimeMillis()}"
        val wrapper = """
            (function() {
                window['$bridgeId'] = { status: 'pending', result: null };
                try {
                    // Execute in global scope using indirect eval
                    var p = (1,eval)(${jsLit(script)});
                    if (p && typeof p.then === 'function') {
                        p.then(function(res) {
                            window['$bridgeId'] = { status: 'resolved', result: res };
                        }).catch(function(err) {
                            window['$bridgeId'] = { status: 'rejected', result: err ? err.toString() : 'Unknown error' };
                        });
                    } else {
                        window['$bridgeId'] = { status: 'resolved', result: p };
                    }
                } catch(e) {
                    window['$bridgeId'] = { status: 'rejected', result: e ? e.toString() : 'Unknown error' };
                }
            })();
        """.trimIndent()
        
        evalRaw(w, wrapper)
        
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val status = evalRaw(w, "window['$bridgeId'].status")
            if (status != "pending" && status.isNotBlank()) {
                val res = evalRaw(w, "JSON.stringify(window['$bridgeId'].result)")
                evalRaw(w, "delete window['$bridgeId']")
                return@withContext if (status == "resolved") res else "❌ JS Error: $res"
            }
            delay(100)
        }
        evalRaw(w, "delete window['$bridgeId']")
        "❌ JS Timeout after ${timeoutMs}ms"
    }

    /**
     * Set the value of an input matched by [selector] and dispatch
     * `input` + `change` events (so frameworks like React notice).
     */
    suspend fun fillField(selector: String, value: String): String {
        val js = """
        (function(){
          var el = document.querySelector(${jsLit(selector)});
          if (!el) return "NOT_FOUND";
          var proto = el.__proto__;
          var setter = Object.getOwnPropertyDescriptor(proto, 'value');
          if (setter && setter.set) { setter.set.call(el, ${jsLit(value)}); }
          else { el.value = ${jsLit(value)}; }
          el.dispatchEvent(new Event('input',  {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          return "OK";
        })();
        """.trimIndent()
        val r = evalJs(js)
        return when {
            r.contains("OK") -> "✅ Filled '$selector'"
            r.contains("NOT_FOUND") -> "❌ Selector not found: '$selector'"
            else -> "❌ fillField returned: $r"
        }
    }

    /** Click an element matched by [selector]. */
    suspend fun click(selector: String): String {
        val js = """
        (function(){
          var el = document.querySelector(${jsLit(selector)});
          if (!el) return "NOT_FOUND";
          if (typeof el.click === 'function') el.click();
          else { var ev = new MouseEvent('click', {bubbles:true, cancelable:true, view:window}); el.dispatchEvent(ev); }
          return "OK";
        })();
        """.trimIndent()
        val r = evalJs(js)
        return when {
            r.contains("OK") -> "✅ Clicked '$selector'"
            r.contains("NOT_FOUND") -> "❌ Selector not found: '$selector'"
            else -> "❌ click returned: $r"
        }
    }

    /** Scroll the page to ([x], [y]) in CSS pixels. */
    suspend fun scroll(x: Int, y: Int): String {
        val r = evalJs("(function(){ window.scrollTo($x, $y); return 'OK'; })();")
        return if (r.contains("OK")) "✅ Scrolled to ($x, $y)" else "❌ scroll returned: $r"
    }

    /** Click at specific screen coordinates (x, y) in CSS pixels. */
    suspend fun clickAt(x: Int, y: Int): String {
        val js = """
        (function(){
          var el = document.elementFromPoint($x, $y);
          if (!el) return "NOT_FOUND";
          var ev = new MouseEvent('click', {
            bubbles: true,
            cancelable: true,
            view: window,
            clientX: $x,
            clientY: $y
          });
          el.dispatchEvent(ev);
          return "OK";
        })();
        """.trimIndent()
        val r = evalJs(js)
        return if (r.contains("OK")) "✅ Clicked at ($x, $y)" else "❌ clickAt failed: $r"
    }

    /** Type text into the currently focused element. */
    suspend fun typeText(text: String): String {
        val js = """
        (function(){
          var el = document.activeElement;
          if (!el || el === document.body) return "NO_FOCUS";
          var val = el.value || "";
          var proto = el.__proto__;
          var setter = Object.getOwnPropertyDescriptor(proto, 'value');
          if (setter && setter.set) { setter.set.call(el, val + ${jsLit(text)}); }
          else { el.value = val + ${jsLit(text)}; }
          el.dispatchEvent(new Event('input',  {bubbles: true}));
          el.dispatchEvent(new Event('change', {bubbles: true}));
          return "OK";
        })();
        """.trimIndent()
        val r = evalJs(js)
        return when {
            r.contains("OK") -> "✅ Typed text"
            r.contains("NO_FOCUS") -> "⚠️ No focused element found to type into"
            else -> "❌ typeText failed: $r"
        }
    }

    // ─── Phase R: agent-browser feature additions ──────────────────────────

    /**
     * Set a viewport preset. Accepts "desktop" (1280x1600), "laptop" (1440x900),
     * "tablet" (820x1180, tablet UA), or "mobile" (412x915, Android UA). Custom
     * dimensions can be passed via the explicit [width]/[height] params; UA can
     * be overridden via [userAgent]. The change applies on next `navigate()`.
     */
    suspend fun setViewport(
        device: String? = null,
        width: Int? = null,
        height: Int? = null,
        userAgent: String? = null,
    ): String = withContext(Dispatchers.Main) {
        when (device?.lowercase()) {
            "desktop" -> { viewportWidth = 1280; viewportHeight = 1600; userAgentOverride = DESKTOP_UA }
            "laptop"  -> { viewportWidth = 1440; viewportHeight = 900;  userAgentOverride = LAPTOP_UA }
            "tablet"  -> { viewportWidth = 820;  viewportHeight = 1180; userAgentOverride = TABLET_UA }
            "mobile"  -> { viewportWidth = 412;  viewportHeight = 915;  userAgentOverride = MOBILE_UA }
            null, "" -> Unit
            else -> return@withContext "❌ Unknown device '$device'. Use desktop|laptop|tablet|mobile, or pass width/height/user_agent."
        }
        if (width != null) viewportWidth = width.coerceIn(320, 4096)
        if (height != null) viewportHeight = height.coerceIn(240, 8192)
        if (userAgent != null) userAgentOverride = userAgent
        // Apply to live WebView immediately if it exists.
        wv?.let { w ->
            runCatching {
                w.settings.userAgentString = userAgentOverride ?: DESKTOP_UA
                w.layout(0, 0, viewportWidth, viewportHeight)
            }
        }
        "✅ Viewport ${viewportWidth}x${viewportHeight}, UA=${(userAgentOverride ?: "default").take(60)}…"
    }

    /**
     * Wait until [selector] resolves to ≥1 element, polling every 250ms
     * up to [timeoutMs]. Returns count or NOT_FOUND.
     */
    suspend fun waitForSelector(selector: String, timeoutMs: Long = 8_000): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = evalJs("document.querySelectorAll(${jsLit(selector)}).length").toIntOrNull() ?: 0
            if (n > 0) return "✅ Matched $n element(s) for '$selector'"
            delay(250)
        }
        return "❌ Timed out after ${timeoutMs}ms waiting for '$selector'"
    }

    /** Get the visible text of the first element matching [selector]. */
    suspend fun getText(selector: String): String {
        val js = "(function(){var e=document.querySelector(${jsLit(selector)});" +
            "return e?(e.innerText||e.textContent||''):'NOT_FOUND';})()"
        val v = evalJs(js)
        return if (v == "NOT_FOUND") "❌ No element matches '$selector'"
        else "✅ ${v.take(2000)}"
    }

    /** Get an attribute from the first element matching [selector]. */
    suspend fun getAttribute(selector: String, attr: String): String {
        val js = "(function(){var e=document.querySelector(${jsLit(selector)});" +
            "return e?(e.getAttribute(${jsLit(attr)})||''):'NOT_FOUND';})()"
        val v = evalJs(js)
        return if (v == "NOT_FOUND") "❌ No element matches '$selector'"
        else "✅ ${v.take(2000)}"
    }

    /** List all <a href> on the page (up to [limit]) as href|text pairs. */
    suspend fun listLinks(limit: Int = 100): String {
        val js = "(function(){var out=[];var as=document.querySelectorAll('a[href]');" +
            "for(var i=0;i<as.length && out.length<$limit;i++){var a=as[i];" +
            "out.push((a.href||'')+'\\t'+((a.innerText||a.textContent||'').trim().slice(0,80)));}" +
            "return out.join('\\n');})()"
        val v = evalJs(js)
        return if (v.isBlank()) "(no links)" else v
    }

    /**
     * Capture a screenshot of either a CSS selector region or an explicit
     * (x,y,w,h) box and write a PNG to [outAbsolutePath]. Returns the path
     * on success or an error string. Caller is responsible for placing the
     * file under workspace/.
     */
    suspend fun screenshotRegion(
        outAbsolutePath: String,
        selector: String? = null,
        x: Int? = null,
        y: Int? = null,
        width: Int? = null,
        height: Int? = null,
    ): String = withContext(Dispatchers.Main) {
        val w = wv ?: return@withContext "❌ No page loaded"
        // Resolve box: prefer selector if provided.
        val box: IntArray = if (selector != null) {
            val js = "(function(){var e=document.querySelector(${jsLit(selector)});" +
                "if(!e) return 'NF'; var r=e.getBoundingClientRect();" +
                "return Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+','+Math.round(r.height);})()"
            val raw = evalRaw(w, js)
            if (raw == "NF" || raw.isBlank()) return@withContext "❌ Selector '$selector' not found"
            val parts = raw.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (parts.size != 4) return@withContext "❌ Could not measure '$selector'"
            intArrayOf(parts[0], parts[1], parts[2], parts[3])
        } else {
            intArrayOf(x ?: 0, y ?: 0, width ?: viewportWidth, height ?: 200)
        }
        // Render WebView to a Bitmap covering the viewport, then crop to box.
        // Off-screen WebViews must be explicitly measured and laid out before drawing,
        // otherwise they may just paint a blank white background.
        w.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(viewportWidth, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(viewportHeight, android.view.View.MeasureSpec.EXACTLY)
        )
        w.layout(0, 0, w.measuredWidth, w.measuredHeight)

        val full = android.graphics.Bitmap.createBitmap(viewportWidth, viewportHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(full)
        canvas.drawColor(android.graphics.Color.WHITE) // Ensure solid background
        runCatching { w.draw(canvas) }.onFailure {
            return@withContext "❌ WebView.draw failed: ${it.message}"
        }
        val cx = box[0].coerceAtLeast(0)
        val cy = box[1].coerceAtLeast(0)
        val cw = box[2].coerceAtLeast(1).coerceAtMost(viewportWidth - cx)
        val ch = box[3].coerceAtLeast(1).coerceAtMost(viewportHeight - cy)
        val crop = android.graphics.Bitmap.createBitmap(full, cx, cy, cw, ch)
        runCatching {
            val f = java.io.File(outAbsolutePath)
            f.parentFile?.mkdirs()
            java.io.FileOutputStream(f).use { fos ->
                crop.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            }
        }.fold(
            onSuccess = { "✅ Saved ${cw}x${ch} region to $outAbsolutePath" },
            onFailure = { "❌ Save failed: ${it.message}" },
        )
    }

    /**
     * Convenience wrapper used by the older API: navigate + read HTML in
     * one shot, returning a [RenderedPage] for compatibility with the
     * previous version of this class.
     */
    suspend fun fetchHtml(
        url: String,
        widthPx: Int = 1280,
        heightPx: Int = 1600,
        waitMs: Long = 1500L,
        timeoutMs: Long = 15_000L,
    ): Result<RenderedPage> = runCatching {
        // widthPx/heightPx kept in the signature for backwards compat.
        val navResult = navigate(url, waitMs = waitMs, timeoutMs = timeoutMs)
        val html = getRawHtml()
        val err = if (navResult.startsWith("❌") || navResult.startsWith("⚠️")) navResult else null
        RenderedPage(url = currentUrl, html = html, error = err)
    }

    // ─── Internal helpers ────────────────────────────────────────────────────

    /** Evaluate JS on the WebView and return the unwrapped string result. */
    private suspend fun evalRaw(w: WebView, script: String): String =
        suspendCancellableCoroutine { cont ->
            try {
                w.evaluateJavascript(script) { raw -> cont.resume(unwrapJsString(raw)) }
            } catch (t: Throwable) {
                Timber.w(t, "evaluateJavascript threw")
                cont.resume("")
            }
        }

    /** JSON-encode a string for safe inlining as a JS literal. */
    private fun jsLit(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /** evaluateJavascript returns a JSON-encoded string literal. Decode it. */
    private fun unwrapJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return try {
            val trimmed = raw.trim()
            if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
                val inner = trimmed.substring(1, trimmed.length - 1)
                val sb = StringBuilder(inner.length)
                var i = 0
                while (i < inner.length) {
                    val c = inner[i]
                    if (c == '\\' && i + 1 < inner.length) {
                        when (val n = inner[i + 1]) {
                            '"', '\\', '/' -> { sb.append(n); i += 2 }
                            'n' -> { sb.append('\n'); i += 2 }
                            'r' -> { sb.append('\r'); i += 2 }
                            't' -> { sb.append('\t'); i += 2 }
                            'b' -> { sb.append('\b'); i += 2 }
                            'f' -> { sb.append('\u000C'); i += 2 }
                            'u' -> {
                                if (i + 5 < inner.length) {
                                    sb.append(inner.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                                } else { sb.append(c); i++ }
                            }
                            else -> { sb.append(c); i++ }
                        }
                    } else { sb.append(c); i++ }
                }
                sb.toString()
            } else trimmed
        } catch (_: Throwable) { raw }
    }
}

/** Result of a fetchHtml call (kept for backwards compatibility). */
data class RenderedPage(
    val url: String,
    val html: String,
    val error: String?,
) {
    val ok: Boolean get() = error == null && html.isNotBlank()

    /** Strip scripts/styles and collapse whitespace into readable text. */
    fun toReadableText(maxChars: Int = 6000): String =
        html.replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)
}
