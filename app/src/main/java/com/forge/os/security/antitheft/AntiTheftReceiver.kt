package com.forge.os.security.antitheft

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DeviceAdminReceiver for Anti-Theft protection.
 *
 * Required for lockNow() and wipeData() to work.
 * User must activate device admin in Settings → Security → Device Admin Apps.
 */
class AntiTheftReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "AntiTheftReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled for anti-theft")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled for anti-theft")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Disabling device admin will prevent anti-theft features from working. Are you sure?"
    }
}
