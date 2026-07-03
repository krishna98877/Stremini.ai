import 'dart:async';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../services/permission_service.dart';
import '../services/overlay_service.dart';

class HomeState {
  final bool isLoading;
  final bool bubbleActive;
  final PermissionStatus permissionStatus;
  final String? errorMessage;

  const HomeState({
    this.isLoading = false,
    this.bubbleActive = false,
    this.permissionStatus = const PermissionStatus(
      hasOverlay: false,
      hasMicrophone: false,
    ),
    this.errorMessage,
  });

  HomeState copyWith({
    bool? isLoading,
    bool? bubbleActive,
    PermissionStatus? permissionStatus,
    String? errorMessage,
  }) {
    return HomeState(
      isLoading: isLoading ?? this.isLoading,
      bubbleActive: bubbleActive ?? this.bubbleActive,
      permissionStatus: permissionStatus ?? this.permissionStatus,
      errorMessage: errorMessage,
    );
  }
}

class HomeController extends Notifier<HomeState> {
  PermissionService get _permissionService => ref.read(permissionServiceProvider);
  OverlayService get _overlayService => ref.read(overlayServiceProvider);

  /// Polling timer — checks permissions every 1.5 s while the app is in foreground.
  /// This is the key fix: when the user returns from Settings after granting a
  /// permission, the UI updates automatically without needing an app restart.
  Timer? _pollTimer;

  @override
  HomeState build() {
    // Kick off first check immediately
    Future.microtask(() => checkPermissions());
    // Start polling so returning from Settings is reflected in ≤1.5 s
    _startPolling();
    // Cancel timer when the notifier is disposed
    ref.onDispose(() => _pollTimer?.cancel());
    return const HomeState();
  }

  // ── Permission polling ────────────────────────────────────────────────────

  void _startPolling() {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(milliseconds: 1500), (_) {
      _silentPermissionCheck();
    });
  }

  /// Silent check — updates state only when a permission status actually changes,
  /// so we don't cause unnecessary UI rebuilds every 1.5 s.
  Future<void> _silentPermissionCheck() async {
    try {
      final status = await _permissionService.checkAllPermissions();
      final current = state.permissionStatus;
      if (status.hasOverlay != current.hasOverlay ||
          status.hasMicrophone != current.hasMicrophone) {
        state = state.copyWith(permissionStatus: status);
      }
    } catch (_) {
      // Silent — don't surface polling errors
    }
  }

  // ── Public API ────────────────────────────────────────────────────────────

  Future<void> checkPermissions() async {
    state = state.copyWith(isLoading: true);
    try {
      final status = await _permissionService.checkAllPermissions();
      state = state.copyWith(permissionStatus: status, isLoading: false);
    } catch (e) {
      state = state.copyWith(isLoading: false, errorMessage: 'Failed to check permissions');
    }
  }

  Future<void> requestOverlayPermission() async {
    try {
      await _permissionService.requestOverlayPermission();
      // Poll more aggressively right after requesting so UX feels instant
      await _pollAfterRequest();
    } catch (e) {
      state = state.copyWith(errorMessage: 'Failed to request overlay permission');
    }
  }

  Future<void> requestMicrophonePermission() async {
    try {
      await _permissionService.requestMicrophonePermission();
      await _pollAfterRequest();
    } catch (e) {
      state = state.copyWith(errorMessage: 'Failed to request microphone permission');
    }
  }

  /// After sending the user to the permission screen, poll every 500 ms for
  /// up to 30 s, stopping early as soon as the permission is granted.
  /// This makes the permission card disappear the moment the user returns.
  Future<void> _pollAfterRequest() async {
    const interval = Duration(milliseconds: 500);
    const maxAttempts = 60; // 30 seconds max
    for (var i = 0; i < maxAttempts; i++) {
      await Future.delayed(interval);
      final status = await _permissionService.checkAllPermissions();
      final old = state.permissionStatus;
      if (status.hasOverlay != old.hasOverlay ||
          status.hasMicrophone != old.hasMicrophone) {
        state = state.copyWith(permissionStatus: status);
        return; // Permission changed — stop early
      }
    }
  }

  Future<bool> toggleBubble(bool value) async {
    if (!state.permissionStatus.hasOverlay) {
      await requestOverlayPermission();
      return false;
    }
    if (!state.permissionStatus.hasMicrophone && value) {
      await requestMicrophonePermission();
      return false;
    }
    try {
      if (value) {
        await _overlayService.startOverlay();
      } else {
        await _overlayService.stopOverlay();
      }
      state = state.copyWith(bubbleActive: value);
      return true;
    } catch (e) {
      state = state.copyWith(errorMessage: e.toString());
      return false;
    }
  }

  void clearError() {
    state = state.copyWith(errorMessage: null);
  }
}

final permissionServiceProvider = Provider<PermissionService>((ref) => PermissionService());
final overlayServiceProvider = Provider<OverlayService>((ref) => OverlayService());
final homeControllerProvider = NotifierProvider<HomeController, HomeState>(HomeController.new);