# JET Android project

## Automatic APK builds

Every push to `main` runs the **Build JET Android APK** workflow. It unpacks the Android source ZIP, applies the updater patch, and builds:
- `JET-Android-Debug-APK` for device testing
- `JET-Android-Unsigned-Release-APK` for signing setup
- `JET-Android-Updated-Source` with the patched source project

Open the repository's **Actions** tab, select the latest workflow run, and download the artifacts.

## In-app APK updater

- JET silently checks `version.json` shortly after startup.
- Settings → **Check for Updates** performs a manual check.
- The app downloads the APK into its private cache and opens Android's package installer.
- The update metadata points to the release asset `v3.4.0/JET-release.apk`.

## Publish an installable update

Android accepts an in-place update only when the APK is signed with the **same signing certificate** as the installed app. The debug APK and unsigned release APK are not compatible update packages for an already signed installation.

Add these repository **Actions secrets** using the existing release keystore:
- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore file
- `ANDROID_KEY_ALIAS` — key alias
- `ANDROID_KEYSTORE_PASSWORD` — keystore password
- `ANDROID_KEY_PASSWORD` — key password

Then push a tag such as `v3.4.0`. The workflow signs the release APK and publishes it as `JET-release.apk`, matching the URL in `version.json`.

Never commit the keystore or passwords to the repository. If the existing signing key is unavailable, Android will not accept this as an in-place update; users would need a separately handled reinstall.

## App version

- `versionCode`: 340
- `versionName`: 3.4.0
