import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';
import '../core/theme/app_colors.dart';
import '../core/theme/app_text_styles.dart';
import '../core/widgets/app_container.dart';

class ContactUsScreen extends StatefulWidget {
  const ContactUsScreen({super.key});

  @override
  State<ContactUsScreen> createState() => _ContactUsScreenState();
}

class _ContactUsScreenState extends State<ContactUsScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameCtrl = TextEditingController();
  final _emailCtrl = TextEditingController();
  final _subjectCtrl = TextEditingController();
  final _messageCtrl = TextEditingController();
  bool _isSending = false;
  bool _sent = false;

  static const String _supportEmail = 'streminiai@gmail.com';

  @override
  void dispose() {
    _nameCtrl.dispose();
    _emailCtrl.dispose();
    _subjectCtrl.dispose();
    _messageCtrl.dispose();
    super.dispose();
  }

  Future<void> _send() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _isSending = true);

    final subject = Uri.encodeComponent(_subjectCtrl.text.trim());
    final body = Uri.encodeComponent(
      'Name: ${_nameCtrl.text.trim()}\n'
      'Email: ${_emailCtrl.text.trim()}\n\n'
      '${_messageCtrl.text.trim()}',
    );
    final mailUri = Uri.parse('mailto:$_supportEmail?subject=$subject&body=$body');

    final launched = await launchUrl(
      mailUri,
      mode: LaunchMode.externalApplication,
    );

    if (!mounted) return;

    setState(() => _isSending = false);

    if (launched) {
      setState(() => _sent = true);
      return;
    }

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Could not open email app. Please mail us at $_supportEmail.'),
        backgroundColor: AppColors.warning,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  void _copyEmail() {
    Clipboard.setData(const ClipboardData(text: _supportEmail));
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text('Email copied to clipboard'),
        backgroundColor: AppColors.success,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        duration: const Duration(seconds: 2),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.black,
      appBar: AppBar(
        backgroundColor: AppColors.black,
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: AppColors.white),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text('Contact Us', style: AppTextStyles.h2),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        child: _sent ? _buildSuccessView() : _buildFormView(),
      ),
    );
  }

  Widget _buildSuccessView() {
    return SizedBox(
      height: MediaQuery.of(context).size.height * 0.7,
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 80,
            height: 80,
            decoration: BoxDecoration(
              color: AppColors.success.withOpacity(0.15),
              shape: BoxShape.circle,
              border: Border.all(color: AppColors.success.withOpacity(0.4)),
            ),
            child: const Icon(Icons.check_rounded, color: AppColors.success, size: 44),
          ),
          const SizedBox(height: 24),
          Text('Email Draft Opened!', style: AppTextStyles.h2.copyWith(color: AppColors.success)),
          const SizedBox(height: 12),
          Text(
            "Your email app was opened with your issue details. Please tap Send to deliver it to $_supportEmail.",
            style: AppTextStyles.subtitle1.copyWith(height: 1.6),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 32),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: () => setState(() { _sent = false; _nameCtrl.clear(); _emailCtrl.clear(); _subjectCtrl.clear(); _messageCtrl.clear(); }),
              style: ElevatedButton.styleFrom(
                backgroundColor: AppColors.primary,
                padding: const EdgeInsets.symmetric(vertical: 16),
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
              ),
              child: Text('Send Another Message', style: AppTextStyles.button),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildFormView() {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 8),

        // Header card
        AppContainer(
          width: double.infinity,
          padding: const EdgeInsets.all(20),
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [const Color(0xFF0D2137), AppColors.primary.withOpacity(0.12)],
          ),
          border: BorderSide(color: AppColors.primary.withOpacity(0.3)),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: AppColors.primary.withOpacity(0.2),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: AppColors.primary.withOpacity(0.4)),
                    ),
                    child: const Icon(Icons.support_agent, color: AppColors.primary, size: 24),
                  ),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('We\'re here to help', style: AppTextStyles.h3.copyWith(fontSize: 17)),
                        Text('Typically respond within 24–48 hours', style: AppTextStyles.subtitle2),
                      ],
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              _buildContactInfoRow(Icons.email_outlined, 'Email', _supportEmail, onTap: _copyEmail),
            ],
          ),
        ),
        const SizedBox(height: 24),

        // Quick contact options
        Text('QUICK CONTACT', style: AppTextStyles.body3.copyWith(color: AppColors.textGray, fontWeight: FontWeight.w700, letterSpacing: 1.2)),
        const SizedBox(height: 12),
        Row(
          children: [
            Expanded(
              child: _buildQuickBtn(
                icon: Icons.email,
                label: 'Email Us',
                color: AppColors.primary,
                onTap: _copyEmail,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: _buildQuickBtn(
                icon: Icons.bug_report_outlined,
                label: 'Report Bug',
                color: AppColors.warning,
                onTap: () => setState(() => _subjectCtrl.text = 'Bug Report: '),
              ),
            ),
          ],
        ),
        const SizedBox(height: 24),

        // Form
        Text('SEND A MESSAGE', style: AppTextStyles.body3.copyWith(color: AppColors.textGray, fontWeight: FontWeight.w700, letterSpacing: 1.2)),
        const SizedBox(height: 12),
        Form(
          key: _formKey,
          child: Column(
            children: [
              _buildField(
                controller: _nameCtrl,
                label: 'Your Name',
                icon: Icons.person_outline,
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Please enter your name' : null,
              ),
              const SizedBox(height: 12),
              _buildField(
                controller: _emailCtrl,
                label: 'Your Email',
                icon: Icons.email_outlined,
                keyboardType: TextInputType.emailAddress,
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return 'Please enter your email';
                  if (!v.contains('@') || !v.contains('.')) return 'Please enter a valid email';
                  return null;
                },
              ),
              const SizedBox(height: 12),
              _buildField(
                controller: _subjectCtrl,
                label: 'Subject',
                icon: Icons.subject_outlined,
                validator: (v) => (v == null || v.trim().isEmpty) ? 'Please enter a subject' : null,
              ),
              const SizedBox(height: 12),
              _buildField(
                controller: _messageCtrl,
                label: 'Message',
                icon: Icons.message_outlined,
                maxLines: 5,
                validator: (v) {
                  if (v == null || v.trim().isEmpty) return 'Please enter your message';
                  if (v.trim().length < 20) return 'Message must be at least 20 characters';
                  return null;
                },
              ),
              const SizedBox(height: 24),
              SizedBox(
                width: double.infinity,
                height: 54,
                child: ElevatedButton(
                  onPressed: _isSending ? null : _send,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: AppColors.primary,
                    disabledBackgroundColor: AppColors.primary.withOpacity(0.5),
                    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
                  ),
                  child: _isSending
                      ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            const Icon(Icons.send_rounded, size: 18, color: Colors.white),
                            const SizedBox(width: 8),
                            Text('Send Message', style: AppTextStyles.button),
                          ],
                        ),
                ),
              ),
            ],
          ),
        ),

        const SizedBox(height: 32),
        // FAQ section
        Text('FAQ', style: AppTextStyles.body3.copyWith(color: AppColors.textGray, fontWeight: FontWeight.w700, letterSpacing: 1.2)),
        const SizedBox(height: 12),
        _buildFaq('How do I enable the floating bubble?', 'Go to Home → tap the power button on the Floating AI Agent card → grant Overlay permission when prompted.'),
        _buildFaq('Why does the app need certain permissions?', 'Overlay permission is needed for the floating AI bubble, microphone for voice input, and notification permission for alerts. You can revoke any permission anytime in your phone Settings.'),
        _buildFaq('Is my chat data stored?', 'Chat history is in-memory only and cleared when you close the app. Nothing is permanently stored on our servers.'),
        _buildFaq('How do I reset all permissions?', 'Go to your phone Settings → Apps → Stremini AI → Permissions and revoke any permission you wish to reset.'),
        const SizedBox(height: 40),
      ],
    );
  }

  Widget _buildContactInfoRow(IconData icon, String label, String value, {VoidCallback? onTap}) {
    return GestureDetector(
      onTap: onTap,
      child: Row(
        children: [
          Icon(icon, color: AppColors.primary, size: 18),
          const SizedBox(width: 10),
          Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(label, style: AppTextStyles.body3.copyWith(color: AppColors.textGray)),
              Text(value, style: AppTextStyles.body2.copyWith(color: AppColors.primary, decoration: TextDecoration.underline)),
            ],
          ),
          const SizedBox(width: 8),
          const Icon(Icons.copy_rounded, color: AppColors.textGray, size: 14),
        ],
      ),
    );
  }

  Widget _buildQuickBtn({required IconData icon, required String label, required Color color, required VoidCallback onTap}) {
    return AppContainer(
      padding: const EdgeInsets.symmetric(vertical: 16),
      color: color.withOpacity(0.1),
      border: BorderSide(color: color.withOpacity(0.3)),
      onTap: onTap,
      child: Column(
        children: [
          Icon(icon, color: color, size: 26),
          const SizedBox(height: 6),
          Text(label, style: AppTextStyles.body3.copyWith(color: color, fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }

  Widget _buildField({
    required TextEditingController controller,
    required String label,
    required IconData icon,
    TextInputType? keyboardType,
    int maxLines = 1,
    String? Function(String?)? validator,
  }) {
    return TextFormField(
      controller: controller,
      keyboardType: keyboardType,
      maxLines: maxLines,
      style: AppTextStyles.body2,
      validator: validator,
      decoration: InputDecoration(
        labelText: label,
        labelStyle: TextStyle(color: AppColors.textGray),
        prefixIcon: Icon(icon, color: AppColors.textGray, size: 20),
        filled: true,
        fillColor: AppColors.darkGray,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
        enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: AppColors.lightGray.withOpacity(0.3))),
        focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: AppColors.primary, width: 1.5)),
        errorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: AppColors.danger)),
        focusedErrorBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: const BorderSide(color: AppColors.danger)),
        contentPadding: EdgeInsets.symmetric(horizontal: 16, vertical: maxLines > 1 ? 14 : 0),
      ),
    );
  }

  Widget _buildFaq(String question, String answer) {
    return Theme(
      data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
      child: AppContainer(
        margin: const EdgeInsets.only(bottom: 8),
        color: AppColors.darkGray,
        child: ExpansionTile(
          tilePadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
          childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
          collapsedIconColor: AppColors.textGray,
          iconColor: AppColors.primary,
          title: Text(question, style: AppTextStyles.body2.copyWith(fontWeight: FontWeight.w600)),
          children: [
            Text(answer, style: AppTextStyles.subtitle1.copyWith(height: 1.6)),
          ],
        ),
      ),
    );
  }
}