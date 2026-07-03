import '../../../core/result/result.dart';
import '../domain/chat_repository.dart';
import 'chat_client.dart';

class ChatRepositoryImpl implements ChatRepository {
  const ChatRepositoryImpl(this._client);

  final ChatClient _client;

  @override
  Future<Result<String>> sendMessage({
    required String message,
    List<Map<String, dynamic>> history = const [],
    String? attachment,
    String? mimeType,
    String? fileName,
  }) {
    return _client.sendMessage(
      message: message,
      history: history,
      attachment: attachment,
      mimeType: mimeType,
      fileName: fileName,
    );
  }

  @override
  Future<Result<String>> sendDocumentMessage({
    required String documentText,
    required String question,
    List<Map<String, dynamic>> history = const [],
  }) {
    return _client.sendDocumentMessage(
      documentText: documentText,
      question: question,
      history: history,
    );
  }
}
