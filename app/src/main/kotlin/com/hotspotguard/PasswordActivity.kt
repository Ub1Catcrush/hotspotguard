package com.tvcs.hotspotguard

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class PasswordActivity : AppCompatActivity() {

    companion object {
        const val MODE        = "mode"
        const val MODE_SETUP  = "setup"
        const val MODE_VERIFY = "verify"
        const val MODE_CHANGE = "change"
    }

    private lateinit var prefs: SecurityPrefs
    private var currentMode = MODE_VERIFY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_password)

        prefs = SecurityPrefs(this)
        currentMode = intent.getStringExtra(MODE) ?: MODE_VERIFY

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentMode == MODE_VERIFY) {
                    moveTaskToBack(true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setupUI()
    }

    private fun setupUI() {
        val titleView   = findViewById<TextView>(R.id.tv_title)
        val subtitleView= findViewById<TextView>(R.id.tv_subtitle)
        val etPassword  = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_password)
        val etConfirm   = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_password_confirm)
        val tilConfirm  = findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.til_confirm)
        val btnConfirm  = findViewById<Button>(R.id.btn_confirm)
        val btnReset    = findViewById<Button>(R.id.btn_reset_app)
        val tvError     = findViewById<TextView>(R.id.tv_error)
        val statusCard  = findViewById<View>(R.id.card_status)

        when (currentMode) {
            MODE_SETUP -> {
                titleView.text   = getString(R.string.title_setup)
                subtitleView.text= getString(R.string.subtitle_setup)
                tilConfirm.visibility = View.VISIBLE
                btnConfirm.text  = getString(R.string.btn_setup)
                statusCard.visibility = View.GONE
                btnReset?.visibility  = View.GONE
            }
            MODE_VERIFY -> {
                titleView.text   = getString(R.string.title_verify)
                subtitleView.text= getString(R.string.subtitle_verify)
                tilConfirm.visibility = View.GONE
                btnConfirm.text  = getString(R.string.btn_unlock)
                btnReset?.visibility  = View.VISIBLE
                updateStatusCard(statusCard)
            }
            MODE_CHANGE -> {
                titleView.text   = getString(R.string.title_change_password)
                subtitleView.text= getString(R.string.subtitle_change)
                tilConfirm.visibility = View.VISIBLE
                btnConfirm.text  = getString(R.string.btn_change_password)
                statusCard.visibility = View.GONE
                btnReset?.visibility  = View.GONE
            }
        }

        btnConfirm.setOnClickListener {
            tvError.visibility = View.GONE
            val password = etPassword.text.toString()

            when (currentMode) {
                MODE_SETUP, MODE_CHANGE -> {
                    val confirm = etConfirm.text.toString()
                    when {
                        password.length < 4 -> {
                            tvError.text = getString(R.string.err_password_too_short)
                            tvError.visibility = View.VISIBLE
                        }
                        password != confirm -> {
                            tvError.text = getString(R.string.err_passwords_mismatch)
                            tvError.visibility = View.VISIBLE
                        }
                        else -> {
                            prefs.setPassword(password)
                            Toast.makeText(this, getString(R.string.msg_password_saved), Toast.LENGTH_SHORT).show()
                            openDashboard()
                        }
                    }
                }
                MODE_VERIFY -> {
                    if (prefs.verifyPassword(password)) {
                        openDashboard()
                    } else {
                        tvError.text = getString(R.string.err_wrong_password)
                        tvError.visibility = View.VISIBLE
                        etPassword.text?.clear()
                        val attempts = prefs.incrementFailedAttempts()
                        if (attempts >= 5) {
                            tvError.text = getString(R.string.err_too_many_attempts)
                        }
                    }
                }
            }
        }

        // Reset-Button: nur im Verify-Modus sichtbar
        btnReset?.setOnClickListener {
            showResetDialog()
        }
    }

    private fun showResetDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_reset_title))
            .setMessage(getString(R.string.dialog_reset_message))
            .setPositiveButton(getString(R.string.btn_reset_confirm)) { _, _ ->
                performReset()
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private fun performReset() {
        // Service stoppen
        stopService(Intent(this, HotspotGuardService::class.java))
        stopService(Intent(this, ProtectionVpnService::class.java))

        // Alle gespeicherten Daten löschen
        prefs.clearAll()

        // Neu starten → landet im Setup
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun updateStatusCard(statusCard: View) {
        statusCard.visibility = View.VISIBLE
        val tvServiceStatus = statusCard.findViewById<TextView>(R.id.tv_service_status)
        val tvHotspotStatus = statusCard.findViewById<TextView>(R.id.tv_hotspot_blocked)
        val isRunning = HotspotGuardService.isRunning
        tvServiceStatus.text = if (isRunning)
            getString(R.string.service_active) else getString(R.string.service_inactive)
        tvServiceStatus.setTextColor(
            if (isRunning) getColor(R.color.status_active) else getColor(R.color.status_inactive)
        )
        tvHotspotStatus.text = getString(R.string.blocked_count, prefs.getBlockedCount())
    }

    private fun openDashboard() {
        prefs.resetFailedAttempts()
        val intent = Intent(this, DashboardActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
