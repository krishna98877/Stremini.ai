package com.android.stremini_ai

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
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
                action = "com.android.stremini_ai.REFRESH_COMPOSIO"
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
                refreshConnectedServicesCache()
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
                getComposioMcpUrl = ::getComposioMcpUrl,
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
                        groqClient = groqClientForAutomation
                    ).getOrThrow()
                },
                automationScope = lifecycleScope,
            )
        ).register(flutterEngine)
        registerOcrChannel(flutterEngine)
        registerComposioEventChannel(flutterEngine)
    }

    // ── Composio event channel (deep-link → Flutter) ────────────────────────
    private var _composioEventSink: io.flutter.plugin.common.EventChannel.EventSink? = null

    // Lazy ComposioClient for service management from MainActivity
    private val composioClient by lazy { ComposioClient(this) }
    // Lazy GroqClient for automation intent parsing from Flutter channel
    private val groqClientForAutomation by lazy { GroqClient(this) }
    // Cached connected services map (refreshed periodically to avoid runBlocking ANR)
    private var _cachedConnectedServices: Map<String, List<String>> = emptyMap()

    override fun onResume() {
        super.onResume()
        refreshConnectedServicesCache()
    }

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

        // Listen for 401-triggered local disconnects from ComposioClient
        // and forward them to Flutter so the Dart-side cache stays in sync.
        // 3-arg overload with RECEIVER_NOT_EXPORTED is required on targetSdk >= 34
        // (Android 14+); 2-arg overload throws SecurityException AND fails lintVitalRelease.
        val disconnectFilter = IntentFilter("com.android.stremini_ai.SERVICE_DISCONNECTED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(_disconnectReceiver, disconnectFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(_disconnectReceiver, disconnectFilter)
        }
    }

    private val _disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val serviceId = intent?.getStringExtra("serviceId") ?: return
            _composioEventSink?.success(mapOf(
                "event" to "connection_lost",
                "serviceId" to serviceId,
                "status" to "disconnected"
            ))
            refreshConnectedServicesCache()
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(_disconnectReceiver) } catch (_: Exception) {}
        super.onDestroy()
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
                    result.error("OCR_FAILED", e.localizedMessage ?: "OCR failed", null)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error preparing OCR", e)
            result.error("OCR_PREP_FAILED", e.localizedMessage ?: "Failed to load image", null)
        }
    }

    // ── Composio actions (delegated to embedded ComposioClient) ──────

    /** Always true — consumer key is embedded */
    private fun isComposioConnected(): Boolean = composioClient.isConfigured()

    /** No-op — key is embedded, users don't set it */
    private fun getComposioToken(): String? = null

    /** No-op — key is embedded */
    private fun saveComposioToken(token: String) {}

    /** No-op — users connect per-service, not at dashboard level */
    private fun openComposioConnect() {}

    /** MCP URL constant */
    private fun getComposioMcpUrl(): String = "https://connect.composio.dev/mcp"

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