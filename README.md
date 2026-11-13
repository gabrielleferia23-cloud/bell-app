# Bell

Bell is a personal memory assistant for Android. You teach her things by typing,
speaking, or giving her files (PDF, Excel, CSV, Word, email, books, text, and
song details), and she remembers them on your phone and answers your questions.

Everything Bell learns stays on the phone. She uses no paid services.

## Installing and updating

Every push to `main` builds a new APK on GitHub Actions and publishes it under
**Releases**. On your phone, open the latest release, download the `.apk`, and
tap it. A newer APK installs over the old one and keeps everything Bell learned.

## Project layout

- `www/index.html` is the whole app (screens, memory, file reading, search).
- `android/` is the Android wrapper made with Capacitor.
- `scripts/vendor.js` copies the PDF, Excel and Word readers into the app so it works offline.
- `.github/workflows/build-apk.yml` builds and signs the APK.

## Building locally

Needs Node 20, Java 21 and the Android SDK.

```
npm ci
npm run build
cd android && ./gradlew assembleDebug
```

## Signing

Release APKs are signed with one fixed key so every new APK installs over the
old one. The key lives only in two repository secrets, `BELL_KEYSTORE_BASE64`
and `BELL_KEYSTORE_PASSWORD`. Without them, the workflow makes a test build
instead of a release. Keep a backup of the key: if it is lost, Android can't
update Bell and you would have to uninstall her first, which erases what she
learned.
