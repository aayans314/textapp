import { mkdirSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';
import assert from 'node:assert';

const testDir = join(process.cwd(), 'data-test');
process.env.DATA_DIR = testDir;
process.env.MAX_UPLOAD_MB = '2';
// Keep tests hermetic: never send real emails during the smoke test.
process.env.RESEND_API_KEY = '';
process.env.SMTP_HOST = '';

rmSync(testDir, { recursive: true, force: true });
mkdirSync(testDir, { recursive: true });

const { createApp } = await import('../src/app.js');
const { initWs } = await import('../src/ws.js');
const { db } = await import('../src/db.js');

const server = createServer(createApp());
initWs(server);
await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;

let failures = 0;
let convId = null;
let mediaId = null;
let aliceToken = '';
let bobToken = '';

async function api(method, path, body, token) {
  const headers = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body && !(body instanceof FormData)) headers['Content-Type'] = 'application/json';
  const res = await fetch(base + path, {
    method,
    headers,
    body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let parsed = null;
  try {
    parsed = text ? JSON.parse(text) : null;
  } catch {
    parsed = text;
  }
  return { status: res.status, body: parsed };
}

const codeFor = (username) => {
  const log = readFileSync(join(testDir, 'dev-mails.log'), 'utf8');
  let found = null;
  for (const line of log.split('\n')) {
    if (line.includes(username)) {
      const m = line.match(/code=(\d{6})/);
      if (m) found = m[1];
    }
  }
  if (!found) throw new Error(`verification code not found for ${username}`);
  return found;
};

const step = async (name, fn) => {
  try {
    await fn();
    console.log('  ok -', name);
  } catch (e) {
    failures += 1;
    console.error('  FAIL -', name, '-', e.message);
  }
};

await step('health', async () => {
  const r = await api('GET', '/api/health');
  assert.equal(r.status, 200);
  assert.equal(r.body.ok, true);
});

await step('register alice + bob', async () => {
  for (const u of ['alice', 'bob']) {
    const r = await api('POST', '/api/auth/register', { username: u, email: `${u}@test.dev`, password: 'password123' });
    assert.equal(r.status, 201);
  }
});

await step('login before verify is blocked', async () => {
  const r = await api('POST', '/api/auth/login', { username: 'alice', password: 'password123' });
  assert.equal(r.status, 403);
  assert.equal(r.body.error, 'verification_required');
});

await step('resend verification code', async () => {
  const r = await api('POST', '/api/auth/resend', { username: 'bob' });
  assert.equal(r.status, 200);
});

await step('verify alice + bob', async () => {
  for (const u of ['alice', 'bob']) {
    const code = codeFor(u);
    const r = await api('POST', '/api/auth/verify', { username: u, code });
    assert.equal(r.status, 200, `${u} code=${code} -> ${JSON.stringify(r.body)}`);
    assert.ok(r.body.token);
    assert.equal(r.body.user.verified, true);
    if (u === 'alice') aliceToken = r.body.token;
    else bobToken = r.body.token;
  }
});

await step('register public keys', async () => {
  const pubKey = Buffer.alloc(32, 7).toString('base64');
  for (const token of [aliceToken, bobToken]) {
    const r = await api('POST', '/api/users/pubkey', { pubKey }, token);
    assert.equal(r.status, 200);
  }
});

  await step('search users', async () => {
  const r = await api('GET', '/api/users/search?q=bob', null, aliceToken);
    assert.equal(r.status, 200);
    assert.equal(r.body.users.length, 1);
    assert.equal(r.body.users[0].username, 'bob');
  });

  await step('search is exact-match only', async () => {
    const r = await api('GET', '/api/users/search?q=bo', null, aliceToken);
    assert.equal(r.status, 200);
    assert.equal(r.body.users.length, 0);
  });

await step('friend request + accept', async () => {
  let r = await api('POST', '/api/friends/request', { username: 'bob' }, aliceToken);
  assert.equal(r.status, 200);
  r = await api('GET', '/api/friends', null, bobToken);
  assert.equal(r.body.requests.length, 1);
  assert.equal(r.body.requests[0].user.username, 'alice');
  r = await api('POST', '/api/friends/respond', { username: 'alice', accept: true }, bobToken);
  assert.equal(r.status, 200);
  r = await api('GET', '/api/friends', null, aliceToken);
  assert.equal(r.body.friends.length, 1);
});

await step('create conversation', async () => {
  const r = await api('POST', '/api/conversations', { username: 'bob' }, aliceToken);
  assert.equal(r.status, 200);
  assert.ok(r.body.conversation.peer.pubKey);
  convId = r.body.conversation.id;
});

await step('send + receive text message', async () => {
  const payload = Buffer.from(JSON.stringify({ t: 'text', text: 'hello bob' })).toString('base64');
  const r = await api('POST', `/api/conversations/${convId}/messages`, { type: 'text', payload }, aliceToken);
  assert.equal(r.status, 200);
  const messageId = r.body.message.id;
  const r2 = await api('GET', `/api/conversations/${convId}/messages`, null, bobToken);
  assert.equal(r2.body.messages.length, 1);
  assert.equal(r2.body.messages[0].id, messageId);
  assert.equal(r2.body.messages[0].senderUsername, 'alice');
});

await step('mark read', async () => {
  const r = await api('POST', `/api/conversations/${convId}/read`, {}, bobToken);
  assert.equal(r.status, 200);
  const r2 = await api('GET', '/api/conversations', null, aliceToken);
  assert.equal(r2.body.conversations[0].unread, 0);
});

await step('realtime delivery over websocket', async () => {
  const ws = new WebSocket(`ws://127.0.0.1:${server.address().port}/ws?token=${bobToken}`);
  await new Promise((resolve, reject) => {
    ws.onopen = resolve;
    ws.onerror = reject;
  });
  const received = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('no ws message within 5s')), 5000);
    ws.onmessage = (e) => {
      const msg = JSON.parse(e.data);
      if (msg.t === 'msg') {
        clearTimeout(timer);
        resolve(msg);
      }
    };
  });
  const payload = Buffer.from(JSON.stringify({ t: 'text', text: 'ws hello' })).toString('base64');
  const r = await api('POST', `/api/conversations/${convId}/messages`, { type: 'text', payload }, aliceToken);
  assert.equal(r.status, 200);
  const m = await received;
  assert.equal(m.m.payload, payload);
  assert.equal(m.m.senderUsername, 'alice');
  ws.close();
});

await step('media upload + media message', async () => {
  const fd = new FormData();
  fd.append('conversationId', convId);
  fd.append('file', new Blob([Buffer.alloc(1024, 3)]), 'blob.bin');
  const r = await api('POST', '/api/media', fd, aliceToken);
  assert.equal(r.status, 201);
  mediaId = r.body.mediaId;
  const payload = Buffer.from(
    JSON.stringify({ t: 'media', mediaId, mediaKey: 'x', mime: 'application/octet-stream', name: 'blob.bin', size: 1024 }),
  ).toString('base64');
  const r2 = await api('POST', `/api/conversations/${convId}/messages`, { type: 'media', payload, mediaId }, aliceToken);
  assert.equal(r2.status, 200);
});

await step('peer can download media blob', async () => {
  const r = await api('GET', `/api/media/${mediaId}`, null, bobToken);
  assert.equal(r.status, 200);
  assert.equal(String(r.body).length, 1024);
});

await step('media TTL is set', async () => {
  const row = db.prepare('SELECT expires_at FROM media WHERE id = ?').get(mediaId);
  assert.ok(row.expires_at > Date.now());
});

await step('auth guard', async () => {
  const r = await api('GET', '/api/conversations', null, 'bogus-token');
  assert.equal(r.status, 401);
});

await step('upload cap enforced', async () => {
  const fd = new FormData();
  fd.append('file', new Blob([Buffer.alloc(3 * 1024 * 1024, 1)]), 'big.bin');
  const r = await api('POST', '/api/media', fd, aliceToken);
  assert.equal(r.status, 413);
});

await new Promise((resolve) => {
  server.close(resolve);
  server.closeAllConnections?.();
});
db.close();

if (failures > 0) {
  console.error(`\n${failures} step(s) failed`);
  process.exit(1);
}
console.log('\nAll smoke tests passed');
