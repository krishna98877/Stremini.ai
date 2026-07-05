package com.android.stremini_ai

import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class KeyboardSettingsActivity : AppCompatActivity() {

    private lateinit var coordinator: KeyboardSettingsCoordinator
    private lateinit var toggleKeyboard: Switch
    private lateinit var btnEnableKeyboard: Button
    private lateinit var btnSelectKeyboard: Button
    private lateinit var themeGroup: RadioGroup
    private lateinit var layoutInfo: TextView
    private lateinit var btnConnectComposio: Button
    private lateinit var tvComposioStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_keyboard_settings)
        coordinator = KeyboardSettingsCoordinator(this)

        initViews()
        setupListeners()
        updateStatus()
    }

    private fun initViews() {
        toggleKeyboard = findViewById(R.id.toggle_keyboard)
        btnEnableKeyboard = findViewById(R.id.btn_enable_keyboard)
        btnSelectKeyboard = findViewById(R.id.btn_select_keyboard)
        themeGroup = findViewById(R.id.theme_group)
        layoutInfo = findViewById(R.id.layout_info)
        btnConnectComposio = findViewById(R.id.btn_connect_composio)
        tvComposioStatus = findViewById(R.id.tv_composio_mcp_status)

        // Back button
        findViewById<ImageButton>(R.id.btn_back)?.setOnClickListener {
            finish()
        }
    }

    private fun setupListeners() {
        toggleKeyboard.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !isKeyboardEnabled()) {
                Toast.makeText(
                    this,
                    "Please enable Stremini AI Keyboard in settings first",
                    Toast.LENGTH_SHORT
                ).show()
                toggleKeyboard.isChecked = false
                openKeyboardSettings()
            }
        }

        btnEnableKeyboard.setOnClickListener {
            openKeyboardSettings()
        }

        btnSelectKeyboard.setOnClickListener {
            showInputMethodPicker()
        }

        btnConnectComposio.setOnClickListener {
            openComposioLogin()
        }

        // Theme selection
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.theme_dark -> applyTheme("dark")
                R.id.theme_blue -> applyTheme("blue")
                R.id.theme_purple -> applyTheme("purple")
            }
        }
    }

    private fun updateStatus() {
        val enabled = isKeyboardEnabled()
        val selected = isKeyboardSelected()

        toggleKeyboard.isChecked = enabled && selected
        
        layoutInfo.text = buildString {
            append("Status:\n")
            append("• Enabled: ${if (enabled) "✓ Yes" else "✗ No"}\n")
            append("• Selected: ${if (selected) "✓ Yes" else "✗ No"}\n")
            append("\nFeatures:\n")
            append("• Smart Text Completion\n")
            append("• Grammar Correction\n")
            append("• Multi-language Translation\n")
            append("• Tone Adjustment\n")
            append("• Text Expansion\n")
            append("• Emoji Suggestions\n")
        }
    }

    private fun isKeyboardEnabled(): Boolean = coordinator.isKeyboardEnabled(packageName)

    private fun isKeyboardSelected(): Boolean = coordinator.isKeyboardSelected(packageName)

    private fun openKeyboardSettings() {
        coordinator.openKeyboardSettings()
        Toast.makeText(this, "Find 'Stremini AI Keyboard' and enable it", Toast.LENGTH_LONG).show()
    }

    private fun showInputMethodPicker() = coordinator.showInputMethodPicker()

    private fun openComposioLogin() {
        val composioAuthUrl = "https://composio.dev/login"
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(composioAuthUrl))
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateComposioStatus() {
        val prefs = EncryptedPrefs.getEncrypted(this, "stremini_prefs")
        val token = prefs.getString("composio_token", null)
        if (token != null) {
            tvComposioStatus.text = "Status: Connected ✓"
            tvComposioStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            btnConnectComposio.text = "Reconnect Automations"
        } else {
            tvComposioStatus.text = "Link your apps (Gmail, Notion, Slack) to enable AI automations."
            tvComposioStatus.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            btnConnectComposio.text = "Connect Automations"
        }
    }

    private fun applyTheme(theme: String) {
        coordinator.saveTheme(theme)
        Toast.makeText(this, "Theme changed to $theme", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateComposioStatus()
    }
}
