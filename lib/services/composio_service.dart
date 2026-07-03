import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import '../core/config/env_config.dart';

/// Service that manages Composio MCP integration:
/// - Opens Composio login via native Chrome Custom Tab
/// - Listens for deep-link auth code callback
/// - Exchanges auth code for Bearer token via backend
/// - Stores token and provides it for automation requests
class ComposioService {
  static const MethodChannel _channel =
      MethodChannel('stremini.composio');
  static const EventChannel _eventChannel =
      EventChannel('stremini.composio/events');

  static const String _tokenKey = 'composio_token';
  static const String _connectedKey = 'composio_connected';

  StreamSubscription? _eventSub;
  String? _cachedToken;
  bool _isConnected = false;

  String? get token => _cachedToken;
  bool get isConnected => _isConnected;

  /// Initialize — restore saved connection state and listen for deep-link events.
  /// On Android the token is read from native EncryptedPrefs (Kotlin side)
  /// so it is never stored in plain-text SharedPreferences.
  Future<void> initialize() async {
    final prefs = await SharedPreferences.getInstance();
    _isConnected = prefs.getBool(_connectedKey) ?? false;

    // On Android, token lives in native EncryptedPrefs — never in plain SP.
    if (Platform.isAndroid) {
      try {
        _cachedToken = await _channel.invokeMethod<String?>('getComposioToken');
        if (_cachedToken != null) _isConnected = true;
      } catch (_) {}
    } else {
      _cachedToken = prefs.getString(_tokenKey);
    }

    // Listen for deep-link auth code events from native
    _eventSub = _eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map && event['event'] == 'auth_code') {
        final code = event['code'] as String?;
        if (code != null) {
          _exchangeCodeForToken(code);
        }
      }
    });
  }

  void dispose() {
    _eventSub?.cancel();
  }

  /// Open Composio login page in Chrome Custom Tab (native).
  Future<void> openConnectPage() async {
    if (!Platform.isAndroid) {
      debugPrint('Composio: only supported on Android');
      return;
    }
    try {
      await _channel.invokeMethod('openComposioConnect');
    } catch (e) {
      debugPrint('Composio: error opening connect page — $e');
      rethrow;
    }
  }

  /// Exchange the auth code (received via deep-link) for a Bearer token.
  Future<void> _exchangeCodeForToken(String authCode) async {
    try {
      final response = await http.post(
        Uri.parse('${EnvConfig.baseUrl}/composio/exchange'),
        headers: {'Content-Type': 'application/json'},
        body: jsonEncode({'authCode': authCode}),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        final token = data['token'] as String?;
        if (token != null && token.isNotEmpty) {
          await _saveToken(token);
          debugPrint('Composio: token exchanged and saved');
        }
      } else {
        debugPrint('Composio: exchange failed — ${response.statusCode}');
      }
    } catch (e) {
      debugPrint('Composio: exchange error — $e');
    }
  }

  /// Save token on native side (encrypted) and cache connection state.
  /// On Android the token is NEVER written to plain-text SharedPreferences.
  Future<void> _saveToken(String token) async {
    _cachedToken = token;
    _isConnected = true;

    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_connectedKey, true);

    // Native side stores the token in EncryptedPrefs (AES-GCM via Keystore)
    if (Platform.isAndroid) {
      try {
        await _channel.invokeMethod('saveComposioToken', {'token': token});
      } catch (_) {}
    }
  }

  /// Disconnect Composio — clear all stored tokens.
  Future<void> disconnect() async {
    _cachedToken = null;
    _isConnected = false;

    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_tokenKey);
    await prefs.setBool(_connectedKey, false);

    // Also clear from native encrypted storage
    if (Platform.isAndroid) {
      try {
        await _channel.invokeMethod('saveComposioToken', {'token': ''});
      } catch (_) {}
    }
  }

  /// Send an automation instruction via Composio MCP.
  /// Returns the AI response string, or an error message.
  Future<String> sendAutomationInstruction(String instruction) async {
    if (_cachedToken == null) {
      return 'Composio is not connected. Go to Settings → Connect Automations first.';
    }
    try {
      final response = await http.post(
        Uri.parse('${EnvConfig.baseUrl}/composio/automate'),
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer $_cachedToken',
        },
        body: jsonEncode({
          'instruction': instruction,
          'mcpUrl': 'https://connect.composio.dev/mcp',
        }),
      );

      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        return (data['response'] ?? data['message'] ?? 'Done.').toString();
      }
      if (response.statusCode == 401) {
        // Token expired — disconnect
        await disconnect();
        return 'Composio session expired. Please reconnect in Settings.';
      }
      return 'Automation failed. Please try again.';
    } catch (e) {
      return 'Network error. Please check your connection and try again.';
    }
  }

  /// Get the MCP URL (hardcoded, for reference).
  Future<String> getMcpUrl() async {
    if (Platform.isAndroid) {
      try {
        return await _channel.invokeMethod('getComposioMcpUrl') as String? ??
            'https://connect.composio.dev/mcp';
      } catch (_) {}
    }
    return 'https://connect.composio.dev/mcp';
  }
}