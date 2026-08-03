# Building the Android APK

## Prerequisites

- JDK 17
- Android SDK (platform 35, build-tools 35)
- Network access (first build downloads dependencies)

The repo includes the Gradle wrapper, so no separate Gradle install is needed.

## 1. The backend endpoint is baked in

The app always talks to the address baked in at build time and never shows it in
the UI. The default is already set to your VPS:

```properties
# android/gradle.properties
serverUrl=http://132.145.214.1:9090
```

So a plain `./gradlew assembleRelease` produces the APK you distribute. If you
ever move to a domain, override it at build time:

```bash
./gradlew assembleRelease -PserverUrl=https://chat.yourdomain.com
```

(Plain `http://` is only allowed for the hosts listed in
`android/app/src/main/res/xml/network_security_config.xml` — your VPS IP is
already there.)

## 2. Firebase push notifications (optional but recommended)

1. Create a Firebase project at [console.firebase.google.com](https://console.firebase.google.com).
2. Add an Android app with package name `app.textapp`.
3. Download `google-services.json` and place it at `android/app/google-services.json`.
4. Enable **Cloud Messaging** → generate a service-account key (Project settings → Service accounts) and put the values in the server's `.env` (`FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY`).

The Gradle plugin is applied **only when the file exists**, so the project also builds without Firebase — push just stays off.

## 3. Build

```bash
# debug (installable, larger)
./gradlew assembleDebug

# release (minified with R8, ~4 MB, signed)
./gradlew assembleRelease
```

Outputs:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## 4. Signing

The repo ships with a self-signed keystore (`android/keystore/textapp-release.jks`, password in `android/keystore.properties`) so the release APK is installable out of the box.

**For an app your friends will keep using, generate your own keystore and keep it safe** — an app can only be updated with the same signing key:

```bash
keytool -genkeypair -v -keystore my-release.jks -alias textapp \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then update `android/keystore.properties`:

```properties
storeFile=keystore/my-release.jks
storePassword=...
keyAlias=textapp
keyPassword=...
```

## 5. Sharing with friends

Send `app-release.apk` (4 MB) over WhatsApp/Telegram/Drive. On Android 8+, "install unknown apps" permission is needed for the messenger app you're sharing from. A private link (e.g. a Cloudflare R2/Workers static site, or Google Drive) works well for updates.

## 6. Release checklist

- [ ] `serverUrl` is your real domain (no IP)
- [ ] `google-services.json` present if you want push
- [ ] Server `.env` has the matching Firebase credentials
- [ ] You built with `assembleRelease` (not debug)
- [ ] You test on at least one fresh phone: register → verify email → friend → text → photo → video → notification
