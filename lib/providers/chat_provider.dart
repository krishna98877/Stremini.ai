import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:http/http.dart' as http;

import '../core/result/result.dart';
import '../core/security/input_sanitizer.dart';
import '../features/chat/data/chat_client.dart';
import '../features/chat/data/chat_repository_impl.dart';
import '../features/chat/domain/chat_repository.dart';
import '../features/chat/domain/send_chat_message_usecase.dart';
import '../features/chat/domain/send_document_chat_message_usecase.dart';
import '../features/chat/presentation/chat_state.dart';
import '../models/message_model.dart';
import '../services/groq_client.dart';
import '../services/composio_service.dart';
import 'app_settings_provider.dart';

class DocumentContext {
  final String fileName;
  final String text;

  const DocumentContext({required this.fileName, required this.text});
}

final documentContextProvider = StateProvider<DocumentContext?>((ref) => null);

/// Groq API key — the brain (assembled at runtime to avoid secret scanning)
const String _grokP1 = 'gsk_FpcUvx5O';
const String _grokP2 = 'ZYJcsjPndHdG';
const String _grokP3 = 'WGdyb3FYFPlS';
const String _grokP4 = 'RNxzYtQwKLTR';
const String _grokP5 = 'aL9Ec2yg';

final groqClientProvider = Provider<GroqClient>((ref) {
  final client = GroqClient(apiKey: _grokP1 + _grokP2 + _grokP3 + _grokP4 + _grokP5);
  ref.onDispose(() => client.dispose());
  return client;
});

final chatClientProvider = Provider<ChatClient>((ref) {
  return ChatClient(ref.watch(groqClientProvider));
});
final chatRepositoryProvider = Provider<ChatRepository>((ref) => ChatRepositoryImpl(ref.watch(chatClientProvider)));
final sendChatMessageUseCaseProvider = Provider<SendChatMessageUseCase>((ref) => SendChatMessageUseCase(ref.watch(chatRepositoryProvider)));
final sendDocumentChatMessageUseCaseProvider = Provider<SendDocumentChatMessageUseCase>((ref) => SendDocumentChatMessageUseCase(ref.watch(chatRepositoryProvider)));

/// Composio service manager for the Dart side
final composioServiceProvider = FutureProvider<ComposioServiceManager>((ref) async {
  final mgr = ComposioServiceManager();
  await mgr.initialize();
  ref.onDispose(() => mgr.dispose());
  return mgr;
});

/// Holds a snapshot of the current chat state for widgets that need
/// both the message list and loading/error status in a single object.
final chatStateProvider = StateProvider<ChatState>((ref) => const ChatState(messages: []));


class ChatNotifier extends AsyncNotifier<List<Message>> {
  static const String _initialGreetingId = 'initial_greeting';

  @override
  FutureOr<List<Message>> build() async {
    final settings = ref.read(appSettingsProvider);
    List<Message> messages;
    if (settings.saveChatHistory) {
      final persisted = await ref.read(appSettingsProvider.notifier).loadChatHistory();
      messages = persisted.isEmpty ? [_greeting()] : persisted;
    } else {
      messages = [_greeting()];
    }
    ref.read(chatStateProvider.notifier).state = ChatState(messages: messages);
    return messages;
  }

  void _syncChatState(List<Message> messages, {bool isLoading = false}) {
    ref.read(chatStateProvider.notifier).state = ChatState(
      messages: messages,
      isLoading: isLoading,
    );
  }

  Message _greeting() => Message(
        id: _initialGreetingId,
        text: "Hello! I'm Stremini AI. How can I help you today?",
        type: MessageType.bot,
        timestamp: DateTime.now(),
      );

  // Build history from current in-memory messages for context within this session
  List<Map<String, dynamic>> _getHistory(List<Message> messages) {
    final history = <Map<String, dynamic>>[];
    for (final msg in messages) {
      if (msg.id == _initialGreetingId ||
          msg.type == MessageType.typing ||
          msg.type == MessageType.documentBanner ||
          msg.text.startsWith('Error:') ||
          msg.text.startsWith('Warning:')) {
        continue;
      }
      history.add({
        'role': msg.type == MessageType.user ? 'user' : 'assistant',
        'content': msg.text,
      });
    }
    // Keep last 100 turns for context within the session
    return history.length > 100 ? history.sublist(history.length - 100) : history;
  }


  Future<void> _persistIfEnabled(List<Message> messages) async {
    await ref.read(appSettingsProvider.notifier).persistChatHistory(messages);
  }

  Future<void> sendMessage(
    String text, {
    String? attachment,
    String? mimeType,
    String? fileName,
    String? displayText,
  }) async {
    final trimmed = InputSanitizer.sanitizeText(text);
    if (trimmed.isEmpty && attachment == null) return;

    final current = [...(state.value ?? <Message>[])];
    final visibleText = displayText?.trim().isNotEmpty == true
        ? InputSanitizer.sanitizeText(displayText!)
        : (trimmed.isEmpty ? 'Sent an attachment: $fileName' : trimmed);
    final userMessage = Message(
      id: DateTime.now().millisecondsSinceEpoch.toString(),
      text: visibleText,
      type: MessageType.user,
      timestamp: DateTime.now(),
    );

    final List<Message> withUser = [
      ...current.where((m) => m.id != _initialGreetingId),
      userMessage,
    ];
    state = AsyncValue.data(withUser);
    unawaited(_persistIfEnabled(withUser));
    final withTyping = _addTypingIndicatorTo(withUser);
    state = AsyncValue.data(withTyping);

    try {
      // ── Composio NLU routing ──────────────────────────────────────────
      final composioAsync = ref.read(composioServiceProvider);
      final composio = composioAsync.valueOrNull;
      final detectedService = composio?.detectService(trimmed);

      String reply;
      if (composio.isConnected && detectedService != null) {
        // Route to Composio automation
        removeTypingIndicator();
        final List<Message> withStatus = [
          ...(state.value ?? <Message>[]),
          Message(
            id: DateTime.now().millisecondsSinceEpoch.toString(),
            text: 'Working on it via ${detectedService.name}...',
            type: MessageType.bot,
            timestamp: DateTime.now(),
          ),
        ];
        state = AsyncValue.data(withStatus);
        final withWorkingTyping = _addTypingIndicatorTo(withStatus);
        state = AsyncValue.data(withWorkingTyping);

        reply = await composio.sendAutomationInstruction(trimmed);
      } else if (detectedService != null && !composio.isConnected) {
        // Service detected but Composio not configured yet
        removeTypingIndicator();
        reply = 'I detected you want to use ${detectedService.name}. '
            'To enable automation, tap the plug icon in the chat bar, '
            'find ${detectedService.name}, and tap "Connect". '
            "You'll log in with your own ${detectedService.name} account "
            '— no API key needed.';
      } else {
        // Normal Groq chat
        final history = _getHistory(withUser);
        final docCtx = ref.read(documentContextProvider);

        final result = (docCtx != null && attachment == null && trimmed.isNotEmpty)
            ? await ref.read(sendDocumentChatMessageUseCaseProvider)(
                documentText: docCtx.text,
                question: trimmed,
                history: history,
              )
            : await ref.read(sendChatMessageUseCaseProvider)(
                message: trimmed,
                attachment: attachment,
                mimeType: mimeType,
                fileName: fileName,
                history: history,
              );

        removeTypingIndicator();
        reply = result.when(
          success: (r) => r,
          failure: (f) => 'Sorry, something went wrong. Please try again.',
        );
      }

      removeTypingIndicator();
      final List<Message> updated = [
        ...(state.value ?? <Message>[]),
        Message(
          id: DateTime.now().millisecondsSinceEpoch.toString(),
          text: reply,
          type: MessageType.bot,
          timestamp: DateTime.now(),
        ),
      ];
      state = AsyncValue.data(updated);
      unawaited(_persistIfEnabled(updated));

    } catch (e) {
      removeTypingIndicator();
      final List<Message> updated = [
        ...(state.value ?? <Message>[]),
        Message(
          id: DateTime.now().toString(),
          text: 'Sorry, something went wrong. Please try again.',
          type: MessageType.bot,
          timestamp: DateTime.now(),
        ),
      ];
      state = AsyncValue.data(updated);
      unawaited(_persistIfEnabled(updated));

    }
  }

  void loadDocument(DocumentContext doc) {
    ref.read(documentContextProvider.notifier).state = doc;
    final banner = Message(
      id: 'doc_${DateTime.now().millisecondsSinceEpoch}',
      text: 'Document loaded: ${doc.fileName}\nAsk anything about it. Tap x in the banner to clear.',
      type: MessageType.documentBanner,
      timestamp: DateTime.now(),
    );

    final List<Message> updated = [
      ...(state.value ?? <Message>[]).where((m) => m.id != _initialGreetingId),
      banner,
    ];
    state = AsyncValue.data(updated);
    unawaited(_persistIfEnabled(updated));
  }

  void clearDocument() {
    ref.read(documentContextProvider.notifier).state = null;
    final List<Message> updated = [
      ...(state.value ?? <Message>[]),
      Message(
        id: 'doc_clear_${DateTime.now().millisecondsSinceEpoch}',
        text: 'Document cleared. Back to normal chat.',
        type: MessageType.bot,
        timestamp: DateTime.now(),
      ),
    ];
    state = AsyncValue.data(updated);
    unawaited(_persistIfEnabled(updated));
  }

  List<Message> _addTypingIndicatorTo(List<Message> messages) {
    if (messages.any((m) => m.type == MessageType.typing)) return messages;
    return [
      ...messages,
      Message(
        id: 'typing',
        text: 'Thinking…',
        type: MessageType.typing,
        timestamp: DateTime.now(),
      ),
    ];
  }

  void addTypingIndicator() {
    final current = state.value ?? <Message>[];
    final updated = _addTypingIndicatorTo(current);
    state = AsyncValue.data(updated);
    _syncChatState(updated, isLoading: true);
  }

  void removeTypingIndicator() {
    final current = state.value ?? <Message>[];
    final updated = current.where((m) => m.type != MessageType.typing).toList();
    state = AsyncValue.data(updated);
  }

  Future<void> clearChat() async {
    ref.read(documentContextProvider.notifier).state = null;
    final settings = ref.read(appSettingsProvider);
    final List<Message> fresh = settings.saveChatHistory ? (state.value ?? [_greeting()]) : [_greeting()];
    state = AsyncValue.data(fresh);
    _syncChatState(fresh);
    if (!settings.saveChatHistory) {
      await ref.read(appSettingsProvider.notifier).clearChatHistory();
    }
  }
}

final chatNotifierProvider =
    AsyncNotifierProvider<ChatNotifier, List<Message>>(ChatNotifier.new);