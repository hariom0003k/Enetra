package com.example.peepguard

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent

/**
 * Monitors which app is in the foreground.
 *
 * Improvement over original: protected-apps set is cached in memory and only
 * refreshed when the SharedPreferences actually change, eliminating repeated
 * disk reads on every window-state event.
 */
class AppWatcherService : AccessibilityService() {

    private lateinit var prefs: SharedPreferences
    private var protectedApps: Set<String> = emptySet()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == PREF_KEY_APPS) refreshProtectedApps()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        refreshProtectedApps()
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Ignore our own app, system UI, and input methods
        if (pkg == packageName ||
            pkg == "com.android.systemui" ||
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard")
        ) return

        GuardState.setActive(pkg in protectedApps)
    }

    override fun onInterrupt() {
        GuardState.setActive(false)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        GuardState.setActive(false)
        super.onDestroy()
    }

    private fun refreshProtectedApps() {
        protectedApps = prefs.getStringSet(PREF_KEY_APPS, emptySet()) ?: emptySet()
    }

    companion object {
        const val PREFS_NAME = "guard"
        const val PREF_KEY_APPS = "apps"
    }
}
