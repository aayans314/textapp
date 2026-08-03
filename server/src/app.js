import express from 'express';
import { config } from './config.js';
import { rateLimit } from './util.js';
import routes from './routes.js';

export function createApp() {
  const app = express();
  app.disable('x-powered-by');
  if (process.env.TRUST_PROXY === '1') app.set('trust proxy', 1);
  app.use(express.json({ limit: '256kb' }));
  app.use(rateLimit({ windowMs: 60_000, max: 300 }));
  app.get('/api/health', (_req, res) => res.json({ ok: true, name: config.appName, time: Date.now() }));
  app.use('/api', routes);
  app.use('/api', (_req, res) => res.status(404).json({ error: 'not found' }));
  app.use((err, _req, res, _next) => {
    console.error('[error]', err);
    res.status(err.status || 500).json({ error: err.message || 'internal error' });
  });
  return app;
}
