package com.tvcs.hotspotguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d("BootReceiver", "Gerät gestartet – starte HotspotGuardService")

                val prefs = SecurityPrefs(context)
                if (prefs.isPasswordSet()) {
                    // Nur starten wenn App bereits eingerichtet wurde
                    val serviceIntent = Intent(context, HotspotGuardService::class.java)
                    ContextCompat.startForegroundService(context, serviceIntent)

                    // VPN falls aktiviert
                    if (prefs.isVpnProtectionEnabled()) {
                        ProtectionVpnService.start(context)
                    }
                }
            }
        }
    }
}
