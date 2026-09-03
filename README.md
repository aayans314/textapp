# TextApp

A lightweight, self-hosted, end-to-end encrypted messenger for Android. Built for running on a single cheap VPS (like Oracle Cloud Free Tier) and handing APKs to friends without a store.

```text
┌────────────┐   HTTPS + WSS   ┌─────────────────────┐
│  Android   │ ──────────────► │  your domain        │
│  Compose   │                 │  Cloudflare Tunnel  │
│  Kotlin    │                 │  (origin IP hidden) │
└─────┬──────┘                 └──────────┬──────────┘
      │                                   │
      │        one tiny Node process      ▼
      └──────────────────────► ┌─────────────────────┐
                               │  server/            │
                               │  Node 22 + SQLite   │
                               │  (no native deps)   │
                               │  stores ciphertext  │
                               │  only               │
                               └─────────────────────┘
```

## What you get

- **Username + password login** with one-time email verification (6-digit code).
- **Find friends by username** → friend request → accept → chat.
- **End-to-end encryption**: keys are derived from the password on each device (X25519 + HKDF + AES-256-GCM). The server only ever sees ciphertext — messages *and* media.
- **Push notifications that third parties can't read**: FCM carries only the encrypted blob; your phone decrypts it locally before showing the notification. Falls back to a generic "New message" when keys aren't available yet.
- **Aggressive media compression before upload**: images are resized to ≤1920px JPEG (~q78); videos are re-encoded to ≤1280p H.264 at ~1.4 Mbps using Android's [Media3 Transformer](https://developer.android.com/media/media3/transformer). No ffmpeg needed on the server.
- **Upload cap + media auto-deletion**: files over the cap are rejected (default 25 MB); media is deleted after a TTL (default 30 days) by a lightweight hourly job.
- **Survives aggressive mobile OS behavior**: WebSocket heartbeat + exponential reconnect, a WorkManager keep-alive every 15 minutes, and FCM data messages as the offline fallback.
- **Baked-in backend**: the app always connects to your server (`http://132.145.214.1:9090`). The address never appears in the UI and users can't change it — you simply rebuild with `-PserverUrl=...` if you ever move servers.
- **Minimal backend**: one Node 22 process, built-in SQLite (`node:sqlite`), ~70 npm packages of which the interesting ones are `express`, `ws`, `busboy`, `nodemailer`. No database server, no Redis, no Docker needed (optional).
- **Modern grayscale UI**: Jetpack Compose + Material 3 in the palette you specified (Void Black / Carbon / Steel / Industrial Gray / Tech Silver).

## Repository layout

```text
server/                 Node.js backend (API, WebSocket, push, media store)
  src/                  app code
  test/smoke.js         end-to-end API + WebSocket test
android/                Android app (Kotlin + Jetpack Compose)
  app/src/main/java/app/textapp/
    crypto/             X25519/HKDF/AES-GCM + Android Keystore wrapper
    data/               API client, WebSocket, repository, session
    media/              client-side compression (Media3 Transformer)
    push/               FCM service with local decryption
    ui/                 screens + theme
dist/textapp-v1.0.0.apk signed release APK (4 MB)
deploy/                 systemd unit + docker-compose
docs/                   deployment + APK build guides
```

## Quick start (local dev)

```bash
cd server
npm install
npm test                 # end-to-end smoke test (18 checks)
npm start                # no email provider => codes written to data/dev-mails.log
```

Email verification uses **Resend**: put your `RESEND_API_KEY` in `server/.env` (see
`server/.env.example`). Once you verify a sending domain in the Resend dashboard,
add `RESEND_FROM` as well; until then codes only deliver to your own Resend inbox.

Then build the app. The backend address is baked in (default `http://132.145.214.1:9090`,
overridable at build time with `-PserverUrl=...`):

```bash
cd android
./gradlew assembleRelease
```

## Documentation

- [Deploy on Oracle VPS (Cloudflare Tunnel hides your IP)](DEPLOY.md)
- [Firebase setup + building/signing APKs](docs/ANDROID_BUILD.md)
- [Security & design notes](docs/ARCHITECTURE.md)

## Important facts to know

- **Keys come from your password.** Log in on a new phone with the same password and your identity keys are restored. Change your password and you get a new identity — old conversations stay encrypted and unreadable (by design).
- **The server is a ciphertext store.** Even if the VPS is compromised, message content and media are unreadable without a device key.
- **Not for production-scale**: this is a friends-and-family messenger. SQLite + single process is perfect for tens to low-hundreds of users.

## License

MIT.

Aayan Shah
Colby '28
Dartmouth '29
