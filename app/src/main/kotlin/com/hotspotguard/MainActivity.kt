package com.tvcs.hotspotguard

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prüfe ob Passwort bereits gesetzt wurde
        val prefs = SecurityPrefs(this)
        if (!prefs.isPasswordSet()) {
            // Erstes Start: Passwort einrichten
            startActivity(Intent(this, PasswordActivity::class.java).apply {
                putExtra(PasswordActivity.MODE, PasswordActivity.MODE_SETUP)
            })
            finish()
            return
        }

        // Passwort-Check beim Öffnen der App
        startActivity(Intent(this, PasswordActivity::class.java).apply {
            putExtra(PasswordActivity.MODE, PasswordActivity.MODE_VERIFY)
        })
        finish()
    }
}
