# Security & design notes

## Threat model

TextApp protects message **content** and **media** from everyone who doesn't hold a device key:

- the VPS host / Oracle admins;
- Google / Firebase (push);
- anyone sniffing TLS (on top of HTTPS, content is already encrypted with a per-conversation key);
- anyone who grabs a database or media-file backup.

It does **not** protect metadata (who talks to whom, when, message sizes) — that stays visible to the server. If you need that, you need a metadata-hiding protocol (e.g. Signal protocol + sealed sender), which is a much bigger project.

## Key derivation (client-side only)

```text
seed    = PBKDF2-SHA256(password, salt=username, 150k iterations, 32 bytes)
privKey = seed (X25519 scalar)
pubKey  = X25519 base-point multiplication of seed     → registered with the server

per conversation:
  shared  = X25519(myPriv, peerPub)                    (same on both sides)
  convKey = HKDF-SHA256(ikm=shared, salt=convId, info="textapp/v1/conv")

per message:
  AES-256-GCM(convKey, random 12-byte IV)
  wire format: base64(iv ‖ ciphertext)
```

Because keys are deterministic from the password, no key backup service is needed and multiple devices "just work". The trade-off (documented in the README): changing your password changes your identity, and old history becomes unreadable — like a WhatsApp-style reset.

## Media pipeline

```text
pick photo/video
  → compress on device (Bitmap ≤1920px q78 / Media3 Transformer ≤1280p @1.4 Mbps)
  → encrypt with a fresh random 256-bit key (AES-256-GCM, streamed)
  → upload ciphertext (server enforces 25 MB cap)
  → media key travels inside the E2E-encrypted message payload

receiver
  → downloads ciphertext blob
  → decrypts into cache/ with the key from the decrypted message
```

Thumbnails are the same pipeline at ≤360px so chat lists stay fast without downloading full media.

## Notifications

The server sends **data-only** FCM messages (per [FCM docs](https://firebase.google.com/docs/cloud-messaging/android/receive-messages), data-only messages reach `onMessageReceived` in background). The payload contains only the encrypted message blob. The app:

1. unwraps the seed from Android Keystore;
2. derives the conversation key;
3. decrypts and posts a **local** notification (content never passed through FCM as plaintext);
4. if keys are missing (e.g. fresh install), posts a generic "New message".

## Server hardening basics

- Password hashes: `scrypt` (N=16384, r=8, p=1), timing-safe comparison.
- Auth: HMAC-SHA256 signed tokens (no session table), 30-day expiry.
- Rate limiting: 10 auth attempts/min/IP, 300 req/min/IP globally.
- Email verification codes: single-use, 10-minute expiry, hashed at rest, 5-attempt cap.
- Media blobs: random IDs, ownership checks, TTL deletion.
- No plaintext ever touches the database: messages table stores base64 ciphertext only.

## Backend footprint

- Runtime: Node ≥ 22.13 (uses built-in `node:sqlite` — no native modules to compile on the VPS).
- npm deps: `express`, `ws`, `busboy`, `nodemailer`, `dotenv` (+ transitive).
- Disk: SQLite DB + `data/media/*.bin` ciphertext blobs.
- Idle memory: well under 100 MB; no background workers besides a 60-minute TTL sweep.
