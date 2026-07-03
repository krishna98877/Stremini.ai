import 'dart:convert';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;
import '../core/config/env_config.dart';
import '../core/security/input_sanitizer.dart';

// ─── URL sanitizer ────────────────────────────────────────────────────────────
String _sanitizeError(Object e) {
  String raw = e.toString();
  raw = raw.replaceAll(RegExp(r'https?://[^\s,]+'), 'the server');
  raw = raw.replaceAll(
      RegExp(r'[a-zA-Z0-9._-]+\.workers\.dev[^\s,]*'), 'the server');
  raw = raw.replaceAll(
      RegExp(r'[a-zA-Z0-9._-]+\.[a-zA-Z]{2,6}(:\d+)?(/[^\s]*)?'),
      'the server');
  raw = raw
      .replaceAll('SocketException:', 'Network error:')
      .replaceAll('ClientException:', '')
      .replaceAll('HandshakeException:', 'Secure connection error:')
      .replaceAll('Exception:', '')
      .trim();

  if (raw.toLowerCase().contains('failed host lookup') ||
      raw.toLowerCase().contains('network is unreachable') ||
      raw.toLowerCase().contains('no address associated')) {
    return 'No internet connection. Please check your network and try again.';
  }
  if (raw.toLowerCase().contains('timed out') ||
      raw.toLowerCase().contains('timeout')) {
    return 'Connection timed out. Please try again.';
  }
  if (raw.isEmpty) return 'Something went wrong. Please try again.';
  return raw;
}

class ApiService {
  static const String baseUrl = EnvConfig.baseUrl;

  // ── Auth helpers ──────────────────────────────────────────────────────────

  /// Returns a fresh Firebase ID token, or null if not signed in.
  Future<String?> _getToken() async {
    try {
      return await FirebaseAuth.instance.currentUser?.getIdToken();
    } catch (e) {
      debugPrint('ApiService._getToken: failed — $e');
      return null;
    }
  }

  /// Builds headers including the Firebase JWT when the user is signed in.
  Future<Map<String, String>> _headers({bool acceptStream = false}) async {
    final token = await _getToken();
    return <String, String>{
      'Content-Type': 'application/json',
      'Accept': acceptStream ? 'text/event-stream' : 'application/json',
      if (token != null) 'Authorization': 'Bearer $token',
    };
  }

  // ── Standard chat ─────────────────────────────────────────────────────────
  Future<String> sendMessage(
    String userMessage, {
    String? attachment,
    String? mimeType,
    String? fileName,
    List<Map<String, dynamic>>? history,
  }) async {
    try {
      final Map<String, dynamic> bodyMap = {
        'message': InputSanitizer.sanitizeText(userMessage),
        'history': InputSanitizer.sanitizeHistory(history ?? []),
      };
      if (attachment != null) {
        bodyMap['attachment'] = <String, dynamic>{
          'data': attachment,
          'mime': mimeType,
          'name': fileName == null
              ? null
              : InputSanitizer.sanitizeText(fileName, maxLength: 160),
        };
      }
      final response = await http.post(
        Uri.parse('$baseUrl/chat/message'),
        headers: await _headers(),
        body: jsonEncode(bodyMap),
      );
      if (response.statusCode == 401) {
        return 'Session expired. Please sign in again.';
      }
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        if (data is Map) {
          return (data['response'] ??
                  data['reply'] ??
                  data['message'] ??
                  'Empty reply.')
              .toString();
        }
        return data.toString();
      }
      return 'Unable to get a response. Please try again.';
    } catch (e) {
      return _sanitizeError(e);
    }
  }

  // ── Document chat ─────────────────────────────────────────────────────────
  Future<String> sendDocumentMessage({
    required String documentText,
    required String question,
    List<Map<String, dynamic>>? history,
  }) async {
    try {
      final response = await http.post(
        Uri.parse('$baseUrl/chat/document'),
        headers: await _headers(),
        body: jsonEncode({
          'documentText': InputSanitizer.protectForAi(documentText,
              source: 'document text'),
          'question': InputSanitizer.sanitizeText(question),
          'history': InputSanitizer.sanitizeHistory(history ?? []),
        }),
      );
      if (response.statusCode == 401) {
        return 'Session expired. Please sign in again.';
      }
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        return (data['response'] ?? 'Empty reply.').toString();
      }
      return 'Unable to get a response. Please try again.';
    } catch (e) {
      return _sanitizeError(e);
    }
  }

  // ── Streaming ─────────────────────────────────────────────────────────────
  Stream<String> streamMessage(
    String userMessage, {
    List<Map<String, dynamic>>? history,
  }) async* {
    try {
      final request =
          http.Request('POST', Uri.parse('$baseUrl/chat/stream'));
      request.headers.addAll(await _headers(acceptStream: true));
      request.body = jsonEncode({
        'message': InputSanitizer.sanitizeText(userMessage),
        'history': InputSanitizer.sanitizeHistory(history ?? []),
      });
      final streamedResponse = await request.send();
      if (streamedResponse.statusCode == 401) {
        yield 'Session expired. Please sign in again.';
        return;
      }
      if (streamedResponse.statusCode == 200) {
        await for (final chunk
            in streamedResponse.stream.transform(utf8.decoder)) {
          for (final line in chunk.split('\n')) {
            if (line.startsWith('data: ')) {
              final jsonStr = line.substring(6);
              if (jsonStr.trim().isNotEmpty && jsonStr != '[DONE]') {
                try {
                  final data = jsonDecode(jsonStr);
                  if (data is Map && data.containsKey('token')) {
                    yield data['token'] as String;
                  }
                } catch (_) {}
              }
            }
          }
        }
      } else {
        yield 'Unable to get a response. Please try again.';
      }
    } catch (e) {
      yield _sanitizeError(e);
    }
  }

  // ── Composio: exchange auth code for Bearer token ─────────────────────────
  Future<String?> exchangeComposioCode(String authCode) async {
    try {
      final response = await http.post(
        Uri.parse('$baseUrl/composio/exchange'),
        headers: await _headers(),
        body: jsonEncode({'authCode': authCode}),
      );
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        return data['token'] as String?;
      }
      return null;
    } catch (e) {
      debugPrint('ApiService.exchangeComposioCode: $e');
      return null;
    }
  }

  // ── Composio: send automation instruction ────────────────────────────────
  Future<String> sendAutomationInstruction({
    required String instruction,
    required String composioToken,
    String mcpUrl = 'https://connect.composio.dev/mcp',
  }) async {
    try {
      final response = await http.post(
        Uri.parse('$baseUrl/composio/automate'),
        headers: {
          'Content-Type': 'application/json',
          'Authorization': 'Bearer $composioToken',
          if ((await _getToken()) != null)
            'X-Firebase-Token': (await _getToken())!,
        },
        body: jsonEncode({
          'instruction': InputSanitizer.sanitizeText(instruction),
          'mcpUrl': mcpUrl,
        }),
      );
      if (response.statusCode == 200) {
        final data = jsonDecode(response.body);
        return (data['response'] ?? data['message'] ?? 'Done.').toString();
      }
      if (response.statusCode == 401) {
        return 'Composio session expired. Reconnect in Settings.';
      }
      return 'Automation failed. Please try again.';
    } catch (e) {
      return _sanitizeError(e);
    }
  }
}

final apiServiceProvider = Provider<ApiService>((ref) => ApiService());
