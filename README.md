# Nox Browser

Nox is an early Android browser prototype focused on a compact interface, lightweight tab management, locally persisted sessions, and basic request blocking.

## Features in this starter
- URL bar with Google search for plain text
- Back, forward, reload, home, and tab picker
- Session saved locally and restored on launch
- Only the selected page has a live WebView; other tabs keep their URLs and reload when selected
- Small starter host blocklist for common ad/tracker requests
- Third-party cookies disabled
- HTTPS mixed content blocked; file/content access disabled

## Build an APK with GitHub Actions
Push to main or master, or manually run **Android Debug APK** from the Actions tab. Download the nox-debug-apk artifact from the workflow run. It is a debug build intended for testing, not a release-signed APK.

## Local build
Requires JDK 17 and Android SDK platform 35/build tools 35.0.0, plus Gradle 8.11.1.
Run: gradle assembleDebug
APK output: app/build/outputs/apk/debug/app-debug.apk.

## Current limitations
This is an initial prototype, not a production-hardened privacy browser. The blocking list is tiny and is not a full filter-list implementation. Inactive tabs restore from their last URL; form values, scroll position, and in-page state may not survive tab switching. Navigation history within a tab is not retained after that tab's WebView is discarded. Incognito mode, bookmarks, downloads, site permission controls, and production release signing are not implemented yet.

Nox uses the Android System WebView provider installed on the device. It does not promise anonymity or protection from every tracker. Never ignore TLS certificate errors.
