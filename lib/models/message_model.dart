enum MessageType {
  user,
  bot,
  typing,
  documentBanner, // shown when a document is loaded into context
}

class Message {
  final String id;
  final String text;
  final MessageType type;
  final DateTime timestamp;

  const Message({
    required this.id,
    required this.text,
    required this.type,
    required this.timestamp,
  });

  Message copyWith({
    String? id,
    String? text,
    MessageType? type,
    DateTime? timestamp,
  }) {
    return Message(
      id: id ?? this.id,
      text: text ?? this.text,
      type: type ?? this.type,
      timestamp: timestamp ?? this.timestamp,
    );
  }
}