import 'package:firebase_auth/firebase_auth.dart';
import 'package:google_sign_in/google_sign_in.dart';

class AuthResult {
  final bool success;
  final String? error;
  final User? user;

  const AuthResult({required this.success, this.error, this.user});
}

class FirebaseAuthService {
  static final FirebaseAuth _auth = FirebaseAuth.instance;
  static final GoogleSignIn _googleSignIn = GoogleSignIn();

  // ── Current user ───────────────────────────────────────────────────────────
  User? get currentUser => _auth.currentUser;
  bool get isAuthenticated => currentUser != null;

  Stream<User?> get authStateChanges => _auth.authStateChanges();

  // ── Email sign up ──────────────────────────────────────────────────────────
  Future<AuthResult> signUpWithEmail({
    required String email,
    required String password,
    required String fullName,
  }) async {
    try {
      final credential = await _auth.createUserWithEmailAndPassword(
        email: email,
        password: password,
      );
      // Save display name
      await credential.user?.updateDisplayName(fullName);
      // Send verification email
      await credential.user?.sendEmailVerification();
      return AuthResult(success: true, user: credential.user);
    } on FirebaseAuthException catch (e) {
      return AuthResult(success: false, error: _friendlyError(e));
    } catch (e) {
      return const AuthResult(
        success: false,
        error: 'Something went wrong. Please try again.',
      );
    }
  }

  // ── Email sign in ──────────────────────────────────────────────────────────
  Future<AuthResult> signInWithEmail({
    required String email,
    required String password,
  }) async {
    try {
      final credential = await _auth.signInWithEmailAndPassword(
        email: email,
        password: password,
      );
      return AuthResult(success: true, user: credential.user);
    } on FirebaseAuthException catch (e) {
      return AuthResult(success: false, error: _friendlyError(e));
    } catch (e) {
      return const AuthResult(
        success: false,
        error: 'Something went wrong. Please try again.',
      );
    }
  }

  // ── Google sign in ─────────────────────────────────────────────────────────
  Future<AuthResult> signInWithGoogle() async {
    try {
      final googleUser = await _googleSignIn.signIn();
      if (googleUser == null) {
        // User cancelled
        return const AuthResult(success: false, error: null);
      }
      final googleAuth = await googleUser.authentication;
      final credential = GoogleAuthProvider.credential(
        accessToken: googleAuth.accessToken,
        idToken: googleAuth.idToken,
      );
      final userCredential = await _auth.signInWithCredential(credential);
      return AuthResult(success: true, user: userCredential.user);
    } on FirebaseAuthException catch (e) {
      return AuthResult(success: false, error: _friendlyError(e));
    } catch (e) {
      final msg = e.toString().toLowerCase();
      if (msg.contains('network') || msg.contains('socket')) {
        return const AuthResult(
          success: false,
          error: 'Network error. Please check your connection and try again.',
        );
      }
      return const AuthResult(
        success: false,
        error: 'Google sign in failed. Please try again.',
      );
    }
  }

  // ── Forgot password ────────────────────────────────────────────────────────
  Future<AuthResult> sendPasswordResetEmail(String email) async {
    try {
      await _auth.sendPasswordResetEmail(email: email);
      return const AuthResult(success: true);
    } on FirebaseAuthException catch (e) {
      return AuthResult(success: false, error: _friendlyError(e));
    } catch (e) {
      return const AuthResult(
        success: false,
        error: 'Failed to send reset email.',
      );
    }
  }

  // ── Get ID token for API calls ─────────────────────────────────────────────
  /// Returns a fresh Firebase ID token, or null if not signed in.
  /// Firebase refreshes the token automatically when it's within 5 minutes
  /// of expiry, so this is always safe to call before an API request.
  Future<String?> getIdToken({bool forceRefresh = false}) async {
    try {
      return await _auth.currentUser?.getIdToken(forceRefresh);
    } catch (_) {
      return null;
    }
  }

  // ── Sign out ───────────────────────────────────────────────────────────────
  Future<void> signOut() async {
    await Future.wait([
      _auth.signOut(),
      _googleSignIn.signOut(),
    ]);
  }

  // ── Error mapping ──────────────────────────────────────────────────────────
  String _friendlyError(FirebaseAuthException e) {
    switch (e.code) {
      case 'user-not-found':
      case 'wrong-password':
      case 'invalid-credential':
        return 'Incorrect email or password.';
      case 'email-already-in-use':
        return 'This email is already registered. Try signing in.';
      case 'weak-password':
        return 'Password must be at least 6 characters.';
      case 'invalid-email':
        return 'Please enter a valid email address.';
      case 'user-disabled':
        return 'This account has been disabled.';
      case 'too-many-requests':
        return 'Too many attempts. Please wait a moment and try again.';
      case 'network-request-failed':
        return 'Network error. Please check your connection.';
      case 'operation-not-allowed':
        return 'This sign-in method is not enabled. Contact support.';
      case 'requires-recent-login':
        return 'Please sign out and sign back in to continue.';
      default:
        return e.message ?? 'An error occurred. Please try again.';
    }
  }
}
