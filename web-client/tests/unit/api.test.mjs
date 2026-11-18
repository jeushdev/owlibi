import test from 'node:test';
import assert from 'node:assert/strict';
import { Api } from '../../src/api.js';
const csrf = { token: 'synthetic-memory-only', headerName: 'X-CSRF-TOKEN' };
const reply = (status, body, headers = {}) => new Response(JSON.stringify(body), { status, headers });
test('bounded GET retry uses one delay and same-origin no-store cookies; writes never replay', async () => {
  let calls = 0; const delays = [];
  const api = new Api({ fetcher: async (path, options) => { assert.equal(options.credentials, 'same-origin'); assert.equal(options.cache, 'no-store'); calls++; return calls % 2 ? reply(503, { code: 'SERVICE_UNAVAILABLE' }) : reply(200, { ok: true }); }, wait: async ms => delays.push(ms), jitter: () => 7 });
  assert.deepEqual(await api.request('/api/v1/me/projects'), { ok: true }); assert.deepEqual(delays, [1007]);
  calls = 0; await assert.rejects(api.request('/api/v1/projects', { method: 'POST', body: {}, csrf, epoch: 'epoch' }), e => e.unknown); assert.equal(calls, 1);
});
test('session/CSRF headers, older replies and epoch switching are rejected distinctly', async () => {
  let release, valid = true;
  const api = new Api({ fetcher: async (path, options) => { assert.equal(options.headers['X-CSRF-TOKEN'], csrf.token); assert.equal(options.headers['X-Session-Epoch'], 'old'); await new Promise(r => { release = r; }); return reply(200, {}, { 'X-Session-Epoch': 'new' }); } });
  const promise = api.request('/api/v1/projects', { method: 'POST', csrf, epoch: 'old', valid: () => valid });
  valid = false; release(); await assert.rejects(promise, e => e.code === 'OBSOLETE');
  const changed = new Api({ fetcher: async () => reply(200, {}, { 'X-Session-Epoch': 'new' }) });
  await assert.rejects(changed.request('/api/v1/me/projects', { epoch: 'old' }), e => e.code === 'SESSION_CHANGED');
});
test('rate limits suppress requests until Retry-After and never retry permission/conflicts/activity', async () => {
  let calls = 0, now = 0;
  const api = new Api({ now: () => now, fetcher: async () => { calls++; return reply(429, { code: 'RATE_LIMITED', message: 'Wait' }, { 'Retry-After': '3' }); } });
  await assert.rejects(api.request('/api/v1/projects', { method: 'POST', csrf }), e => e.retryAfter === 3);
  await assert.rejects(api.request('/api/v1/projects', { method: 'POST', csrf })); assert.equal(calls, 1);
  now = 3000; await assert.rejects(api.request('/api/v1/projects', { method: 'POST', csrf })); assert.equal(calls, 2);
  for (const code of ['FORBIDDEN', 'CSRF_INVALID', 'STALE_VERSION', 'STALE_AGREEMENT']) {
    let count = 0; const client = new Api({ fetcher: async () => { count++; return reply(409, { code, fieldErrors: {} }); } });
    await assert.rejects(client.request('/api/v1/projects', { method: 'POST', csrf }), e => e.code === code); assert.equal(count, 1);
  }
  let count = 0; const activity = new Api({ fetcher: async () => { count++; throw new Error('offline'); } });
  await assert.rejects(activity.request('/api/v1/auth/sessions/current/activity', { method: 'POST', body: {}, csrf, activity: true }), e => !e.unknown); assert.equal(count, 1);
});
