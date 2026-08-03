import crypto from 'node:crypto';
import { config } from './config.js';

let cached = { value: null, exp: 0 };

const b64url = (buf) => Buffer.from(buf).toString('base64url');

function makeJwt() {
  const nowSec = Math.floor(Date.now() / 1000);
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = b64url(
    JSON.stringify({
      iss: config.firebase.clientEmail,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: 'https://oauth2.googleapis.com/token',
      iat: nowSec,
      exp: nowSec + 3600,
    }),
  );
  const data = `${header}.${payload}`;
  const sig = crypto.sign('RSA-SHA256', Buffer.from(data), config.firebase.privateKey);
  return `${data}.${b64url(sig)}`;
}

async function accessToken() {
  if (cached.value && cached.exp > Date.now() + 60_000) return cached.value;
  const res = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: makeJwt(),
    }),
  });
  const data = await res.json();
  if (!data.access_token) throw new Error(`FCM token exchange failed: ${JSON.stringify(data)}`);
  cached = { value: data.access_token, exp: Date.now() + Number(data.expires_in || 3600) * 1000 };
  return data.access_token;
}

/**
 * Send a data-only push. Returns { skipped, ok, invalid }.
 * `invalid` means the token is no longer registered and should be removed.
 */
export async function sendPush(token, data) {
  if (!config.firebase.projectId || !config.firebase.privateKey) return { skipped: true, ok: false, invalid: false };
  try {
    const access = await accessToken();
    const res = await fetch(
      `https://fcm.googleapis.com/v1/projects/${config.firebase.projectId}/messages:send`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${access}`,
        },
        body: JSON.stringify({
          message: {
            token,
            data: Object.fromEntries(Object.entries(data).map(([k, v]) => [k, String(v)])),
            android: { priority: 'high', ttl: '86400s' },
          },
        }),
      },
    );
    return { skipped: false, ok: res.ok, invalid: res.status === 400 || res.status === 404 };
  } catch (e) {
    console.error('[push] send failed:', e.message);
    return { skipped: false, ok: false, invalid: false };
  }
}
