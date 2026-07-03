import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../providers/chat_provider.dart';

class SessionLifecycleManager extends ConsumerStatefulWidget {
  final Widget child;

  const SessionLifecycleManager({
    super.key,
    required this.child,
  });

  @override
  ConsumerState<SessionLifecycleManager> createState() =>
      _SessionLifecycleManagerState();
}

class _SessionLifecycleManagerState
    extends ConsumerState<SessionLifecycleManager>
    with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);

    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.detached) {
      ref.read(chatNotifierProvider.notifier).clearChat();
    }
  }

  @override
  Widget build(BuildContext context) {
    return widget.child;
  }
}
