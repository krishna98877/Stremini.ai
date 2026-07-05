import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;

/// Direct Groq API client for Dart/Flutter side.
///
/// Bypasses the broken BaseClient (certificate pinning) and calls
/// api.groq.com/openai/v1/chat/completions directly with standard HTTPS.
class GroqClient {
  static const String _groqApiUrl =
      'https://api.groq.com/openai/v1/chat/completions';
  static const String _defaultModel = 'llama-3.3-70b-versatile';

  static const String _systemPrompt = '''You are Stremini AI, a powerful AI assistant built into a keyboard app. You help users with anything — writing, coding, research, creative tasks, and more.

You also have automation capabilities through Composio. When a user asks you to do something that involves an external service (like sending an email via Gmail, posting a tweet, creating a GitHub issue, sending a Discord message, etc.), you should help them understand that they can trigger that automation by mentioning the service name.

Available services: GitHub, Gmail, Telegram, Twitter/X, Instagram, Facebook, WhatsApp, Chrome, Google Drive, Discord, LinkedIn, Reddit, Google Sheets, YouTube, TikTok.

When a user's request clearly involves one of these services, acknowledge it and let them know the automation will be triggered. If their request is general conversation, just respond normally as a helpful AI assistant.

Keep responses concise and conversational. You're inside a floating chat bubble, so be quick and useful.''';

  final String _apiKey;
  final String model;
  final http.Client _httpClient;

  GroqClient({
    required String apiKey,
    this.model = _defaultModel,
    http.Client? httpClient,
  })  : _apiKey = apiKey,
        _httpClient = httpClient ?? http.Client();

  /// Send a chat message to Groq and get the full response.
  Future<String> sendMessage({
    required String message,
    List<Map<String, dynamic>> history = const [],
  }) async {
    if (_apiKey.isEmpty) {
      throw Exception('Groq API key not configured.');
    }

    final messages = <Map<String, dynamic>>[
      {'role': 'system', 'content': _systemPrompt},
    ];

    // Add conversation history (only user/assistant, skip system)
    for (final turn in history) {
      final role = turn['role'] as String? ?? 'user';
      if (role == 'user' || role == 'assistant') {
        messages.add({
          'role': role,
          'content': (turn['content'] as String?) ?? '',
        });
      }
    }

    // Add current user message
    messages.add({
      'role': 'user',
      'content': message.length > 12000
          ? message.substring(0, 12000)
          : message,
    });

    final response = await _httpClient.post(
      Uri.parse(_groqApiUrl),
      headers: {
        'Authorization': 'Bearer $_apiKey',
        'Content-Type': 'application/json',
      },
      body: jsonEncode({
        'model': model,
        'messages': messages,
        'max_tokens': 2048,
        'temperature': 0.7,
      }),
    );

    if (response.statusCode == 401) {
      throw Exception('Invalid API key. Please update your Groq key.');
    }
    if (response.statusCode == 429) {
      throw Exception('Rate limit reached. Please wait a moment.');
    }
    if (response.statusCode >= 500) {
      throw Exception('Groq is temporarily unavailable. Try again.');
    }
    if (response.statusCode != 200) {
      throw Exception('Could not get a response. Please try again.');
    }

    final data = jsonDecode(response.body) as Map<String, dynamic>;
    final choices = data['choices'] as List<dynamic>?;
    if (choices != null && choices.isNotEmpty) {
      final message =
          (choices[0] as Map<String, dynamic>)['message'] as Map<String, dynamic>?;
      if (message != null) {
        return (message['content'] as String?) ??
            'I couldn\'t generate a response. Try again.';
      }
    }

    // Check for error in body
    if (data.containsKey('error')) {
      final err = data['error'] as Map<String, dynamic>?;
      throw Exception(err?['message'] ?? 'Unknown error from Groq.');
    }

    return 'I couldn\'t generate a response. Try again.';
  }

  /// Send a document-aware message (system prompt includes document context).
  Future<String> sendDocumentMessage({
    required String documentText,
    required String question,
    List<Map<String, dynamic>> history = const [],
  }) async {
    if (_apiKey.isEmpty) {
      throw Exception('Groq API key not configured.');
    }

    final docSystemPrompt = '''You are Stremini AI. The user has loaded a document and is asking questions about it.

DOCUMENT CONTENT:
$documentText

Answer the user's question based on the document content above. Be concise and helpful.''';

    final messages = <Map<String, dynamic>>[
      {'role': 'system', 'content': docSystemPrompt},
      // Add relevant history (just the last few turns)
      ...history.take(10).map((turn) => {
            'role': turn['role'] ?? 'user',
            'content': turn['content'] ?? '',
          }),
      {'role': 'user', 'content': question},
    ];

    final response = await _httpClient.post(
      Uri.parse(_groqApiUrl),
      headers: {
        'Authorization': 'Bearer $_apiKey',
        'Content-Type': 'application/json',
      },
      body: jsonEncode({
        'model': model,
        'messages': messages,
        'max_tokens': 2048,
        'temperature': 0.5,
      }),
    );

    if (response.statusCode != 200) {
      throw Exception('Could not process document. Please try again.');
    }

    final data = jsonDecode(response.body) as Map<String, dynamic>;
    final choices = data['choices'] as List<dynamic>?;
    if (choices != null && choices.isNotEmpty) {
      final message =
          (choices[0] as Map<String, dynamic>)['message'] as Map<String, dynamic>?;
      if (message != null) {
        return (message['content'] as String?) ?? 'No response from document analysis.';
      }
    }
    return 'No response from document analysis.';
  }

  void dispose() {
    _httpClient.close();
  }
}