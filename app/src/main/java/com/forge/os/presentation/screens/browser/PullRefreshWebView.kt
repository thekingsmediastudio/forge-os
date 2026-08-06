package com.forge.os.presentation.screens.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import kotlin.math.abs

/**
 * WebView with a lightweight pull-to-refresh gesture: when the page is
 * scrolled to the very top and the user drags down past a threshold,
 * [onPullRefresh] fires. No external libs — avoids the SwipeRefreshLayout
 * dependency and the nested-scroll quirks of wrapping a WebView.
 */
@SuppressLint("ClickableViewAccessibility")
class PullRefreshWebView(
    context: Context,
    private val onPullProgress: (Float) -> Unit,
    private val onPullRefresh: () -> Unit
) : WebView(context) {

    private var startY = 0f
    private var pulling = false
    private val thresholdPx = context.resources.displayMetrics.density * 96f

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startY = event.y
                pulling = scrollY == 0
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (pulling && scrollY == 0) {
                    val dy = event.y - startY
                    if (dy > 0) {
                        onPullProgress((dy / thresholdPx).coerceIn(0f, 1f))
                    } else {
                        pulling = false
                        onPullProgress(0f)
                    }
                } else if (pulling) {
                    pulling = false
                    onPullProgress(0f)
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    val dy = event.y - startY
                    if (dy >= thresholdPx && scrollY == 0) onPullRefresh()
                    onPullProgress(0f)
                    pulling = false
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
