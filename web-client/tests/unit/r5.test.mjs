import test from 'node:test';
import assert from 'node:assert/strict';
import { Api, ApiError, ReportDownload, REPORT_BYTES, REPORT_TIMEOUT, REPORT_MIME } from '../../src/api.js';
import { markup } from '../../src/ui.js';
import { ReadState } from '../../src/model.js';
const project = '11111111-1111-4111-8111-111111111111', epoch = '22222222-2222-4222-8222-222222222222';
const snapshot = '2026-10-13T04:00:00.123456Z', filename = `owlibi-${project}-20261013T040000Z.xlsx`;
const headers = extra => ({ 'Content-Type': REPORT_MIME, 'Content-Disposition': `attachment; filename="${filename}"`, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'X-Session-Epoch': epoch, 'X-Owlibi-Snapshot-At': snapshot, 'X-Owlibi-State-Token': 'a'.repeat(64), ...extra });
const zip = Uint8Array.of(0x50, 0x4b, 0x03, 0x04, 1, 2, 3, 4);
const reply = (bytes = zip, extra = {}) => new Response(bytes, { headers: headers(extra) });
const errorReply = (code, status, extra = {}) => new Response(JSON.stringify({ code, message: 'Safe server message', fieldErrors: {} }), { status, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'no-store', ...extra } });
const code = expected => error => error.code === expected;
function scheduler() {
  let time = 0, next = 0; const timers = new Map();
  return { now: () => time, later: (fn, ms) => { timers.set(++next, { fn, at: time + ms }); return next; }, cancelTimer: id => timers.delete(id), advance: ms => { time += ms; for (const [id, timer] of timers) if (timer.at <= time) { timers.delete(id); timer.fn(); } }, get pending() { return timers.size; } };
}
function adapter(parts, extra = {}) {
  let at = 0; const stats = { cancelled: 0, released: 0, reads: 0 };
  const reader = { read: async () => { stats.reads++; const part = parts[at++]; if (part instanceof Error) throw part; return part === undefined ? { done: true } : { done: false, value: part }; }, cancel: async () => { stats.cancelled++; }, releaseLock: () => { stats.released++; } };
  return { response: { ok: true, status: 200, headers: new Headers(headers(extra)), body: { getReader: () => reader } }, stats, reader };
}
test('R5-unit-01 exact same-origin export request and split-signature complete bounded receipt', async () => {
  const mock = adapter([zip.subarray(0, 1), zip.subarray(1, 3), zip.subarray(3)], { 'Content-Length': '8' }); let request;
  const api = new Api({ fetcher: async (...args) => { request = args; return mock.response; } });
  const result = await api.professorReport(project, { epoch }); assert.equal(result.filename, filename); assert.equal(result.bytes, 8); assert.equal(result.snapshotAtUtc, snapshot); assert.equal(result.blob.type, REPORT_MIME); assert.deepEqual(new Uint8Array(await result.blob.arrayBuffer()), zip);
  assert.equal(request[0], `/api/v1/projects/${project}/professor-report`); assert.equal(request[1].method, 'GET'); assert.equal(request[1].credentials, 'same-origin'); assert.equal(request[1].cache, 'no-store'); assert.deepEqual(request[1].headers, { Accept: `${REPORT_MIME}, application/json` }); assert.ok(!('body' in request[1])); assert.ok(request[1].signal.aborted); assert.deepEqual(mock.stats, { cancelled: 1, released: 1, reads: 4 });
});
test('R5-unit-02 reject unsafe malformed missing success headers before body materialization', async () => {
  const variants = [ { 'Content-Type': 'application/json' }, { 'Content-Disposition': 'attachment; filename="../../secret.xlsx"' }, { 'Content-Disposition': `attachment; filename="${filename}"; filename*=evil` }, { 'X-Owlibi-Snapshot-At': '2026-02-31T04:00:00Z' }, { 'X-Owlibi-Snapshot-At': 'local date' }, { 'X-Owlibi-State-Token': 'invalid' }, { 'X-Session-Epoch': '' }, { 'Cache-Control': 'public' }, { 'X-Content-Type-Options': '' }, { 'Content-Length': '-1' }, { 'Content-Length': '1e2' }, { 'Content-Length': '0' }, { 'Content-Length': '16777217' } ];
  for (const value of variants) { const mock = adapter([zip], value); await assert.rejects(new Api({ fetcher: async () => mock.response }).professorReport(project, { epoch }), code('REPORT_INVALID')); assert.equal(mock.stats.reads, 0); }
  await assert.rejects(new Api({ fetcher: async () => reply(zip, { 'X-Session-Epoch': project }) }).professorReport(project, { epoch }), code('SESSION_CHANGED'));
});
test('R5-unit-03 empty corrupt interrupted and exact-length mismatch never yield a blob', async () => {
  for (const bytes of [new Uint8Array(), Uint8Array.of(0x50, 0x4b, 0x03), new TextEncoder().encode('PK00broken'), new TextEncoder().encode('{"error":true}')]) await assert.rejects(new Api({ fetcher: async () => reply(bytes) }).professorReport(project, { epoch }), code('REPORT_INVALID'));
  for (const length of ['7', '9']) await assert.rejects(new Api({ fetcher: async () => reply(zip, { 'Content-Length': length }) }).professorReport(project, { epoch }), code('REPORT_INVALID'));
  const mock = adapter([zip, new Error('interrupted')]); await assert.rejects(new Api({ fetcher: async () => mock.response }).professorReport(project, { epoch }), code('REPORT_NETWORK')); assert.equal(mock.stats.cancelled, 1); assert.equal(mock.stats.released, 1);
});
test('R5-unit-04 exact sixteen MiB and limit plus one enforced without declared length', async () => {
  const bytes = new Uint8Array(REPORT_BYTES); bytes.set(zip); const exact = await new Api({ fetcher: async () => reply(bytes) }).professorReport(project, { epoch }); assert.equal(exact.blob.size, REPORT_BYTES);
  const mock = adapter([bytes, Uint8Array.of(1)]); await assert.rejects(new Api({ fetcher: async () => mock.response }).professorReport(project, { epoch }), code('REPORT_INVALID')); assert.equal(mock.stats.cancelled, 1);
  await assert.rejects(new Api({ fetcher: async () => reply(new Uint8Array(REPORT_BYTES + 1)) }).professorReport(project, { epoch }), code('REPORT_INVALID'));
});
test('R5-unit-05 forty-five-second header deadline races an adapter ignoring abort without retry', async () => {
  const clock = scheduler(); let calls = 0, release;
  const api = new Api({ ...clock, reportNow: clock.now, fetcher: () => { calls++; return new Promise(resolve => { release = resolve; }); } });
  const pending = api.professorReport(project, { epoch }); clock.advance(REPORT_TIMEOUT - 1); assert.equal(clock.pending, 1); clock.advance(1); await assert.rejects(pending, code('REPORT_CLIENT_TIMEOUT')); assert.equal(calls, 1); assert.equal(clock.pending, 0); let cancelled=0;release({body:{cancel:async()=>cancelled++}});await new Promise(setImmediate);assert.equal(cancelled,1);
});
test('R5-unit-06 header time and delayed-body reads share one deadline with reader cleanup', async () => {
  const clock = scheduler(), mock = adapter([]); let resolveRead;
  mock.reader.read = () => new Promise(resolve => { resolveRead = resolve; });
  const api = new Api({ ...clock, reportNow: clock.now, fetcher: async () => { clock.advance(30000); return mock.response; } });
  const pending = api.professorReport(project, { epoch }); await new Promise(setImmediate); clock.advance(14999); assert.equal(clock.pending, 1); clock.advance(1); await assert.rejects(pending, code('REPORT_CLIENT_TIMEOUT')); assert.equal(mock.stats.cancelled, 1); assert.equal(mock.stats.released, 1); assert.equal(clock.pending, 0); resolveRead({ done: true });
});
test('R5-unit-07 safe errors busy Retry-After and deliberate fresh retry never replay automatically', async () => {
  let calls = 0, now = 0; const api = new Api({ now: () => now, fetcher: async () => { calls++; return calls === 1 ? errorReply('REPORT_BUSY', 429, { 'Retry-After': '5' }) : reply(); } });
  await assert.rejects(api.professorReport(project, { epoch }), e => e.code === 'REPORT_BUSY' && e.retryAfter === 5); await assert.rejects(api.professorReport(project, { epoch }), code('REPORT_BUSY')); assert.equal(calls, 1); now = 5000; await api.professorReport(project, { epoch }); assert.equal(calls, 2);
  for (const [name, status] of [['SETTINGS_REQUIRED',409],['REPORT_TOO_LARGE',422],['REPORT_TIMEOUT',503],['REPORT_UNAVAILABLE',503],['UNAUTHENTICATED',401],['FORBIDDEN',403],['NOT_FOUND',404],['SESSION_CHANGED',409]]) { let sent = 0; await assert.rejects(new Api({ fetcher: async () => { sent++; return errorReply(name,status); } }).professorReport(project, { epoch }), code(name)); assert.equal(sent, 1); }
  await assert.rejects(new Api({ fetcher: async () => new Response('not json', { status: 503, headers: { 'Content-Type':'application/json' } }) }).professorReport(project, { epoch }), code('REPORT_INVALID'));
});
test('R5-unit-08 cancellation invalid context and obsolete body completion cannot yield downloads', async () => {
  let valid = true; const mock = adapter([zip]); const read = mock.reader.read; mock.reader.read = async () => { valid = false; return read(); };
  await assert.rejects(new Api({ fetcher: async () => mock.response }).professorReport(project, { epoch, valid: () => valid }), code('OBSOLETE')); assert.equal(mock.stats.released, 1);
  const external = new AbortController(); external.abort(new ApiError('REPORT_CANCELLED', 'Cancelled')); let calls = 0;
  await assert.rejects(new Api({ fetcher: async () => { calls++; return reply(); } }).professorReport(project, { epoch, signal: external.signal }), code('REPORT_CANCELLED')); assert.equal(calls, 0);
});
test('R5-unit-09 handoff leases revoke URLs remove links and reject obsolete context or click failure', () => {
  const clock = scheduler(); let created = 0, revoked = 0, appended = 0, clicked = 0, removed = 0, fail = false;
  const link = { remove: () => removed++, click: () => { clicked++; if (fail) throw new Error('handoff failed'); } };
  const download = new ReportDownload({ ...clock, document: { body: { append: () => appended++ }, createElement: () => link }, urls: { createObjectURL: () => `blob:${++created}`, revokeObjectURL: () => revoked++ } });
  const report = { blob: new Blob([zip]), filename }; assert.equal(download.offer(report, () => false), false); assert.equal(created, 0);
  assert.ok(download.offer(report, () => true)); assert.equal(link.download, filename); assert.equal(appended, 1); assert.equal(clicked, 1); assert.equal(download.leases.size, 1); assert.equal(revoked, 0); clock.advance(1000); assert.equal(revoked, 1); assert.equal(download.leases.size, 0); assert.ok(removed);
  download.offer(report, () => true); download.clear(); assert.equal(revoked, 2); assert.equal(clock.pending, 0); fail = true; assert.throws(() => download.offer(report, () => true)); assert.equal(revoked, 3); assert.equal(download.leases.size, 0);
});
test('R5-unit-10 leader empty and unconfigured summaries still expose action members do not', () => {
  const state = { user: { id: epoch }, page: 'completed', projectId: project, board: { project: { id: project, name: 'Synthetic', role: 'LEADER' }, members: [] }, resources: Object.fromEntries(['contribution','workload','settings','catalogue'].map(k => [k,new ReadState()])), notice: '', filters: {}, form: null, errors: {}, draft: {}, rateUntil: 0, exportState: { running:false,message:'' } };
  assert.match(markup(state), /id="export-report"\s+(?!disabled)/); assert.match(markup(state), /Ready to export/);
  state.board.project.role = 'MEMBER'; assert.ok(!markup(state).includes('id="export-report"')); state.board.project.role = 'LEADER'; state.exportState.running = true; state.exportState.remaining = 45; assert.match(markup(state), /id="export-report" disabled/); assert.match(markup(state), /id="cancel-report"/);
});
test('R5-unit-11 ordinary GET request retains its ten-second budget and JSON behavior', async () => {
  const original = AbortSignal.timeout; const times = []; AbortSignal.timeout = ms => { times.push(ms); return new AbortController().signal; };
  try { const api = new Api({ now: () => 100, fetcher: async () => new Response('{"value":1}') }); assert.deepEqual(await api.request('/api/v1/me/projects', { retry:false }), { value:1 }); assert.deepEqual(times, [10000]); }
  finally { AbortSignal.timeout = original; }
});
