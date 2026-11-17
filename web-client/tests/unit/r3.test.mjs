import test from 'node:test';
import assert from 'node:assert/strict';
import { ReadState, ruleDecimal, validateRules, rulesDraft, rulesPatch, summaryMatches, workloadExpiryDelay } from '../../src/model.js';
import { Api } from '../../src/api.js';
const rules = { configured: true, settingsVersion: '9223372036854775807', effortScaleName: 'Relative effort', effortScaleExamples: [{ points: '1.00', label: 'Small' }], overloadThreshold: '20.00', nearDueDays: 3 };
test('R3-unit-01 exact decimal feedback preserves approved bounds without binary-float arithmetic', () => {
  for (const [input, output] of [['00020', '20.00'], ['.01', null], ['0.01', '0.01'], ['99999999.99', '99999999.99'], ['100000000', null], ['1e2', null], ['1.001', null], ['0', null]]) assert.equal(ruleDecimal(input), output);
  assert.deepEqual(validateRules(rulesDraft(rules)).errors, {});
  for (const days of ['-1', '31', '1.5', '']) assert.ok(validateRules({ ...rulesDraft(rules), nearDueDays: days }).errors.nearDueDays);
});
test('R3-unit-02 sparse normalization no-op and initialization retain string versions and ordered examples', () => {
  const draft = rulesDraft(rules); draft.overloadThreshold = '020'; draft.effortScaleName = ' Relative effort '; draft.effortScaleExamples[0].label = ' Small ';
  assert.deepEqual(rulesPatch(rules, draft), { settingsVersion: rules.settingsVersion });
  assert.deepEqual(rulesPatch({ configured: false, settingsVersion: '0' }, draft), { settingsVersion: '0', effortScaleName: 'Relative effort', effortScaleExamples: [{ points: '1.00', label: 'Small' }], overloadThreshold: '20.00', nearDueDays: 3 });
  draft.effortScaleExamples.push({ points: '01', label: 'duplicate' }); assert.equal(rulesPatch(rules, draft), null);
  assert.equal(rules.effortScaleExamples.length, 1); draft.effortScaleName = '\u00a0Relative effort\u00a0'; assert.notEqual(validateRules(draft).values.effortScaleName, rules.effortScaleName);
});
test('R3-unit-03 independent read generations cancel older results and retain failed data as stale', () => {
  const workload = new ReadState(), contribution = new ReadState(); const old = workload.begin(); const signal = workload.controller.signal;
  const fresh = workload.begin(); assert.ok(signal.aborted); assert.equal(workload.resolve(old, { wrong: true }), false);
  const separate = contribution.begin(); assert.ok(contribution.resolve(separate, { total: '1.00' }));
  workload.resolve(fresh, { remaining: '20.01' }); const failure = workload.begin(); workload.fail(failure, { code: 'SUMMARY_UNAVAILABLE' });
  assert.equal(workload.data.remaining, '20.01'); assert.equal(workload.stale, true); assert.equal(contribution.stale, false);
  const delayed = workload.begin(); workload.invalidate('mutation'); assert.equal(workload.resolve(delayed, { remaining: '0' }), false);
});
test('R3-unit-04 project tokens and settings versions compare observations without mixing snapshots', () => {
  const board = { project: { id: 'project' }, snapshot: { stateToken: 'one' } };
  const data = { projectId: 'project', snapshot: { stateToken: 'one' }, rules: { settingsVersion: '9223372036854775807' } };
  assert.ok(summaryMatches(data, board, rules)); assert.ok(!summaryMatches(data, { ...board, snapshot: { stateToken: 'two' } }, rules));
  assert.ok(!summaryMatches(data, board, { ...rules, settingsVersion: '1' })); assert.ok(!summaryMatches(data, { project: { id: 'other' } }, rules));
  assert.ok(summaryMatches({ ...data, rules: null }, board, { settingsVersion: '0' }));
});
test('R3-unit-05 Manila expiry scheduling derives only from server date/instant and request elapsed time', () => {
  const data = { projectToday: '2026-10-13', snapshot: { snapshotAtUtc: '2026-10-13T15:59:59.900000Z' } };
  const original = Date.now; Date.now = () => 0;
  try { assert.equal(workloadExpiryDelay(data, 20), 80); Date.now = () => 9999999999999; assert.equal(workloadExpiryDelay(data, 20), 80); assert.equal(workloadExpiryDelay(data, 200), 0); }
  finally { Date.now = original; }
});
test('R3-unit-06 cancelled protected reads stop without retry and summary 503 retries stay bounded', async () => {
  const controller = new AbortController(); let calls = 0;
  const api = new Api({ fetcher: async () => { calls++; controller.abort(); throw new Error('aborted'); }, wait: async () => {} });
  await assert.rejects(api.request('/api/v1/projects/p/workload-summary', { signal: controller.signal }), e => e.code === 'OBSOLETE'); assert.equal(calls, 1);
  calls = 0; const retry = new Api({ fetcher: async () => { calls++; return new Response(JSON.stringify({ code: 'SUMMARY_UNAVAILABLE', message: 'Unavailable' }), { status: 503 }); }, wait: async () => {}, jitter: () => 0 });
  await assert.rejects(retry.request('/api/v1/projects/p/workload-summary'), e => e.code === 'SUMMARY_UNAVAILABLE'); assert.equal(calls, 2);
});
