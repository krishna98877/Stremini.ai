import '../../../core/result/result.dart';
import 'chat_repository.dart';

class SendDocumentChatMessageUseCase {
  const SendDocumentChatMessageUseCase(this._repository);

  final ChatRepository _repository;

  Future<Result<String>> call({
    required String documentText,
    required String question,
    List<Map<String, dynamic>> history = const [],
  }) {
    return _repository.sendDocumentMessage(
      documentText: documentText,
      question: question,
      history: history,
    );
  }
}
