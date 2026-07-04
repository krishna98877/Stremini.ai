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
}