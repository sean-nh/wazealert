'use strict';
const test = require('node:test');
const assert = require('node:assert');
const os = require('os');
const path = require('path');
const { createApp } = require('../server');

const DEV = (n) => 'device-' + String(n).padStart(12, '0');

async function boot() {
  let t = Date.UTC(2026, 10, 25, 14, 0, 0);               // a Wednesday, 14:00 UTC
  const clock = { advance: (ms) => { t += ms; }, now: () => t };
  const file = path.join(os.tmpdir(), 'wa-test-' + Math.random().toString(36).slice(2) + '.json');
  const app = createApp({ now: clock.now, dataFile: file });
  await new Promise((r) => app.server.listen(0, '127.0.0.1', r));
  const base = 'http://127.0.0.1:' + app.server.address().port;
  const call = async (method, p, body) => {
    const r = await fetch(base + p, { method, headers: { 'Content-Type': 'application/json' }, body: body ? JSON.stringify(body) : undefined });
    return { status: r.status, body: await r.json() };
  };
  return { app, clock, call };
}

test('create, list and expire a police report', async () => {
  const { app, clock, call } = await boot();
  const c = await call('POST', '/v1/reports', { type: 'POLICE', lat: 39.75, lon: -84.22, heading: 270, deviceId: DEV(1) });
  assert.strictEqual(c.status, 201);
  let l = await call('GET', '/v1/reports?lat=39.75&lon=-84.22&radius=5000');
  assert.strictEqual(l.body.reports.length, 1);
  assert.strictEqual(l.body.reports[0].type, 'POLICE');
  assert.strictEqual(l.body.reports[0].heading, 270);
  assert.ok(!('dev' in l.body.reports[0]) && !('deviceId' in l.body.reports[0]), 'no device ids leak');
  clock.advance(2 * 3600 * 1000 + 1000);                  // police TTL is 2 h
  l = await call('GET', '/v1/reports?lat=39.75&lon=-84.22');
  assert.strictEqual(l.body.reports.length, 0);
  app.close();
});

test('radius filter excludes far reports', async () => {
  const { app, call } = await boot();
  await call('POST', '/v1/reports', { type: 'HAZARD', lat: 39.75, lon: -84.22, deviceId: DEV(1) });
  const far = await call('GET', '/v1/reports?lat=40.75&lon=-84.22&radius=5000');
  assert.strictEqual(far.body.reports.length, 0);
  app.close();
});

test('nearby same-type report from another device is a confirmation, not a duplicate', async () => {
  const { app, call } = await boot();
  const a = await call('POST', '/v1/reports', { type: 'POLICE', lat: 39.75, lon: -84.22, deviceId: DEV(1) });
  const b = await call('POST', '/v1/reports', { type: 'POLICE', lat: 39.7505, lon: -84.22, deviceId: DEV(2) });
  assert.strictEqual(b.status, 200);
  assert.strictEqual(b.body.merged, true);
  assert.strictEqual(b.body.id, a.body.id);
  const l = await call('GET', '/v1/reports?lat=39.75&lon=-84.22');
  assert.strictEqual(l.body.reports.length, 1);
  assert.strictEqual(l.body.reports[0].score, 2);
  app.close();
});

test('votes: "still there" extends life, enough "gone" votes hide it', async () => {
  const { app, clock, call } = await boot();
  const a = await call('POST', '/v1/reports', { type: 'POLICE', lat: 39.75, lon: -84.22, deviceId: DEV(1) });
  clock.advance(90 * 60 * 1000);
  await call('POST', `/v1/reports/${a.body.id}/vote`, { deviceId: DEV(2), vote: 1 });
  clock.advance(90 * 60 * 1000);                          // 3 h after creation, but refreshed 90 min ago
  let l = await call('GET', '/v1/reports?lat=39.75&lon=-84.22');
  assert.strictEqual(l.body.reports.length, 1);
  await call('POST', `/v1/reports/${a.body.id}/vote`, { deviceId: DEV(3), vote: -1 });
  await call('POST', `/v1/reports/${a.body.id}/vote`, { deviceId: DEV(4), vote: -1 });
  await call('POST', `/v1/reports/${a.body.id}/vote`, { deviceId: DEV(5), vote: -1 });
  l = await call('GET', '/v1/reports?lat=39.75&lon=-84.22');
  assert.strictEqual(l.body.reports.length, 1, 'score 2-3 = -1 is still visible');
  await call('POST', `/v1/reports/${a.body.id}/vote`, { deviceId: DEV(6), vote: -1 });
  l = await call('GET', '/v1/reports?lat=39.75&lon=-84.22');
  assert.strictEqual(l.body.reports.length, 0, 'score -2 hides it');
  app.close();
});

test('one device cannot vote twice to move the score', async () => {
  const { app, call } = await boot();
  const a = await call('POST', '/v1/reports', { type: 'POLICE', lat: 39.75, lon: -84.22, deviceId: DEV(1) });
  for (let i = 0; i < 5; i++) await call('POST', `/v1/reports/${a.body.id}/vote`, { deviceId: DEV(2), vote: -1 });
  const l = await call('GET', '/v1/reports?lat=39.75&lon=-84.22');
  assert.strictEqual(l.body.reports[0].score, 0);         // reporter +1, one down-vote -1
  app.close();
});

test('rate limiting and validation', async () => {
  const { app, clock, call } = await boot();
  assert.strictEqual((await call('POST', '/v1/reports', { type: 'POLICE', lat: 39.75, lon: -84.22, deviceId: DEV(1) })).status, 201);
  assert.strictEqual((await call('POST', '/v1/reports', { type: 'HAZARD', lat: 41, lon: -84, deviceId: DEV(1) })).status, 429);
  clock.advance(20 * 1000);
  assert.strictEqual((await call('POST', '/v1/reports', { type: 'HAZARD', lat: 41, lon: -84, deviceId: DEV(1) })).status, 201);
  assert.strictEqual((await call('POST', '/v1/reports', { type: 'LASER', lat: 1, lon: 1, deviceId: DEV(2) })).status, 400);
  assert.strictEqual((await call('POST', '/v1/reports', { type: 'POLICE', lat: 999, lon: 1, deviceId: DEV(2) })).status, 400);
  assert.strictEqual((await call('POST', '/v1/reports', { type: 'POLICE', lat: 1, lon: 1, deviceId: 'x' })).status, 400);
  assert.strictEqual((await call('GET', '/v1/reports?lat=abc&lon=1')).status, 400);
  assert.strictEqual((await call('POST', '/v1/reports/aaaaaaaaaaaa/vote', { deviceId: DEV(2), vote: 1 })).status, 404);
  app.close();
});

test('risk score rises with similar-time history and is low with none', async () => {
  const { app, clock, call } = await boot();
  let r = await call('GET', '/v1/risk?lat=39.75&lon=-84.22');
  assert.strictEqual(r.body.score, 0);
  assert.strictEqual(r.body.level, 'low');
  // 6 reports on earlier weekdays around 14:00, each from a different device
  for (let d = 1; d <= 6; d++) {
    clock.advance(-24 * 3600 * 1000 * 0);                 // keep the clock; backdate via direct store edit below
    await call('POST', '/v1/reports', { type: 'POLICE', lat: 39.75 + d * 0.0005, lon: -84.22, deviceId: DEV(10 + d) });
  }
  app.db().reports.forEach((x, i) => { x.ts -= (i + 1) * 24 * 3600 * 1000; x.lastSeen = x.ts; });
  r = await call('GET', '/v1/risk?lat=39.75&lon=-84.22&radius=3000');
  assert.ok(r.body.score >= 25, 'expected at least moderate, got ' + JSON.stringify(r.body));
  // a single old report must not look like a hotspot
  app.db().reports.splice(1);
  r = await call('GET', '/v1/risk?lat=39.75&lon=-84.22&radius=3000');
  assert.strictEqual(r.body.level, 'low', JSON.stringify(r.body));
  const far = await call('GET', '/v1/risk?lat=10&lon=10');
  assert.strictEqual(far.body.score, 0);
  app.close();
});

test('data survives a restart', async () => {
  const t = Date.UTC(2026, 10, 25, 14, 0, 0);
  const file = path.join(os.tmpdir(), 'wa-restart-' + Math.random().toString(36).slice(2) + '.json');
  const a1 = createApp({ now: () => t, dataFile: file });
  await new Promise((r) => a1.server.listen(0, '127.0.0.1', r));
  const base1 = 'http://127.0.0.1:' + a1.server.address().port;
  await fetch(base1 + '/v1/reports', { method: 'POST', body: JSON.stringify({ type: 'POLICE', lat: 39.75, lon: -84.22, deviceId: DEV(1) }) });
  a1.close();                                              // flushes to disk

  const a2 = createApp({ now: () => t + 60000, dataFile: file });
  await new Promise((r) => a2.server.listen(0, '127.0.0.1', r));
  const res = await fetch('http://127.0.0.1:' + a2.server.address().port + '/v1/reports?lat=39.75&lon=-84.22');
  assert.strictEqual((await res.json()).reports.length, 1);
  a2.close();
});
