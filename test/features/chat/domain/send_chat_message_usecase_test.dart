import 'package:flutter_test/flutter_test.dart';
import 'package:stremini_chatbot/core/result/result.dart';
import 'package:stremini_chatbot/features/chat/domain/chat_repository.dart';
import 'package:stremini_chatbot/features/chat/domain/send_chat_message_usecase.dart';

class _FakeChatRepository implements ChatRepository {
  Result<String> nextResult = const Success('ok');

  @override
  Future<Result<String>> sendMessage({required String message, List<Map<String, dynamic>> history = const [], String? attachment, String? mimeType, String? fileName}) async {
    return nextResult;
  }

  @override
  Future<Result<String>> sendDocumentMessage({required String documentText, required String question, List<Map<String, dynamic>> history = const []}) async {
    return const Success('doc');
  }
}

void main() {
  test('returns success result from repository', () async {
    final repo = _FakeChatRepository();
    final useCase = SendChatMessageUseCase(repo);

    final result = await useCase(message: 'hello');

    expect(result is Success<String>, true);
  });

  test('returns failure result from repository', () async {
    final repo = _FakeChatRepository()..nextResult = const Error(NetworkFailure('offline'));
    final useCase = SendChatMessageUseCase(repo);

    final result = await useCase(message: 'hello');

    expect(result is Error<String>, true);
    expect(
      result.when(success: (_) => '', failure: (f) => f.message),
      'offline',
    );
  });
}
