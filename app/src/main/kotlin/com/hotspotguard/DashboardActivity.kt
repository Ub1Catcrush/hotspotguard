package com.tvcs.hotspotguard

import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DashboardActivity : AppCompatActivity() {

    private lateinit var prefs: SecurityPrefs
    private var updatingUI = false
    private val handler = Handler(Looper.getMainLooper())

    private val adbCommand get() =
        "adb shell dpm set-device-owner ${packageName}/.DeviceAdminReceiver"

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == RESULT_OK) ProtectionVpnService.start(this)
        }

    private val accessibilitySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            updateAllUI()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        prefs = SecurityPrefs(this)
        setupListeners()
        startServices()
        updateAllUI()
    }

    override fun onResume() {
        super.onResume()
        updateAllUI()
    }

    // ─── Listener einmalig registrieren ──────────────────────────────────────

    private fun setupListeners() {
        // Guard toggle
        findViewById<Switch>(R.id.switch_guard)
            .setOnCheckedChangeListener { _, on ->
                if (updatingUI) return@setOnCheckedChangeListener
                if (on) startGuardService() else stopGuardService()
            }

        // VPN toggle
        findViewById<Switch>(R.id.switch_vpn)
            .setOnCheckedChangeListener { _, on ->
                if (updatingUI) return@setOnCheckedChangeListener
                prefs.setVpnProtection(on)
                if (on) requestVpnPermission() else ProtectionVpnService.stop(this)
            }

        // Accessibility toggle
        findViewById<Switch>(R.id.switch_accessibility)
            .setOnCheckedChangeListener { _, on ->
                if (updatingUI) return@setOnCheckedChangeListener
                if (on && !HotspotAccessibilityService.isEnabled(this))
                    showAccessibilityDialog()
                // Deaktivieren nicht möglich – bleibt wie es ist
            }

        // Counter reset
        findViewById<Button>(R.id.btn_reset_counter).setOnClickListener {
            prefs.resetBlockedCount()
            findViewById<TextView>(R.id.tv_blocked_count).text = "0"
        }

        // Password change
        findViewById<Button>(R.id.btn_change_password).setOnClickListener {
            startActivity(Intent(this, PasswordActivity::class.java).apply {
                putExtra(PasswordActivity.MODE, PasswordActivity.MODE_CHANGE)
            })
        }

        // Device Owner setup
        findViewById<Button>(R.id.btn_setup_device_owner).setOnClickListener {
            showDeviceOwnerSetupDialog()
        }

        // Accessibility info
        findViewById<TextView>(R.id.tv_accessibility_info).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.dialog_accessibility_info_title))
                .setMessage(getString(R.string.dialog_accessibility_info_message))
                .setPositiveButton(getString(R.string.btn_ok), null)
                .show()
        }
    }

    // ─── UI aktualisieren ────────────────────────────────────────────────────

    private fun updateAllUI() {
        val isOwner      = isDeviceOwner()
        val guardRunning = HotspotGuardService.isRunning
        val accessOn     = HotspotAccessibilityService.isEnabled(this)
        val blocked      = prefs.getBlockedCount()

        // Status-Icon + Texte
        val ivIcon   = findViewById<ImageView>(R.id.iv_status_icon)
        val tvStatus = findViewById<TextView>(R.id.tv_main_status)
        val tvDesc   = findViewById<TextView>(R.id.tv_status_desc)

        when {
            isOwner -> {
                tvStatus.text = getString(R.string.status_fully_locked)
                tvDesc.text   = getString(R.string.status_fully_locked_desc)
                tvStatus.setTextColor(getColor(R.color.status_active))
                ivIcon.setImageResource(R.drawable.ic_shield)
                ivIcon.imageTintList = colorStateList(R.color.status_active)
            }
            guardRunning && accessOn -> {
                tvStatus.text = getString(R.string.status_reactive)
                tvDesc.text   = getString(R.string.status_reactive_desc)
                tvStatus.setTextColor(getColor(R.color.status_warning))
                ivIcon.setImageResource(R.drawable.ic_shield_alert)
                ivIcon.imageTintList = colorStateList(R.color.status_warning)
            }
            guardRunning -> {
                tvStatus.text = getString(R.string.status_limited)
                tvDesc.text   = getString(R.string.status_limited_desc)
                tvStatus.setTextColor(getColor(R.color.status_warning))
                ivIcon.setImageResource(R.drawable.ic_shield_alert)
                ivIcon.imageTintList = colorStateList(R.color.status_warning)
            }
            else -> {
                tvStatus.text = getString(R.string.status_inactive)
                tvDesc.text   = getString(R.string.status_inactive_desc)
                tvStatus.setTextColor(getColor(R.color.status_inactive))
                ivIcon.setImageResource(R.drawable.ic_shield_alert)
                ivIcon.imageTintList = colorStateList(R.color.status_inactive)
            }
        }

        // Blocked counter
        findViewById<TextView>(R.id.tv_blocked_count).text = "$blocked"

        // Device Owner hint
        val tvHint    = findViewById<TextView>(R.id.tv_device_owner_hint)
        val ivHint    = findViewById<ImageView>(R.id.iv_device_owner_icon)
        val btnSetup  = findViewById<Button>(R.id.btn_setup_device_owner)
        if (isOwner) {
            tvHint.text = getString(R.string.device_owner_active)
            tvHint.setTextColor(getColor(R.color.status_active))
            ivHint.imageTintList = colorStateList(R.color.status_active)
            btnSetup.visibility = View.GONE
        } else {
            tvHint.text = getString(R.string.device_owner_inactive)
            tvHint.setTextColor(getColor(R.color.status_warning))
            ivHint.imageTintList = colorStateList(R.color.status_warning)
            btnSetup.visibility = View.VISIBLE
        }

        // Toggles (ohne Listener-Triggering)
        setCheckedSilently(R.id.switch_guard, guardRunning)
        setCheckedSilently(R.id.switch_vpn, prefs.isVpnProtectionEnabled())
        setCheckedSilently(R.id.switch_accessibility, accessOn)
    }

    /** Switch-Zustand setzen ohne den OnCheckedChangeListener auszulösen */
    private fun setCheckedSilently(id: Int, checked: Boolean) {
        val sw = findViewById<Switch>(id) ?: return
        if (sw.isChecked == checked) return  // Keine Änderung – Listener nicht nötig
        updatingUI = true
        sw.isChecked = checked
        updatingUI = false
    }

    private fun colorStateList(colorRes: Int) =
        android.content.res.ColorStateList.valueOf(getColor(colorRes))

    // ─── Device Owner ────────────────────────────────────────────────────────

    private fun isDeviceOwner(): Boolean =
        getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)

    private fun showDeviceOwnerSetupDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_device_owner_setup, null)
        view.findViewById<TextView>(R.id.tv_adb_command)?.text = adbCommand

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_device_owner_title))
            .setView(view)
            .setNeutralButton(getString(R.string.btn_copy_command)) { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("adb", adbCommand))
                Toast.makeText(this, getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton(getString(R.string.btn_check)) { _, _ ->
                if (isDeviceOwner()) {
                    // Restriktion sofort anwenden
                    val i = Intent(this, HotspotGuardService::class.java)
                    i.action = "ACTION_APPLY_RESTRICTION"
                    ContextCompat.startForegroundService(this, i)
                    handler.postDelayed({ updateAllUI() }, 500)
                    Toast.makeText(this, getString(R.string.msg_device_owner_active), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.msg_device_owner_not_yet), Toast.LENGTH_LONG).show()
                    showDeviceOwnerSetupDialog()
                }
            }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    // ─── Services ────────────────────────────────────────────────────────────

    private fun startServices() {
        if (!HotspotGuardService.isRunning) startGuardService()
        if (prefs.isVpnProtectionEnabled()) requestVpnPermission()
    }

    private fun startGuardService() =
        ContextCompat.startForegroundService(this, Intent(this, HotspotGuardService::class.java))

    private fun stopGuardService() =
        stopService(Intent(this, HotspotGuardService::class.java))

    private fun requestVpnPermission() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) vpnPermissionLauncher.launch(vpnIntent)
        else ProtectionVpnService.start(this)
    }

    private fun showAccessibilityDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_accessibility_title))
            .setMessage(getString(R.string.dialog_accessibility_message))
            .setPositiveButton(getString(R.string.btn_open_settings)) { _, _ ->
                accessibilitySettingsLauncher.launch(
                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
