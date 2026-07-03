import '../../../core/result/result.dart';
import 'chat_repository.dart';

class SendChatMessageUseCase {
  const SendChatMessageUseCase(this._repository);

  final ChatRepository _repository;

  Future<Result<String>> call({
    required String message,
    List<Map<String, dynamic>> history = const [],
    String? attachment,
    String? mimeType,
    String? fileName,
  }) {
    return _repository.sendMessage(
      message: message,
      history: history,
      attachment: attachment,
      mimeType: mimeType,
      fileName: fileName,
    );
  }
}
