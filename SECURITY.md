# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Stremini AI, please report it
**privately** — do NOT open a public GitHub issue.

Email: **security@stremini.ai**

Include:
- A clear description of the issue
- Steps to reproduce
- The impact (what an attacker could do)
- Suggested fix (optional)

We will acknowledge within 48 hours and aim for a fix within 7 days.

---

## Secret Management

This repository is **open source** — anyone can read every line of code.
Therefore, **no real API keys, tokens, or account IDs are ever committed**.

### What counts as a secret?

| Type | Example | Where it lives |
|------|---------|----------------|
| Groq API key | `gsk_…` | `local.properties` (gitignored) |
| Composio consumer key | `ak_…` | `local.properties` (gitignored) |
| Composio `auth_config_id` per service | `ac_…` | `local.properties` (gitignored) |
| WhatsApp Business `phone_number_id` | `1098765432100000` (example only — never commit a real one) | `local.properties` (gitignored) |
| Instagram Page-Scoped ID (PSID) | `178414…` | `local.properties` (gitignored) |
| GitHub Personal Access Token | `github_pat_…` / `ghp_…` | your password manager |
| Android signing keystore | `*.keystore` / `*.jks` | gitignored |
| Google service account JSON | `*.json` | gitignored |

### How the app reads secrets at runtime

1. **Build time (Kotlin / Android)** — `android/app/build.gradle.kts` reads
   from `android/local.properties` (or env vars) and injects every secret as
   a `BuildConfig` field. The Kotlin code reads from `BuildConfig.<NAME>`.
2. **Build time (Dart / Flutter)** — pass `--dart-define=GROQ_API_KEY=…` to
   `flutter build`. The Dart code reads via `String.fromEnvironment(…)`.
3. **Runtime (user-supplied)** — users can enter their own Groq key in the
   app's Settings screen; it's stored in `EncryptedSharedPreferences`.

### Setup for forks / contributors

1. Copy `android/local.properties.example` → `android/local.properties`
2. Fill in your own keys (Groq + Composio minimum)
3. For Dart-side Groq access, build with:
   ```bash
   flutter build apk --dart-define=GROQ_API_KEY=gsk_your_key_here
   ```
4. Never commit `local.properties`. It's in `.gitignore`.

### What if a secret was accidentally committed?

1. **Rotate the secret immediately** at the provider's dashboard. Once a key
   is in git history, assume it's compromised — even after `git filter-repo`
   removes it from your fork, anyone who already cloned has it forever.
2. Force-push the cleaned history.
3. Audit logs at the provider for unauthorized usage during the leak window.

---

## Architecture Notes

- The app uses Composio's **managed authentication** model. End users never
  provide API keys — they tap "Connect" and log in to GitHub/Gmail/etc. via
  Composio's hosted OAuth page using their own credentials.
- The **developer** provides the Composio consumer key (for billing + routing)
  and one `auth_config_id` per service (to identify the OAuth app config).
- All network calls use HTTPS. Certificate pinning is NOT enforced because
  Composio and Groq use public CAs that rotate periodically.
- User-supplied keys (entered in Settings) are stored in
  `EncryptedSharedPreferences` (Android Keystore-backed).
- ProGuard rules preserve Flutter plugin classes so reflection-based
  deserialization of API responses isn't broken in release builds.

---

## Threat Model

| Threat | Mitigation |
|--------|------------|
| Attacker reads repo, steals hardcoded API key, burns your Groq quota | No hardcoded keys — every secret is injected via `BuildConfig` or `dart-define` from a gitignored file |
| Attacker reads repo, finds your Composio `auth_config_id`, impersonates your OAuth app | `auth_config_id` is also injected from `local.properties` — empty in the public repo |
| Attacker reads git history, finds a key you committed by accident before scrubbing | Document the rotation procedure above; secrets in history = compromised forever |
| MITM intercepts Groq / Composio traffic | HTTPS only (`usesCleartextTraffic="false"`); Groq + Composio have valid public certs |
| User installs a malicious fork that exfiltrates their contacts | `READ_CONTACTS` permission is declared; users see it at install time. Contact resolution only runs locally on-device, never uploaded. |
| Prompt injection via chat message leaks system prompt | `SecurityGuards.kt` sanitizes user input; Groq system prompt explicitly forbids revealing instructions |

---

## License

This project is open source. By contributing, you agree that your
contributions will be licensed under the same license as the project.
