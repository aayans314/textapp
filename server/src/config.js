import 'dotenv/config';
import crypto from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const dataDir = process.env.DATA_DIR || join(process.cwd(), 'data');
mkdirSync(dataDir, { recursive: true });
mkdirSync(join(dataDir, 'media'), { recursive: true });

function loadSecret() {
  if (process.env.SECRET) return process.env.SECRET;
  const file = join(dataDir, 'secret.key');
  if (existsSync(file)) return readFileSync(file, 'utf8').trim();
  const secret = crypto.randomBytes(32).toString('hex');
  writeFileSync(file, secret, { mode: 0o600 });
  return secret;
}

const bool = (v) => v === '1' || v === 'true';

export const config = {
  appName: 'TextApp',
  port: Number(process.env.PORT || 9090),
  host: process.env.HOST || '0.0.0.0',
  dataDir,
  secret: loadSecret(),
  tokenTtlMs: Number(process.env.TOKEN_TTL_HOURS || 24 * 30) * 3600 * 1000,
  maxUploadBytes: Number(process.env.MAX_UPLOAD_MB || 25) * 1024 * 1024,
  maxPayloadBytes: Number(process.env.MAX_PAYLOAD_KB || 64) * 1024,
  mediaTtlMs: Number(process.env.MEDIA_TTL_DAYS || 30) * 24 * 3600 * 1000,
  cleanupIntervalMs: Number(process.env.CLEANUP_INTERVAL_MIN || 60) * 60 * 1000,
  publicUrl: process.env.PUBLIC_URL || '',
  mailer: {
    resendKey: process.env.RESEND_API_KEY || '',
    host: process.env.SMTP_HOST || '',
    port: Number(process.env.SMTP_PORT || 587),
    secure: process.env.SMTP_SECURE ? bool(process.env.SMTP_SECURE) : String(process.env.SMTP_PORT || '') === '465',
    user: process.env.SMTP_USER || '',
    pass: process.env.SMTP_PASS || '',
    from: process.env.RESEND_FROM || process.env.SMTP_FROM || '',
  },
  firebase: {
    projectId: process.env.FIREBASE_PROJECT_ID || '',
    clientEmail: process.env.FIREBASE_CLIENT_EMAIL || '',
    privateKey: (process.env.FIREBASE_PRIVATE_KEY || '').replace(/\\n/g, '\n'),
  },
};
