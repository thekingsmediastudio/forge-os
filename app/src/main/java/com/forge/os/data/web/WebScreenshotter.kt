package com.forge.os.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.forge.os.domain.control.AgentControlPlane
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Phase Q — render an arbitrary URL in an offscreen WebView and save the
 * captured bitmap to the workspace.
 *
 * The agent calls this through the `web_screenshot` tool. Gated by the
 * [AgentControlPlane.HW_SCREENSHOT_WEB] capability.
 */
@Singleton
class WebScreenshotter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controlPlane: AgentControlPlane,
) {

    data class Spec(
        val url: String,
        val widthPx: Int = 1280,
        val heightPx: Int = 1600,
        val fullPage: Boolean = true,
        val waitMs: Long = 1500,
        val outputPath: File,
    )

    suspend fun capture(spec: Spec): Result<File> {
        if (!controlPlane.isEnabled(AgentControlPlane.HW_SCREENSHOT_WEB)) {
            return Result.failure(SecurityException("web_screenshot capability disabled"))
        }
        return runCatching { withContext(Dispatchers.Main) { renderInternal(spec) } }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderInternal(spec: Spec): File = suspendCancellableCoroutine { cont ->
        try {
            val wv = WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString + " ForgeOS/Screenshot"
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
            }
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(spec.widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(spec.heightPx, View.MeasureSpec.EXACTLY),
            )
            wv.layout(0, 0, spec.widthPx, spec.heightPx)

            val handler = Handler(Looper.getMainLooper())
            val timeout = Runnable {
                if (cont.isActive) cont.resumeWithException(
                    RuntimeException("web_screenshot: page load timed out (15s)"))
            }
            handler.postDelayed(timeout, 15_000)

            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    handler.postDelayed({
                        try {
                            val height = if (spec.fullPage) {
                                maxOf(view.contentHeight, spec.heightPx)
                            } else spec.heightPx
                            view.measure(
                                View.MeasureSpec.makeMeasureSpec(spec.widthPx, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                            )
                            view.layout(0, 0, spec.widthPx, height)
                            val bmp = Bitmap.createBitmap(spec.widthPx, height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bmp))
                            spec.outputPath.parentFile?.mkdirs()
                            FileOutputStream(spec.outputPath).use { out ->
                                bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                            }
                            handler.removeCallbacks(timeout)
                            if (cont.isActive) cont.resume(spec.outputPath)
                        } catch (t: Throwable) {
                            handler.removeCallbacks(timeout)
                            if (cont.isActive) cont.resumeWithException(t)
                        } finally {
                            view.destroy()
                        }
                    }, spec.waitMs)
                }
            }
            wv.loadUrl(spec.url)
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeout)
                runCatching { wv.destroy() }
            }
        } catch (t: Throwable) {
            Timber.e(t, "web_screenshot: setup failed")
            if (cont.isActive) cont.resumeWithException(t)
        }
    }
}
