abstract class NativeBridgeService {
  Future<void> initialize({required Future<void> Function(String method) onEvent});
}
