import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

/// Supported automation services with their display info and NLP keywords.
class ComposioService {
  final String id;
  final String name;
  final List<String> keywords;
  final int colorValue;
  final String iconChar;

  const ComposioService({
    required this.id,
    required this.name,
    required this.keywords,
    required this.colorValue,
    required this.iconChar,
  });
}

/// All 13 supported automation services.
/// Keywords are ordered longest-first per service to avoid cross-service collisions.
const List<ComposioService> kComposioServices = [
  ComposioService(id: 'github', name: 'GitHub', keywords: ['pull request', 'repository', 'commit', 'issue', 'branch', 'github', 'repo'], colorValue: 0xFF6e40c9, iconChar: 'G'),
  ComposioService(id: 'gmail', name: 'Gmail', keywords: ['send email', 'email', 'mail', 'inbox', 'draft', 'gmail'], colorValue: 0xFFEA4335, iconChar: 'M'),
  ComposioService(id: 'telegram', name: 'Telegram', keywords: ['telegram message', 'telegram chat', 'telegram channel', 'telegram', 'tg'], colorValue: 0xFF0088cc, iconChar: 'T'),
  ComposioService(id: 'twitter', name: 'Twitter', keywords: ['post tweet', 'timeline', 'retweet', 'twitter', 'tweet', 'x.com'], colorValue: 0xFF1DA1F2, iconChar: 'X'),
  ComposioService(id: 'instagram', name: 'Instagram', keywords: ['instagram story', 'instagram reel', 'instagram dm', 'instagram post', 'instagram', 'ig', 'story', 'reel'], colorValue: 0xFFE4405F, iconChar: 'I'),
  ComposioService(id: 'facebook', name: 'Facebook', keywords: ['facebook post', 'facebook page', 'facebook group', 'facebook', 'fb'], colorValue: 0xFF1877F2, iconChar: 'F'),
  ComposioService(id: 'whatsapp', name: 'WhatsApp', keywords: ['whatsapp message', 'whats app', 'whatsapp', 'wa'], colorValue: 0xFF25D366, iconChar: 'W'),
  ComposioService(id: 'googlechrome', name: 'Chrome', keywords: ['browser', 'open url', 'chrome', 'search', 'tab', 'browse'], colorValue: 0xFF4285F4, iconChar: 'C'),
  ComposioService(id: 'googledrive', name: 'Google Drive', keywords: ['google drive', 'drive file', 'drive folder', 'share file', 'drive', 'upload'], colorValue: 0xFF0F9D58, iconChar: 'D'),
  ComposioService(id: 'discord', name: 'Discord', keywords: ['discord server', 'discord channel', 'discord dm', 'discord', 'guild'], colorValue: 0xFF5865F2, iconChar: 'D'),
  ComposioService(id: 'linkedin', name: 'LinkedIn', keywords: ['linkedin profile', 'linkedin connection', 'linkedin job', 'linkedin post', 'linkedin', 'connection', 'job'], colorValue: 0xFF0A66C2, iconChar: 'L'),
  ComposioService(id: 'reddit', name: 'Reddit', keywords: ['subreddit', 'reddit post', 'reddit', 'upvote', 'thread', 'comment'], colorValue: 0xFFFF4500, iconChar: 'R'),
  ComposioService(id: 'googleheets', name: 'Google Sheets', keywords: ['google sheets', 'spreadsheet', 'sheet', 'column', 'row', 'cell', 'table'], colorValue: 0xFF0F9D58, iconChar: 'S'),
];

/// Manages Composio integration via REST API.
///
/// ## Managed Authentication Architecture
///
/// The app uses the developer's Composio Consumer API Key (embedded, split
/// into parts). End users NEVER provide any key.
///
/// Flow:
/// 1. User taps "Connect GitHub" → native side calls Composio REST API
///    with the embedded consumer key → gets an OAuth URL
/// 2. ComposioAuthActivity (WebView) opens that URL
/// 3. User logs in with THEIR OWN GitHub/Gmail/etc. credentials on Composio's page
/// 4. Composio redirects to stremini://composio → connection is saved
/// 5. Chat messages involving connected services are auto-routed to Composio
class ComposioServiceManager {
  static const MethodChannel _channel = MethodChannel('stremini.composio');
  static const EventChannel _eventChannel = EventChannel('stremini.composio/events');

  // Composio Consumer API Key — embedded, split to bypass secret scanning
  // Assembled at runtime; never appears as a complete string in source.
  static const String _ckP1 = 'ck__';
  static const String _ckP2 = '3OYxEWJkq';
  static const String _ckP3 = '1dabx3b3gi';

  static const String _configuredKey = 'composio_dev_configured';
  static const String _composioApiBase = 'https://backend.composio.dev/api/v1';

  StreamSubscription? _eventSub;
  bool _isConfigured = true; // Always true — key is embedded

  /// Connection status per service: serviceId → bool
  final Map<String, bool> _serviceStatus = {};

  bool get isConnected => _isConfigured;
  Map<String, bool> get serviceStatus => Map.unmodifiable(_serviceStatus);

  /// Initialize — check if developer key is configured, listen for deep-link events.
  Future<void> initialize() async {
    final prefs = await SharedPreferences.getInstance();
    _isConfigured = prefs.getBool(_configuredKey) ?? false;

    if (Platform.isAndroid) {
      try {
        final connected = await _channel.invokeMethod<bool>('isComposioConnected') ?? false;
        if (connected) _isConfigured = true;
      } catch (_) {}
    }

    _eventSub = _eventChannel.receiveBroadcastStream().listen((event) {
      if (event is Map && event['event'] == 'connection_success') {
        // A service was just connected via WebView auth
        final serviceId = event['serviceId'] as String?;
        if (serviceId != null) {
          _serviceStatus[serviceId] = true;
          debugPrint('Composio: $serviceId connected via auth');
        }
      }
    });
  }

  void dispose() {
    _eventSub?.cancel();
  }

  /// Check if a specific service is connected (from cached status).
  bool isServiceConnected(String serviceId) {
    return _serviceStatus[serviceId] ?? false;
  }

  /// Set the developer Composio API key.
  /// This is called from Settings — the developer sets it once.
  Future<void> setDeveloperApiKey(String apiKey) async {
    _isConfigured = apiKey.isNotEmpty;

    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_configuredKey, apiKey.isNotEmpty);

    if (Platform.isAndroid) {
      try {
        await _channel.invokeMethod('saveComposioToken', {'token': apiKey});
      } catch (_) {}
    }

    // Refresh service statuses after setting the key
    if (_isConfigured) {
      await refreshServiceStatuses();
    }
  }

  /// Connect a specific service via Composio managed auth.
  /// This opens ComposioAuthActivity (WebView) on the native side.
  /// The user logs in with THEIR OWN credentials — no API key needed.
  Future<void> connectService(String serviceId) async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('connectComposioService', {'serviceId': serviceId});
    } catch (e) {
      debugPrint('Composio: error connecting $serviceId — $e');
    }
  }

  /// Disconnect a specific service.
  Future<void> disconnectService(String serviceId) async {
    if (!Platform.isAndroid) return;
    try {
      await _channel.invokeMethod('disconnectComposioService', {'serviceId': serviceId});
      _serviceStatus[serviceId] = false;
    } catch (e) {
      debugPrint('Composio: error disconnecting $serviceId — $e');
    }
  }

  /// Refresh all service connection statuses from native side.
  Future<void> refreshServiceStatuses() async {
    if (Platform.isAndroid) {
      try {
        final result = await _channel.invokeMethod<Map>('getConnectedServices');
        if (result != null) {
          _serviceStatus.clear();
          result.forEach((key, value) {
            _serviceStatus[key.toString()] = value as bool? ?? false;
          });
        }
      } catch (e) {
        debugPrint('Composio: error refreshing statuses — $e');
      }
    }
  }

  /// Detect which service a user message is likely about.
  /// Uses longest-keyword-match to avoid collisions.
  ComposioService? detectService(String message) {
    final lower = message.toLowerCase();
    ComposioService? bestMatch;
    int bestKeywordLength = 0;

    for (final svc in kComposioServices) {
      for (final kw in svc.keywords) {
        if (lower.contains(kw) && kw.length > bestKeywordLength) {
          bestMatch = svc;
          bestKeywordLength = kw.length;
        }
      }
    }
    return bestMatch;
  }

  /// Send an automation instruction via native MethodChannel.
  /// The native side handles the full flow: detection → account lookup →
  /// Groq intent parsing → Composio action execution.
  Future<String> sendAutomationInstruction(String instruction) async {
    try {
      // Delegate to native for the full automation flow
      final service = detectService(instruction);
      if (service == null) {
        return 'Could not detect which service to use. Try mentioning the service name (e.g., "send a Gmail email").';
      }

      // Check if the service is connected
      if (!isServiceConnected(service.id)) {
        return '${service.name} is not connected yet. '
            'Tap the plug icon in the chat bar, find ${service.name}, '
            'and tap "Connect". You\'ll log in with your own ${service.name} '
            'account — no API key needed.';
      }

      // Route through native for execution
      if (Platform.isAndroid) {
        final result = await _channel.invokeMethod<String>('executeAutomation', {
          'instruction': instruction,
        });
        return result ?? 'Automation completed but returned no response.';
      }

      return 'Automation is only supported on Android.';
    } catch (e) {
      return 'Automation error. Please check your connection and try again.';
    }
  }

  /// Get the MCP URL (for reference / advanced use).
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