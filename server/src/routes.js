import crypto from 'node:crypto';
import { createReadStream, createWriteStream, existsSync, unlinkSync } from 'node:fs';
import Busboy from 'busboy';
import { Router } from 'express';
import { authRequired, hashPassword, signToken, verifyPassword } from './auth.js';
import { config } from './config.js';
import { db, mediaPath } from './db.js';
import { sendVerification } from './mailer.js';
import { sendPush } from './push.js';
import { isOnline, sendToUser } from './ws.js';
import { now, rateLimit, uid, wrap } from './util.js';

const router = Router();
const USERNAME_RE = /^[A-Za-z0-9_]{3,20}$/;
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const VERIFY_TTL_MS = 10 * 60 * 1000;
const MAX_VERIFY_ATTEMPTS = 5;
const sha256 = (s) => crypto.createHash('sha256').update(String(s)).digest('hex');

function publicUser(u) {
  return {
    id: u.id,
    username: u.username,
    email: u.email,
    pubKey: u.pub_key,
    verified: !!u.verified,
    createdAt: u.created_at,
  };
}

const findByUsername = (username) => db.prepare('SELECT * FROM users WHERE username = ? COLLATE NOCASE').get(username);
const findByEmail = (email) => db.prepare('SELECT * FROM users WHERE email = ?').get(email);

function isFriend(a, b) {
  return !!db
    .prepare(
      `SELECT 1 FROM friends
       WHERE ((user_a = ? AND user_b = ?) OR (user_a = ? AND user_b = ?)) AND status = 'accepted'`,
    )
    .get(a, b, b, a);
}

function getOrCreateConv(a, b) {
  const [x, y] = [a, b].sort();
  let conv = db.prepare('SELECT * FROM conversations WHERE user_a = ? AND user_b = ?').get(x, y);
  if (!conv) {
    const id = uid('c');
    db.prepare('INSERT INTO conversations (id, user_a, user_b, created_at) VALUES (?, ?, ?, ?)').run(id, x, y, now());
    conv = { id, user_a: x, user_b: y, created_at: now(), last_msg_at: 0 };
  }
  return conv;
}

function messageDto(r) {
  return {
    id: r.id,
    conversationId: r.conv_id,
    senderId: r.sender_id,
    senderUsername: r.sender_username || null,
    type: r.type,
    payload: r.payload,
    mediaId: r.media_id,
    readAt: r.read_at,
    createdAt: r.created_at,
  };
}

function convDto(conv, me) {
  const peerId = conv.user_a === me ? conv.user_b : conv.user_a;
  const peer = db.prepare('SELECT id, username, pub_key FROM users WHERE id = ?').get(peerId);
  const last = db
    .prepare(
      `SELECT m.*, u.username AS sender_username FROM messages m
       JOIN users u ON u.id = m.sender_id
       WHERE m.conv_id = ? ORDER BY m.created_at DESC LIMIT 1`,
    )
    .get(conv.id);
  const unread = db
    .prepare('SELECT COUNT(*) AS n FROM messages WHERE conv_id = ? AND sender_id != ? AND read_at IS NULL')
    .get(conv.id, me).n;
  return {
    id: conv.id,
    peer: { id: peer.id, username: peer.username, pubKey: peer.pub_key },
    lastMsg: last ? messageDto(last) : null,
    unread,
    createdAt: conv.created_at,
  };
}

async function createCode(userId, username, email) {
  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
  db.prepare(
    `INSERT INTO verify_codes (user_id, code_hash, attempts, expires_at)
     VALUES (?, ?, 0, ?)
     ON CONFLICT(user_id) DO UPDATE SET code_hash = excluded.code_hash, attempts = 0, expires_at = excluded.expires_at`,
  ).run(userId, sha256(code), now() + VERIFY_TTL_MS);
  await sendVerification(email, username, code);
}

function requireConv(req, res) {
  const conv = db.prepare('SELECT * FROM conversations WHERE id = ?').get(req.params.id);
  if (!conv || (conv.user_a !== req.userId && conv.user_b !== req.userId)) {
    res.status(404).json({ error: 'conversation not found' });
    return null;
  }
  return conv;
}

const authLimit = rateLimit({ windowMs: 60_000, max: 10 });

// ---------- auth ----------

router.post('/auth/register', authLimit, wrap(async (req, res) => {
  const { username, email, password } = req.body || {};
  if (!USERNAME_RE.test(String(username || ''))) {
    return res.status(400).json({ error: 'username must be 3-20 characters (letters, numbers, underscore)' });
  }
  const mail = String(email || '').trim().toLowerCase();
  if (!EMAIL_RE.test(mail)) return res.status(400).json({ error: 'invalid email address' });
  if (typeof password !== 'string' || password.length < 8) {
    return res.status(400).json({ error: 'password must be at least 8 characters' });
  }
  if (findByUsername(username) || findByEmail(mail)) {
    return res.status(409).json({ error: 'username or email already registered' });
  }
  const id = uid('u');
  db.prepare('INSERT INTO users (id, username, email, pw_hash, created_at) VALUES (?, ?, ?, ?, ?)').run(
    id,
    String(username),
    mail,
    hashPassword(password),
    now(),
  );
  console.log(`[auth] register: ${username} <${mail}>`);
  await createCode(id, String(username), mail);
  res.status(201).json({ ok: true });
}));

router.post('/auth/verify', authLimit, wrap(async (req, res) => {
  const user = findByUsername(String((req.body || {}).username || ''));
  if (!user) return res.status(404).json({ error: 'unknown username' });
  const row = db.prepare('SELECT * FROM verify_codes WHERE user_id = ?').get(user.id);
  if (!row || row.expires_at < now()) {
    return res.status(400).json({ error: 'code expired, request a new one' });
  }
  if (row.attempts >= MAX_VERIFY_ATTEMPTS) {
    return res.status(400).json({ error: 'too many attempts, request a new code' });
  }
  if (sha256(String((req.body || {}).code || '')) !== row.code_hash) {
    db.prepare('UPDATE verify_codes SET attempts = attempts + 1 WHERE user_id = ?').run(user.id);
    return res.status(400).json({ error: 'invalid code' });
  }
  db.prepare('UPDATE users SET verified = 1 WHERE id = ?').run(user.id);
  db.prepare('DELETE FROM verify_codes WHERE user_id = ?').run(user.id);
  const fresh = db.prepare('SELECT * FROM users WHERE id = ?').get(user.id);
  console.log(`[auth] verified: ${fresh.username}`);
  res.json({ token: signToken(user.id), user: publicUser(fresh) });
}));

router.post('/auth/login', authLimit, wrap(async (req, res) => {
  const { username, password } = req.body || {};
  const user = findByUsername(String(username || ''));
  if (!user || !verifyPassword(String(password || ''), user.pw_hash)) {
    return res.status(401).json({ error: 'invalid username or password' });
  }
  if (!user.verified) {
    await createCode(user.id, user.username, user.email);
    return res.status(403).json({ error: 'verification_required', message: 'Check your email for a verification code' });
  }
  console.log(`[auth] login: ${user.username}`);
  res.json({ token: signToken(user.id), user: publicUser(user) });
}));

router.post('/auth/resend', authLimit, wrap(async (req, res) => {
  const user = findByUsername(String((req.body || {}).username || ''));
  if (!user) return res.status(404).json({ error: 'unknown username' });
  if (user.verified) return res.status(400).json({ error: 'already verified' });
  await createCode(user.id, user.username, user.email);
  res.json({ ok: true });
}));

// ---------- users ----------

router.get('/users/me', authRequired, (req, res) => {
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(req.userId);
  res.json({ user: publicUser(user) });
});

router.post('/users/pubkey', authRequired, (req, res) => {
  const { pubKey } = req.body || {};
  if (typeof pubKey !== 'string' || Buffer.from(pubKey, 'base64').length !== 32) {
    return res.status(400).json({ error: 'invalid public key' });
  }
  const u = db.prepare('SELECT username FROM users WHERE id = ?').get(req.userId);
  console.log(`[keys] setPubKey: ${u?.username} (${String(pubKey).length} chars, head=${String(pubKey).slice(0, 6)})`);
  db.prepare('UPDATE users SET pub_key = ? WHERE id = ?').run(pubKey, req.userId);
  res.json({ ok: true });
});

router.get('/users/search', authRequired, (req, res) => {
  const q = String(req.query.q || '').trim();
  if (!q) return res.json({ users: [] });
  const row = db
    .prepare(
      `SELECT id, username, pub_key FROM users
       WHERE username = ? COLLATE NOCASE AND id != ? AND verified = 1`,
    )
    .get(q, req.userId);
  res.json({ users: row ? [{ id: row.id, username: row.username, pubKey: row.pub_key }] : [] });
});

// ---------- friends ----------

router.get('/friends', authRequired, (req, res) => {
  const me = req.userId;
  const accepted = db
    .prepare(
      `SELECT u.id, u.username, u.pub_key, f.created_at FROM friends f
       JOIN users u ON u.id = CASE WHEN f.user_a = ? THEN f.user_b ELSE f.user_a END
       WHERE (f.user_a = ? OR f.user_b = ?) AND f.status = 'accepted'`,
    )
    .all(me, me, me);
  const pending = db
    .prepare(
      `SELECT u.id, u.username, u.pub_key, f.created_at FROM friends f
       JOIN users u ON u.id = f.requester
       WHERE (f.user_a = ? OR f.user_b = ?) AND f.status = 'pending' AND f.requester != ?`,
    )
    .all(me, me, me);
  res.json({
    friends: accepted.map((f) => ({
      user: { id: f.id, username: f.username, pubKey: f.pub_key },
      since: f.created_at,
      online: isOnline(f.id),
    })),
    requests: pending.map((r) => ({
      user: { id: r.id, username: r.username, pubKey: r.pub_key },
      createdAt: r.created_at,
    })),
  });
});

router.post('/friends/request', authRequired, (req, res) => {
  const me = req.userId;
  const target = findByUsername(String((req.body || {}).username || ''));
  if (!target) return res.status(404).json({ error: 'no user with that username' });
  if (target.id === me) return res.status(400).json({ error: 'you cannot add yourself' });
  if (!target.verified) return res.status(400).json({ error: 'that user has not verified their email yet' });
  if (isFriend(me, target.id)) return res.status(409).json({ error: 'already friends' });
  const [a, b] = [me, target.id].sort();
  const existing = db.prepare('SELECT status FROM friends WHERE user_a = ? AND user_b = ?').get(a, b);
  if (existing) {
    return res.status(409).json({ error: existing.status === 'pending' ? 'request already pending' : 'already friends' });
  }
  db.prepare('INSERT INTO friends (user_a, user_b, requester, status, created_at) VALUES (?, ?, ?, ?, ?)').run(
    a,
    b,
    me,
    'pending',
    now(),
  );
  const meUser = db.prepare('SELECT id, username FROM users WHERE id = ?').get(me);
  sendToUser(target.id, { t: 'friend_request', from: { id: meUser.id, username: meUser.username } });
  res.json({ ok: true });
});

router.post('/friends/respond', authRequired, (req, res) => {
  const me = req.userId;
  const { username, accept } = req.body || {};
  const requester = findByUsername(String(username || ''));
  if (!requester) return res.status(404).json({ error: 'unknown username' });
  const [a, b] = [me, requester.id].sort();
  const edge = db.prepare('SELECT * FROM friends WHERE user_a = ? AND user_b = ?').get(a, b);
  if (!edge || edge.status !== 'pending' || edge.requester !== requester.id) {
    return res.status(409).json({ error: 'no pending request from that user' });
  }
  if (accept) {
    db.prepare("UPDATE friends SET status = 'accepted' WHERE user_a = ? AND user_b = ?").run(a, b);
    const meUser = db.prepare('SELECT id, username FROM users WHERE id = ?').get(me);
    sendToUser(requester.id, { t: 'friend_accept', from: { id: meUser.id, username: meUser.username } });
    sendToUser(me, { t: 'friend_accept', from: { id: requester.id, username: requester.username } });
  } else {
    db.prepare('DELETE FROM friends WHERE user_a = ? AND user_b = ?').run(a, b);
  }
  res.json({ ok: true });
});

// ---------- conversations ----------

router.post('/conversations', authRequired, (req, res) => {
  const me = req.userId;
  const target = findByUsername(String((req.body || {}).username || ''));
  if (!target) return res.status(404).json({ error: 'no user with that username' });
  if (target.id === me) return res.status(400).json({ error: 'you cannot chat with yourself' });
  if (!isFriend(me, target.id)) return res.status(403).json({ error: 'add them as a friend first' });
  if (!target.pub_key) {
    return res.status(400).json({ error: `${target.username} has not set up encryption yet - ask them to log in once` });
  }
  const conv = getOrCreateConv(me, target.id);
  res.json({ conversation: convDto(conv, me) });
});

router.get('/conversations', authRequired, (req, res) => {
  const me = req.userId;
  const rows = db
    .prepare('SELECT * FROM conversations WHERE user_a = ? OR user_b = ? ORDER BY last_msg_at DESC')
    .all(me, me);
  res.json({ conversations: rows.map((c) => convDto(c, me)) });
});

router.get('/conversations/:id/messages', authRequired, (req, res) => {
  const conv = requireConv(req, res);
  if (!conv) return;
  const before = Number(req.query.before || now());
  const limit = Math.min(100, Math.max(1, Number(req.query.limit || 50)));
  const rows = db
    .prepare(
      `SELECT m.*, u.username AS sender_username FROM messages m
       JOIN users u ON u.id = m.sender_id
       WHERE m.conv_id = ? AND m.created_at < ?
       ORDER BY m.created_at DESC LIMIT ?`,
    )
    .all(conv.id, before, limit)
    .reverse();
  res.json({ conversation: convDto(conv, req.userId), messages: rows.map(messageDto) });
});

router.post('/conversations/:id/messages', authRequired, wrap(async (req, res) => {
  const conv = requireConv(req, res);
  if (!conv) return;
  const me = req.userId;
  const { type, payload, mediaId } = req.body || {};
  if (type !== 'text' && type !== 'media') {
    return res.status(400).json({ error: 'type must be text or media' });
  }
  let decoded = null;
  try {
    decoded = Buffer.from(String(payload || ''), 'base64');
  } catch {
    decoded = null;
  }
  if (!decoded || decoded.length === 0 || decoded.length > config.maxPayloadBytes) {
    return res.status(400).json({ error: `invalid payload (max ${Math.round(config.maxPayloadBytes / 1024)} KB)` });
  }
  let media = null;
  if (type === 'media') {
    if (typeof mediaId !== 'string') return res.status(400).json({ error: 'mediaId required for media messages' });
    media = db.prepare('SELECT * FROM media WHERE id = ?').get(mediaId);
    if (!media || media.owner_id !== me) return res.status(400).json({ error: 'invalid media id' });
  }
  const id = uid('m');
  db.prepare(
    'INSERT INTO messages (id, conv_id, sender_id, type, payload, media_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)',
  ).run(id, conv.id, me, type, String(payload), media ? media.id : null, now());
  db.prepare('UPDATE conversations SET last_msg_at = ? WHERE id = ?').run(now(), conv.id);
  const row = db
    .prepare('SELECT m.*, u.username AS sender_username FROM messages m JOIN users u ON u.id = m.sender_id WHERE m.id = ?')
    .get(id);
  const other = conv.user_a === me ? conv.user_b : conv.user_a;
  if (isOnline(other)) {
    sendToUser(other, { t: 'msg', m: messageDto(row) });
  } else {
    const tokens = db.prepare('SELECT token FROM push_tokens WHERE user_id = ?').all(other);
    for (const { token } of tokens) {
      const pushRes = await sendPush(token, {
        t: 'msg',
        conv: conv.id,
        mid: row.id,
        sid: row.sender_id,
        sn: row.sender_username || '',
        typ: row.type,
        ct: row.payload,
        mt: row.media_id || '',
        ts: String(row.created_at),
      });
      if (pushRes.invalid) db.prepare('DELETE FROM push_tokens WHERE token = ?').run(token);
    }
  }
  res.json({ message: messageDto(row) });
}));

router.post('/conversations/:id/read', authRequired, (req, res) => {
  const conv = requireConv(req, res);
  if (!conv) return;
  const me = req.userId;
  const result = db
    .prepare('UPDATE messages SET read_at = ? WHERE conv_id = ? AND sender_id != ? AND read_at IS NULL')
    .run(now(), conv.id, me);
  if (result.changes > 0) {
    const other = conv.user_a === me ? conv.user_b : conv.user_a;
    const meUser = db.prepare('SELECT username FROM users WHERE id = ?').get(me);
    if (meUser) sendToUser(other, { t: 'read', conv: conv.id, by: meUser.username });
  }
  res.json({ ok: true });
});

// ---------- media ----------

router.post('/media', authRequired, (req, res) => {
  const me = req.userId;
  const id = uid('med');
  const filePath = mediaPath(id);
  const bb = Busboy({ headers: req.headers, limits: { fileSize: config.maxUploadBytes, files: 1, fields: 4 } });
  let convId = null;
  let fileReceived = false;
  let overLimit = false;
  let writeError = null;
  let bytes = 0;
  const pendingWrites = [];
  const cleanup = () => {
    try {
      unlinkSync(filePath);
    } catch {
      /* noop */
    }
  };

  bb.on('field', (name, value) => {
    if (name === 'conversationId') convId = value;
  });
  bb.on('file', (name, stream) => {
    if (name !== 'file') {
      stream.resume();
      return;
    }
    fileReceived = true;
    const ws = createWriteStream(filePath, { flags: 'wx' });
    pendingWrites.push(
      new Promise((resolve) => {
        ws.on('close', resolve);
        ws.on('error', () => {
          writeError = true;
          resolve();
        });
      }),
    );
    ws.on('error', () => {
      writeError = true;
    });
    stream.on('data', (chunk) => {
      bytes += chunk.length;
      if (!ws.destroyed) ws.write(chunk);
    });
    stream.on('limit', () => {
      overLimit = true;
      ws.destroy();
    });
    stream.on('end', () => {
      if (!ws.destroyed) ws.end();
    });
    stream.on('error', () => ws.destroy());
  });
  bb.on('error', (err) => {
    cleanup();
    res.status(500).json({ error: 'upload failed' });
  });
  bb.on('finish', wrap(async (_req, _res) => {
    await Promise.all(pendingWrites);
    if (!fileReceived) {
      cleanup();
      return res.status(400).json({ error: 'missing file field' });
    }
    if (overLimit || writeError || bytes > config.maxUploadBytes) {
      cleanup();
      return res
        .status(413)
        .json({ error: `file exceeds the ${Math.round(config.maxUploadBytes / 1048576)} MB limit` });
    }
    db.prepare('INSERT INTO media (id, owner_id, conv_id, size, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)').run(
      id,
      me,
      convId || null,
      bytes,
      now() + config.mediaTtlMs,
      now(),
    );
    res.status(201).json({ mediaId: id, size: bytes });
  }));
  req.pipe(bb);
});

router.get('/media/:id', authRequired, (req, res) => {
  const me = req.userId;
  const media = db.prepare('SELECT * FROM media WHERE id = ?').get(req.params.id);
  if (!media) return res.status(404).json({ error: 'media not found' });
  if (media.owner_id !== me) {
    if (!media.conv_id) return res.status(403).json({ error: 'not allowed' });
    const conv = db.prepare('SELECT * FROM conversations WHERE id = ?').get(media.conv_id);
    if (!conv || (conv.user_a !== me && conv.user_b !== me)) return res.status(403).json({ error: 'not allowed' });
  }
  const filePath = mediaPath(media.id);
  if (!existsSync(filePath)) return res.status(404).json({ error: 'media file missing' });
  res.setHeader('Content-Type', 'application/octet-stream');
  res.setHeader('Content-Length', media.size);
  createReadStream(filePath).pipe(res);
});

// ---------- push ----------

router.post('/push/register', authRequired, (req, res) => {
  const { token } = req.body || {};
  if (typeof token !== 'string' || token.length < 20) return res.status(400).json({ error: 'invalid push token' });
  db.prepare(
    `INSERT INTO push_tokens (user_id, token, platform, updated_at)
     VALUES (?, ?, 'android', ?)
     ON CONFLICT(token) DO UPDATE SET user_id = excluded.user_id, updated_at = excluded.updated_at`,
  ).run(req.userId, token, now());
  res.json({ ok: true });
});

export default router;
