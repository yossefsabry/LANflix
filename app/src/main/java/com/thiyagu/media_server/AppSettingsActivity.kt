package com.thiyagu.media_server

import android.content.res.Configuration
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.thiyagu.media_server.data.UserPreferences
import com.thiyagu.media_server.utils.PinAuth
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AppSettingsActivity : AppCompatActivity() {

    private val userPreferences: UserPreferences by inject()
    private var isServerAuthEnabled: Boolean = false
    private var hasServerPin: Boolean = false
    private var currentTheme: String = "system"
    private var themeSwitch: SwitchMaterial? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_settings)
        ThemeTransitionController.attachIfPending(this)

        val btnBack = findViewById<View>(R.id.btn_back)
        btnBack?.setOnClickListener { finish() }

        // Setup Options
        setupOption(R.id.opt_video_quality, R.drawable.ic_video, "Video Quality", "Auto (Recommended)")
        setupOption(R.id.opt_notifications, R.drawable.ic_settings, "Notifications", getNotificationStatus()) {
            openAppNotificationSettings()
        }
        setupOption(R.id.opt_autoplay, R.drawable.ic_video, "Auto-play Next", "On")
        
        // Theme Option with Logic
        setupOption(R.id.opt_theme, R.drawable.ic_settings, "Theme", "System Default") {
            showThemeSelectionDialog()
        }
        themeSwitch = findViewById<View>(R.id.opt_theme)?.findViewById(R.id.toggle)

        // History Retention Option
        setupOption(R.id.opt_history_retention, R.drawable.ic_settings, "Resume History", "10 Days") {
            showHistoryRetentionDialog()
        }
        
        // Server Name Option
        setupOption(R.id.opt_server_name, R.drawable.ic_settings, "Server Name", "LANflix Server") {
            showServerNameDialog()
        }

        // Server PIN Option
        setupOption(R.id.opt_server_pin, R.drawable.ic_lock, "Server PIN", "Off") {
            showServerPinDialog()
        }
        
        // Observe Theme changes
        lifecycleScope.launch {
            userPreferences.themeFlow.collect { theme ->
                currentTheme = theme
                val title = when(theme) {
                    "light" -> "Light"
                    "dark" -> "Dark"
                    else -> "System Default"
                }
                updateOptionSubtitle(R.id.opt_theme, title)
                updateThemeSwitch(isDarkModeFor(theme))
            }
        }

        // Observe History Retention changes
        lifecycleScope.launch {
            userPreferences.historyRetentionFlow.collect { days ->
                val subtitle = if (days == -1) "Never" else "$days Days"
                updateOptionSubtitle(R.id.opt_history_retention, subtitle)
            }
        }
        
        // Observe Server Name changes
        lifecycleScope.launch {
            userPreferences.serverNameFlow.collect { name ->
                updateOptionSubtitle(R.id.opt_server_name, name)
            }
        }

        // Observe Server PIN changes
        lifecycleScope.launch {
            combine(
                userPreferences.serverAuthEnabledFlow,
                userPreferences.serverAuthPinHashFlow
            ) { enabled, hash ->
                Pair(enabled, !hash.isNullOrBlank())
            }.collect { (enabled, hasPin) ->
                isServerAuthEnabled = enabled
                hasServerPin = hasPin
                val subtitle = if (enabled && hasPin) "On" else "Off"
                updateOptionSubtitle(R.id.opt_server_pin, subtitle)
            }
        }
    }
    
    // ... (onResume)

    private fun showServerNameDialog() {
        val input = android.widget.EditText(this)
        input.hint = "Enter Server Name"
        val layout = android.widget.FrameLayout(this)
        layout.setPadding(50, 20, 50, 20)
        layout.addView(input)
        
        // Pre-fill current name
        lifecycleScope.launch {
            userPreferences.serverNameFlow.collect { name ->
                if (input.text.isEmpty()) input.setText(name)
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set Server Name")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    lifecycleScope.launch {
                        userPreferences.saveServerName(newName)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showServerPinDialog() {
        if (isServerAuthEnabled && hasServerPin) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Server PIN")
                .setItems(arrayOf("Change PIN", "Disable PIN")) { _, which ->
                    when (which) {
                        0 -> showPinSetupDialog(title = "Change Server PIN")
                        1 -> disableServerPin()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            showPinSetupDialog(title = "Enable Server PIN")
        }
    }

    private fun showPinSetupDialog(title: String) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 20, 50, 10)
        }

        val pinInput = android.widget.EditText(this).apply {
            hint = "Enter PIN (4-8 digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
        }

        val confirmInput = android.widget.EditText(this).apply {
            hint = "Confirm PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
        }

        layout.addView(pinInput)
        layout.addView(confirmInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val pin = pinInput.text.toString().trim()
                val confirm = confirmInput.text.toString().trim()
                if (!PinAuth.isValidPin(pin)) {
                    Toast.makeText(this, "PIN must be 4-8 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (pin != confirm) {
                    Toast.makeText(this, "PINs do not match", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val salt = PinAuth.generateSalt()
                val hash = PinAuth.hashPin(pin, salt)
                lifecycleScope.launch {
                    userPreferences.saveServerAuthPin(hash, salt)
                    userPreferences.saveServerAuthEnabled(true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disableServerPin() {
        lifecycleScope.launch {
            userPreferences.saveServerAuthEnabled(false)
            userPreferences.clearServerAuthPin()
        }
    }

    
    override fun onResume() {
        super.onResume()
        // Dynamically update notification status
        updateOptionSubtitle(R.id.opt_notifications, getNotificationStatus())
        // Start discovery if needed, but not relevant here
    }
    
    private fun getNotificationStatus(): String {
        return if (NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            "Enabled"
        } else {
            "Disabled"
        }
    }
    
    private fun showThemeSelectionDialog() {
        val themes = arrayOf("System Default", "Light", "Dark")
        val values = arrayOf("system", "light", "dark")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Choose Theme")
            .setItems(themes) { _, which ->
                applyThemeSelection(values[which])
            }
            .show()
    }

    private fun applyThemeSelection(selectedTheme: String) {
        if (selectedTheme == currentTheme) return
        val shouldAnimate = isDarkModeFor(selectedTheme) != isDarkModeFor(currentTheme)
        if (shouldAnimate) {
            ThemeTransitionController.prepareTransition(this)
        }
        lifecycleScope.launch {
            userPreferences.saveTheme(selectedTheme)
        }
    }

    private fun updateThemeSwitch(isDark: Boolean) {
        val toggle = themeSwitch ?: return
        toggle.setOnCheckedChangeListener(null)
        toggle.isChecked = isDark
        toggle.setOnCheckedChangeListener { _, checked ->
            val targetTheme = if (checked) "dark" else "light"
            applyThemeSelection(targetTheme)
        }
    }

    private fun isDarkModeFor(theme: String): Boolean {
        return when (theme) {
            "dark" -> true
            "light" -> false
            else -> {
                val mode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                mode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    private fun showHistoryRetentionDialog() {
        val options = arrayOf("5 Days", "10 Days", "20 Days", "30 Days", "Never")
        val values = arrayOf(5, 10, 20, 30, -1)
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Keep Resume History For")
            .setItems(options) { _, which ->
                val selectedDays = values[which]
                lifecycleScope.launch {
                    userPreferences.saveHistoryRetention(selectedDays)
                    // Optional: Prune immediately if user reduced retention? 
                    // For now, let the next VideoPlayer launch handle pruning.
                }
            }
            .show()
    }
    
    private fun openAppNotificationSettings() {
        try {
            val intent = android.content.Intent()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                intent.action = android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                intent.action = android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                intent.data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open notification settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOptionSubtitle(viewId: Int, subtitle: String) {
        val view = findViewById<View>(viewId) ?: return
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        subtitleView?.text = subtitle
    }

    private fun setupOption(viewId: Int, iconRes: Int, title: String, subtitle: String, onClick: (() -> Unit)? = null) {
        val view = findViewById<View>(viewId) ?: return
        
        val iconView = view.findViewById<ImageView>(R.id.icon)
        val titleView = view.findViewById<TextView>(R.id.title)
        val subtitleView = view.findViewById<TextView>(R.id.subtitle)
        
        iconView?.setImageResource(iconRes)
        titleView?.text = title
        subtitleView?.text = subtitle
        
        view.setOnClickListener {
            if (onClick != null) {
                onClick()
            } else {
                Toast.makeText(this, "Opening $title settings...", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
