import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:stremini_chatbot/providers/auth_provider.dart';
import '../widgets/auth_widgets.dart';

class SignUpScreen extends ConsumerStatefulWidget {
  const SignUpScreen({super.key});

  @override
  ConsumerState<SignUpScreen> createState() => _SignUpScreenState();
}

class _SignUpScreenState extends ConsumerState<SignUpScreen>
    with SingleTickerProviderStateMixin {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _emailCtrl = TextEditingController();
  final _passwordCtrl = TextEditingController();
  final _confirmCtrl = TextEditingController();

  late AnimationController _fadeCtrl;
  late Animation<double> _fadeAnim;
  late Animation<Offset> _slideAnim;

  @override
  void initState() {
    super.initState();
    _fadeCtrl = AnimationController(
        vsync: this, duration: const Duration(milliseconds: 600));
    _fadeAnim = CurvedAnimation(parent: _fadeCtrl, curve: Curves.easeOut);
    _slideAnim = Tween<Offset>(
            begin: const Offset(0, 0.04), end: Offset.zero)
        .animate(CurvedAnimation(parent: _fadeCtrl, curve: Curves.easeOut));
    WidgetsBinding.instance.addPostFrameCallback((_) => _fadeCtrl.forward());
  }

  @override
  void dispose() {
    _fadeCtrl.dispose();
    _nameCtrl.dispose();
    _emailCtrl.dispose();
    _passwordCtrl.dispose();
    _confirmCtrl.dispose();
    super.dispose();
  }

  Future<void> _signUp() async {
    if (!_formKey.currentState!.validate()) return;
    await ref.read(authProvider.notifier).signUp(
          fullName: _nameCtrl.text.trim(),
          email: _emailCtrl.text.trim(),
          password: _passwordCtrl.text,
        );
  }

  Future<void> _googleSignIn() async {
    await ref.read(authProvider.notifier).signInWithGoogle();
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authProvider);
    final isLoading = authState.isLoading;

    return Scaffold(
      backgroundColor: const Color(0xFF080A0E),
      body: SafeArea(
        child: FadeTransition(
          opacity: _fadeAnim,
          child: SlideTransition(
            position: _slideAnim,
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 28),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const SizedBox(height: 28),

                  // Back button
                  GestureDetector(
                    onTap: () => Navigator.pop(context),
                    child: Container(
                      width: 38,
                      height: 38,
                      decoration: BoxDecoration(
                        color: const Color(0xFF0F1117),
                        borderRadius: BorderRadius.circular(10),
                        border: Border.all(color: const Color(0xFF1C2030)),
                      ),
                      child: const Icon(Icons.arrow_back_ios_new_rounded,
                          color: Colors.white, size: 14),
                    ),
                  ),
                  const SizedBox(height: 40),

                  const Text(
                    'Create account',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.5,
                      height: 1.1,
                    ),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Set up your Stremini AI account.',
                    style: TextStyle(color: Color(0xFF4A5568), fontSize: 14),
                  ),
                  const SizedBox(height: 36),

                  // Google fast path
                  GoogleSignInButton(
                      isLoading: isLoading,
                      onPressed: isLoading ? null : _googleSignIn),
                  const SizedBox(height: 28),
                  AuthDivider(),
                  const SizedBox(height: 28),

                  // Messages
                  if (authState.errorMessage != null) ...[
                    AuthMessageBanner(
                        message: authState.errorMessage!, isError: true),
                    const SizedBox(height: 20),
                  ],
                  if (authState.successMessage != null) ...[
                    AuthMessageBanner(
                        message: authState.successMessage!, isError: false),
                    const SizedBox(height: 20),
                  ],

                  // Form
                  Form(
                    key: _formKey,
                    child: Column(
                      children: [
                        AuthTextField(
                          label: 'FULL NAME',
                          hint: 'Your name',
                          prefixIcon: Icons.person_outline_rounded,
                          controller: _nameCtrl,
                          validator: (v) {
                            if (v == null || v.trim().isEmpty)
                              return 'Name is required';
                            if (v.trim().length < 2)
                              return 'At least 2 characters';
                            return null;
                          },
                        ),
                        const SizedBox(height: 14),
                        AuthTextField(
                          label: 'EMAIL',
                          hint: 'you@example.com',
                          prefixIcon: Icons.alternate_email_rounded,
                          controller: _emailCtrl,
                          keyboardType: TextInputType.emailAddress,
                          validator: (v) {
                            if (v == null || v.trim().isEmpty)
                              return 'Email is required';
                            if (!v.contains('@') || !v.contains('.'))
                              return 'Enter a valid email';
                            return null;
                          },
                        ),
                        const SizedBox(height: 14),
                        AuthTextField(
                          label: 'PASSWORD',
                          hint: 'At least 8 characters',
                          prefixIcon: Icons.lock_outline_rounded,
                          controller: _passwordCtrl,
                          isPassword: true,
                          validator: (v) {
                            if (v == null || v.isEmpty)
                              return 'Password is required';
                            if (v.length < 8) return 'At least 8 characters';
                            return null;
                          },
                        ),
                        const SizedBox(height: 14),
                        AuthTextField(
                          label: 'CONFIRM PASSWORD',
                          hint: 'Repeat password',
                          prefixIcon: Icons.lock_outline_rounded,
                          controller: _confirmCtrl,
                          isPassword: true,
                          textInputAction: TextInputAction.done,
                          onSubmitted: (_) => _signUp(),
                          validator: (v) {
                            if (v == null || v.isEmpty)
                              return 'Please confirm password';
                            if (v != _passwordCtrl.text)
                              return 'Passwords do not match';
                            return null;
                          },
                        ),
                      ],
                    ),
                  ),

                  const SizedBox(height: 8),
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    child: Text(
                      'By creating an account you agree to our Terms of Service and Privacy Policy.',
                      style: TextStyle(
                          color: Colors.white.withOpacity(0.22),
                          fontSize: 12,
                          height: 1.5),
                    ),
                  ),

                  AuthPrimaryButton(
                    label: 'CREATE ACCOUNT',
                    isLoading: isLoading,
                    onPressed: isLoading ? null : _signUp,
                  ),
                  const SizedBox(height: 28),

                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      const Text('Already have an account?  ',
                          style: TextStyle(
                              color: Color(0xFF3A4255), fontSize: 13)),
                      GestureDetector(
                        onTap: () => Navigator.pop(context),
                        child: const Text(
                          'Sign in',
                          style: TextStyle(
                            color: Color(0xFF23A6E2),
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 40),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}