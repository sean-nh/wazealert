'use strict';
// WazeAlert community server: anonymous police/hazard reports with confirm/deny votes and a risk score.
// No dependencies (Node 18+). Storage is a JSON file; fine for a prototype / small community.
const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const MIN = 60 * 1000, HOUR = 60 * MIN, DAY = 24 * HOUR;
const TTL = { POLICE: 2 * HOUR, HAZARD: 4 * HOUR, ACCIDENT: 2 * HOUR }; // lifetime since last "still there"
const HISTORY_MS = 90 * DAY;
const MERGE_M = 150;            // same-type report this close to an active one counts as a confirmation
const HIDE_SCORE = -2;          // reports voted down this far are hidden
const MIN_REPORT_GAP_MS = 15 * 1000;
const MAX_REPORTS_PER_HOUR = 20;
const IP_LIMIT_PER_MIN = 120;
const MAX_BODY = 2048;

function distanceM(lat1, lon1, lat2, lon2) {
  const r = Math.PI / 180, p1 = lat1 * r, p2 = lat2 * r;
  const a = Math.sin((p2 - p1) / 2) ** 2 + Math.cos(p1) * Math.cos(p2) * Math.sin(((lon2 - lon1) * r) / 2) ** 2;
  return 2 * 6371000 * Math.asin(Math.min(1, Math.sqrt(a)));
}

function createApp(opts = {}) {
  const now = opts.now || (() => Date.now());
  const dataFile = opts.dataFile || process.env.DATA_FILE || path.join(__dirname, 'data', 'reports.json');
  let db = { salt: crypto.randomBytes(16).toString('hex'), reports: [] };
  try { db = JSON.parse(fs.readFileSync(dataFile, 'utf8')); } catch (_) { /* first run */ }
  let dirty = false;
  const ipHits = new Map();                 // ip -> [timestamps]; kept in memory only, never stored
  const lastReportAt = new Map();           // device hash -> ms
  const hashDev = (id) => crypto.createHash('sha256').update(db.salt + id).digest('hex').slice(0, 24);

  function save() {
    if (!dirty) return;
    fs.mkdirSync(path.dirname(dataFile), { recursive: true });
    const tmp = dataFile + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(db));
    fs.renameSync(tmp, dataFile);
    dirty = false;
  }
  function prune() {
    const cutoff = now() - HISTORY_MS;
    const before = db.reports.length;
    db.reports = db.reports.filter((r) => r.ts >= cutoff);
    if (db.reports.length !== before) dirty = true;
  }
  const score = (r) => Object.values(r.votes).reduce((a, b) => a + b, 0);
  const isActive = (r) => now() - r.lastSeen < TTL[r.type] && score(r) > HIDE_SCORE;

  function limited(ip) {
    const t = now(), arr = (ipHits.get(ip) || []).filter((x) => t - x < MIN);
    arr.push(t); ipHits.set(ip, arr);
    return arr.length > IP_LIMIT_PER_MIN;
  }

  function validDevice(id) { return typeof id === 'string' && /^[A-Za-z0-9-]{16,64}$/.test(id); }
  function validCoord(lat, lon) {
    return typeof lat === 'number' && typeof lon === 'number' && isFinite(lat) && isFinite(lon) &&
      Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
  }

  function createReport(b) {
    if (!TTL[b.type]) return [400, { error: 'type must be POLICE, HAZARD or ACCIDENT' }];
    if (!validCoord(b.lat, b.lon)) return [400, { error: 'bad coordinates' }];
    if (!validDevice(b.deviceId)) return [400, { error: 'bad deviceId' }];
    const dev = hashDev(b.deviceId), t = now();
    if (t - (lastReportAt.get(dev) || 0) < MIN_REPORT_GAP_MS) return [429, { error: 'slow down' }];
    const recent = db.reports.filter((r) => r.dev === dev && t - r.ts < HOUR).length;
    if (recent >= MAX_REPORTS_PER_HOUR) return [429, { error: 'too many reports this hour' }];
    lastReportAt.set(dev, t);

    // merge with a nearby active report of the same type: that is a confirmation, not a new report
    const near = db.reports.find((r) => r.type === b.type && isActive(r) && distanceM(r.lat, r.lon, b.lat, b.lon) <= MERGE_M);
    if (near) {
      near.votes[dev] = 1; near.lastSeen = t; dirty = true;
      return [200, { id: near.id, merged: true }];
    }
    const heading = typeof b.heading === 'number' && b.heading >= 0 && b.heading < 360 ? Math.round(b.heading) : null;
    const r = { id: crypto.randomBytes(6).toString('hex'), type: b.type, lat: b.lat, lon: b.lon, heading,
                ts: t, lastSeen: t, dev, votes: { [dev]: 1 } };
    db.reports.push(r); dirty = true;
    return [201, { id: r.id, merged: false }];
  }

  function vote(id, b) {
    if (!validDevice(b.deviceId)) return [400, { error: 'bad deviceId' }];
    if (b.vote !== 1 && b.vote !== -1) return [400, { error: 'vote must be 1 or -1' }];
    const r = db.reports.find((x) => x.id === id);
    if (!r) return [404, { error: 'unknown report' }];
    const dev = hashDev(b.deviceId);
    r.votes[dev] = b.vote;
    if (b.vote === 1) r.lastSeen = now();
    dirty = true;
    return [200, { score: score(r), active: isActive(r) }];
  }

  function list(q) {
    const lat = parseFloat(q.get('lat')), lon = parseFloat(q.get('lon'));
    if (!validCoord(lat, lon)) return [400, { error: 'bad coordinates' }];
    const radius = Math.min(parseFloat(q.get('radius')) || 10000, 30000);
    const t = now();
    const out = db.reports.filter(isActive).map((r) => ({ r, d: distanceM(lat, lon, r.lat, r.lon) }))
      .filter((x) => x.d <= radius).sort((a, b) => a.d - b.d).slice(0, 100)
      .map(({ r }) => ({ id: r.id, type: r.type, lat: r.lat, lon: r.lon, heading: r.heading,
                         ageSec: Math.round((t - r.ts) / 1000), seenSec: Math.round((t - r.lastSeen) / 1000),
                         score: score(r) }));
    return [200, { reports: out }];
  }

  // Risk of police enforcement near a point at this time, from the last 90 days of reports.
  function risk(q) {
    const lat = parseFloat(q.get('lat')), lon = parseFloat(q.get('lon'));
    if (!validCoord(lat, lon)) return [400, { error: 'bad coordinates' }];
    const radius = Math.min(parseFloat(q.get('radius')) || 3000, 20000);
    const tz = (parseInt(q.get('tz'), 10) || 0) * MIN;            // client's UTC offset in minutes
    const local = new Date(now() + tz);
    const hour = local.getUTCHours() + local.getUTCMinutes() / 60, weekend = [0, 6].includes(local.getUTCDay());
    let sum = 0, n = 0;
    for (const r of db.reports) {
      if (r.type !== 'POLICE' || distanceM(lat, lon, r.lat, r.lon) > radius || score(r) <= HIDE_SCORE) continue;
      const rl = new Date(r.ts + tz), rh = rl.getUTCHours() + rl.getUTCMinutes() / 60;
      const dh = Math.min(Math.abs(rh - hour), 24 - Math.abs(rh - hour));
      const sameKind = ([0, 6].includes(rl.getUTCDay())) === weekend;
      const timeW = dh <= 2 && sameKind ? 1 : 0.25;
      const recency = Math.pow(0.5, (now() - r.ts) / (30 * DAY));   // 30-day half-life
      sum += timeW * recency * (1 + Math.max(0, score(r) - 1) * 0.25); n++;
    }
    const s = Math.round(100 * (1 - Math.exp(-sum / 6)));   // one report ~15 (low); ~6 similar reports ~63 (high)
    return [200, { score: s, level: s < 25 ? 'low' : s < 60 ? 'moderate' : 'high', reports: n }];
  }

  function handler(req, res) {
    const send = (code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
    const ip = req.socket.remoteAddress || '?';
    if (limited(ip)) return send(429, { error: 'rate limited' });
    const url = new URL(req.url, 'http://x');
    const readBody = (cb) => {
      let size = 0; const chunks = [];
      req.on('data', (c) => { size += c.length; if (size > MAX_BODY) { req.destroy(); } else chunks.push(c); });
      req.on('end', () => { try { cb(JSON.parse(Buffer.concat(chunks).toString() || '{}')); } catch (_) { send(400, { error: 'bad json' }); } });
    };
    if (req.method === 'GET' && url.pathname === '/healthz') return send(200, { ok: true });
    if (req.method === 'GET' && url.pathname === '/v1/reports') return send(...list(url.searchParams));
    if (req.method === 'GET' && url.pathname === '/v1/risk') return send(...risk(url.searchParams));
    if (req.method === 'POST' && url.pathname === '/v1/reports') return readBody((b) => send(...createReport(b)));
    const m = url.pathname.match(/^\/v1\/reports\/([a-f0-9]{12})\/vote$/);
    if (req.method === 'POST' && m) return readBody((b) => send(...vote(m[1], b)));
    send(404, { error: 'not found' });
  }

  const server = http.createServer(handler);
  const timer = setInterval(() => { prune(); save(); }, 10 * 1000);
  timer.unref();
  return { server, save, prune, db: () => db,
           close: () => { clearInterval(timer); save(); server.close(); server.closeAllConnections(); } };
}

module.exports = { createApp, distanceM };

if (require.main === module) {
  const app = createApp();
  const port = process.env.PORT || 8080;
  app.server.listen(port, () => console.log(`WazeAlert community server on :${port}`));
  const stop = () => { app.close(); process.exit(0); };
  process.on('SIGINT', stop); process.on('SIGTERM', stop);
}
