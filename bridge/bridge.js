#!/usr/bin/env node
// PTZ Bridge – serves the PTZ controller page to tablets and relays tally from the switcher.
// Runs on macOS and Windows with plain Node.js (no extra packages).
'use strict';
const http = require('http');
const dgram = require('dgram');
const net = require('net');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { exec, spawn } = require('child_process');
const QRCode = require('./vendor/QRCode');                 // Apache-2.0, from qrcode-terminal
const QRLevel = require('./vendor/QRCode/QRErrorCorrectLevel');

function qrMatrix(text) {
  const q = new QRCode(-1, QRLevel.M); q.addData(text); q.make();
  const n = q.getModuleCount(), m = [];
  for (let r = 0; r < n; r++) { const row = []; for (let c = 0; c < n; c++) row.push(q.isDark(r, c)); m.push(row); }
  return m;
}
function qrSvg(text) {
  const m = qrMatrix(text), n = m.length, pad = 4, size = n + pad * 2; let d = '';
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (m[r][c]) d += `M${c + pad} ${r + pad}h1v1h-1z`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" shape-rendering="crispEdges"><rect width="${size}" height="${size}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}
function qrConsole(text) { // two rows per character with half blocks, white quiet zone
  const m = qrMatrix(text), n = m.length, P = 2, get = (r, c) => r >= 0 && c >= 0 && r < n && c < n && m[r][c];
  const lines = [];
  for (let r = -P; r < n + P; r += 2) {
    let l = '  ';
    for (let c = -P; c < n + P; c++) { const a = get(r, c), b = get(r + 1, c); l += a && b ? ' ' : a ? '\u2584' : b ? '\u2580' : '\u2588'; }
    lines.push(l);
  }
  return lines.join('\n');
}

const VERSION = '0.9';
const PORT = Number(process.env.PTZ_PORT) || 8080;
const DIR = __dirname;
// Packaged as a Node single executable (Mac app / Windows exe): pages are embedded assets,
// settings live in the user's app-data folder, and there may be no console window.
let SEA = null;
try { const s = require('node:sea'); if (s.isSea()) SEA = s; } catch (e) {}
const PACKED = !!SEA;
function dataDir() {
  if (!PACKED) return DIR;
  const home = os.homedir();
  const d = process.platform === 'win32' ? path.join(process.env.APPDATA || home, 'PTZ Bridge')
    : process.platform === 'darwin' ? path.join(home, 'Library', 'Application Support', 'PTZ Bridge')
    : path.join(home, '.config', 'ptz-bridge');
  try { fs.mkdirSync(d, { recursive: true }); } catch (e) {}
  return d;
}
const CONFIG_FILE = path.join(dataDir(), 'config.json');
const PUBLIC = path.join(DIR, 'public');
function readPublic(file, cb) {
  if (PACKED) { try { return cb(null, Buffer.from(SEA.getAsset(file))); } catch (e) { return cb(e); } }
  fs.readFile(path.join(PUBLIC, file), cb);
}
function openUrl(u) {
  const cmd = process.platform === 'darwin' ? `open "${u}"` : process.platform === 'win32' ? `start "" "${u}"` : `xdg-open "${u}"`;
  exec(cmd, () => {});
}
function quit() { if (client) client.stop(); setTimeout(() => process.exit(0), 150); }

// ---------------- config ----------------
const DEFAULT_CONFIG = { switcher: { type: 'none', ip: '' } };
function loadConfig() {
  try { return { ...DEFAULT_CONFIG, ...JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')) }; }
  catch (e) { return JSON.parse(JSON.stringify(DEFAULT_CONFIG)); }
}
function saveConfig(c) { fs.writeFileSync(CONFIG_FILE, JSON.stringify(c, null, 2)); }
let config = loadConfig();

// ---------------- shared tally state ----------------
const tally = { type: 'none', connected: false, model: '', program: [], preview: [], inputs: 0, error: '', since: 0 };
function setTally(patch) { Object.assign(tally, patch); }

// ---------------- ATEM client (UDP 9910) ----------------
// Minimal implementation of the ATEM switcher protocol: handshake, acknowledgements and
// the "TlIn" (tally by input) and "_pin" (product name) commands.
class AtemClient {
  constructor(ip) {
    this.ip = ip; this.sock = null; this.session = 0; this.lastRx = 0; this.lastRemoteId = -1;
    this.state = 'idle'; this.timer = null; this.stopped = false;
  }
  start() {
    this.stopped = false;
    this.sock = dgram.createSocket('udp4');
    this.sock.on('message', (m) => this.onMessage(m));
    this.sock.on('error', (e) => { setTally({ connected: false, error: 'Sieťová chyba: ' + e.message }); });
    this.sock.bind(0, () => this.hello());
    this.timer = setInterval(() => this.tick(), 1000);
  }
  stop() {
    this.stopped = true; clearInterval(this.timer);
    try { this.sock && this.sock.close(); } catch (e) {}
    this.sock = null;
  }
  hello() {
    this.state = 'connecting'; this.lastRemoteId = -1; this.helloAt = Date.now();
    const pkt = Buffer.from([0x10, 0x14, 0x53, 0xab, 0x00, 0x00, 0x00, 0x00, 0x00, 0x3a, 0x00, 0x00,
      0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00]);
    this.send(pkt);
  }
  send(buf) { if (this.sock) this.sock.send(buf, 9910, this.ip); }
  ack(remoteId) {
    const b = Buffer.alloc(12);
    b.writeUInt16BE((0x10 << 11) | 12, 0);
    b.writeUInt16BE(this.session, 2);
    b.writeUInt16BE(remoteId, 4);
    this.send(b);
  }
  tick() {
    const now = Date.now();
    if (this.state === 'connected' && now - this.lastRx > 3000) {
      setTally({ connected: false, program: [], preview: [], error: 'Réžia prestala odpovedať, pripájam znova…' });
      this.hello();
    } else if (this.state === 'connecting' && now - this.helloAt > 2000) {
      setTally({ connected: false, error: `Réžia na ${this.ip} neodpovedá` });
      this.hello();
    }
  }
  onMessage(m) {
    if (m.length < 12) return;
    const flags = m[0] >> 3;
    const length = m.readUInt16BE(0) & 0x07ff;
    this.session = m.readUInt16BE(2);
    const remoteId = m.readUInt16BE(10);
    this.lastRx = Date.now();
    if (flags & 0x02) { // new session id / hello answer
      if (m.length > 12 && m[12] === 0x03) { setTally({ connected: false, error: 'Réžia odmietla pripojenie (príliš veľa klientov)' }); return; }
      this.state = 'connected'; this.lastRemoteId = remoteId; this.ack(remoteId);
      return;
    }
    if (this.state !== 'connected') return;
    if (flags & 0x01) { // reliable packet, must be acknowledged
      const expected = (this.lastRemoteId + 1) & 0x7fff;
      if (this.lastRemoteId < 0 || remoteId === expected) {
        this.lastRemoteId = remoteId; this.ack(remoteId);
        this.parseCommands(m.subarray(12, Math.min(length, m.length)));
      } else {
        this.ack(remoteId); // duplicate or out of order – acknowledge, ignore
      }
    }
  }
  parseCommands(buf) {
    let off = 0;
    while (off + 8 <= buf.length) {
      const len = buf.readUInt16BE(off);
      if (len < 8 || off + len > buf.length) break;
      const name = buf.toString('ascii', off + 4, off + 8);
      const data = buf.subarray(off + 8, off + len);
      this.onCommand(name, data);
      off += len;
    }
  }
  onCommand(name, d) {
    if (name === '_pin') {
      setTally({ model: d.toString('utf8').replace(/\0.*$/s, '').trim() });
    } else if (name === 'InCm') { // initial state dump finished
      setTally({ connected: true, error: '', since: Date.now() });
    } else if (name === 'TlIn' && d.length >= 2) {
      const n = d.readUInt16BE(0); const program = []; const preview = [];
      for (let i = 0; i < n && 2 + i < d.length; i++) {
        const f = d[2 + i];
        if (f & 0x01) program.push(i + 1);
        if (f & 0x02) preview.push(i + 1);
      }
      setTally({ program, preview, inputs: n, connected: true, error: '' });
    }
  }
}

// ---------------- vMix (TCP API, port 8099) ----------------
// "SUBSCRIBE TALLY" makes vMix push "TALLY OK <digits>" on every change; one digit per input:
// 0 = off, 1 = program, 2 = preview.
class VmixClient {
  constructor(ip) { this.ip = ip; this.sock = null; this.buf = ''; this.timer = null; this.stopped = false; this.lastRx = 0; }
  start() { this.stopped = false; this.connect(); this.timer = setInterval(() => this.tick(), 1000); }
  stop() { this.stopped = true; clearInterval(this.timer); clearTimeout(this.retry); if (this.sock) this.sock.destroy(); this.sock = null; }
  connect() {
    if (this.stopped) return;
    this.buf = ''; this.lastRx = Date.now();
    const s = net.createConnection({ host: this.ip, port: 8099 });
    this.sock = s; s.setNoDelay(true); s.setEncoding('utf8');
    const to = setTimeout(() => { setTally({ connected: false, error: `vMix na ${this.ip} neodpovedá` }); s.destroy(); }, 3000);
    s.on('connect', () => { clearTimeout(to); s.write('VERSION\r\nSUBSCRIBE TALLY\r\nTALLY\r\n'); });
    s.on('data', (d) => { this.lastRx = Date.now(); this.buf += d; let i; while ((i = this.buf.indexOf('\r\n')) >= 0) { const line = this.buf.slice(0, i); this.buf = this.buf.slice(i + 2); this.onLine(line); } });
    s.on('error', (e) => { clearTimeout(to); setTally({ connected: false, program: [], preview: [], error: e.code === 'ECONNREFUSED' ? `vMix na ${this.ip} odmietol pripojenie – beží vMix?` : `vMix na ${this.ip} neodpovedá` }); });
    s.on('close', () => { clearTimeout(to); if (this.sock === s) this.sock = null; if (!this.stopped) { setTally({ connected: false, program: [], preview: [] }); this.retry = setTimeout(() => this.connect(), 2000); } });
  }
  tick() { // keep-alive: ask for tally if nothing arrived for a while
    if (this.sock && Date.now() - this.lastRx > 5000) { this.sock.write('TALLY\r\n'); if (Date.now() - this.lastRx > 10000) this.sock.destroy(); }
  }
  onLine(line) {
    let m;
    if ((m = line.match(/^VERSION OK (.+)$/))) setTally({ model: 'vMix ' + m[1].trim() });
    else if ((m = line.match(/^TALLY OK (\d*)$/))) {
      const program = [], preview = [];
      for (let i = 0; i < m[1].length; i++) { const c = m[1][i]; if (c === '1') program.push(i + 1); else if (c === '2') preview.push(i + 1); }
      setTally({ program, preview, inputs: m[1].length, connected: true, error: '' });
    }
  }
}

// ---------------- OSEE GoStream (UDP 19018, JSON) ----------------
// Per the GoStream network protocol: send {"id":"pgmTally","type":"get","value":[]} (and pvwTally);
// the switcher answers with type "res" and pushes type "pus" on every change. value = list of source IDs (IN1 = 1 …).
// Split a datagram into top-level JSON objects (GoStream sends pretty-printed JSON across several lines).
function jsonObjects(txt) {
  const out = []; let depth = 0, start = -1, str = false, esc = false;
  for (let i = 0; i < txt.length; i++) {
    const c = txt[i];
    if (str) { if (esc) esc = false; else if (c === '\\') esc = true; else if (c === '"') str = false; continue; }
    if (c === '"') str = true;
    else if (c === '{') { if (depth++ === 0) start = i; }
    else if (c === '}' && depth > 0 && --depth === 0) out.push(txt.slice(start, i + 1));
  }
  return out;
}

class OseeClient {
  constructor(ip) { this.ip = ip; this.sock = null; this.timer = null; this.lastRx = 0; this.pgm = null; this.pvw = null; this.tx = 0; this.rx = 0; this.raw = ''; this.port = 0; }
  start() {
    setTally({ model: 'OSEE GoStream' });
    this.open(19018);
    this.timer = setInterval(() => this.tick(), 1000);
  }
  // GoStream may answer to port 19018 instead of our source port, so listen on 19018 when it is free.
  open(port) {
    const s = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    this.sock = s;
    s.on('message', (m, r) => this.onMessage(m, r));
    s.on('error', (e) => {
      if (port && e.code === 'EADDRINUSE' && this.sock === s) { try { s.close(); } catch (x) {} console.log('OSEE: port 19018 je obsadený, počúvam na náhodnom porte'); return this.open(0); }
      setTally({ connected: false, error: 'Sieťová chyba: ' + e.message });
    });
    s.bind(port, () => { this.port = s.address().port; console.log(`OSEE: počúvam na UDP ${this.port}, pýtam sa ${this.ip}:19018`); this.ask(); });
  }
  stop() { clearInterval(this.timer); try { this.sock && this.sock.close(); } catch (e) {} this.sock = null; }
  send(obj) {
    if (!this.sock) return;
    this.sock.send(Buffer.from(JSON.stringify(obj)), 19018, this.ip, (e) => { if (e) setTally({ connected: false, error: 'Odoslanie zlyhalo: ' + e.message }); });
    this.tx++;
  }
  ask() { this.send({ id: 'pgmTally', type: 'get', value: [] }); this.send({ id: 'pvwTally', type: 'get', value: [] }); }
  tick() {
    this.ask(); // keeps the switcher talking to us and doubles as a connection check
    if (Date.now() - this.lastRx > 3000) {
      const why = this.rx ? `prišlo ${this.rx} správ, ale nie tally. Posledná: ${this.raw.slice(0, 160)}`
        : `odoslaných ${this.tx} požiadaviek na port 19018, odpoveď 0 (počúvam na porte ${this.port})`;
      setTally({ connected: false, program: [], preview: [], error: `OSEE GoStream na ${this.ip} neodpovedá – ${why}` });
      this.pgm = this.pvw = null;
    }
  }
  onMessage(m, r) {
    const txt = m.toString('utf8').replace(/\0+/g, '').trim();
    this.rx++; this.raw = `${r.address}:${r.port} ${txt}`;
    if (this.rx <= 5) console.log('OSEE prijaté:', this.raw.slice(0, 300));
    for (const part of jsonObjects(txt)) {
      let j; try { j = JSON.parse(part); } catch (e) { continue; }
      if (!j || (j.type !== 'res' && j.type !== 'pus')) continue;
      const arr = Array.isArray(j.value) ? j.value : j.value == null || j.value === '' ? [] : [j.value];
      const v = arr.map(Number).filter(n => Number.isFinite(n) && n > 0);
      if (j.id === 'pgmTally') this.pgm = v; else if (j.id === 'pvwTally') this.pvw = v; else continue;
      this.lastRx = Date.now();
      const all = [...(this.pgm || []), ...(this.pvw || [])];
      setTally({ program: this.pgm || [], preview: this.pvw || [], inputs: Math.max(tally.inputs || 0, ...all, 0),
        connected: this.pgm !== null && this.pvw !== null, error: '' });
    }
  }
}

// ---------------- switcher lifecycle ----------------
let client = null;
function startSwitcher() {
  if (client) client.stop();
  client = null;
  const { type, ip } = config.switcher;
  setTally({ type, connected: false, model: '', program: [], preview: [], inputs: 0, error: '' });
  if (type === 'none') return;
  if (!ip) { setTally({ error: 'Zadaj IP adresu réžie' }); return; }
  client = type === 'atem' ? new AtemClient(ip) : type === 'vmix' ? new VmixClient(ip) : new OseeClient(ip);
  client.start();
}

// ---------------- tablets seen ----------------
const clients = new Map(); // ip -> last seen
function seen(req) { clients.set((req.socket.remoteAddress || '').replace(/^::ffff:/, ''), Date.now()); }
function activeClients() {
  const now = Date.now(); const list = [];
  for (const [ip, t] of clients) { if (now - t < 5000) list.push(ip); else clients.delete(ip); }
  return list;
}

// ---------------- addresses ----------------
function hostName() {
  let h = os.hostname().replace(/\.local$/i, '');
  return h;
}
function lanAddresses() {
  const out = [];
  for (const [name, list] of Object.entries(os.networkInterfaces())) {
    for (const a of list || []) {
      if (a.family === 'IPv4' && !a.internal) out.push({ name, ip: a.address });
    }
  }
  return out;
}
function primaryUrl() { const a = lanAddresses()[0]; return a ? `http://${a.ip}:${PORT}/` : `http://localhost:${PORT}/`; }
function tabletUrls() {
  const urls = lanAddresses().map(a => `http://${a.ip}:${PORT}/`);
  if (process.platform === 'darwin') urls.unshift(`http://${hostName()}.local:${PORT}/`);
  return urls;
}

// ---------------- HTTP ----------------
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.webmanifest': 'application/manifest+json' };
function sendJson(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Private-Network': 'true' });
  res.end(JSON.stringify(obj));
}
function statusObj() {
  return {
    app: 'ptz-bridge', version: VERSION, platform: process.platform, host: hostName(), port: PORT, urls: tabletUrls(), primary: primaryUrl(),
    switcher: { type: tally.type, ip: config.switcher.ip, connected: tally.connected, model: tally.model, error: tally.error,
      program: tally.program, preview: tally.preview, inputs: tally.inputs },
    tablets: activeClients()
  };
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let b = ''; req.on('data', c => { b += c; if (b.length > 1e5) req.destroy(); });
    req.on('end', () => resolve(b)); req.on('error', reject);
  });
}
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  if (req.method === 'OPTIONS') {
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type', 'Access-Control-Allow-Private-Network': 'true' });
    return res.end();
  }
  if (p === '/tally') {
    seen(req);
    return sendJson(res, 200, { connected: tally.connected, switcher: tally.type, model: tally.model, program: tally.program, preview: tally.preview });
  }
  if (p === '/api/status') return sendJson(res, 200, statusObj());
  if (p === '/api/qr.svg') {
    const u = url.searchParams.get('u'); const target = tabletUrls().includes(u) ? u : primaryUrl();
    res.writeHead(200, { 'Content-Type': 'image/svg+xml', 'Cache-Control': 'no-store' }); return res.end(qrSvg(target));
  }
  if (p === '/api/quit' && req.method === 'POST') { sendJson(res, 200, { ok: true }); return quit(); }
  if (p === '/api/config' && req.method === 'POST') {
    try {
      const body = JSON.parse(await readBody(req));
      const type = ['none', 'atem', 'vmix', 'osee'].includes(body.type) ? body.type : 'none';
      const ip = String(body.ip || '').trim();
      if (ip && !/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return sendJson(res, 400, { error: 'Neplatná IP adresa' });
      config.switcher = { type, ip };
      saveConfig(config); startSwitcher();
      return sendJson(res, 200, statusObj());
    } catch (e) { return sendJson(res, 400, { error: 'Chybná požiadavka' }); }
  }
  // static files
  let file = p === '/' ? 'index.html' : p === '/admin' ? 'admin.html' : p.slice(1);
  file = path.normalize(file).replace(/^(\.\.[\/\\])+/, '');
  if (!/^[\w.-]+$/.test(file)) { res.writeHead(403); return res.end(); }
  readPublic(file, (err, data) => {
    if (err) { res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' }); return res.end('Nenájdené'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-cache' });
    res.end(data);
  });
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    // Already running (e.g. the app was opened twice): just show its window and leave.
    if (PACKED) { openUrl(`http://localhost:${PORT}/admin`); setTimeout(() => process.exit(0), 500); return; }
    console.error(`\nPort ${PORT} je obsadený – program už pravdepodobne beží v inom okne.\n`);
  }
  else console.error(e);
  process.exit(1);
});

server.listen(PORT, '0.0.0.0', () => {
  startSwitcher();
  console.log(`\nPTZ Bridge ${VERSION} beží.\n`);
  console.log('Na tablete otvor (alebo naskenuj QR kód):');
  for (const u of tabletUrls()) console.log('   ' + u);
  console.log('');
  try { console.log(qrConsole(primaryUrl())); } catch (e) {}
  console.log('   ' + primaryUrl());
  console.log(`\nNastavenia a stav: http://localhost:${PORT}/admin`);
  console.log('Program ukončíš zatvorením tohto okna (alebo Ctrl+C).\n');
  if (!process.env.PTZ_NO_BROWSER) openUrl(`http://localhost:${PORT}/admin`);
  // Keep the Mac awake while the program runs, otherwise tablets lose tally when it sleeps.
  if (process.platform === 'darwin') { try { spawn('caffeinate', ['-is', '-w', String(process.pid)], { stdio: 'ignore', detached: true }).unref(); } catch (e) {} }
});

let lastLine = '';
setInterval(() => {
  const line = tally.type === 'none' ? 'Réžia: nenastavená' :
    tally.connected ? `Réžia: ${tally.model || tally.type.toUpperCase()} pripojená | program ${tally.program.join(',') || '–'} | preview ${tally.preview.join(',') || '–'}` :
    `Réžia: nepripojená – ${tally.error || 'pripájam…'}`;
  if (line !== lastLine) { console.log(new Date().toLocaleTimeString() + '  ' + line); lastLine = line; }
}, 500);

process.on('SIGINT', quit);
process.on('SIGTERM', quit);
module.exports = { AtemClient, tally };
