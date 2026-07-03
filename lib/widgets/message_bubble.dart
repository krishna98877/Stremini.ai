import 'dart:ui';
import 'package:flutter/material.dart';
import '../models/message_model.dart';
import '../core/theme/app_colors.dart';

class MessageBubble extends StatelessWidget {
  final Message message;

  const MessageBubble({
    super.key,
    required this.message,
  });

  @override
  Widget build(BuildContext context) {
    if (message.type == MessageType.typing) {
      return _buildTypingIndicator();
    }

    final isUser = message.type == MessageType.user;

    return Align(
      alignment: isUser ? Alignment.centerRight : Alignment.centerLeft,
      child: ClipRRect(
        borderRadius: BorderRadius.circular(20),
        child: BackdropFilter(
          filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
          child: Container(
            margin: const EdgeInsets.symmetric(vertical: 4, horizontal: 10),
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            decoration: BoxDecoration(
              color: isUser
                  ? const Color(0x1A00F0FF) // cyan tint at 10%
                  : const Color(0x0DFFFFFF), // white at 5%
              borderRadius: BorderRadius.circular(20),
              border: isUser
                  ? Border.all(color: const Color(0x3300F0FF), width: 0.5)
                  : Border.all(color: const Color(0x14FFFFFF), width: 0.5),
            ),
            constraints: BoxConstraints(
              maxWidth: isUser
                  ? MediaQuery.of(context).size.width * 0.75
                  : MediaQuery.of(context).size.width * 0.95,
            ),
            child: SelectableText(
              message.text,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 16,
                height: 1.5,
              ),
              cursorColor: Colors.blue,
              contextMenuBuilder: (context, editableTextState) {
                return AdaptiveTextSelectionToolbar.editableText(
                  editableTextState: editableTextState,
                );
              },
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildTypingIndicator() {
    return Align(
      alignment: Alignment.centerLeft,
      child: Container(
        padding: const EdgeInsets.all(16),
        child: const Text("...",
            style: TextStyle(color: Colors.white, fontSize: 24)),
      ),
    );
  }
}