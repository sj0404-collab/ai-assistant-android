import express from 'express';
import { createServer } from 'http';
import { WebSocketServer } from 'ws';
import { spawn, execSync } from 'child_process';
import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync, statSync, unlinkSync, rmSync } from 'fs';
import { join, resolve, extname, basename } from 'path';
import { fileURLToPath } from 'url';

const __dirname = resolve(fileURLToPath(import.meta.url), '..');
const WORK_DIR = process.env.HOME + '/hub-work';
const HUB_TOKEN = process.env.HUB_TOKEN || '';
const GH_TOKEN = process.env.GH_TOKEN || '';
const PORT = process.env.PORT || 8097;

mkdirSync(WORK_DIR, { recursive: true });

const app = express();
app.use(express.json({ limit: '50mb' }));
app.use(express.raw({ type: 'application/octet-stream', limit: '50mb' }));

// Auth middleware
function auth(req, res, next) {
  if (HUB_TOKEN && req.headers['x-hub-token'] !== HUB_TOKEN) {
    return res.status(401).json({ error: 'unauthorized' });
  }
  next();
}

app.use(auth);

// Health check
app.get('/api/health', (req, res) => {
  res.json({ ok: true, time: Date.now() });
});

// List installed CLI tools
app.get('/api/tools', (req, res) => {
  const tools = [
    'opencode', 'claude', 'gemini', 'codex', 'copilot', 'qwen', 'aider', 'goose', 'http-server'
  ].map(name => {
    let installed = false;
    let path = '';
    try {
      path = execSync(`which ${name}`, { encoding: 'utf8', stdio: 'pipe' }).trim();
      installed = path !== '';
    } catch {}
    return { name, installed, path };
  });
  res.json({ tools, warming: false });
});

// List files in workspace
app.get('/api/files', (req, res) => {
  try {
    const files = readdirSync(WORK_DIR).map(name => {
      const p = join(WORK_DIR, name);
      const s = statSync(p);
      return { name, size: s.size, mtime: s.mtimeMs, isDir: s.isDirectory() };
    });
    res.json({ files });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Download file
app.get('/api/files/:name', (req, res) => {
  try {
    const name = req.params.name;
    const p = join(WORK_DIR, name);
    if (!existsSync(p)) return res.status(404).json({ error: 'not found' });
    res.download(p, name);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Upload file
app.post('/api/files', (req, res) => {
  try {
    const name = req.headers['x-file-name'] || 'upload.bin';
    const p = join(WORK_DIR, name);
    writeFileSync(p, req.body);
    res.json({ ok: true, name, size: req.body.length });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Delete file
app.delete('/api/files/:name', (req, res) => {
  try {
    const name = req.params.name;
    const p = join(WORK_DIR, name);
    if (existsSync(p)) {
      statSync(p).isDirectory() ? rmSync(p, { recursive: true }) : unlinkSync(p);
    }
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// GitHub proxy (if GH_TOKEN set)
app.all('/gh/*', async (req, res) => {
  if (!GH_TOKEN) return res.status(404).json({ error: 'gh proxy disabled' });
  try {
    const path = req.params[0];
    const url = `https://api.github.com/${path}${req.url.includes('?') ? req.url.substring(req.url.indexOf('?')) : ''}`;
    const response = await fetch(url, {
      method: req.method,
      headers: {
        'Authorization': `Bearer ${GH_TOKEN}`,
        'Accept': 'application/vnd.github+json',
        'Content-Type': 'application/json',
        'User-Agent': 'ai-hub'
      },
      body: ['GET', 'HEAD'].includes(req.method) ? undefined : JSON.stringify(req.body)
    });
    const data = await response.text();
    res.status(response.status).set(response.headers).send(data);
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// Serve static frontend (xterm.js)
app.use(express.static(join(__dirname, 'public')));

// HTTP server + WebSocket
const server = createServer(app);
const wss = new WebSocketServer({ server, path: '/ws/terminal' });

const sessions = new Map();

wss.on('connection', (ws, req) => {
  const sessionId = Math.random().toString(36).slice(2);
  let ptyProcess = null;
  let shell = process.env.SHELL || '/bin/bash';

  // Spawn PTY using node-pty if available
  try {
    const pty = await import('node-pty');
    ptyProcess = pty.spawn(shell, [], {
      name: 'xterm-color',
      cols: 80,
      rows: 24,
      cwd: WORK_DIR,
      env: { ...process.env, TERM: 'xterm-256color', HUB_WORK_DIR: WORK_DIR }
    });
  } catch (e) {
    // Fallback: simple spawn (no PTY, but works)
    ptyProcess = spawn(shell, [], { cwd: WORK_DIR, env: { ...process.env, TERM: 'xterm-256color' } });
    ptyProcess.stdout.on('data', d => ws.send(JSON.stringify({ type: 'data', data: d.toString() })));
    ptyProcess.stderr.on('data', d => ws.send(JSON.stringify({ type: 'data', data: d.toString() })));
  }

  if (ptyProcess?.onData) {
    ptyProcess.onData(data => {
      if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'data', data }));
    });
    ptyProcess.onExit(() => {
      if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'exit' }));
      sessions.delete(sessionId);
    });
  } else if (ptyProcess?.on) {
    ptyProcess.on('exit', () => {
      if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'exit' }));
      sessions.delete(sessionId);
    });
  }

  sessions.set(sessionId, { ws, pty: ptyProcess });

  ws.on('message', msg => {
    try {
      const m = JSON.parse(msg);
      if (m.type === 'data' && ptyProcess?.write) {
        ptyProcess.write(m.data);
      } else if (m.type === 'resize' && ptyProcess?.resize) {
        ptyProcess.resize(m.cols, m.rows);
      } else if (m.type === 'data' && ptyProcess?.stdin?.write) {
        ptyProcess.stdin.write(m.data);
      }
    } catch {}
  });

  ws.on('close', () => {
    ptyProcess?.kill?.();
    ptyProcess?.stdin?.end?.();
    sessions.delete(sessionId);
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`AI Hub listening on ${PORT}, workdir: ${WORK_DIR}`);
});