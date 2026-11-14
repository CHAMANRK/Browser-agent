# CHAMAN AI Base v1.0.0

A standalone native Android app built with Kotlin + Jetpack Compose.

## Included
- Minimal chat interface
- Groq `openai/gpt-oss-120b` for text requests
- NVIDIA `nvidia/nemotron-3.5-super-vl-120b-a12b` for image requests
- Separate API key storage encrypted with Android Keystore (AES-GCM)
- Streaming responses using Server-Sent Events
- GitHub Actions Debug APK build

## Build with GitHub Actions
1. Create a new empty GitHub repository.
2. Upload/push the contents of this folder to the repository root.
3. Open **Actions**.
4. Run **Build CHAMAN AI APK** (or push to `main`).
5. Download the `chaman-ai-debug-apk` artifact.
6. Install `app-debug.apk` on Android.

## Local build
Requires JDK 17 and Android SDK 35:
```bash
gradle assembleDebug
```

## API keys
Open Settings inside the app and enter Groq and NVIDIA keys separately.
Keys are encrypted using Android Keystore and are not stored in source code.
Never commit API keys.

## Current routing
- Text-only messages -> Groq
- Image-attached messages -> NVIDIA

This is the initial base routing policy. It does not yet implement autonomous browser control, OCR, long-term memory, or computer-use tools.

## Important
The first GitHub Actions build is the authoritative Android/Gradle compile check. If CI reports an error, share the complete failed step log so it can be fixed before adding more features.
