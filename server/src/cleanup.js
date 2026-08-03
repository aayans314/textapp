import { existsSync, unlinkSync } from 'node:fs';
import { config } from './config.js';
import { db, mediaPath } from './db.js';
import { now } from './util.js';

export function cleanupOnce() {
  const expired = db.prepare('SELECT id FROM media WHERE expires_at < ?').all(now());
  for (const { id } of expired) {
    const p = mediaPath(id);
    if (existsSync(p)) {
      try {
        unlinkSync(p);
      } catch {
        /* noop */
      }
    }
  }
  db.prepare('DELETE FROM media WHERE expires_at < ?').run(now());
  db.prepare('DELETE FROM verify_codes WHERE expires_at < ?').run(now());
  db.prepare('DELETE FROM push_tokens WHERE updated_at < ?').run(now() - 90 * 24 * 3600 * 1000);
}

export function startCleanup() {
  cleanupOnce();
  setInterval(cleanupOnce, config.cleanupIntervalMs).unref();
}
