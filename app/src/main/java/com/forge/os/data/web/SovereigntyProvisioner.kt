package com.forge.os.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebView
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Docking Bay" for the Sovereign WebView.
 * 
 * This ensures that a single WebView instance persists across the entire OS lifecycle,
 * allowing it to move between background (Headless) and foreground (Headed) states
 * without losing scroll position, JS state, or triggering a reload.
 */
@Singleton
class SovereigntyProvisioner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @SuppressLint("SetJavaScriptEnabled")
    private val masterWebView: WebView by lazy {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 ForgeOS/Sovereign"
        }
    }

    private var currentParent: ViewGroup? = null

    /**
     * Docks the master WebView into a UI container.
     * Detaches it from any previous parent first.
     */
    fun dockToUI(newParent: ViewGroup) {
        masterWebView.post {
            masterWebView.parent?.let { (it as ViewGroup).removeView(masterWebView) }
            newParent.addView(masterWebView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            currentParent = newParent
            Timber.i("Sovereign WebView DOCKED to UI.")
        }
    }

    /**
     * Undocks the master WebView from the UI, preparing it for background work.
     */
    fun undockFromUI() {
        masterWebView.post {
            masterWebView.parent?.let { (it as ViewGroup).removeView(masterWebView) }
            currentParent = null
            Timber.i("Sovereign WebView CLOAKED to background.")
        }
    }

    fun getMasterWebView(): WebView = masterWebView
}
