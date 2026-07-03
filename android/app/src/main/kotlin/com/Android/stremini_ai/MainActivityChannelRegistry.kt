package com.Android.stremini_ai

import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
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
    )

    fun register(flutterEngine: FlutterEngine) {
        registerOverlayChannel(flutterEngine)
        registerKeyboardChannel(flutterEngine)
        registerComposioChannel(flutterEngine)
        registerEventChannel(flutterEngine)
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
                    else -> result.notImplemented()
                }
            }
    }

    private fun registerEventChannel(flutterEngine: FlutterEngine) {
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, "stremini.chat.overlay/events")
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) = actions.setEventSink(events)
                override fun onCancel(arguments: Any?) = actions.setEventSink(null)
            })
    }
}