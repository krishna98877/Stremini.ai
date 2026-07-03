package com.Android.stremini_ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

class KeyboardSettingsCoordinator(private val context: Context) {
    fun isKeyboardEnabled(packageName: String): Boolean {
        val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        return imeManager.enabledInputMethodList.any { it.packageName == packageName }
    }

    fun isKeyboardSelected(packageName: String): Boolean {
        val currentInputMethod = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        )
        return currentInputMethod?.contains(packageName) == true
    }

    fun openKeyboardSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun showInputMethodPicker() {
        val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imeManager.showInputMethodPicker()
    }

    fun saveTheme(theme: String) {
        context.getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("theme", theme)
            .apply()
    }
}
