import '../../../models/message_model.dart';

class ChatState {
  const ChatState({
    required this.messages,
    this.isLoading = false,
    this.errorMessage,
    this.activeDocumentName,
  });

  final List<Message> messages;
  final bool isLoading;
  final String? errorMessage;
  final String? activeDocumentName;

  ChatState copyWith({
    List<Message>? messages,
    bool? isLoading,
    String? errorMessage,
    String? activeDocumentName,
  }) {
    return ChatState(
      messages: messages ?? this.messages,
      isLoading: isLoading ?? this.isLoading,
      errorMessage: errorMessage,
      activeDocumentName: activeDocumentName ?? this.activeDocumentName,
    );
  }
}
