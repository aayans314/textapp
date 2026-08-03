import { WebSocketServer } from 'ws';
import { verifyToken } from './auth.js';
import { db } from './db.js';
import { now } from './util.js';

const clients = new Map(); // userId -> Set<WebSocket>

export function isOnline(userId) {
  return (clients.get(userId)?.size ?? 0) > 0;
}

export function sendToUser(userId, obj) {
  const sockets = clients.get(userId);
  if (!sockets || sockets.size === 0) return false;
  const msg = JSON.stringify(obj);
  for (const ws of sockets) {
    if (ws.readyState === ws.OPEN) ws.send(msg);
  }
  return true;
}

function usernameOf(userId) {
  return db.prepare('SELECT username FROM users WHERE id = ?').get(userId)?.username || null;
}

function friendIds(userId) {
  return db
    .prepare(
      `SELECT CASE WHEN user_a = ? THEN user_b ELSE user_a END AS fid
       FROM friends WHERE (user_a = ? OR user_b = ?) AND status = 'accepted'`,
    )
    .all(userId, userId, userId)
    .map((r) => r.fid);
}

function broadcastPresence(userId, online) {
  const username = usernameOf(userId);
  if (!username) return;
  const msg = { t: 'presence', username, online };
  for (const fid of friendIds(userId)) sendToUser(fid, msg);
}

function relayTyping(userId, convId) {
  const conv = db.prepare('SELECT user_a, user_b FROM conversations WHERE id = ?').get(convId);
  if (!conv) return;
  const other = conv.user_a === userId ? conv.user_b : conv.user_a;
  const username = usernameOf(userId);
  if (username) sendToUser(other, { t: 'typing', conv: convId, username });
}

function handleRead(userId, convId) {
  const conv = db.prepare('SELECT user_a, user_b FROM conversations WHERE id = ?').get(convId);
  if (!conv || (conv.user_a !== userId && conv.user_b !== userId)) return;
  const t = now();
  const res = db
    .prepare('UPDATE messages SET read_at = ? WHERE conv_id = ? AND sender_id != ? AND read_at IS NULL')
    .run(t, convId, userId);
  if (res.changes > 0) {
    const other = conv.user_a === userId ? conv.user_b : conv.user_a;
    const username = usernameOf(userId);
    if (username) sendToUser(other, { t: 'read', conv: convId, by: username });
  }
}

export function initWs(server) {
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (req, socket, head) => {
    let userId = null;
    try {
      userId = verifyToken(new URL(req.url, 'http://localhost').searchParams.get('token') || '');
    } catch {
      userId = null;
    }
    if (!userId) {
      socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.userId = userId;
      ws.isAlive = true;
      wss.emit('connection', ws);
    });
  });

  wss.on('connection', (ws) => {
    const userId = ws.userId;
    if (!clients.has(userId)) clients.set(userId, new Set());
    clients.get(userId).add(ws);
    broadcastPresence(userId, true);

    ws.on('pong', () => {
      ws.isAlive = true;
    });
    ws.on('message', (raw) => {
      let msg;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }
      if (msg.t === 'typing' && msg.conv) relayTyping(userId, msg.conv);
      else if (msg.t === 'read' && msg.conv) handleRead(userId, msg.conv);
      else if (msg.t === 'ping') ws.send(JSON.stringify({ t: 'pong' }));
    });
    ws.on('close', () => {
      const set = clients.get(userId);
      if (set) {
        set.delete(ws);
        if (set.size === 0) clients.delete(userId);
      }
      broadcastPresence(userId, false);
    });
    ws.on('error', () => {});
  });

  setInterval(() => {
    for (const ws of wss.clients) {
      if (ws.isAlive === false) {
        ws.terminate();
        continue;
      }
      ws.isAlive = false;
      ws.ping();
    }
  }, 30_000).unref();
}
