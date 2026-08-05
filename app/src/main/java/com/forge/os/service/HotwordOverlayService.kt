package com.forge.os.service

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import timber.log.Timber

/**
 * Lightweight system-overlay bubble shown when the "Hello Forge" wake word is
 * heard while Forge OS is in the background. The in-app [HotwordActivationOverlay]
 * (a Compose Dialog) can only render while MainActivity is foreground, so this
 * TYPE_APPLICATION_OVERLAY view is the bridge for the "I'm in another app" case.
 *
 * Behaviour: shows a small floating "🎤 Hello Forge" chip. Tapping it launches
 * MainActivity (the pending HotwordEvent then drives the full voice popup).
 * It auto-dismisses after [AUTO_DISMISS_MS] so a stale bubble never lingers.
 *
 * Requires SYSTEM_ALERT_WINDOW; if the permission isn't granted the service
 * stops itself silently (the in-app overlay still works when the app is open).
 */
class HotwordOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: LinearLayout? = null
    private val dismissRunnable = Runnable { dismiss() }
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        private const val AUTO_DISMISS_MS = 8_000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Timber.w("HotwordOverlayService: overlay permission not granted, skipping")
            stopSelf()
            return START_NOT_STICKY
        }
        showBubble()
        return START_NOT_STICKY
    }

    private fun showBubble() {
        if (bubbleView != null) return // already showing
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val label = TextView(this).apply {
            text = "🎤  Hello Forge — tap to talk"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(36, 24, 36, 24)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // Rounded dark background
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 56f
                setColor(0xE6121212.toInt())
                setStroke(2, 0xFFFF7A1A.toInt())
            }
            elevation = 24f
            addView(label)
            setOnClickListener { openApp() }
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 180
        }

        try {
            windowManager?.addView(container, params)
            bubbleView = container
            handler.postDelayed(dismissRunnable, AUTO_DISMISS_MS)
            Timber.d("HotwordOverlayService: bubble shown")
        } catch (e: Exception) {
            Timber.e(e, "HotwordOverlayService: failed to add overlay view")
            stopSelf()
        }
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        if (launch != null) startActivity(launch)
        dismiss()
    }

    private fun dismiss() {
        handler.removeCallbacks(dismissRunnable)
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Timber.w("HotwordOverlayService: removeView failed: ${e.message}")
            }
        }
        bubbleView = null
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(dismissRunnable)
        bubbleView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        bubbleView = null
        super.onDestroy()
    }
}
