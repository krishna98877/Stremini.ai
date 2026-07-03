import '../../../core/config/env_config.dart';
import '../../../core/network/base_client.dart';
import '../../../core/result/result.dart';
import '../../../core/security/input_sanitizer.dart';

class ChatClient {
  const ChatClient(this._baseClient);

  final BaseClient _baseClient;

  Future<Result<String>> sendMessage({
    required String message,
    List<Map<String, dynamic>> history = const [],
    String? attachment,
    String? mimeType,
    String? fileName,
  }) async {
    // FIX: Removed protectForAi() wrapper on the user message.
    // protectForAi() prepends a large security-boundary string that the
    // Cloudflare Worker backend does not expect and cannot parse, causing it
    // to return an empty / error response.  Plain sanitisation is sufficient
    // for the chat endpoint; prompt-injection protection belongs in the
    // backend system prompt, not in the user-message field.
    final body = <String, dynamic>{
      'message': InputSanitizer.sanitizeText(message),
      'history': InputSanitizer.sanitizeHistory(history),
    };

    if (attachment != null) {
      body['attachment'] = {
        'data': attachment,
        'mime': mimeType,
        'name': fileName == null
            ? null
            : InputSanitizer.sanitizeText(fileName, maxLength: 160),
      };
    }

    try {
      final data = await _baseClient.postJson(
        Uri.parse('${EnvConfig.baseUrl}/chat/message'),
        body,
      );
      return Success(
        (data['response'] ?? data['reply'] ?? data['message'] ?? 'Empty reply.')
            .toString(),
      );
    } catch (e) {
      return Error(NetworkFailure(e.toString()));
    }
  }

  Future<Result<String>> sendDocumentMessage({
    required String documentText,
    required String question,
    List<Map<String, dynamic>> history = const [],
  }) async {
    try {
      final data = await _baseClient.postJson(
        Uri.parse('${EnvConfig.baseUrl}/chat/document'),
        {
          // Document text can legitimately contain instruction-like content
          // (e.g. a PDF with "ignore all previous rules").  We keep the
          // protectForAi wrapper here so the backend treats document content
          // as untrusted data, not as instructions.
          'documentText': InputSanitizer.protectForAi(
              documentText, source: 'document text'),
          'question': InputSanitizer.sanitizeText(question),
          'history': InputSanitizer.sanitizeHistory(history),
        },
      );
      return Success((data['response'] ?? 'Empty reply.').toString());
    } catch (e) {
      return Error(NetworkFailure(e.toString()));
    }
  }
}
