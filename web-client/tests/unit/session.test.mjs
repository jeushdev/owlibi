import test from 'node:test';
import assert from 'node:assert/strict';
import { Activity } from '../../src/session.js';
const input = { isTrusted: true, type: 'keydown' };
test('first trusted visible input immediate; ten-second coalescing and trailing only with pending input', async () => {
  let now = 0, timer = null, calls = 0;
  const activity = new Activity({ send: async () => calls++, allowed: () => true, now: () => now, later: (fn, delay) => { timer = { fn, delay }; return 1; }, cancel: () => { timer = null; } });
  for (const event of [{ ...input, isTrusted: false }, { isTrusted: true, type: 'focus' }, { isTrusted: true, type: 'mousemove' }]) activity.input(event, true);
  activity.input(input, false); assert.equal(calls, 0);
  activity.input(input, true); await Promise.resolve(); assert.equal(calls, 1);
  now = 1000; activity.input(input, true); activity.input(input, true); assert.equal(timer.delay, 9000);
  now = 10000; timer.fn(); await Promise.resolve(); assert.equal(calls, 2); assert.equal(activity.timer, null);
  activity.pause(); now = 20000; assert.equal(calls, 2);
});
test('failed pending reports abandoned; reset isolates late reports and hidden input', async () => {
  let calls = 0, release;
  const activity = new Activity({ send: () => { calls++; return new Promise((resolve, reject) => { release = () => reject(new Error('offline')); }); }, allowed: () => true });
  activity.input(input, true); activity.input(input, true); release(); await new Promise(r => setImmediate(r));
  assert.equal(activity.pending, false); assert.equal(calls, 1);
  activity.reset(); activity.input(input, true); assert.equal(calls, 2); activity.reset(); release(); await new Promise(r => setImmediate(r)); assert.equal(activity.running, false);
});
