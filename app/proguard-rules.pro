# HotspotGuard ProGuard Rules

# Behalte alle Klassen im Package
-keep class com.hotspotguard.** { *; }

# WifiManager Reflection-Methoden beibehalten
-keepclassmembers class android.net.wifi.WifiManager {
    public boolean setWifiApEnabled(android.net.wifi.WifiConfiguration, boolean);
    public int getWifiApState();
    public android.net.wifi.WifiConfiguration getWifiApConfiguration();
}

# ConnectivityManager Tethering-Methoden
-keepclassmembers class android.net.ConnectivityManager {
    public void startTethering(int, boolean, android.net.ConnectivityManager$OnStartTetheringCallback, android.os.Handler);
    public void stopTethering(int);
}

# Accessibility Service
-keep public class com.hotspotguard.HotspotAccessibilityService
-keep public class com.hotspotguard.ProtectionVpnService
-keep public class com.hotspotguard.HotspotGuardService
-keep public class com.hotspotguard.BootReceiver
-keep public class com.hotspotguard.HotspotReceiver
