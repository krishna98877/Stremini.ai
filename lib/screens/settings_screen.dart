import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart' as url_launcher;
import '../core/localization/app_strings.dart';
import '../core/theme/app_colors.dart';
import '../providers/app_settings_provider.dart';
import '../services/keyboard_service.dart';

class SettingsScreen extends ConsumerStatefulWidget {
  const SettingsScreen({super.key});

  @override
  ConsumerState<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends ConsumerState<SettingsScreen> {
  @override
  Widget build(BuildContext context) {
    final settings = ref.watch(appSettingsProvider);
    String tr(String key) => AppStrings.t(settings.language, key);

    final textColor = Theme.of(context).textTheme.bodyLarge?.color ?? Colors.white;

    return Scaffold(
      appBar: AppBar(
        leading: IconButton(
          icon: Icon(Icons.arrow_back, color: textColor),
          onPressed: () => Navigator.pop(context),
        ),
        title: Text(tr('settings')),
        centerTitle: true,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _buildSectionHeader(tr('ai_assistant'), context),
            const SizedBox(height: 12),
            _buildToggleTile(
              icon: Icons.notifications_outlined,
              iconColor: AppColors.primary,
              title: tr('notifications'),
              subtitle: tr('notifications_subtitle'),
              value: settings.notificationsEnabled,
              context: context,
              onChanged: (v) async {
                await ref
                    .read(appSettingsProvider.notifier)
                    .setNotificationsEnabled(v);
                _maybeHaptic(settings.hapticFeedback);
              },
            ),
            const SizedBox(height: 24),

            _buildSectionHeader(tr('keyboard'), context),
            const SizedBox(height: 12),
            _buildActionTile(
              icon: Icons.keyboard,
              iconColor: AppColors.secondary,
              title: tr('ai_keyboard_setup'),
              subtitle: tr('ai_keyboard_setup_subtitle'),
              context: context,
              onTap: _openKeyboardSetup,
            ),
            const SizedBox(height: 8),
            _buildActionTile(
              icon: Icons.switch_access_shortcut,
              iconColor: AppColors.scanCyan,
              title: tr('switch_keyboard'),
              subtitle: tr('switch_keyboard_subtitle'),
              context: context,
              onTap: _switchKeyboard,
            ),
            const SizedBox(height: 24),

            _buildSectionHeader(tr('privacy'), context),
            const SizedBox(height: 12),
            _buildToggleTile(
              icon: Icons.vibration,
              iconColor: AppColors.primary,
              title: tr('haptic_feedback'),
              subtitle: tr('haptic_feedback_subtitle'),
              value: settings.hapticFeedback,
              context: context,
              onChanged: (v) async {
                await ref
                    .read(appSettingsProvider.notifier)
                    .setHapticFeedback(v);
                _maybeHaptic(v);
              },
            ),
            const SizedBox(height: 8),
            _buildToggleTile(
              icon: Icons.history,
              iconColor: AppColors.warning,
              title: tr('save_chat_history'),
              subtitle: tr('save_chat_history_subtitle'),
              value: settings.saveChatHistory,
              context: context,
              onChanged: (v) async {
                await ref
                    .read(appSettingsProvider.notifier)
                    .setSaveChatHistory(v);
                _maybeHaptic(settings.hapticFeedback);
              },
            ),
            const SizedBox(height: 24),

            _buildSectionHeader('AUTOMATIONS', context),
            const SizedBox(height: 12),
            _buildComposioCard(settings, context),
            const SizedBox(height: 24),

            _buildSectionHeader(tr('about'), context),
            const SizedBox(height: 12),
            _buildInfoTile(
                icon: Icons.info_outline,
                iconColor: AppColors.textGray,
                title: tr('version'),
                value: '1.0.0',
                context: context),
            const SizedBox(height: 8),
            _buildActionTile(
              icon: Icons.privacy_tip_outlined,
              iconColor: AppColors.textGray,
              title: tr('privacy_policy'),
              subtitle: 'Read our privacy policy',
              context: context,
              onTap: () => _showDialog(
                  'Privacy Policy',
                  _privacyPolicyText,
                  context),
            ),
            const SizedBox(height: 8),
            _buildActionTile(
              icon: Icons.description_outlined,
              iconColor: AppColors.textGray,
              title: tr('terms'),
              subtitle: 'Read our terms of service',
              context: context,
              onTap: () => _showDialog(
                  'Terms of Service',
                  _termsOfServiceText,
                  context),
            ),

            const SizedBox(height: 8),
            _buildActionTile(
              icon: Icons.verified_user_outlined,
              iconColor: AppColors.textGray,
              title: 'Data Compliance',
              subtitle: 'Review data handling and compliance commitments',
              context: context,
              onTap: () => _showDialog(
                  'Data Compliance', _dataComplianceText, context),
            ),
            const SizedBox(height: 8),
            _buildActionTile(
              icon: Icons.gavel_outlined,
              iconColor: AppColors.textGray,
              title: 'GDPR Rights',
              subtitle: 'Review GDPR and privacy rights',
              context: context,
              onTap: () => _showDialog('GDPR Rights', _gdprText, context),
            ),
            const SizedBox(height: 8),
            _buildActionTile(
              icon: Icons.workspace_premium_outlined,
              iconColor: AppColors.textGray,
              title: 'Trademark Notice',
              subtitle: 'Review trademark and brand usage terms',
              context: context,
              onTap: () => _showDialog(
                  'Trademark Notice', _trademarkText, context),
            ),
            const SizedBox(height: 40),
          ],
        ),
      ),
    );
  }

  Widget _buildComposioCard(dynamic settings, BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Theme.of(context).cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: AppColors.scanCyan.withOpacity(0.4)),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: AppColors.scanCyan.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(10),
                ),
                child: const Icon(
                  Icons.auto_fix_high_rounded,
                  color: AppColors.scanCyan,
                  size: 20,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'AI Automations',
                      style: TextStyle(
                        color: Theme.of(context).textTheme.bodyLarge?.color ?? Colors.white,
                        fontSize: 14,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      'Link your apps (Gmail, Notion, Slack) to enable AI automations.',
                      style: TextStyle(
                        color: Theme.of(context).textTheme.labelSmall?.color ?? const Color(0xFF64748B),
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _connectAutomations,
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF38BDF8),
                foregroundColor: Colors.white,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
                padding: const EdgeInsets.symmetric(vertical: 12),
              ),
              child: const Text(
                'Connect Automations',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _connectAutomations() async {
    _maybeHaptic(ref.read(appSettingsProvider).hapticFeedback);
    const url = 'https://app.composio.dev';
    try {
      final bool success = await const MethodChannel('stremini.composio')
          .invokeMethod('openComposioConnect');
      if (!success) {
        await url_launcher.launchUrl(Uri.parse(url), mode: url_launcher.LaunchMode.externalApplication);
      }
    } catch (e) {
      await url_launcher.launchUrl(Uri.parse(url), mode: url_launcher.LaunchMode.externalApplication);
    }
  }

  Widget _buildSectionHeader(String title, BuildContext context) {
    return Text(
      title,
      style: TextStyle(
        color: Theme.of(context).textTheme.labelSmall?.color ??
            AppColors.textGray,
        fontWeight: FontWeight.w700,
        letterSpacing: 1.2,
        fontSize: 11,
      ),
    );
  }

  Widget _buildToggleTile({
    required IconData icon,
    required Color iconColor,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
    required BuildContext context,
  }) {
    final cardColor = Theme.of(context).cardColor;
    final textColor =
        Theme.of(context).textTheme.bodyLarge?.color ?? Colors.white;
    final subColor = Theme.of(context).textTheme.labelSmall?.color ??
        const Color(0xFF64748B);

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(
          color: Theme.of(context).dividerColor,
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: iconColor.withOpacity(0.15),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, color: iconColor, size: 20),
          ),
          const SizedBox(width: 16),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: TextStyle(color: textColor, fontSize: 14)),
                Text(subtitle,
                    style: TextStyle(color: subColor, fontSize: 12)),
              ],
            ),
          ),
          Switch(
            value: value,
            onChanged: onChanged,
            activeColor: AppColors.primary,
          ),
        ],
      ),
    );
  }

  Widget _buildActionTile({
    required IconData icon,
    required Color iconColor,
    required String title,
    required String subtitle,
    required VoidCallback onTap,
    required BuildContext context,
  }) {
    final cardColor = Theme.of(context).cardColor;
    final textColor =
        Theme.of(context).textTheme.bodyLarge?.color ?? Colors.white;
    final subColor = Theme.of(context).textTheme.labelSmall?.color ??
        const Color(0xFF64748B);

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(
          color: cardColor,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Theme.of(context).dividerColor),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: BoxDecoration(
                color: iconColor.withOpacity(0.15),
                borderRadius: BorderRadius.circular(10),
              ),
              child: Icon(icon, color: iconColor, size: 20),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(title,
                      style: TextStyle(color: textColor, fontSize: 14)),
                  Text(subtitle,
                      style: TextStyle(color: subColor, fontSize: 12)),
                ],
              ),
            ),
            Icon(Icons.chevron_right,
                color: subColor, size: 20),
          ],
        ),
      ),
    );
  }

  Widget _buildInfoTile({
    required IconData icon,
    required Color iconColor,
    required String title,
    required String value,
    required BuildContext context,
  }) {
    final cardColor = Theme.of(context).cardColor;
    final textColor =
        Theme.of(context).textTheme.bodyLarge?.color ?? Colors.white;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: cardColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Theme.of(context).dividerColor),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: iconColor.withOpacity(0.15),
              borderRadius: BorderRadius.circular(10),
            ),
            child: Icon(icon, color: iconColor, size: 20),
          ),
          const SizedBox(width: 16),
          Expanded(
              child:
                  Text(title, style: TextStyle(color: textColor, fontSize: 14))),
          Text(value,
              style: const TextStyle(
                  color: AppColors.primary,
                  fontSize: 14,
                  fontWeight: FontWeight.w600)),
        ],
      ),
    );
  }

  Future<void> _openKeyboardSetup() async {
    final KeyboardService service = KeyboardService();
    final status = await service.checkKeyboardStatus();
    _maybeHaptic(ref.read(appSettingsProvider).hapticFeedback);
    if (!status.isEnabled) {
      await service.openKeyboardSettings();
      return;
    }
    if (!status.isSelected) {
      await service.showKeyboardPicker();
      return;
    }
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
          content: Text(AppStrings.t(
              ref.read(appSettingsProvider).language, 'already_active'))),
    );
  }

  Future<void> _switchKeyboard() async {
    _maybeHaptic(ref.read(appSettingsProvider).hapticFeedback);
    await KeyboardService().showKeyboardPicker();
  }

  void _maybeHaptic(bool enabled) {
    if (!enabled) return;
    HapticFeedback.selectionClick();
  }

  void _showDialog(String title, String content, BuildContext context) {
    final settings = ref.read(appSettingsProvider);
    showDialog(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(title),
        content: SingleChildScrollView(
          child: Text(content,
              style: const TextStyle(height: 1.6, fontSize: 14)),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: Text(
              AppStrings.t(settings.language, 'close'),
              style: const TextStyle(color: AppColors.primary),
            ),
          ),
        ],
      ),
    );
  }
}

const String _termsOfServiceText = '''Effective date: June 2, 2026

These Terms of Service govern your access to and use of Stremini AI, including the AI assistant, keyboard, floating overlay, OCR, and related features.

1. Acceptance of Terms. By installing, accessing, or using Stremini AI, you agree to these terms. If you do not agree, do not use the app.

2. Eligibility and Account Security. You are responsible for keeping your device, account, unlock credentials, and any connected third-party accounts secure. You must not use the app if you are prohibited from doing so by applicable law.

3. Permitted Use. Use Stremini AI only for lawful, safe, and authorized purposes. You must not use the app to harass others, commit fraud, bypass security controls, scrape private data, infringe intellectual property, distribute malware, or automate actions you are not allowed to perform.

4. AI Limitations. AI responses, text analysis, translations, rewritten text, OCR output may be inaccurate, incomplete, delayed, or unsafe for a particular situation. You are responsible for reviewing outputs before relying on them or sending them to others. Do not use the app as a substitute for professional legal, medical, financial, security, or emergency advice.

5. Overlay Features. The floating overlay is intended to help you interact with your own device and access assistant features. You must enable overlay permission voluntarily in Android settings. Do not use these features to monitor another person, capture private information without consent, or violate platform rules.

6. User Content. You retain rights to text, images, voice input, screenshots, and other content you provide. You grant Stremini AI the limited permission needed to process that content to provide app features, maintain safety, troubleshoot, and improve reliability as described in the Privacy Policy.

7. Security. Stremini AI includes safeguards such as input sanitization, trusted-host networking, prompt-injection warnings, and protected AI payloads. No app can guarantee complete security. You agree to keep the app updated and report suspected vulnerabilities responsibly.

8. Third-Party Services. Some features may rely on device services, speech recognition, backend APIs, Android settings, or AI providers. Third-party terms and privacy practices may apply.

9. Intellectual Property. Stremini AI, its names, logos, designs, source code, and brand assets are owned by their respective rights holders. You receive a limited, non-transferable, revocable license to use the app for personal or authorized business use.

10. Disclaimers. The app is provided as is and as available without warranties of any kind, including implied warranties of merchantability, fitness for a particular purpose, non-infringement, availability, accuracy, or security.

11. Limitation of Liability. To the maximum extent permitted by law, Stremini AI and its contributors are not liable for indirect, incidental, special, consequential, exemplary, or punitive damages, lost profits, lost data, device issues, account actions, or decisions made based on AI output.

12. Termination. We may suspend or stop access to features if use appears unlawful, abusive, unsafe, or harmful to the service or others.

13. Changes. These terms may be updated from time to time. Continued use after changes means you accept the updated terms.

14. Contact. For legal, privacy, security, or support questions, contact the app owner through the official support channel listed for Stremini AI.''';

const String _privacyPolicyText = '''Effective date: June 2, 2026

This Privacy Policy explains how Stremini AI handles information when you use the app.

1. Information You Provide. Depending on the features you use, the app may process chat messages, keyboard text selected for correction or translation, OCR text extracted from images, voice-to-text transcripts, settings preferences, and support messages.

2. Device and Permission Data. The app may check whether overlay, microphone, keyboard, and notification permissions are enabled. These checks are used to operate requested features and do not grant access unless you enable them in Android settings.

3. OCR and Images. When text is extracted from an image, the app sanitizes the extracted text and removes emojis from OCR output before using it in app flows. Images and extracted text should be treated as sensitive if they contain personal information.

4. How Data Is Used. Data is used to provide AI responses, text correction, translation, keyboard features, account/session functionality, troubleshooting, security protections, abuse prevention, and compliance.

5. Data Sharing. Content may be sent to trusted backend services or AI processing endpoints when needed for the feature you request. We do not sell personal information. Data may be disclosed if required by law, to protect rights and safety, to prevent abuse, or to operate service providers under appropriate obligations.

6. Security Measures. The app applies input sanitization, prompt-injection protection wrappers, error-message redaction, HTTPS-only trusted host checks for native networking, session-based authorization where available, and length limits. These safeguards reduce risk but cannot eliminate all security risks.

7. Retention. The app should retain personal data only as long as needed for the feature, legal compliance, security, troubleshooting, or user-controlled history settings. If chat history saving is disabled, local history should not be intentionally retained beyond what is required for active use.

8. Your Choices. You can disable app permissions in Android settings, turn off chat history where available, clear app data from device settings, stop the floating overlay, disable the keyboard, or uninstall the app.

9. Children. Stremini AI is not intended for children under the age required by applicable law to consent to digital services. Do not provide children's personal data without appropriate authority.

10. International Transfers. If backend or AI services operate in other regions, data may be processed outside your location with appropriate safeguards where required.

11. Contact. For privacy requests, deletion questions, security reports, or complaints, contact the official Stremini AI support channel.''';

const String _dataComplianceText = '''Data Compliance Overview

Stremini AI is designed around data minimization, purpose limitation, transparency, and user control.

1. Lawful Basis. Data is processed to provide requested app functionality, perform a contract with the user, secure the service, comply with legal obligations, and, where required, based on consent.

2. Minimization. Inputs are sanitized, length-limited, and sent only when needed for the requested feature. OCR output is cleaned and emojis are removed from extracted image text.

3. Security Controls. Native network calls are restricted to HTTPS trusted hosts, user-visible error messages are redacted, and AI-bound text is wrapped with prompt-injection protection instructions.

4. Access Controls. Account-backed requests use authorization tokens where configured. Android permissions must be granted by the user and can be revoked in system settings.

5. Vendor Management. Third-party processors should be reviewed for confidentiality, security, availability, and data-processing commitments before production use.

6. Incident Response. Suspected data incidents should be investigated, contained, documented, and reported to affected users or authorities where legally required.

7. Records and Review. Privacy, security, and permission behavior should be reviewed before release and whenever features materially change.''';

const String _gdprText = '''GDPR and Privacy Rights

If the GDPR, UK GDPR, or similar privacy laws apply to you, you may have rights to access, correct, delete, restrict, object to, or receive a copy of your personal data.

1. Access. You may request confirmation of whether personal data is processed and request a copy of relevant data.

2. Correction. You may request correction of inaccurate or incomplete personal data.

3. Deletion. You may request deletion where data is no longer needed, consent is withdrawn, processing is unlawful, or deletion is otherwise required by law.

4. Restriction and Objection. You may request restricted processing or object to certain processing where applicable.

5. Portability. Where legally required, you may request a portable copy of data you provided.

6. Consent Withdrawal. Where processing is based on consent, you may withdraw consent at any time by changing permissions, disabling features, clearing app data, uninstalling the app, or contacting support.

7. Complaints. You may contact a data protection authority if you believe your rights have been violated.

8. Verification. Requests may require reasonable verification to protect your account and prevent unauthorized disclosure.''';

const String _trademarkText = '''Trademark Notice

Stremini AI, the Stremini AI name, logos, icons, interface designs, and related brand elements are trademarks, service marks, trade dress, or proprietary assets of their respective owners.

1. No License to Marks. Use of the app does not grant permission to copy, modify, distribute, register, or use Stremini AI marks except as necessary to identify the app truthfully.

2. Third-Party Marks. Android, Google, Supabase, and other names or logos referenced in the app belong to their respective owners. References are for compatibility, integration, or informational purposes and do not imply endorsement.

3. Prohibited Brand Use. Do not use Stremini AI branding in a way that suggests sponsorship, approval, partnership, or affiliation without written permission. Do not create confusingly similar apps, icons, names, domains, social handles, or marketing materials.

4. Feedback. If you submit feedback, suggestions, or ideas, they may be used to improve the app without obligation unless a separate written agreement says otherwise.''';
