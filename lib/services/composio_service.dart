import 'dart:async';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

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
  ComposioService(id: 'github', name: 'GitHub', keywords: ['pull request', 'repository', 'commit', 'issue', 'branch', 'github', 'repo'], colorValue: 0xFF181717, iconChar: 'G'),
  ComposioService(id: 'gmail', name: 'Gmail', keywords: ['send email', 'email', 'mail', 'inbox', 'draft', 'gmail'], colorValue: 0xFFEA4335, iconChar: 'M'),
  ComposioService(id: 'whatsapp', name: 'WhatsApp', keywords: ['whatsapp message', 'whats app', 'whatsapp', 'wa'], colorValue: 0xFF25D366, iconChar: 'W'),
  ComposioService(id: 'instagram', name: 'Instagram', keywords: ['instagram dm', 'instagram message', 'instagram', 'ig'], colorValue: 0xFFE4405F, iconChar: 'I'),
  ComposioService(id: 'facebook', name: 'Facebook', keywords: ['facebook post', 'facebook page', 'facebook', 'fb'], colorValue: 0xFF1877F2, iconChar: 'F'),
  ComposioService(id: 'googledrive', name: 'Google Drive', keywords: ['google drive', 'drive file', 'drive folder', 'drive', 'upload'], colorValue: 0xFF0F9D58, iconChar: 'D'),
  ComposioService(id: 'discord', name: 'Discord', keywords: ['discord server', 'discord channel', 'discord dm', 'discord'], colorValue: 0xFF5865F2, iconChar: 'D'),
  ComposioService(id: 'linkedin', name: 'LinkedIn', keywords: ['linkedin profile', 'linkedin post', 'linkedin', 'connection', 'job'], colorValue: 0xFF0A66C2, iconChar: 'L'),
  ComposioService(id: 'reddit', name: 'Reddit', keywords: ['subreddit', 'reddit post', 'reddit', 'upvote'], colorValue: 0xFFFF4500, iconChar: 'R'),
  ComposioService(id: 'googlesheets', name: 'Google Sheets', keywords: ['google sheets', 'spreadsheet', 'sheet', 'column', 'row', 'cell'], colorValue: 0xFF0F9D58, iconChar: 'S'),
  ComposioService(id: 'youtube', name: 'YouTube', keywords: ['youtube', 'youtube video', 'youtube channel', 'upload video', 'subscribe'], colorValue: 0xFFFF0000, iconChar: 'Y'),
];

/// Manages Composio integration via REST API.
///
/// ## Managed Authentication Architecture
///
/// The app uses the developer's Composio Consumer API Key (injected via
/// BuildConfig from local.properties / env var). End users NEVER provide any key.
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

  // The Composio consumer key is embedded on the native Kotlin side.
  // The Dart side delegates all Composio operations via MethodChannel.

  StreamSubscription? _eventSub;
  bool _isConfigured = true; // Key is embedded natively; always true for managed auth

  /// Connection status per service: serviceId → bool
  final Map<String, bool> _serviceStatus = {};

  bool get isConnected => _isConfigured;
  Map<String, bool> get serviceStatus => Map.unmodifiable(_serviceStatus);

  /// Initialize — pull persisted connection state from native, then listen
  /// for live deep-link events.
  Future<void> initialize() async {
    if (Platform.isAndroid) {
      try {
        final connected = await _channel.invokeMethod<bool>('isComposioConnected') ?? false;
        if (connected) _isConfigured = true;
      } catch (_) {}

      // Pull the real per-service connection map from native so that
      // services connected in a previous session are recognised immediately
      // instead of appearing disconnected until the user re-connects.
      await refreshServiceStatuses();
    }

    _eventSub = _eventChannel.receiveBroadcastStream().listen(
      (event) {
        if (event is Map) {
          final eventType = event['event'] as String?;
          if (eventType == 'connection_success') {
            // A service was just connected via WebView auth
            final serviceId = event['serviceId'] as String?;
            if (serviceId != null) {
              _serviceStatus[serviceId] = true;
              debugPrint('Composio: $serviceId connected via auth');
            }
          } else if (eventType == 'connection_lost') {
            // A 401 triggered a local disconnect on the native side.
            // Update the Dart cache so the UI and next automation check
            // immediately reflect reality without waiting for a restart.
            final serviceId = event['serviceId'] as String?;
            if (serviceId != null) {
              _serviceStatus[serviceId] = false;
              debugPrint('Composio: $serviceId disconnected (session expired)');
            }
          }
        }
      },
      onError: (e) => debugPrint('Composio event stream error: $e'),
    );
  }

  void dispose() {
    _eventSub?.cancel();
  }

  /// Check if a specific service is connected (from cached status).
  bool isServiceConnected(String serviceId) {
    return _serviceStatus[serviceId] ?? false;
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
      // Don't optimistically update — the EventChannel will send connection_lost
      // when the server confirms the disconnect.
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

      // Route through native for execution — Kotlin side handles connection check
      if (Platform.isAndroid) {
        final result = await _channel.invokeMethod<String>('executeAutomation', {
          'instruction': instruction,
        });
        return result ?? 'Automation completed but returned no response.';
      }

      return 'Automation is only supported on Android.';
    } catch (e) {
      return 'Automation error: ${e is PlatformException ? (e.message ?? e.code) : e.toString()}';
    }
  }

}