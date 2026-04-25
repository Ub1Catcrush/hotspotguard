package com.tvcs.hotspotguard

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.TetheringManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.lang.reflect.Method

class HotspotGuardService : Service() {

    companion object {
        const val TAG                   = "HotspotGuardService"
        const val NOTIFICATION_ID       = 1001
        const val NOTIFICATION_ID_ALERT = 1003
        const val CHANNEL_ID            = "hotspot_guard_channel"
        const val CHANNEL_ID_ALERT      = "hotspot_guard_alert"
        const val ACTION_CHECK          = "ACTION_CHECK_HOTSPOT"

        const val WIFI_AP_STATE_DISABLED  = 11
        const val WIFI_AP_STATE_DISABLING = 10
        const val WIFI_AP_STATE_ENABLED   = 13
        const val WIFI_AP_STATE_ENABLING  = 12

        @Volatile var isRunning = false
    }

    private lateinit var wifiManager: WifiManager
    private lateinit var tetheringManager: TetheringManager
    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var prefs: SecurityPrefs
    private lateinit var wakeLock: PowerManager.WakeLock
    private var hotspotObserver: ContentObserver? = null
    private val handler = Handler(Looper.getMainLooper())

    // Notification debounce – max 1 Alert alle 4 Sekunden
    private var lastAlertNotifTime = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkAndDisableHotspot()
            handler.postDelayed(this, 750)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager         = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        tetheringManager    = getSystemService(TetheringManager::class.java)
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent      = ComponentName(this, DeviceAdminReceiver::class.java)
        prefs               = SecurityPrefs(this)
        isRunning           = true

        createNotificationChannels()
        startForeground(NOTIFICATION_ID, buildStatusNotification())
        acquireWakeLock()
        applyDeviceOwnerRestriction()
        registerHotspotObserver()
        handler.post(pollRunnable)

        Log.d(TAG, "Service gestartet – DeviceOwner=${isDeviceOwner()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CHECK -> checkAndDisableHotspot()
            "ACTION_APPLY_RESTRICTION" -> applyDeviceOwnerRestriction()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        hotspotObserver?.let { contentResolver.unregisterContentObserver(it) }
        if (::wakeLock.isInitialized && wakeLock.isHeld) wakeLock.release()
        // Sofort neu starten
        val i = Intent(applicationContext, HotspotGuardService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            applicationContext.startForegroundService(i)
        else
            applicationContext.startService(i)
    }

    // ─── Device Owner ────────────────────────────────────────────────────────

    fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(packageName)

    /**
     * Setzt DISALLOW_CONFIG_TETHERING wenn Device Owner.
     * Einmalig per ADB einrichten:
     *   adb shell dpm set-device-owner com.tvcs.hotspotguard/.DeviceAdminReceiver
     *
     * Das ist die EINZIGE zuverlässige Methode auf Android 13+ ohne Root.
     * Kein API-Aufruf einer normalen App kann einen User-Hotspot stoppen.
     */
    fun applyDeviceOwnerRestriction() {
        if (!isDeviceOwner()) return
        try {
            devicePolicyManager.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_TETHERING)
            Log.d(TAG, "✓ DISALLOW_CONFIG_TETHERING gesetzt – Hotspot-UI deaktiviert")
            updateStatusNotification()
        } catch (e: Exception) {
            Log.e(TAG, "addUserRestriction fehlgeschlagen: ${e.message}")
        }
    }

    fun removeDeviceOwnerRestriction() {
        if (!isDeviceOwner()) return
        try {
            devicePolicyManager.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_TETHERING)
            Log.d(TAG, "DISALLOW_CONFIG_TETHERING entfernt")
        } catch (e: Exception) {
            Log.e(TAG, "clearUserRestriction fehlgeschlagen: ${e.message}")
        }
    }

    // ─── Erkennung ───────────────────────────────────────────────────────────

    private fun registerHotspotObserver() {
        hotspotObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = checkAndDisableHotspot()
        }
        try {
            contentResolver.registerContentObserver(
                Settings.Global.getUriFor("wifi_ap_enabled"),
                false, hotspotObserver!!
            )
        } catch (e: Exception) {
            Log.w(TAG, "ContentObserver: ${e.message}")
        }
    }

    fun checkAndDisableHotspot() {
        if (isDeviceOwner()) return  // Device Owner sperrt präventiv – nichts zu tun
        val state = getWifiApState()
        if (state != WIFI_AP_STATE_ENABLED && state != WIFI_AP_STATE_ENABLING) return

        Log.d(TAG, "Hotspot erkannt (State: $state) – reaktive Deaktivierung...")
        val count = prefs.incrementBlockedCount()
        postAlertNotification(count)
        updateStatusNotification()

        // Accessibility-Service ist die einzige reaktive Methode die wirklich
        // arbeitet: der Toggle wird direkt in der Settings-UI zurückgekippt.
        // Alle programmatischen Stop-Aufrufe werden vom System ohne TETHER_PRIVILEGED
        // Permission ignoriert. Wir loggen trotzdem alle Versuche für Diagnose.
        tryStopTethering()
    }

    private fun tryStopTethering() {
        // Diese Aufrufe haben auf Android 13+ ohne TETHER_PRIVILEGED keinen Effekt.
        // Sie bleiben als Best-Effort-Fallback und für ältere Android-Versionen.
        try {
            val m = TetheringManager::class.java
                .getDeclaredMethod("stopTethering", Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(tetheringManager, TetheringManager.TETHERING_WIFI)
        } catch (_: Exception) {}

        try {
            val wifiConfigClass = Class.forName("android.net.wifi.WifiConfiguration")
            val m = WifiManager::class.java
                .getDeclaredMethod("setWifiApEnabled", wifiConfigClass, Boolean::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(wifiManager, null, false)
        } catch (_: Exception) {}
    }

    private fun getWifiApState(): Int = try {
        val m = WifiManager::class.java.getDeclaredMethod("getWifiApState")
        m.isAccessible = true
        m.invoke(wifiManager) as Int
    } catch (_: Exception) { -1 }

    // ─── WakeLock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HotspotGuard::Lock")
        wakeLock.acquire()
    }

    // ─── Notifications ───────────────────────────────────────────────────────

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID,
                getString(R.string.notif_channel_status_name),
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID_ALERT,
                getString(R.string.notif_channel_alert_name),
                NotificationManager.IMPORTANCE_DEFAULT).apply {
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = 0xFFEF5350.toInt()
            }
        )
    }

    private fun buildStatusNotification(): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val blocked = prefs.getBlockedCount()
        val title = if (isDeviceOwner())
            getString(R.string.notif_title_locked)
        else
            getString(R.string.notif_title_reactive)
        val text = when {
            isDeviceOwner() -> getString(R.string.notif_text_locked)
            blocked == 0    -> getString(R.string.notif_text_active)
            blocked == 1    -> getString(R.string.notif_text_blocked, blocked)
            else            -> getString(R.string.notif_text_blocked_plural, blocked)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield_status)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setNumber(blocked)
            .build()
    }

    fun updateStatusNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildStatusNotification())
    }

    private fun postAlertNotification(blockedCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastAlertNotifTime < 4000) return  // Debounce
        lastAlertNotifTime = now

        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val n = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle(getString(R.string.notif_alert_title))
            .setContentText(getString(R.string.notif_alert_text, blockedCount))
            .setSmallIcon(R.drawable.ic_shield_alert)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(0xFFEF5350.toInt())
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID_ALERT, n)
    }
}
