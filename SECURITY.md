# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in Stremini AI, please report it
**privately** — do NOT open a public GitHub issue.

Email: **streminiai@gmail.com**

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

## ⚠️ Known Limitation: Client-Embedded API Keys (S4)

**This is the highest-impact security limitation of the current architecture.**

`COMPOSIO_CONSUMER_KEY`, `GROQ_API_KEY`, and all `AUTH_CONFIG_*` values are
injected as `BuildConfig` string constants (and `--dart-define` on the
Flutter side). **ProGuard/R8 does NOT encrypt string constants** — they are
trivially recoverable from any distributed APK via `apktool`, `strings`, or
`jadx` regardless of minification.

### Impact

A leaked Composio consumer key lets anyone call the Composio API under your
account/quota. Depending on Composio's authorization model, an attacker who
also discovers a `connected_account_id` / `entity_id` (which are also
transmitted in API requests and could be sniffed from network traffic on a
rooted device) could potentially target accounts whose connections were
established by your users.

A leaked Groq API key lets anyone burn your Groq inference quota.

### Why it's this way

The app uses a **managed-auth, shared-developer-key** model: end users never
provide API keys — they tap "Connect" and log in via Composio's hosted OAuth.
This is great UX but fundamentally requires the developer key to live
client-side.

### Recommended mitigation (best — for production)

**Proxy all Composio + Groq calls through your own backend.** The app would
send requests to `api.yourdomain.com/composio/execute` instead of
`backend.composio.dev` directly. Your backend holds the consumer key
server-side, authenticates the user (via Firebase Auth, JWT, etc.), and
forwards the request to Composio with the developer key injected there.
The APK never contains the key.

### Interim mitigations (already in place)

- Keys are NOT in the open-source repo (scrubbed in commit `ef218cd`).
- Keys are injected at build time from `local.properties` (gitignored).
- `BuildConfig` fields are at least obfuscated by R8 in release builds
  (string constants are still recoverable, but it raises the bar slightly).
- ProGuard rules strip `Log.i`/`Log.d` calls in release builds so keys
  aren't accidentally logged.
- `usesCleartextTraffic="false"` + `network_security_config.xml` enforce
  HTTPS-only, preventing MITM sniffing of the key in transit.

### What YOU should do

1. **Rotate the Composio consumer key regularly** (monthly for a public app).
2. **Monitor Composio dashboard** for anomalous API usage patterns.
3. **Set up Composio quota limits** so a leaked key can't run up unlimited bills.
4. **Migrate to a backend proxy** before any large-scale public release.

---

## Threat Model

| Threat | Mitigation |
|--------|------------|
| Attacker reads repo, steals hardcoded API key, burns your Groq quota | No hardcoded keys — every secret is injected via `BuildConfig` or `dart-define` from a gitignored file |
| Attacker reads repo, finds your Composio `auth_config_id`, impersonates your OAuth app | `auth_config_id` is also injected from `local.properties` — empty in the public repo |
| Attacker reads git history, finds a key you committed by accident before scrubbing | Document the rotation procedure above; secrets in history = compromised forever |
| **Attacker extracts API keys from a distributed APK via apktool/jadx** | **Known limitation (S4) — see above. Migrate to a backend proxy for production.** |
| MITM intercepts Groq / Composio traffic | HTTPS only (`usesCleartextTraffic="false"`); Groq + Composio have valid public certs |
| User installs a malicious fork that exfiltrates their contacts | `READ_CONTACTS` permission is declared; users see it at install time. Contact resolution only runs locally on-device, never uploaded. |
| Prompt injection via chat message leaks system prompt | `SecurityGuards.kt` sanitizes user input; Groq system prompt explicitly forbids revealing instructions |
| **Malicious app fires `stremini://composio?status=success` to spoof a connection (S1)** | **Fixed: MainActivity re-verifies with the real Composio API before notifying Flutter; Flutter only trusts server-verified events.** |
| **Spoofed OAuth redirect without a prior user-initiated connect (S2)** | **Fixed: pending-connect nonce with 10-min TTL. Redirect rejected if no pending connect exists for the claimed service.** |
| **Ambiguous OAuth redirect shows false success (S3)** | **Fixed: ComposioAuthActivity verifies via `isServiceConnected()` before showing the green checkmark.** |
| **PII (message text, phone numbers) leaked to logcat (S5)** | **Fixed: all `Log.i` calls now log field presence / truncated values, never raw params. See ComposioClient.kt.** |

---

## License

This project is open source. By contributing, you agree that your
contributions will be licensed under the same license as the project.
