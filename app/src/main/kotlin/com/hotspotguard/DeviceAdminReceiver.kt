package com.tvcs.hotspotguard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * DeviceAdminReceiver – Voraussetzung für Device Owner Mode.
 *
 * Einmalige Einrichtung per ADB (einmaliger Schritt für Elternteil):
 *   adb shell dpm set-device-owner com.tvcs.hotspotguard/.DeviceAdminReceiver
 *
 * Danach kann die App DISALLOW_CONFIG_TETHERING setzen, was die Hotspot-UI
 * im System komplett deaktiviert – ohne Root, ohne System-App.
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("DeviceAdmin", "Device Admin aktiviert")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.d("DeviceAdmin", "Device Admin deaktiviert")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.d("DeviceAdmin", "Profil-Bereitstellung abgeschlossen")
    }
}
