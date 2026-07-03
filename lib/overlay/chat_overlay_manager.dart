import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:stremini_chatbot/screens/chat_screen.dart';
import 'package:stremini_chatbot/widgets/draggable_chat_icon.dart';
import 'package:stremini_chatbot/services/keyboard_service.dart';

import '../providers/chat_window_state_provider.dart';

class ChatOverlayManager extends ConsumerStatefulWidget {
  final Widget child;
  const ChatOverlayManager({super.key, required this.child});

  @override
  ConsumerState<ChatOverlayManager> createState() => _ChatOverlayManagerState();
}

class _ChatOverlayManagerState extends ConsumerState<ChatOverlayManager> {
  Offset _bubblePosition = const Offset(20, 200);
  final _keyboardService = KeyboardService();

  void updatePosition(Offset newPosition) {
    setState(() {
      _bubblePosition = newPosition;
    });
  }

  void cycleOverlayMode() {
    final notifier = ref.read(chatWindowStateProvider.notifier);
    final currentMode = ref.read(chatWindowStateProvider).overlayMode;

    if (currentMode == "icon") {
      notifier.setMode("radial");
    } else if (currentMode == "radial") {
      notifier.setMode("icon");
    }
  }

  void openMaximizedChat() {
    ref.read(chatWindowStateProvider.notifier).setMode("maximized");
  }

  void closeMaximizedChat() {
    ref.read(chatWindowStateProvider.notifier).setMode("radial");
  }

  /// Settings tap → collapse menu (composio is handled on Kotlin side)
  void _onSettingsTap() {
    final notifier = ref.read(chatWindowStateProvider.notifier);
    notifier.setMode("icon");
  }

  /// Brain tap → open maximized chat (floating chatbot equivalent on Dart side)
  void _onBrainTap() {
    openMaximizedChat();
  }

  /// Keyboard tap → open keyboard settings
  void _onKeyboardTap() {
    _keyboardService.openKeyboardSettingsActivity();
  }

  @override
  Widget build(BuildContext context) {
    final state = ref.watch(chatWindowStateProvider);
    final isMaximized = state.overlayMode == "maximized";

    return Stack(
      textDirection: TextDirection.ltr,
      children: [
        widget.child,

        if (!isMaximized)
          IgnorePointer(
            ignoring: false,
            child: DraggableChatIcon(
              position: _bubblePosition,
              onDragEnd: updatePosition,
              overlayMode: state.overlayMode,
              onTapMain: cycleOverlayMode,
              onOpenApp: openMaximizedChat,
              onSettingsTap: _onSettingsTap,
              onBrainTap: _onBrainTap,
              onKeyboardTap: _onKeyboardTap,
            ),
          ),

        if (isMaximized) _buildMaximizedChat(context),
      ],
    );
  }

  Widget _buildMaximizedChat(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.95),
      child: Stack(
        children: [
          const ChatScreen(),
          Positioned(
            top: MediaQuery.of(context).padding.top + 10,
            right: 10,
            child: IconButton(
              icon: const Icon(Icons.close, color: Colors.white, size: 30),
              onPressed: closeMaximizedChat,
            ),
          ),
        ],
      ),
    );
  }
}