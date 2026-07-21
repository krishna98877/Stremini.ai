# GitHub Actions Quick Start Setup

## TL;DR - 3 Steps to Get Started

### Step 1: Create GitHub Fine-Grained PAT
```
https://github.com/settings/tokens?type=beta
- Create new fine-grained token
- Name: stremini-ai-build
- Repository: krishna98877/Stremini.ai
- Permissions: actions:read, contents:read
```

### Step 2: Add Repository Secrets
Visit: https://github.com/krishna98877/Stremini.ai/settings/secrets/actions

Add these secrets:
- `GROQ_API_KEY` - Your Groq API key
- `COMPOSIO_CONSUMER_KEY` - Composio consumer key
- `AUTH_CONFIG_GITHUB` - GitHub OAuth config
- `AUTH_CONFIG_GMAIL` - Gmail OAuth config
- `AUTH_CONFIG_INSTAGRAM` - Instagram OAuth config
- `AUTH_CONFIG_FACEBOOK` - Facebook OAuth config
- `AUTH_CONFIG_WHATSAPP` - WhatsApp OAuth config
- `AUTH_CONFIG_GOOGLEDRIVE` - Google Drive OAuth config
- `AUTH_CONFIG_DISCORD` - Discord OAuth config
- `AUTH_CONFIG_LINKEDIN` - LinkedIn OAuth config
- `AUTH_CONFIG_REDDIT` - Reddit OAuth config
- `AUTH_CONFIG_GOOGLESHEETS` - Google Sheets OAuth config
- `AUTH_CONFIG_YOUTUBE` - YouTube OAuth config
- `WHATSAPP_PHONE_NUMBER_ID` - WhatsApp phone number ID
- `INSTAGRAM_DEFAULT_PSID` - Instagram default PSID

### Step 3: Push or Manually Trigger Build
```bash
# Option A: Push to main branch
git push origin main

# Option B: Manual trigger via GitHub Actions tab
# Go to Actions → Build APK → Run workflow
```

## Check Build Status
- Go to: https://github.com/krishna98877/Stremini.ai/actions
- Look for "Build APK" workflow
- Click to view logs and download APK

## What Changed?

### New/Modified Files:
1. `.github/workflows/build.yml` - Updated to use GitHub Secrets instead of hardcoded keys
2. `scripts/setup-github-secrets.sh` - Script to automate secret setup
3. `GITHUB_ACTIONS_SETUP.md` - Comprehensive setup guide
4. `.env.local` - Local environment configuration (do not commit)

### Key Improvements:
✓ Secrets are now managed through GitHub Secrets (secure)
✓ Removed hardcoded API keys from workflow
✓ Added GitHub token support with fine-grained permissions
✓ Automatic builds on push to main branch
✓ Gradle caching for faster builds
✓ APK artifacts retained for 30 days

## Accessing Your Built APK

1. Go to GitHub Actions: https://github.com/krishna98877/Stremini.ai/actions
2. Click on a completed "Build APK" workflow run
3. Scroll to "Artifacts" section
4. Download "stremini-final.apk"

## Need Help?

- GitHub Actions docs: https://docs.github.com/en/actions
- Flutter CI/CD guide: https://flutter.dev/docs/deployment/cd
- GitHub CLI guide: https://cli.github.com/manual/

## Environment Variables Reference

Variables passed to Flutter build:
```dart
const String groqApiKey = String.fromEnvironment('GROQ_API_KEY');
```

Local development (`.env.local`):
```
GROQ_API_KEY=your_key_here
GITHUB_FINE_GRAINED_PAT=your_token_here
```

## Security Notes

- Never commit `.env.local` or `.env*` files
- Rotate your GitHub PAT regularly
- Use fine-grained tokens with minimal permissions
- Keep API keys in GitHub Secrets, not in code
