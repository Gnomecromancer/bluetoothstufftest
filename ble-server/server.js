const express = require('express');
const http    = require('http');
const path    = require('path');
const { WebSocketServer } = require('ws');

const app    = express();
const server = http.createServer(app);
const wss    = new WebSocketServer({ server });
const PORT   = 3000;

app.use(express.static(path.join(__dirname, 'public')));

// Track connected clients: ws -> { name }
const clients = new Map();

function broadcast(msg, exclude = null) {
  const data = JSON.stringify(msg);
  for (const [ws] of clients) {
    if (ws !== exclude && ws.readyState === ws.OPEN) ws.send(data);
  }
}

function userList() {
  return [...clients.values()].map(c => c.name);
}

wss.on('connection', (ws) => {
  clients.set(ws, { name: null });

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    const client = clients.get(ws);

    if (msg.type === 'join') {
      const name = String(msg.name || '').trim().slice(0, 32) || 'Anonymous';
      client.name = name;
      // Send current user list back to the joiner
      ws.send(JSON.stringify({ type: 'users', users: userList() }));
      // Tell everyone else
      broadcast({ type: 'joined', name }, ws);
      console.log(`[room] ${name} joined (${clients.size} online)`);

    } else if (msg.type === 'chat') {
      if (!client.name) return;
      const text = String(msg.text || '').trim().slice(0, 1000);
      if (!text) return;
      const out = { type: 'chat', name: client.name, text, ts: Date.now() };
      ws.send(JSON.stringify(out));        // echo back to sender
      broadcast(out, ws);                 // relay to everyone else

    } else if (msg.type === 'rename') {
      const oldName = client.name;
      const newName = String(msg.name || '').trim().slice(0, 32) || 'Anonymous';
      client.name = newName;
      broadcast({ type: 'renamed', oldName, newName });
    }
  });

  ws.on('close', () => {
    const client = clients.get(ws);
    if (client?.name) broadcast({ type: 'left', name: client.name });
    clients.delete(ws);
  });
});

server.listen(PORT, () => {
  console.log(`Server running at http://localhost:${PORT}`);
  console.log('BLE tester + chat room — open in Chrome or Edge.');
  console.log('Self-host on a local network: others on the same network can reach you at http://<your-ip>:3000');
});
