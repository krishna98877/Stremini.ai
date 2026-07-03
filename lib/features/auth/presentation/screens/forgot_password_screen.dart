import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:stremini_chatbot/providers/auth_provider.dart';
import '../widgets/auth_widgets.dart';

class ForgotPasswordScreen extends ConsumerStatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  ConsumerState<ForgotPasswordScreen> createState() =>
      _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends ConsumerState<ForgotPasswordScreen>
    with SingleTickerProviderStateMixin {
  final _formKey = GlobalKey<FormState>();
  final _emailCtrl = TextEditingController();
  bool _emailSent = false;

  late AnimationController _fadeCtrl;
  late Animation<double> _fadeAnim;

  @override
  void initState() {
    super.initState();
    _fadeCtrl = AnimationController(
        vsync: this, duration: const Duration(milliseconds: 500));
    _fadeAnim = CurvedAnimation(parent: _fadeCtrl, curve: Curves.easeOut);
    WidgetsBinding.instance.addPostFrameCallback((_) => _fadeCtrl.forward());
  }

  @override
  void dispose() {
    _fadeCtrl.dispose();
    _emailCtrl.dispose();
    super.dispose();
  }

  Future<void> _sendReset() async {
    if (!_formKey.currentState!.validate()) return;
    await ref
        .read(authProvider.notifier)
        .sendPasswordReset(_emailCtrl.text.trim());
    final state = ref.read(authProvider);
    if (state.successMessage != null) setState(() => _emailSent = true);
  }

  @override
  Widget build(BuildContext context) {
    final authState = ref.watch(authProvider);

    return Scaffold(
      backgroundColor: const Color(0xFF080A0E),
      body: SafeArea(
        child: FadeTransition(
          opacity: _fadeAnim,
          child: Padding(
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

                if (!_emailSent) ...[
                  const Text(
                    'Reset password',
                    style: TextStyle(
                      color: Colors.white,
                      fontSize: 28,
                      fontWeight: FontWeight.w700,
                      letterSpacing: -0.5,
                    ),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    "Enter your email and we'll send a reset link.",
                    style: TextStyle(
                        color: Color(0xFF4A5568), fontSize: 14, height: 1.5),
                  ),
                  const SizedBox(height: 40),

                  if (authState.errorMessage != null) ...[
                    AuthMessageBanner(
                        message: authState.errorMessage!, isError: true),
                    const SizedBox(height: 20),
                  ],

                  Form(
                    key: _formKey,
                    child: AuthTextField(
                      label: 'EMAIL',
                      hint: 'you@example.com',
                      prefixIcon: Icons.alternate_email_rounded,
                      controller: _emailCtrl,
                      keyboardType: TextInputType.emailAddress,
                      textInputAction: TextInputAction.done,
                      onSubmitted: (_) => _sendReset(),
                      validator: (v) {
                        if (v == null || v.trim().isEmpty)
                          return 'Email is required';
                        if (!v.contains('@') || !v.contains('.'))
                          return 'Enter a valid email';
                        return null;
                      },
                    ),
                  ),
                  const SizedBox(height: 28),

                  AuthPrimaryButton(
                    label: 'SEND RESET LINK',
                    isLoading: authState.isLoading,
                    onPressed: authState.isLoading ? null : _sendReset,
                  ),
                ] else ...[
                  Expanded(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        Container(
                          width: 60,
                          height: 60,
                          decoration: BoxDecoration(
                            color: const Color(0xFF0D1F17),
                            borderRadius: BorderRadius.circular(16),
                            border:
                                Border.all(color: const Color(0xFF153326)),
                          ),
                          child: const Icon(Icons.mark_email_read_outlined,
                              color: Color(0xFF34C47C), size: 28),
                        ),
                        const SizedBox(height: 24),
                        const Text(
                          'Check your inbox',
                          style: TextStyle(
                              color: Colors.white,
                              fontSize: 22,
                              fontWeight: FontWeight.w700,
                              letterSpacing: -0.3),
                        ),
                        const SizedBox(height: 10),
                        Text(
                          'Reset link sent to\n${_emailCtrl.text.trim()}',
                          textAlign: TextAlign.center,
                          style: const TextStyle(
                              color: Color(0xFF4A5568),
                              fontSize: 14,
                              height: 1.6),
                        ),
                        const SizedBox(height: 36),
                        AuthPrimaryButton(
                          label: 'BACK TO SIGN IN',
                          onPressed: () => Navigator.pop(context),
                        ),
                        const SizedBox(height: 14),
                        TextButton(
                          onPressed: () {
                            setState(() => _emailSent = false);
                            ref.read(authProvider.notifier).clearMessages();
                          },
                          child: const Text(
                            'Resend email',
                            style: TextStyle(
                                color: Color(0xFF23A6E2),
                                fontSize: 13,
                                fontWeight: FontWeight.w500),
                          ),
                        ),
                      ],
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}