import crypto from 'node:crypto';

export const now = () => Date.now();
export const uid = (prefix = 'id') => `${prefix}_${crypto.randomBytes(12).toString('hex')}`;

export function rateLimit({ windowMs = 60000, max = 60 } = {}) {
  const hits = new Map();
  return (req, res, next) => {
    const key = req.ip || req.socket.remoteAddress || '?';
    const t = Date.now();
    let rec = hits.get(key);
    if (!rec || rec.at + windowMs < t) {
      rec = { n: 0, at: t };
      hits.set(key, rec);
    }
    rec.n += 1;
    if (hits.size > 5000) {
      for (const [k, v] of hits) if (v.at + windowMs < t) hits.delete(k);
    }
    if (rec.n > max) return res.status(429).json({ error: 'too many requests, slow down' });
    next();
  };
}

export function wrap(fn) {
  return (req, res, next) => Promise.resolve(fn(req, res, next)).catch(next);
}
