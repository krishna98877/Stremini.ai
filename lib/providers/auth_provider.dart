import 'dart:async';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../features/auth/data/firebase_auth_service.dart';

// ── Auth state ─────────────────────────────────────────────────────────────

enum AuthStatus { unknown, authenticated, unauthenticated }

class AuthState {
  final AuthStatus status;
  final User? user;
  final bool isLoading;
  final String? errorMessage;
  final String? successMessage;

  const AuthState({
    this.status = AuthStatus.unknown,
    this.user,
    this.isLoading = false,
    this.errorMessage,
    this.successMessage,
  });

  AuthState copyWith({
    AuthStatus? status,
    User? user,
    bool? isLoading,
    String? errorMessage,
    String? successMessage,
    bool clearError = false,
    bool clearSuccess = false,
  }) {
    return AuthState(
      status: status ?? this.status,
      user: user ?? this.user,
      isLoading: isLoading ?? this.isLoading,
      errorMessage: clearError ? null : errorMessage ?? this.errorMessage,
      successMessage:
          clearSuccess ? null : successMessage ?? this.successMessage,
    );
  }
}

// ── Auth notifier ──────────────────────────────────────────────────────────

class AuthNotifier extends Notifier<AuthState> {
  FirebaseAuthService get _service => ref.read(authServiceProvider);
  StreamSubscription<User?>? _authSub;

  @override
  AuthState build() {
    _listenToAuthChanges();
    ref.onDispose(() => _authSub?.cancel());

    // Immediately reflect existing Firebase session (persisted across restarts)
    final user = FirebaseAuth.instance.currentUser;
    if (user != null) {
      return AuthState(status: AuthStatus.authenticated, user: user);
    }
    return const AuthState(status: AuthStatus.unauthenticated);
  }

  void _listenToAuthChanges() {
    _authSub = FirebaseAuth.instance.authStateChanges().listen((user) {
      if (user != null) {
        state = AuthState(status: AuthStatus.authenticated, user: user);
      } else {
        state = const AuthState(status: AuthStatus.unauthenticated);
      }
    });
  }

  // ── Email sign up ──────────────────────────────────────────────────────────
  Future<void> signUp({
    required String email,
    required String password,
    required String fullName,
  }) async {
    state = state.copyWith(
        isLoading: true, clearError: true, clearSuccess: true);
    final result = await _service.signUpWithEmail(
      email: email,
      password: password,
      fullName: fullName,
    );
    if (result.success) {
      state = state.copyWith(
        isLoading: false,
        successMessage:
            'Account created! Check your email to verify your account.',
      );
    } else {
      state = state.copyWith(isLoading: false, errorMessage: result.error);
    }
  }

  // ── Email sign in ──────────────────────────────────────────────────────────
  Future<void> signIn({
    required String email,
    required String password,
  }) async {
    state = state.copyWith(
        isLoading: true, clearError: true, clearSuccess: true);
    final result =
        await _service.signInWithEmail(email: email, password: password);
    if (!result.success) {
      state = state.copyWith(isLoading: false, errorMessage: result.error);
    }
    // On success the Firebase auth stream listener handles state transition.
  }

  // ── Google sign in ─────────────────────────────────────────────────────────
  Future<void> signInWithGoogle() async {
    state = state.copyWith(
        isLoading: true, clearError: true, clearSuccess: true);
    final result = await _service.signInWithGoogle();
    if (!result.success && result.error != null) {
      state = state.copyWith(isLoading: false, errorMessage: result.error);
    } else if (!result.success) {
      // User cancelled — just stop loading, no error shown
      state = state.copyWith(isLoading: false);
    }
  }

  // ── Forgot password ────────────────────────────────────────────────────────
  Future<void> sendPasswordReset(String email) async {
    state = state.copyWith(
        isLoading: true, clearError: true, clearSuccess: true);
    final result = await _service.sendPasswordResetEmail(email);
    state = state.copyWith(
      isLoading: false,
      errorMessage: result.success ? null : result.error,
      successMessage: result.success
          ? 'Password reset email sent! Check your inbox.'
          : null,
    );
  }

  // ── Sign out ───────────────────────────────────────────────────────────────
  Future<void> signOut() async {
    await _service.signOut();
    // The auth stream will fire and set state to unauthenticated automatically.
  }

  void clearMessages() {
    state = state.copyWith(clearError: true, clearSuccess: true);
  }
}

// ── Providers ─────────────────────────────────────────────────────────────

final authServiceProvider =
    Provider<FirebaseAuthService>((ref) => FirebaseAuthService());

final authProvider =
    NotifierProvider<AuthNotifier, AuthState>(AuthNotifier.new);
