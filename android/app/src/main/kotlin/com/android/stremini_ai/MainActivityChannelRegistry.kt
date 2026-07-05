package com.android.stremini_ai

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivityChannelRegistry(
    private val actions: Actions,
) {
    data class Actions(
        val hasOverlayPermission: () -> Boolean,
        val requestOverlayPermission: () -> Unit,
        val hasMicrophonePermission: () -> Boolean,
        val requestMicrophonePermission: () -> Unit,
        val startOverlayService: () -> Unit,
        val stopOverlayService: () -> Unit,
        val isKeyboardEnabled: () -> Boolean,
        val isKeyboardSelected: () -> Boolean,
        val openKeyboardSettings: () -> Unit,
        val showKeyboardPicker: () -> Unit,
        val openKeyboardSettingsActivity: () -> Unit,
        val setEventSink: (EventChannel.EventSink?) -> Unit,
        // Composio MCP
        val openComposioConnect: () -> Unit,
        val getComposioToken: () -> String?,
        val saveComposioToken: (String) -> Unit,
        val isComposioConnected: () -> Boolean,
        val getComposioMcpUrl: () -> String,
        // Composio service management
        val connectComposioService: (String) -> Unit,
        val disconnectComposioService: (String) -> Unit,
        val getConnectedServices: () -> Map<String, List<String>>,
        val executeComposioAutomation: (suspend (String) -> String)? = null,
        val automationScope: CoroutineScope? = null,
    )

    fun register(flutterEngine: FlutterEngine) {
        registerOverlayChannel(flutterEngine)
        registerKeyboardChannel(flutterEngine)
        registerComposioChannel(flutterEngine)
        // NOTE: The dead `stremini.chat.overlay/events` EventChannel was removed —
        // it shared the same `setEventSink` callback as the active
        // `stremini.composio/events` channel in MainActivity, causing the second
        // listener to clobber the first's sink. Dart only listens to
        // `stremini.composio/events`.
    }

    private fun registerOverlayChannel(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "stremini.chat.overlay")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasOverlayPermission" -> result.success(actions.hasOverlayPermission())
                    "requestOverlayPermission" -> { actions.requestOverlayPermission(); result.success(true) }
                    "hasMicrophonePermission" -> result.success(actions.hasMicrophonePermission())
                    "requestMicrophonePermission" -> { actions.requestMicrophonePermission(); result.success(true) }
                    "startOverlayService" -> { actions.startOverlayService(); result.success(true) }
                    "stopOverlayService" -> { actions.stopOverlayService(); result.success(true) }
                    else -> result.notImplemented()
                }
            }
    }

    private fun registerKeyboardChannel(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "stremini.keyboard")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isKeyboardEnabled" -> result.success(actions.isKeyboardEnabled())
                    "isKeyboardSelected" -> result.success(actions.isKeyboardSelected())
                    "openKeyboardSettings" -> { actions.openKeyboardSettings(); result.success(true) }
                    "showKeyboardPicker" -> { actions.showKeyboardPicker(); result.success(true) }
                    "openKeyboardSettingsActivity" -> {
                        runCatching { actions.openKeyboardSettingsActivity() }
                            .onSuccess { result.success(true) }
                            .onFailure {
                                Log.e("MainActivity", "Error opening keyboard settings", it)
                                result.error("ERROR", "Failed to open keyboard settings: ${it.message}", null)
                            }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    /** Composio MCP — bridge Flutter ↔ native token storage + Chrome Custom Tab */
    private fun registerComposioChannel(flutterEngine: FlutterEngine) {
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "stremini.composio")
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "openComposioConnect" -> {
                        actions.openComposioConnect()
                        result.success(true)
                    }
                    "getComposioToken" -> result.success(actions.getComposioToken())
                    "saveComposioToken" -> {
                        val token = call.argument<String>("token")
                        if (token.isNullOrBlank()) {
                            result.error("INVALID", "Token is required", null)
                        } else {
                            actions.saveComposioToken(token)
                            result.success(true)
                        }
                    }
                    "isComposioConnected" -> result.success(actions.isComposioConnected())
                    "getComposioMcpUrl" -> result.success(actions.getComposioMcpUrl())
                    "connectComposioService" -> {
                        val serviceId = call.argument<String>("serviceId") ?: ""
                        actions.connectComposioService(serviceId)
                        result.success(true)
                    }
                    "disconnectComposioService" -> {
                        val serviceId = call.argument<String>("serviceId") ?: ""
                        actions.disconnectComposioService(serviceId)
                        result.success(true)
                    }
                    "getConnectedServices" -> {
                        val services = actions.getConnectedServices()
                        // Convert Map<String, List<String>> to Map<String, Boolean> for Dart
                        val flat = services.mapValues { it.value.isNotEmpty() }
                        result.success(flat)
                    }
                    "executeAutomation" -> {
                        val instruction = call.argument<String>("instruction") ?: ""
                        val automationFn = actions.executeComposioAutomation
                        val scope = actions.automationScope
                        if (automationFn != null && scope != null) {
                            scope.launch {
                                try {
                                    val response = automationFn(instruction)
                                    result.success(response)
                                } catch (e: Exception) {
                                    result.error("AUTOMATION_ERROR", e.message, null)
                                }
                            }
                        } else {
                            result.error("NOT_AVAILABLE", "Automation not available", null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

}