package com.Android.stremini_ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import android.content.pm.PackageManager
import kotlinx.coroutines.launch
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MIC_REQUEST_CODE = 1001
        private const val COMPOSIO_PREFS = "composio_prefs"
        private const val KEY_COMPOSIO_TOKEN = "composio_token"
        // Opens Composio dashboard where user can get their API key
        private const val COMPOSIO_DASHBOARD_URL = "https://composio.dev/settings"
        private const val COMPOSIO_MCP_URL = "https://connect.composio.dev/mcp"
    }

    private val microphonePermissionRequestCode = MIC_REQUEST_CODE

    // ── Deep-link callback: Composio sends stremini://composio?provider=xxx&status=success ──────
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val data = intent.data ?: return
        if (data.scheme == "stremini" && data.host == "composio") {
            val provider = data.getQueryParameter("provider")
            val status = data.getQueryParameter("status")
            val code = data.getQueryParameter("code")

            Log.i(TAG, "Composio callback: provider=$provider, status=$status")

            // Notify the overlay service to refresh connected services
            val refreshIntent = Intent(this, ChatOverlayService::class.java).apply {
                action = "com.Android.stremini_ai.REFRESH_COMPOSIO"
                putExtra("provider", provider ?: "")
                putExtra("status", status ?: "")
            }
            startService(refreshIntent)

            // Notify Flutter via the composio event channel
            _composioEventSink?.success(mapOf(
                "event" to "connection_success",
                "serviceId" to (provider ?: ""),
                "status" to (status ?: "success")
            ))

            if (status == "success" || status == "connected" || code != null) {
                Toast.makeText(this, "Service connected! You can now use it in chat.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Flutter engine setup ─────────────────────────────────────────────────
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MainActivityChannelRegistry(
            actions = MainActivityChannelRegistry.Actions(
                hasOverlayPermission = ::hasOverlayPermission,
                requestOverlayPermission = ::requestOverlayPermissionSafe,
                hasMicrophonePermission = ::hasMicrophonePermission,
                requestMicrophonePermission = ::requestMicrophonePermission,
                startOverlayService = ::startOverlayServiceSafe,
                stopOverlayService = { stopService(Intent(this, ChatOverlayService::class.java)) },
                isKeyboardEnabled = ::isKeyboardEnabled,
                isKeyboardSelected = ::isKeyboardSelected,
                openKeyboardSettings = ::openKeyboardSettings,
                showKeyboardPicker = ::showKeyboardPicker,
                openKeyboardSettingsActivity = {
                    val intent = Intent(this, KeyboardSettingsActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                },
                openComposioConnect = ::openComposioConnect,
                getComposioToken = ::getComposioToken,
                saveComposioToken = ::saveComposioToken,
                isComposioConnected = ::isComposioConnected,
                getComposioMcpUrl = { COMPOSIO_MCP_URL },
                setEventSink = { sink -> _composioEventSink = sink },
                // Composio service management
                connectComposioService = { serviceId -> composioClient.connectService(serviceId) },
                disconnectComposioService = { serviceId ->
                    lifecycleScope.launch { composioClient.disconnectService(serviceId) }
                },
                getConnectedServices = {
                    // Return cached value synchronously to avoid ANR from runBlocking.
                    // The overlay service refreshes this cache periodically.
                    _cachedConnectedServices
                },
                executeComposioAutomation = { instruction ->
                    composioClient.executeAutomation(
                        instruction = instruction,
                        groqClient = null // Will use keyword-based fallback from MainActivity
                    ).getOrThrow()
                },
            )
        ).register(flutterEngine)
        registerOcrChannel(flutterEngine)
        registerComposioEventChannel(flutterEngine)
    }

    // ── Composio event channel (deep-link → Flutter) ────────────────────────
    private var _composioEventSink: io.flutter.plugin.common.EventChannel.EventSink? = null

    // Lazy ComposioClient for service management from MainActivity
    private val composioClient by lazy { ComposioClient(this) }
    // Cached connected services map (refreshed periodically to avoid runBlocking ANR)
    private var _cachedConnectedServices: Map<String, List<String>> = emptyMap()

    /** Refresh the cached connected-services map (call from a coroutine) */
    fun refreshConnectedServicesCache() {
        lifecycleScope.launch {
            _cachedConnectedServices = composioClient.getConnectedServices()
        }
    }

    private fun registerComposioEventChannel(flutterEngine: FlutterEngine) {
        io.flutter.plugin.common.EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            "stremini.composio/events"
        ).setStreamHandler(object : io.flutter.plugin.common.EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: io.flutter.plugin.common.EventChannel.EventSink?) {
                _composioEventSink = events
            }
            override fun onCancel(arguments: Any?) {
                _composioEventSink = null
            }
        })
    }

    // ── OCR channel ─────────────────────────────────────────────────────────
    private fun registerOcrChannel(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "stremini.ocr")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "extractTextFromImage" -> {
                        val path = call.argument<String>("path")
                        if (path.isNullOrBlank()) {
                            result.error("INVALID_PATH", "Image path is missing", null)
                        } else {
                            extractTextFromImage(path, result)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun extractTextFromImage(path: String, result: MethodChannel.Result) {
        try {
            val image = InputImage.fromFilePath(this, Uri.fromFile(java.io.File(path)))
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText -> result.success(visionText.text) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    result.success("")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing OCR", e)
            result.success("")
        }
    }

    // ── Composio actions ────────────────────────────────────────────────────

    /** Open Composio dashboard so user can get their API key */
    private fun openComposioConnect() {
        try {
            val tab = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
            tab.launchUrl(this, Uri.parse(COMPOSIO_DASHBOARD_URL))
        } catch (e: Exception) {
            Log.e(TAG, "Error opening Composio", e)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(COMPOSIO_DASHBOARD_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    /** Get saved Composio token */
    private fun getComposioToken(): String? {
        return EncryptedPrefs.getEncrypted(this, COMPOSIO_PREFS)
            .getString(KEY_COMPOSIO_TOKEN, null)
    }

    /** Save Composio Bearer token (called after Flutter exchanges auth code) */
    private fun saveComposioToken(token: String) {
        val prefs = EncryptedPrefs.getEncrypted(this, COMPOSIO_PREFS)
        if (token.isBlank()) prefs.remove(KEY_COMPOSIO_TOKEN)
        else prefs.putString(KEY_COMPOSIO_TOKEN, token)
        Log.i(TAG, "Composio token saved (encrypted)")
    }

    /** Check if Composio is connected */
    private fun isComposioConnected(): Boolean {
        return getComposioToken() != null
    }

    // ── Permission helpers ──────────────────────────────────────────────────

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun requestOverlayPermissionSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Toast.makeText(this, "Please enable 'Display over other apps'", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Overlay setting error", e)
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophonePermission() {
        if (hasMicrophonePermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            microphonePermissionRequestCode
        )
    }

    // ── Overlay service ────────────────────────────────────────────────────

    private fun startOverlayServiceSafe() {
        try {
            if (!hasOverlayPermission()) {
                requestOverlayPermissionSafe()
                return
            }
            val intent = Intent(this, ChatOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "Floating bubble activated", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Overlay start error", e)
            Toast.makeText(this, "Error starting service: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Keyboard methods ────────────────────────────────────────────────────

    private fun isKeyboardEnabled(): Boolean {
        return try {
            val imeManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            val enabledInputMethods = imeManager?.enabledInputMethodList ?: return false
            enabledInputMethods.any { it.packageName == packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking keyboard enabled", e)
            false
        }
    }

    private fun isKeyboardSelected(): Boolean {
        return try {
            val currentInputMethod = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD
            )
            currentInputMethod?.contains(packageName) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking keyboard selected", e)
            false
        }
    }

    private fun openKeyboardSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, "Find 'Stremini AI Keyboard' and enable it", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening keyboard settings", e)
            Toast.makeText(this, "Error opening settings: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showKeyboardPicker() {
        try {
            val imeManager = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
            imeManager?.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing keyboard picker", e)
            Toast.makeText(this, "Error showing picker: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Permission result callback ──────────────────────────────────────────

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == microphonePermissionRequestCode) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            Toast.makeText(
                this,
                if (granted) "Microphone permission granted" else "Microphone permission denied",
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}