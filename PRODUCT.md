# Stremini AI — Product Documentation

> **Version 1.0.0** | Android | Flutter + Kotlin | Groq LLM + Composio Automation
> **Repository:** [github.com/krishna98877/Stremini.ai](https://github.com/krishna98877/Stremini.ai)

---

## Table of Contents

1. [What is Stremini AI?](#1-what-is-stremini-ai)
2. [Core Features Overview](#2-core-features-overview)
3. [Floating Bubble Assistant](#3-floating-bubble-assistant)
4. [AI Keyboard (IME)](#4-ai-keyboard-ime)
5. [In-App Chat Screen](#5-in-app-chat-screen)
6. [Composio Automation — 11 Services](#6-composio-automation--11-services)
7. [AI Brain — Dual-Model Architecture](#7-ai-brain--dual-model-architecture)
8. [Settings & Personalization](#8-settings--personalization)
9. [Security & Privacy](#9-security--privacy)
10. [Localization — 6 Languages](#10-localization--6-languages)
11. [Themes](#11-themes)
12. [Contact & Support](#12-contact--support)
13. [Legal Documents](#13-legal-documents)
14. [Technical Architecture](#14-technical-architecture)
15. [Permissions](#15-permissions)

---

## 1. What is Stremini AI?

Stremini AI is an Android app that puts an AI assistant **everywhere** on your phone — not locked inside a single chat app. It works in three ways:

1. **Floating Bubble** — a draggable bubble that floats over any app. Tap it to open a semicircle menu with AI Chat, Connectors, and Keyboard Switcher.
2. **AI Keyboard** — a full replacement keyboard (like Gboard) with AI-powered text completion, grammar correction, tone rewriting, translation, voice typing, emoji, and kaomoji panels.
3. **In-App Chat** — a polished chat screen inside the app where you can attach PDFs, text files, and images for AI analysis.

The app integrates **Groq's ultra-fast LLM inference** (Llama 3.3 70B for chat, Llama 3.1 8B for keyboard actions) and **Composio's managed OAuth platform** for automation — enabling users to send emails, post to social media, manage GitHub repos, and interact with 11 services through natural language, all without ever providing API keys.

### Key Differentiators
- AI that lives **outside** any single app — a true system-wide assistant
- Zero-config automation — users connect services with their own OAuth login, not API keys
- Two AI models: powerful 70B for chat, fast 8B for keyboard actions
- Works even when the Groq API is down — falls back to regex-based intent parsing
- Enterprise-grade security: AES-256-GCM encryption, prompt injection defense, PII-safe logging

---

## 2. Core Features Overview

| Feature | Description |
|---------|-------------|
| Floating Bubble | Draggable overlay bubble accessible from any app with semicircle radial menu |
| AI Chatbot (overlay) | Full chat UI floating over any app with voice input + connectors toggle |
| AI Chatbot (in-app) | Full-screen chat with PDF/text/image attachments + OCR |
| AI Keyboard | QWERTY + 2 symbol layers, AI actions, voice typing, emoji, kaomoji, clipboard |
| Composio Automation | 11 services with managed OAuth — send emails, post to social, manage repos |
| Dual AI Models | 70B for chat, 8B for keyboard — both via Groq API |
| 6-Language UI | English, Hindi, Spanish, French, Arabic, Japanese |
| 13 Voice Languages | Voice typing in Hindi, English, Bengali, Tamil, Telugu, Marathi, Gujarati, Kannada, Malayalam, Punjabi, Urdu, Japanese, Arabic |
| Dark/Light/System Themes | Full Material 3 theming with glassmorphic UI |
| Chat History | Toggleable local persistence of conversations |
| Document Q&A | Load PDFs/text files and ask questions about their content |
| Image OCR | Extract text from images via Google ML Kit for AI reasoning |

---

## 3. Floating Bubble Assistant

### How it works
A foreground service (`ChatOverlayService`) renders a draggable bubble using Android's `WindowManager` with `TYPE_APPLICATION_OVERLAY`. The bubble floats over any app.

### Bubble behavior
- **Drag**: Touch and drag to reposition. Releases snap to the nearest screen edge.
- **Tap**: Opens a semicircle radial menu with 3 items.
- **Idle dim**: After 3 seconds of inactivity, the bubble dims to 50% alpha. Touching it restores full opacity.
- **Long press**: Opens the system keyboard switcher.

### Semicircle Menu (3 items)
The menu opens as an animated semicircle arc around the bubble:

| Position | Icon | Action |
|----------|------|--------|
| Left | Settings (plug) | Opens the Connectors panel — manage all 11 Composio services |
| Center | Brain | Opens the floating chatbot — full chat UI in an overlay window |
| Right | Keyboard | Opens the system keyboard picker to switch keyboards |

The menu intelligently opens away from the nearest screen edge (left half → opens right, right half → opens left).

### Floating Chatbot
When the brain icon is tapped, a full chat window (300×420dp) slides in from the bottom-right corner with:
- **Header**: Stremini AI logo + title + close button (draggable to reposition)
- **Messages area**: Scrollable chat bubbles (user = cyan-tinted, bot = white-tinted)
- **Voice input**: Microphone button with live partial results display
- **Connectors toggle**: Plug icon with active-count badge
- **Text input**: "Ask anything..." field with send button
- **Connectors panel**: Manus-style bottom sheet showing all 11 services with Connect/Disconnect buttons

### Connectors Panel (overlay)
- Manus-style horizontal rows: `[icon] [name + status] [Connect/Disconnect button]`
- Slide-up + fade-in entry animation (300ms)
- Slide-down + fade-out exit animation (220ms)
- Drag handle pill at top
- Search bar to filter services
- Shows connected count in header
- Tap Connect → OAuth opens in Chrome → polls for connection every 3s for up to 2 minutes
- Tap Disconnect → removes the connection immediately

### Notification controls
The foreground service shows a persistent notification with:
- Title: "Stremini AI"
- Text: "Bubble active — tap to open app"
- Actions: Toggle Bubble, Stop Service

---

## 4. AI Keyboard (IME)

### Layout
- **QWERTY** alphanumeric layout with full number row
- **Symbols Layer 1** (Gboard ?123 style): `@#$%_-&+()/*"'':;!?~\|=`
- **Symbols Layer 2** (Gboard =\\< style): `~`|•√π÷×¶∆£¢€¥^°≤≥≈±§©®™{}`
- **Long-press**: Dot key long-press → comma
- **Backspace repeater**: 40ms interval (25 chars/sec)
- **Shift**: Tap toggles shift, double-tap enables CapsLock
- **Enter**: Context-aware (send, go, search, done, next)

### AI Actions (4 features, hidden until toggled on)
Toggled via the AI button in the keyboard toolbar. When enabled, 4 action buttons appear:

| Action | Icon | What it does |
|--------|------|-------------|
| **Correct** | Spell-check | Fixes grammar, spelling, and punctuation in selected text |
| **Complete** | Auto-complete | Contextually completes the sentence based on app context |
| **Tone** | Palette | Rewrites selected text in a chosen tone (8 options) |
| **Translate** | Globe | Translates selected text to a chosen language (8+ options) |

### Tone Changer (8 tones)
| Tone | Icon | Description |
|------|------|-------------|
| Formal | 👔 | Professional & polished |
| Friendly | 😊 | Warm & approachable |
| Concise | 🎯 | Direct & to the point |
| Persuasive | 💡 | Convincing marketing copy |
| Funny | 😂 | Witty with a sense of humor |
| Empathetic | 🤝 | Supportive & polite |
| Confident | 🔥 | Bold & assertive |
| Social Media | 📱 | Trendy for social posts |

### Translation (8+ languages)
English, Spanish, French, German, Hindi, Portuguese, Arabic, Japanese (full list fetched at runtime, 30 languages available).

### Voice Typing (13 languages)
Continuous speech recognition with:
- **13 supported languages**: Hindi, English (India/US/UK), Bengali, Tamil, Telugu, Marathi, Gujarati, Kannada, Malayalam, Punjabi, Urdu
- **Continuous mode**: Auto-restarts recognition when speech ends
- **Error tolerance**: Up to 3 consecutive errors before aborting
- **Visual indicator**: Cyan microphone indicator with pulse animation
- **Partial results**: Shows recognized text in real-time as you speak

### Emoji Panel (9 categories)
| Category | Icon | Example |
|----------|------|---------|
| Recent | 😊 | (last 30 used) |
| Smileys | 😀 | 😀😁😂🤣😃😄 |
| People | 👋 | 👋👌👍👎✌️🤞 |
| Animals | 🐱 | 🐱🐶🦊🐻🐼🐨 |
| Food | 🍕 | 🍕🍔🍟🌭🍿🧂 |
| Activity | ⚽ | ⚽🏀🏈⚾🎾🏐 |
| Travel | 🚗 | 🚗✈️🚀🚕🚙🏎️ |
| Objects | 💡 | 💡📱💻⌚📷🔔 |
| Symbols | ❤️ | ❤️💔💯💢💥💫💦 |

8-column grid, 28sp emoji size, category tab bar at bottom.

### Kaomoji Panel (15 categories)
Happy & Smile, Sad & Crying, Surprise & Shock, Angry & Upset, Love & Romance, Laugh & Joke, Shy & Embarrassed, Confused & Thinking, Animals, Food & Eating, Hand Gestures, Sleep & Tired, Dance & Party, Cute & Baby, Victorious & Strong.

### Clipboard History
- Stores last 5 copied text items (ring buffer)
- Quick-paste panel with tap-to-insert
- Inline paste suggestions when clipboard has content
- Toggleable on/off

### Additional keyboard features
- **One-handed mode**: Shifts keyboard left/right for thumb typing
- **Height adjustment**: Expand/collapse keyboard height
- **Settings menu**: Access keyboard settings (FLAG_SECURE)
- **Haptic feedback**: Vibration on key presses (toggleable)
- **App-context detection**: Tracks which app is active for smarter AI completions
- **Undo**: Undo last text input

---

## 5. In-App Chat Screen

### Overview
A full-screen chat interface inside the app with glassmorphic dark UI.

### Features
- **Message bubbles**: User (cyan-tinted, right-aligned) and bot (white-tinted, left-aligned) with asymmetric corner radii
- **Typing indicator**: Animated "Thinking…" bubble while AI processes
- **File attachments**: 4 types supported:
  - **PDF Document** → extracts text via Syncfusion PDF library, all pages
  - **Text File** → `.txt`, `.md`, `.csv`, `.json`, `.log`
  - **Image** → OCR via Google ML Kit, extracted text included in AI context
  - **Other File** → any file type
- **Document Q&A mode**: When a document is loaded, a banner shows the filename. All subsequent questions are answered in document context using a doc-aware system prompt.
- **Selectable text**: Long-press any message to select/copy text
- **Clear chat**: Button in app bar to reset conversation
- **File preview**: Thumbnail (images) or icon (documents) with filename + "Text extracted for AI reasoning" confirmation
- **Attachment picker**: Bottom sheet with 4 colorful options

### Message model
```dart
enum MessageType { user, bot, typing, documentBanner }
```

---

## 6. Composio Automation — 11 Services

### How it works
1. User opens the Connectors panel (from Settings or the floating bubble)
2. Taps "Connect" on a service (e.g., Gmail)
3. Chrome opens Composio's hosted OAuth page
4. User logs in with their **own** Gmail/GitHub/etc. credentials
5. Composio redirects back to `stremini://composio` → connection saved
6. User toggles the connector ON in the panel
7. Now the chatbot can automate that service via natural language

**No API keys needed.** The developer's Composio consumer key is embedded at build time. Users only provide their own OAuth login.

### Supported Services (11)

| Service | Actions | Example Commands |
|---------|---------|-----------------|
| **Gmail** | Send email, fetch emails, list messages | "send email to john@example.com saying hello" |
| **GitHub** | Create issue, create repo, list repos, create PR | "create a github issue titled Bug fix" |
| **WhatsApp** | Send message | "send hi to royal on whatsapp" |
| **Instagram** | Send DM | "send hello on instagram to john" |
| **Facebook** | Create post | "post hello world on facebook" |
| **Discord** | Send channel message | "send hello everyone on discord" |
| **LinkedIn** | Create post | "post on linkedin about our new product" |
| **Reddit** | Create post | "post on reddit title My Post with text content" |
| **Google Drive** | Create file from text, find file | "upload a file to google drive named test.txt with content hello" |
| **Google Sheets** | Get values, append values | "read google sheets spreadsheet abc123" |
| **YouTube** | Upload video, post comment | "upload a youtube video titled My Video" |

### Automation pipeline
```
User message → detectService() → isConnectorActive()?
  → YES: executeAutomation()
    → Check cache (instant repeat)
    → parseIntentWithLLM() OR parseIntentByKeywords() (fallback)
    → resolveContactParams() (normalize param names per service)
    → executeAction() → POST /api/v3.1/tools/execute/{actionId}
    → Cache result for instant repeat
  → NO: "Please connect X in Manage Connectors"
```

### Retry logic
- First attempt fails → "Let me try that again..." → 1.5s pause → retry
- Permanent errors (not connected, expired, permission denied) → no retry, show clear message
- Second failure → shows specific error message (403, 401, 429, timeout, parse failure)

### Keyword fallback (works without Groq)
If the Groq API is unreachable (403, network error, rate limit), the automation falls back to regex-based keyword parsing that needs **no network**. This means simple commands like "send email to X" work even when the AI brain is down.

### AI learning cache
- Successful automations are cached locally
- Repeat commands execute instantly (~0ms vs 2-5s LLM round-trip)
- Cache stores resolved params (contact names → phone numbers already resolved)
- Cache-hit path re-runs `resolveContactParams()` defensively

### Security
- **OAuth state nonce** (Fix S2): Each connect attempt generates a UUID nonce with 10-minute TTL. Redirects are rejected if no pending connect exists.
- **Server-verified events** (Fix S1): Deep-link callbacks are re-verified against the real Composio API before notifying Flutter. Flutter only trusts events with `verified: true`.
- **Ambiguous redirect protection** (Fix S3): `ComposioAuthActivity` calls `isServiceConnected()` to verify before showing the green checkmark.

---

## 7. AI Brain — Dual-Model Architecture

| Use case | Model | Max tokens | Temperature | Features |
|----------|-------|------------|-------------|----------|
| Chat (overlay + in-app) | `llama-3.3-70b-versatile` | 1024–2048 | 0.3–0.7 | Multi-turn conversation, document Q&A, automation intent parsing |
| Keyboard actions | `llama-3.1-8b-instant` | 1024 | 0.2–0.8 | Grammar correction, text completion, tone rewriting, translation |

### API key injection
- **Kotlin side**: `BuildConfig.GROQ_API_KEY` ← `local.properties` → `groq.api.key`
- **Dart side**: `--dart-define=GROQ_API_KEY=...` at build time
- **All 3 AI brain locations use the same key:**
  1. `GroqClient.kt` (chatbot) → `BuildConfig.GROQ_API_KEY`
  2. `IMEBackendClient.kt` (keyboard AI) → `BuildConfig.GROQ_API_KEY`
  3. `chat_provider.dart` (Flutter chat) → `String.fromEnvironment('GROQ_API_KEY')`

### System prompt (chatbot)
Identifies as "Stremini AI, a powerful AI assistant built into a keyboard app." Lists all 11 Composio services. Instructs the model to acknowledge when a user request involves an external service and that automation will be triggered. Designed for "floating chat bubble" — "be quick and useful."

---

## 8. Settings & Personalization

### AI Assistant
- **Notifications** (toggle): "Receive alerts from the AI assistant"

### Keyboard
- **AI Keyboard Setup**: Opens system keyboard settings to enable Stremini keyboard
- **Switch Keyboard**: Opens system keyboard picker

### Privacy
- **Haptic Feedback** (toggle): "Vibration on key presses"
- **Save Chat History** (toggle): "Store conversations locally" — JSON-encoded in SharedPreferences

### Automations
- **Manage Connectors**: Opens the Manus-style ConnectorsPanel bottom sheet with all 11 services

### About
- **Version**: 1.0.0
- **Privacy Policy**: 11 sections (effective June 2, 2026)
- **Terms of Service**: 14 sections
- **Data Compliance**: 7 sections
- **GDPR Rights**: 8 sections
- **Trademark Notice**: 4 sections

---

## 9. Security & Privacy

### Network Security
- HTTPS-only enforcement (`usesCleartextTraffic="false"`)
- Trusted host whitelist: `api.groq.com`, `backend.composio.dev`, `connect.composio.dev`
- Custom `network_security_config.xml` with system CAs only
- Response body cap: 512KB. Request body cap: 1MB.

### Data Storage
- **AES-256-GCM** encrypted SharedPreferences via Android Keystore (128-bit GCM auth tag, unique IV per operation)
- Used for: Composio keys, OAuth tokens, connector toggle states, pending-connect nonces
- Regular SharedPreferences for non-sensitive data (theme, language, clipboard history, chat history)

### Input Sanitization
- Control character stripping
- Bidirectional control character removal (prevents Unicode spoofing)
- Whitespace normalization
- Prompt injection detection: "ignore previous instructions", "reveal your prompt", "jailbreak", etc.
- `protectForAi()` wrapper prepends security boundary to all LLM-bound text
- OCR text sanitization: strips emojis from extracted image text

### WebView Security (OAuth)
- `FLAG_SECURE` — prevents screenshots and screen recording
- On destroy: clears all cookies, cache, history, and WebStorage
- No JavaScript bridge exposed
- Cookies cleared before each OAuth attempt (prevents wrong-account reconnects)

### PII-Safe Logging
- `safeInstruction(s)`: truncates to first 30 chars + length
- `safeParams(params)`: formats as `{key: <len=N>}` — never logs values
- `safePhoneTail(number)`: shows only last 4 digits
- ProGuard strips `Log.d/v/i` in release builds

### ProGuard (release builds)
- `isMinifyEnabled = true`, `isShrinkResources = true`
- Keeps: Stremini, Flutter, OkHttp, JSON, ML Kit classes
- Strips: Log.d/v/i calls, unused code

### Known Limitation
API keys are embedded in `BuildConfig` string constants and are recoverable from the APK via `apktool`/`jadx`. For production, proxy all API calls through your own backend. See `SECURITY.md` for details.

---

## 10. Localization — 6 Languages

| Language | Code | Locale |
|----------|------|--------|
| English | en | `Locale('en')` |
| Hindi | hi | `Locale('hi')` |
| Spanish | es | `Locale('es')` |
| French | fr | `Locale('fr')` |
| Arabic | ar | `Locale('ar')` |
| Japanese | ja | `Locale('ja')` |

Localized strings include: app title, settings labels, section headers, keyboard setup, theme, language names, greeting message, close button.

---

## 11. Themes

### Dark Theme (default)
- Background: `#000000`
- Surface: `#111111` (90% opacity)
- Surface highlight: `#1A1A1A` (95% opacity)
- Border: white @ 10%
- Card: 18-radius, glass border 0.5px
- AppBar: black, no elevation, centered titles
- Text: white (body), `#6B7280` (muted), `#4A5568` (dim)

### Light Theme
- Background: `#F7F8FA`
- Surface: white
- Border: `#E8EAF0`
- Text: `#0A0C10` (primary), `#6B7280` (muted)

### System Default
Follows the device's dark/light setting.

### Color Palette
| Token | Value | Usage |
|-------|-------|-------|
| primary | `#23A6E2` | Buttons, highlights, active states |
| secondary | `#AA75F4` | Secondary actions |
| scanCyan | `#00D9FF` | Keyboard, scan indicators |
| success | `#4CAF50` | Connected status |
| danger | `#D32F2F` | Errors, delete |
| warning | `#FF9800` | Warnings, save history |
| glass | white @ 5% | Glassmorphic card fills |
| glassBorder | white @ 10% | Glassmorphic card borders |
| accentGlow | cyan @ 20% | Focused input borders |

---

## 12. Contact & Support

### Contact screen
- **Support email**: `streminiai@gmail.com`
- **Form fields**: Name, Email (validated), Subject, Message (min 20 chars)
- **Quick actions**: "Email Us" (copies to clipboard), "Report Bug" (auto-fills subject)
- **Send flow**: Builds `mailto:` URI → opens in external email app
- **Response time**: "Typically respond within 24–48 hours"

### FAQ
1. "How do I enable the floating bubble?"
2. "Why does the app need certain permissions?"
3. "Is my chat data stored?" (in-memory only, cleared on close)
4. "How do I reset all permissions?"

---

## 13. Legal Documents

All accessible from Settings → About:

| Document | Sections | Content |
|----------|----------|---------|
| Privacy Policy | 11 | Data collection, usage, retention, third-party services, user rights |
| Terms of Service | 14 | Acceptable use, intellectual property, disclaimers, liability |
| Data Compliance | 7 | GDPR, CCPA, data processing, international transfers |
| GDPR Rights | 8 | Right to access, rectify, erase, restrict, port, object |
| Trademark Notice | 4 | Stremini AI branding, third-party trademarks |

All documents are effective June 2, 2026.

---

## 14. Technical Architecture

### Tech Stack
| Layer | Technology |
|-------|-----------|
| Frontend | Flutter 3.41+ (Dart 3.11+) |
| Native | Kotlin 2.1.0, AGP 8.9.1, Gradle 8.11.1 |
| State Management | flutter_riverpod 3.x (AsyncNotifier pattern) |
| AI (chat) | Groq API — Llama 3.3 70B Versatile |
| AI (keyboard) | Groq API — Llama 3.1 8B Instant |
| Automation | Composio Managed OAuth (v3/v3.1 API) |
| HTTP | OkHttp 4.12.0 (Kotlin), http 1.5.0 (Dart) |
| OCR | Google ML Kit Text Recognition 16.0.1 |
| PDF | Syncfusion Flutter PDF 33.1.47 |
| Storage | EncryptedSharedPreferences (AES-256-GCM) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 36 (Android 16) |

### Method Channels (Dart ↔ Kotlin bridge)
| Channel | Direction | Purpose |
|---------|-----------|---------|
| `stremini.composio` | Dart→Kotlin | connect/disconnect/execute automation |
| `stremini.composio/events` | Kotlin→Dart | connection_success (verified), connection_lost |
| `stremini.chat.overlay` | Dart→Kotlin | start/stop overlay, permission checks |
| `stremini.keyboard` | Dart→Kotlin | keyboard status, settings, picker |
| `stremini.ocr` | Dart→Kotlin | extract text from image |

### Project structure
```
lib/
├── main.dart                          — App entry, ProviderScope, MaterialApp
├── controllers/
│   └── home_controller.dart           — Home screen state
├── providers/
│   ├── chat_provider.dart             — Chat state, Groq client, Composio manager
│   └── app_settings_provider.dart     — Settings state, chat history persistence
├── services/
│   ├── composio_service.dart          — Composio Dart client (11 services)
│   ├── groq_client.dart               — Groq LLM client
│   ├── keyboard_service.dart          — Keyboard MethodChannel bridge
│   ├── image_text_extractor.dart      — OCR via MethodChannel
│   ├── overlay_service.dart           — Overlay MethodChannel bridge
│   └── permission_service.dart        — Permission helpers
├── screens/
│   ├── home/home_screen.dart          — Dashboard
│   ├── chat_screen.dart               — In-app chat
│   ├── settings_screen.dart           — Settings
│   └── contact_us_screen.dart         — Contact form + FAQ
├── features/
│   ├── connectors/
│   │   └── connectors_panel.dart      — Manus-style connectors bottom sheet
│   └── chat/
│       ├── data/                      — Chat client, repository
│       └── domain/                    — Use cases, repository interface
├── core/
│   ├── theme/                         — Colors, text styles, themes
│   ├── localization/app_strings.dart  — 6-language strings
│   ├── security/input_sanitizer.dart  — Prompt injection defense
│   ├── constants/app_constants.dart   — App constants
│   ├── result/result.dart             — Result type
│   └── widgets/                       — AppContainer, AppDrawer
└── models/
    └── message_model.dart             — Message data model

android/app/src/main/kotlin/com/android/stremini_ai/
├── StreminiIME.kt                     — AI Keyboard IME (1,740 lines)
├── ChatOverlayService.kt              — Floating bubble service (1,463 lines)
├── ComposioClient.kt                  — Composio REST client (1,845 lines)
├── GroqClient.kt                      — Groq chat client
├── IMEBackendClient.kt                — Keyboard AI client
├── ChatCommandCoordinator.kt          — Chat routing + retry logic
├── ComposioAuthActivity.kt            — OAuth WebView activity
├── KeyboardPanels.kt                  — Emoji, kaomoji, tone, translate panels
├── SecurityGuards.kt                  — Sanitization, rate limiting, trusted hosts
├── EncryptedPrefs.kt                  — AES-256-GCM storage
├── MainActivity.kt                    — Flutter embedding + channel hub
└── ... (supporting files)
```

---

## 15. Permissions

| Permission | Why it's needed |
|------------|----------------|
| `INTERNET` | Groq API (chat), Composio API (automation) |
| `SYSTEM_ALERT_WINDOW` | Floating bubble overlay |
| `FOREGROUND_SERVICE` | Keep bubble alive in background |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ foreground service type |
| `POST_NOTIFICATIONS` | Android 13+ notification permission |
| `RECORD_AUDIO` | Voice typing in keyboard + chatbot |
| `READ_CONTACTS` | Resolve contact names to phone numbers for WhatsApp |

---

*Built with Flutter, Kotlin, Groq, and Composio. Open source at [github.com/krishna98877/Stremini.ai](https://github.com/krishna98877/Stremini.ai).*
