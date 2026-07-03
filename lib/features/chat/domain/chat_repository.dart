import '../../../core/result/result.dart';

abstract class ChatRepository {
  Future<Result<String>> sendMessage({
    required String message,
    List<Map<String, dynamic>> history,
    String? attachment,
    String? mimeType,
    String? fileName,
  });

  Future<Result<String>> sendDocumentMessage({
    required String documentText,
    required String question,
    List<Map<String, dynamic>> history,
  });
}
