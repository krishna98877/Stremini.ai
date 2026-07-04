package com.android.stremini_ai

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

class KeyboardSettingsCoordinator(private val context: Context) {
    companion object { private const val TAG = "KBSettingsCoordinator" }

    fun isKeyboardEnabled(packageName: String): Boolean {
        return try {
            val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imeManager?.enabledInputMethodList?.any { it.packageName == packageName } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking keyboard enabled", e)
            false
        }
    }

    fun isKeyboardSelected(packageName: String): Boolean {
        return try {
            val currentInputMethod = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            currentInputMethod?.contains(packageName) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking keyboard selected", e)
            false
        }
    }

    fun openKeyboardSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening keyboard settings", e)
        }
    }

    fun showInputMethodPicker() {
        try {
            val imeManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imeManager?.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing input method picker", e)
        }
    }

    fun saveTheme(theme: String) {
        context.getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("theme", theme)
            .apply()
    }
}
