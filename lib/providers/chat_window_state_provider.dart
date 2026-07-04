import 'package:flutter_riverpod/flutter_riverpod.dart';

class ChatWindowState {
  final String overlayMode; // "icon", "radial", or "maximized"

  ChatWindowState({this.overlayMode = "icon"});

  ChatWindowState copyWith({String? overlayMode}) {
    return ChatWindowState(
      overlayMode: overlayMode ?? this.overlayMode,
    );
  }
}

class ChatWindowNotifier extends Notifier<ChatWindowState> {
  @override
  ChatWindowState build() => ChatWindowState();

  void setMode(String mode) {
    state = state.copyWith(overlayMode: mode);
  }
}

final chatWindowStateProvider =
    NotifierProvider<ChatWindowNotifier, ChatWindowState>(
  ChatWindowNotifier.new,
);