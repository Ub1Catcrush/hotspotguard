package com.tvcs.hotspotguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.content.ContextCompat

class HotspotReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "HotspotReceiver"
        const val WIFI_AP_STATE_ENABLED = 13
        const val WIFI_AP_STATE_ENABLING = 12
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "android.net.wifi.WIFI_AP_STATE_CHANGED" -> {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                Log.d(TAG, "WiFi AP State geändert: $state")

                if (state == WIFI_AP_STATE_ENABLED || state == WIFI_AP_STATE_ENABLING) {
                    Log.d(TAG, "Hotspot wird aktiviert – blockiere!")
                    ensureServiceRunning(context)
                    // Service direkt benachrichtigen
                    triggerServiceCheck(context)
                }
            }

            "android.net.conn.TETHER_STATE_CHANGED" -> {
                val active = intent.getStringArrayListExtra("activeArray")
                if (!active.isNullOrEmpty()) {
                    Log.d(TAG, "Tethering aktiv erkannt: $active")
                    ensureServiceRunning(context)
                    triggerServiceCheck(context)
                }
            }
        }
    }

    private fun ensureServiceRunning(context: Context) {
        if (!HotspotGuardService.isRunning) {
            val serviceIntent = Intent(context, HotspotGuardService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    private fun triggerServiceCheck(context: Context) {
        // Service via Intent benachrichtigen
        val checkIntent = Intent(context, HotspotGuardService::class.java).apply {
            action = "ACTION_CHECK_HOTSPOT"
        }
        ContextCompat.startForegroundService(context, checkIntent)
    }
}
