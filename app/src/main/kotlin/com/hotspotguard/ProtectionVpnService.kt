package com.tvcs.hotspotguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Dummy-VPN-Service der keinen echten Datenverkehr umleitet.
 *
 * Zweck: VPN-Prozesse haben auf Android die höchste Prozesspriorität und werden
 * von Family Link / Android-System nicht beendet. Durch Aktivierung dieses
 * Dummy-VPNs wird der gesamte App-Prozess vor dem Kill geschützt.
 *
 * Der VPN-Tunnel leitet KEINEN Traffic um – er ist rein als Prozessschutz gedacht.
 */
class ProtectionVpnService : VpnService() {

    companion object {
        const val TAG = "ProtectionVpnService"
        const val CHANNEL_ID = "vpn_protection_channel"
        const val NOTIFICATION_ID = 1002

        @Volatile
        var isRunning = false

        fun start(context: Context) {
            val intent = Intent(context, ProtectionVpnService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ProtectionVpnService::class.java)
            context.stopService(intent)
        }
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        establishDummyVpn()
        return START_STICKY
    }

    private fun establishDummyVpn() {
        try {
            vpnInterface = Builder()
                .setSession("HotspotGuard Protection")
                // Minimale, nicht-routbare IP – kein echter Traffic
                .addAddress("10.255.255.254", 32)
                // Kein echtes Route-Setting → kein Traffic wird umgeleitet
                .setMtu(1500)
                .establish()
            Log.d(TAG, "Dummy-VPN erfolgreich etabliert")
        } catch (e: Exception) {
            Log.e(TAG, "Fehler beim VPN-Aufbau: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Fehler beim VPN-Schließen: ${e.message}")
        }
        // Neustart
        start(applicationContext)
    }

    override fun onRevoke() {
        super.onRevoke()
        isRunning = false
        // Versuche neu zu starten
        start(applicationContext)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN Schutz",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Schutz aktiv")
            .setContentText("HotspotGuard-Schutz läuft")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
