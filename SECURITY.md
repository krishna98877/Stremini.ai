# Security Review & Threat Model

This document captures a security-focused code review of the current Stremini AI codebase, including observed threats and concrete fixes.

## Scope Reviewed

- Flutter app network clients and configuration.
- Android manifest exposure and privileged services.
- Android IME (keyboard) data handling.
- Android Accessibility automation pathways.
- Logging and local data persistence.

## Executive Summary

The app combines **high-privilege Android capabilities** (custom keyboard, accessibility service, overlay service, automation actions, media/clipboard/system controls) with **remote AI-driven decisioning**. This creates a powerful UX but also a high-risk security profile.

## Security Hardening Implemented

- Enforced HTTPS + trusted-host allowlist for Android OkHttp clients via a shared security interceptor (`TrustedHostInterceptor`).
- Added IME sensitive-field guard to block AI processing in password-style input fields.
- Changed clipboard history behavior to **opt-in by default** and added TTL-based expiry for stored entries.
- Added a high-risk action gate in device automation to block dangerous actions unless explicit opt-in is present in security preferences.

Most important risks to address first:

1. **Overly broad accessibility + automation control surface** can enable abuse if command sources are not strongly authenticated/authorized.
2. **Sensitive text exfiltration risk from IME workflows** (typed content, clipboard history, app context) if not blocked in secure fields and minimized.
3. **No transport hardening beyond default TLS** (no certificate pinning / network security constraints against trust-chain abuse).
4. **No documented incident response/disclosure flow** for security reports.

## Findings, Threats, and Fixes

### 1) High-Privilege Accessibility Service Exposure

**Threat**
- The Accessibility service is exported and can retrieve window content, view IDs, key events, and perform gestures globally.
- This expands impact if the service is ever abused by malformed intents, logic flaws, or prompt/command injection chains.

**Evidence**
- Service is exported and permission-bound in manifest.
- Accessibility config enables broad flags (retrieve windows, include not-important views, filter key events, perform gestures).

**Fixes**
- Keep `android.permission.BIND_ACCESSIBILITY_SERVICE` (already present), and additionally:
  - Implement strict command origin validation before any automation execution.
  - Add allowlist gating per action category (navigation-only vs destructive actions).
  - Require explicit in-app user confirmation for risky actions (open app, clipboard, media, system controls).
  - Add runtime “panic switch” to disable automation immediately.
  - Add telemetry for rejected/accepted actions with privacy-safe event metadata.

**Priority**: **P0**

---

### 2) IME Sensitive Data Handling (Typed Text + Clipboard)

**Threat**
- Keyboard logic reads surrounding text and sends content to remote backend for AI actions.
- Clipboard history is persisted in plain SharedPreferences JSON.
- Without secure-field checks, sensitive content (passwords, OTPs, PII, secrets) may be transmitted or stored.

**Evidence**
- IME reads before/after cursor text.
- AI action sends user text to backend endpoints.
- Clipboard history is stored in app preferences.

**Fixes**
- Block AI processing and cloud transmission when input type indicates sensitive fields:
  - `TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_NUMBER_VARIATION_PASSWORD`, visible password, OTP/autofill-sensitive contexts.
- Disable clipboard history by default; make opt-in with clear retention policy.
- Encrypt local clipboard history with Android Keystore-backed encryption.
- Add auto-expiry (e.g., 15–60 min) and max entry size.
- Add redaction/minimization before network send (emails/tokens/cards/phone numbers where feasible).

**Priority**: **P0**

---

### 3) Remote Command/Prompt Injection Risk in Automation Loop

**Threat**
- Backend-driven action execution can perform taps, typing, app launch, global actions, media keys, screenshots, clipboard operations.
- If backend output is compromised, manipulated, or prompted by untrusted screen text, the device may perform unsafe actions.

**Evidence**
- Full device command executor supports broad action list.
- Screen reader service can run generic automation commands.

**Fixes**
- Enforce a signed-command protocol for high-risk actions (HMAC/JWT with short TTL + nonce).
- Add local policy engine:
  - denylist dangerous combinations (e.g., open banking app + type secret),
  - per-action risk scoring,
  - “confirm before execute” for high-risk classes.
- Add step budget and domain/app allowlist in autonomous mode.
- Add prompt-injection resistance: treat on-screen text as untrusted input and sanitize instructions.

**Priority**: **P0**

---

### 4) Network Hardening Gaps

**Threat**
- App uses HTTPS but relies on default trust store only.
- No certificate pinning or Android network security policy for stricter trust behavior.

**Evidence**
- Multiple clients call fixed backend URLs over HTTPS.

**Fixes**
- Add certificate/public-key pinning (OkHttp `CertificatePinner`) for production domains.
- Add Android `network_security_config.xml` with clear production trust restrictions.
- Separate staging/prod base URLs and keys by build flavor; fail closed on unknown env.
- Add request authentication and replay protection (timestamp + nonce + signature).

**Priority**: **P1**

---

### 5) Data Governance & Privacy Controls Are Underspecified

**Threat**
- High-sensitivity features (keyboard + accessibility + screen analysis) require explicit user transparency and retention guarantees.

**Fixes**
- Publish in-app “Data Use & Security” page:
  - exactly what leaves device,
  - retention periods,
  - deletion controls,
  - third-party processors.
- Add per-feature toggles (AI typing assist, screen analysis, clipboard memory).
- Add local-only mode that prevents remote calls.

**Priority**: **P1**

---

### 6) Security Testing Coverage Needs Expansion

**Threat**
- Minimal security-focused tests increase regression risk.

**Fixes**
- Add tests for:
  - secure-field blocking,
  - command policy deny/allow matrix,
  - malformed backend action payload handling,
  - clipboard encryption/expiry,
  - network pinning failures.
- Add CI jobs for static analysis and dependency vulnerability scanning.

**Priority**: **P2**

## Recommended Remediation Plan

### Phase 1 (Immediate, 1–2 sprints)
- Implement secure-field AI block in IME.
- Disable/opt-in clipboard history + add TTL.
- Add local policy confirmation for risky automation actions.
- Add structured audit logging for automation decisions.

### Phase 2 (Near term, 2–4 sprints)
- Add certificate pinning + hardened network security config.
- Introduce signed backend commands with anti-replay controls.
- Add security test suite and CI security gates.

### Phase 3 (Program maturity)
- Formal threat modeling per release.
- External pentest (focus: accessibility/IME abuse paths).
- Bug bounty or coordinated vulnerability disclosure workflow.

## Vulnerability Disclosure Policy

If you discover a security issue:

1. Do **not** publicly disclose details immediately.
2. Report privately to the maintainers with:
   - affected version/commit,
   - reproduction steps,
   - impact assessment,
   - suggested remediation.
3. Maintainers should acknowledge within **7 days** and provide a remediation timeline.
4. Public disclosure should occur after a fix is available or by mutual timeline agreement.

## Security Baseline Checklist

- [ ] Sensitive-field detection blocks cloud AI processing.
- [ ] Clipboard persistence is encrypted, time-limited, and opt-in.
- [ ] High-risk automation requires explicit user confirmation.
- [ ] Backend commands are authenticated and replay-resistant.
- [ ] Certificate pinning enabled for production backends.
- [ ] Security tests run in CI for each PR.
- [ ] Incident response + disclosure policy documented and active.
