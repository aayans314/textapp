import { createServer } from 'node:http';
import { createApp } from './app.js';
import { startCleanup } from './cleanup.js';
import { config } from './config.js';
import { initWs } from './ws.js';

const app = createApp();
const server = createServer(app);
initWs(server);
startCleanup();

server.listen(config.port, config.host, () => {
  console.log(`[textapp] listening on http://${config.host}:${config.port}`);
  console.log(
    `[textapp] limits: upload ${Math.round(config.maxUploadBytes / 1048576)} MB, payload ${Math.round(
      config.maxPayloadBytes / 1024,
    )} KB, media TTL ${Math.round(config.mediaTtlMs / 86400000)} days`,
  );
  if (!config.firebase.projectId) console.log('[textapp] FCM not configured - push notifications disabled');
  if (!config.mailer.host) console.log('[textapp] SMTP not configured - codes logged to data/dev-mails.log');
});
