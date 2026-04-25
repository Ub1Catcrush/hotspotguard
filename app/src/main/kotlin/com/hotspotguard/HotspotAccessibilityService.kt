package com.tvcs.hotspotguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class HotspotAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "HotspotAccessibility"

        val SETTINGS_PACKAGES = setOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.sec.android.app.settings",
            "com.miui.settings",
            "com.oneplus.settings",
            "com.google.android.settings",
            "com.lge.settings",
            "com.motorola.settings"
        )

        val HOTSPOT_SCREEN_HINTS = listOf(
            "hotspot", "tethering", "mobiler hotspot", "wlan-hotspot",
            "personal hotspot", "mobile hotspot", "access point",
            "persönlicher hotspot", "internet teilen", "share internet"
        )

        val HOTSPOT_TOGGLE_IDS = listOf(
            "switch_widget", "switchWidget", "toggle",
            "hotspot_switch", "tethering_switch",
            "switch_bar", "main_switch_bar",
            "hotspot_main_switch"
        )

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val name = "${context.packageName}/${HotspotAccessibilityService::class.java.name}"
            return enabled.contains(name, ignoreCase = true)
        }
    }

    private var lastBlockTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                         AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                         AccessibilityEvent.TYPE_VIEW_CLICKED or
                         AccessibilityEvent.TYPE_VIEW_SCROLLED
            packageNames = SETTINGS_PACKAGES.toTypedArray()
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 50
        }
        Log.d(TAG, "AccessibilityService verbunden")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName?.toString() !in SETTINGS_PACKAGES) return

        // rootInActiveWindow gibt Ownership ab API 33 an GC – kein recycle() nötig
        val root = rootInActiveWindow ?: return
        if (isOnHotspotScreen(root)) {
            disableAnyActiveToggle(root)
        }
    }

    // ─── Hotspot-Screen erkennen ─────────────────────────────────────────────

    private fun isOnHotspotScreen(root: AccessibilityNodeInfo): Boolean {
        // 1. Titel prüfen
        val title = (root.text ?: root.contentDescription)?.toString()?.lowercase() ?: ""
        if (HOTSPOT_SCREEN_HINTS.any { title.contains(it) }) return true

        // 2. Bekannte View-IDs suchen
        for (toggleId in HOTSPOT_TOGGLE_IDS) {
            for (pkg in SETTINGS_PACKAGES) {
                val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$toggleId")
                // Kein recycle() – seit API 33 deprecated, GC übernimmt
                if (nodes.isNotEmpty()) return true
            }
        }

        // 3. Text im Baum suchen
        return containsHotspotText(root)
    }

    private fun containsHotspotText(node: AccessibilityNodeInfo): Boolean {
        val t = "${node.text ?: ""} ${node.contentDescription ?: ""}".lowercase()
        if (HOTSPOT_SCREEN_HINTS.any { t.contains(it) }) return true
        for (i in 0 until node.childCount) {
            // getChild() gibt eine neue Referenz zurück – seit API 33 kein recycle() nötig
            val child = node.getChild(i) ?: continue
            if (containsHotspotText(child)) return true
        }
        return false
    }

    // ─── Toggle deaktivieren ─────────────────────────────────────────────────

    private fun disableAnyActiveToggle(root: AccessibilityNodeInfo) {
        val now = System.currentTimeMillis()
        if (now - lastBlockTime < 1000) return

        if (clickActiveToggle(root)) {
            lastBlockTime = now
            Log.d(TAG, "Toggle via Accessibility deaktiviert")
            triggerGuardService()
        }
    }

    private fun clickActiveToggle(node: AccessibilityNodeInfo): Boolean {
        val cls = node.className?.toString() ?: ""
        val isToggleClass = cls.contains("Switch", ignoreCase = true) ||
                            cls.contains("ToggleButton", ignoreCase = true) ||
                            cls.contains("CheckBox", ignoreCase = true)

        // isChecked ist seit API 33 deprecated – stateDescription als modernen Ersatz
        // prüfen, mit isChecked als Fallback (beide Wege suppressed)
        val nodeIsOn = isNodeChecked(node)

        if ((isToggleClass || node.isCheckable) && nodeIsOn && node.isEnabled && node.isClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickActiveToggle(child)) return true
        }
        return false
    }

    /**
     * Prüft ob ein Node eingeschaltet ist.
     * Nutzt stateDescription (API 30+) als primären Weg,
     * fällt auf isChecked zurück (deprecated seit API 33, aber weiterhin funktional).
     */
    @Suppress("DEPRECATION")
    private fun isNodeChecked(node: AccessibilityNodeInfo): Boolean {
        // Moderner Weg: stateDescription enthält z.B. "Ein", "On", "Checked"
        val state = node.stateDescription?.toString()?.lowercase() ?: ""
        if (state.isNotEmpty()) {
            // "on", "ein", "checked", "aktiviert", "enabled" → an
            // "off", "aus", "unchecked", "deaktiviert" → aus
            val onWords  = listOf("on", "ein", "checked", "aktiviert", "enabled", "active")
            val offWords = listOf("off", "aus", "unchecked", "deaktiviert", "disabled")
            if (onWords.any  { state.contains(it) }) return true
            if (offWords.any { state.contains(it) }) return false
        }
        // Fallback: isChecked (deprecated aber zuverlässig)
        return node.isChecked
    }

    private fun triggerGuardService() {
        try {
            startService(Intent(this, HotspotGuardService::class.java).apply {
                action = HotspotGuardService.ACTION_CHECK
            })
        } catch (e: Exception) {
            Log.w(TAG, "Service-Trigger fehlgeschlagen: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "AccessibilityService unterbrochen")
    }
}
