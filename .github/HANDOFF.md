# TextApp — Handoff Notes

> Written 2026-08-04. Read this fully before touching the code. It tells you what
> the project is, what state it is in, what has been tried, and exactly what the
> next agent should do next.

## 1. What this project is

**TextApp**: a lightweight, self-hosted, end-to-end-encrypted messenger for Android.
One Node.js backend on a single Oracle VPS; friends install a signed APK. No store,
no heavy infra. The APK is a thin client — all logic (auth, friends, chat, media,
push) lives on the server, and the server URL is baked into the APK so users never
see or change it.

Design highlights (all implemented):
- Username + password login, one-time 6-digit email verification (Resend).
- Find friends by **exact username** → friend request → accept → chat.
- E2E encryption: keys are derived from `PBKDF2(password, username.lowercase(), 150k)`
  → X25519 identity keys → HKDF per-conversation key → AES-256-GCM. Server stores
  only ciphertext (messages and media).
- Media: client-side compression (images ≤1920px JPEG, video ≤1280p H.264 via
  Media3 Transformer), 25 MB upload cap, 30-day media TTL with hourly cleanup.
- WebSocket + heartbeat + exponential reconnect + WorkManager keep-alive
  (15 min). Firebase/FCM push is **NOT configured** — user explicitly chose
  "no-go" on Firebase; do not add it unless asked.
- UI: Jetpack Compose, Material 3, grayscale palette (Void Black / Carbon Charcoal /
  Steel Shadow / Industrial Gray / Tech Silver).

## 2. Repository layout

```
D:\text-app\
  server/                  Node 22 backend (ESM, node:sqlite, express, ws, busboy, nodemailer, dotenv)
    src/routes.js          ALL API routes — currently has exact-match search AND diagnostic logging
    src/config.js          env config (PORT, HOST, RESEND, SMTP fallback, FIREBASE optional)
    src/mailer.js          Resend-first mailer (falls back to SMTP, then dev log)
    test/smoke.js          19 end-to-end checks — ALL PASS locally
  android/                 Kotlin + Jetpack Compose app
    app/build.gradle.kts   versionCode 5, versionName 1.4.0, SERVER_URL baked via -PserverUrl
    app/src/main/java/app/textapp/
      crypto/              Crypto.kt (PBKDF2/X25519/HKDF/AES-GCM), KeyStoreCrypto.kt, MediaCipher.kt
      data/                ApiClient, ChatRepository (incl. decryptMessageReason), SessionManager, WsManager
      ui/screens/          PeopleScreen (search), ChatScreen (shows "Can't decrypt: <reason>"), etc.
    keystore/              textapp-release.jks (signing) — gitignored
    keystore.properties    keystore credentials — gitignored, referenced by build
  dist/                    Signed APKs: v1.0.0, v1.1.0, v1.2.0, v1.4.0 (current)
  deploy/                  textapp.service (systemd unit), docker-compose.yml (unused)
  DEPLOY.md                Deployment guide (updated: Resend, package-lock.json, /opt/node22, .env gotchas)
  README.md, docs/         Docs incl. ANDROID_BUILD.md, ARCHITECTURE.md
  HANDOFF.md               This file
```

No git repo is initialized in this folder (there is a .gitignore but no .git).
Don't assume git history exists.

## 3. Live deployment state (as of handoff)

- VPS: `132.145.214.1` (Oracle), app port **9090**. Health endpoint verified
  reachable from outside: `{"ok":true,"name":"TextApp",...}`.
- Backend runs as systemd service `textapp.service` (enabled, survives SSH logout).
  Unit lives at `/etc/systemd/system/textapp.service`; copy also in `deploy/`.
  Key settings: `User=ubuntu`, `WorkingDirectory=/opt/textapp`,
  `EnvironmentFile=/opt/textapp/.env`,
  `ExecStart=/opt/node22/bin/node --disable-warning=ExperimentalWarning src/index.js`,
  `ReadWritePaths=/opt/textapp/data`.
- **Node is isolated at `/opt/node22/bin/node` (v22.20.0)**. The system Node 18
  (`/usr/bin/node`) is deliberately untouched because another user process,
  `sad-hours/serve.js` on port 9000, runs on it. Never replace system node.
- `/opt/textapp/.env` contains: `PORT=9090`, `HOST=0.0.0.0`,
  `RESEND_API_KEY=<redacted - real value only on the VPS>`, `PUBLIC_URL=http://132.145.214.1:9090`,
  `SECRET=<generated>`. **`.env` is gitignored — never commit its contents.**
- Resend API key is valid and send-only scoped (verified: live send returned HTTP 200
  with a message id). `onboarding@resend.dev` only delivers to the account owner's own
  inbox. To send codes to friends, domain `aayanshah.me` must be verified in Resend
  and `RESEND_FROM="TextApp <noreply@aayanshah.me>"` set. User said "did all that" —
  **assume the domain may or may not actually be verified; confirm before relying on
  codes reaching arbitrary inboxes.**
- The server's database was **purged** (user requested a clean slate). The current
  DB contains only the user's two fresh test accounts, which STILL reproduce the
  decryption bug (see §5). Old data dir was deleted; a `data.bak-*` may exist on the
  VPS from the earlier safe-move attempt.

## 4. What works (verified)

- Backend: `npm test` in `server/` → **19/19 pass** (health, register, email-code
  flow, verify, public-key upload, exact-match search, friends, conversation, send
  text, read receipts, WebSocket delivery, media upload/download, TTL, auth guard,
  upload cap).
- Registration + email verification works on real phones (codes arrive via Resend).
- Friends + conversations work; messages ARE delivered to the receiving phone.
- Server stays up across SSH disconnects and restarts (systemd).
- Search is now **exact-match** server-side (deployed to VPS by user).
- The E2E crypto math was independently verified with a faithful Node simulation
  (same PBKDF2/X25519/HKDF/AES-GCM): shared secrets match, round-trip decrypts.
  The algorithm itself is sound.

## 5. THE CURRENT BLOCKER (read carefully)

**Symptom:** any phone that RECEIVES a message cannot read it. It shows
`Message unavailable` (v1.2.0) / `Can't decrypt: <reason>` (v1.4.0). The SENDING
phone always reads its own sent messages fine. This is true for old messages and
new messages, and it reproduced with **fresh accounts on a fresh database** — so it
is systemic, not corrupted account state.

**What has been tried (all FAILED to fix it):**
1. v1.1.0: fixed key-upload ordering bug (`establishSession` uploaded the public key
   BEFORE caching the token → 401 → silently swallowed → server key never set).
   Order swapped. Also added a startup "self-heal" that re-uploads a missing key.
2. v1.2.0: `decryptMessage` now re-fetches conversation/friend mappings on demand
   (fixes a real race where WS-delivered messages rendered before the peer-key
   cache existed). Fallback label changed from "…" to "Message unavailable".
3. Re-login on both phones with correct passwords (re-derives + re-uploads keys).
4. Server-side: cleared both users' `pub_key` to NULL, reopened apps to force
   re-upload. Keys re-appeared (`has_key=1`) — messages still failed.
5. Full database purge (`rm -rf /opt/textapp/data`) + brand-new accounts.
   **Still fails.** Conclusion: the bug is in the app's key upload or decryption
   path, not in account data.

**What is known for certain:**
- Both accounts have `pub_key` set on the server (checked via node:sqlite query).
- Sender's own copies decrypt (sender uses own priv + receiver's server pubkey).
- Receiver's copies fail (receiver uses own priv + sender's server pubkey).
- Crypto math, KeyStoreCrypto (deterministic AES-GCM wrap/unwrap), server payload
  paths (HTTP response vs WS event carry identical ciphertext), DTO field names,
  and conversation IDs are all verified correct.
- Therefore at least one phone's current private key ≠ the public key stored on the
  server for that account — but re-login (which re-uploads) did not fix it, so the
  exact mechanism is still unknown. That is what the diagnostics below will reveal.

**Leading hypotheses for the next agent:**
- (a) The authenticated key upload is still failing silently somewhere
  (`runCatching` swallows errors in `establishSession` and the startup self-heal).
- (b) The phone encrypts with a key derived from a seed that differs from the one
  whose public key got uploaded (seed derivation/state drift on one device).
- (c) The receiver is using a stale or wrong peer key from the on-device cache
  despite the on-demand refresh.

## 6. Diagnostics already in place (this is where we left off)

**Android v1.4.0 (BUILT, in `dist/textapp-v1.4.0.apk`)** — includes:
- `ChatRepository.decryptMessageReason()` returns a `DecryptResult(payload, reason)`;
  `ChatScreen` now displays **`Can't decrypt: <reason>`** where the reason is one of:
  `no session key` / `no conversation mapping` / `no peer key for <username>` /
  `crypto <ExceptionClass>: <message>`.
- Search crash fix: `PeopleScreen` debounces input (300 ms) and wraps the search
  call in `runCatching` — rapid typing no longer crashes the app.
- Also contains the earlier v1.3.0 hardening (self-heal derives the key from the
  current seed instead of a possibly-stale stored value; `establishSession` always
  refreshes the stored pubkey). Version 1.4.0 / versionCode 5.

**Server-side logging (IN CODE, NOT YET DEPLOYED):** `server/src/routes.js` logs:
- `[auth] register: <username> <email>`
- `[auth] verified: <username>`
- `[auth] login: <username>`
- `[keys] setPubKey: <username> (<len> chars, head=<prefix>)`

The VPS currently runs an OLDER routes.js (exact-match search, but NO logging).

## 7. Next steps for the next agent (exact order)

1. **Deploy the instrumented server** (no APK rebuild needed):
   copy `server/src/routes.js` → `/opt/textapp/src/routes.js` on the VPS, then
   `sudo systemctl restart textapp`.
2. **Install v1.4.0 on both phones** (update over existing install).
3. On the RECEIVING phone, open the failing chat and record the EXACT
   `Can't decrypt: <reason>` text. Send a fresh message too.
4. Collect the two server-side data points:
   ```bash
   cd /opt/textapp && /opt/node22/bin/node --disable-warning=ExperimentalWarning -e "const{DatabaseSync}=require('node:sqlite');const db=new DatabaseSync('data/textapp.db');console.table(db.prepare('SELECT username, CASE WHEN pub_key IS NULL THEN 0 ELSE 1 END AS has_key FROM users').all())"
   sudo journalctl -u textapp -n 60 --no-pager
   ```
   Look for `[keys] setPubKey` lines (did uploads happen? when? whose?),
   `[auth] login` lines (which device logged in), and any errors.
5. The `Can't decrypt:` reason tells you the failing branch:
   - `no session key` → device seed/keystore problem on the receiver.
   - `no conversation mapping` → receiver never cached conv→peer and refresh failed.
   - `no peer key for X` → server had no pubkey for the sender when the receiver
     refreshed (or refresh failed) — correlates with `has_key`/`[keys]` logs.
   - `crypto ...` → keys exist but do NOT agree → identity drift; compare the
     `[keys]` head prefixes across devices/logins to spot which account changed.
6. Identify root cause, implement the real fix, and ONLY THEN rebuild the final APK.
   Follow the version convention (next would be 1.5.0 / versionCode 6) so phones
   update cleanly.

Optional extra diagnostic if needed: add `console.log('[msg] ...')` to the
`POST /conversations/:id/messages` handler (payload length, sender, conv) — not
currently present.

## 8. How to build the APK

```powershell
cd D:\text-app\android
.\gradlew.bat assembleRelease --no-daemon   # ~5-8 min; may need network/escalation
Copy-Item app\build\outputs\apk\release\app-release.apk ..\dist\textapp-vX.Y.Z.apk
```
- Signing is automatic via `android/keystore.properties` +
  `android/keystore/textapp-release.jks` (both gitignored). Do not lose them —
  reinstalling over an existing install requires the same signature.
- The baked server URL lives in `app/build.gradle.kts` (`serverUrl` property,
  default `http://132.145.214.1:9090`); it is compiled into `BuildConfig.SERVER_URL`.
  Changing the server later = rebuild.
- Verify the built BuildConfig before shipping:
  `android/app/build/generated/source/buildConfig/release/app/textapp/BuildConfig.java`.

## 9. Deployment gotchas learned the hard way (do not repeat)

- **systemd `EnvironmentFile` does NOT support inline comments.** A line like
  `HOST=0.0.0.0 # comment` makes the whole string the value → server crashes with
  `getaddrinfo ENOTFOUND`. Comments must be on their own lines (DEPLOY.md now warns).
- **Do not delete `/opt/textapp/data` while the service is stopped and then start
  it** — the unit's `ReadWritePaths=/opt/textapp/data` mount fails if the dir is
  missing, so the service won't start. Create it first:
  `sudo mkdir -p /opt/textapp/data && sudo chown -R ubuntu:ubuntu /opt/textapp/data`.
- The apt lock on the VPS was stuck for a long time (`apt-get` PID 257456). Avoid
  apt entirely: Node 22 lives in `/opt/node22` and all installs use
  `/opt/node22/bin/npm`. The system Node 18 must stay untouched for `sad-hours`.
- The VPS user is `ubuntu` (`ubuntu@testingvnic`); chown commands use
  `ubuntu:ubuntu`.
- VS Code Remote-SSH cannot write into `/etc` — write files under `/opt/textapp`
  first, then `sudo cp` into place.
- `scp` from the local Windows machine must include `package-lock.json` and `.env`
  (the earlier missing-file error was exactly that).

## 10. Secrets & access notes (keep this file local)

- `server/.env` (gitignored) holds `RESEND_API_KEY` and `SECRET`; it is also copied
  to `/opt/textapp/.env` on the VPS.
- The Resend key is send-only (can't list domains/keys) — that's intentional.
- APK signing keystore + password: see `android/keystore.properties` (gitignored).
- VPS SSH: user `ubuntu`, via VS Code Remote-SSH; host `testingvnic`.

## 11. Open items / decisions

- **Root cause of the decrypt bug** (this is the ONLY thing blocking the app).
- Confirm whether `aayanshah.me` is verified in Resend; if yes, set
  `RESEND_FROM="TextApp <noreply@aayanshah.me>"` in `/opt/textapp/.env` and restart,
  so friends can receive verification codes (until then codes only reach the
  account owner's own inbox).
- Firebase/FCM push: decided NO for now. Chat works via WebSocket while app is
  open/backgrounded.
- The user distributes `dist/textapp-v1.4.0.apk` to friends once the decrypt bug is
  fixed; the address `http://132.145.214.1:9090` is baked in and never shown in UI.
