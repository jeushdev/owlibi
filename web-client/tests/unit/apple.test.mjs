import test from 'node:test';
import assert from 'node:assert/strict';
import { markup } from '../../src/ui.js';
const state = role => ({ user: { id: 'one', displayName: '<Alex & sample>' }, board: { project: { id: 'project-one', name: 'Project <script> & review', role }, tasks: [], members: [{ id: 'one', displayName: '<Alex & sample>', role }] }, page: 'members', form: null, theme: 'light', resources: {}, notice: 'Confirmed', confirmedAt: null, projects: [], draft: {}, errors: {} });
test('Apple workspace chrome uses escaped server context and preserves permission controls', () => {
  const leader = markup(state('LEADER')), member = markup(state('MEMBER'));
  for (const html of [leader, member]) { assert.ok(html.includes('class="app-header"')); assert.ok(html.includes('Project &lt;script&gt; &amp; review')); assert.ok(!html.includes('<script>')); assert.ok(html.includes('&lt;Alex &amp; sample&gt;')); assert.ok(html.includes('aria-current="page"')); assert.ok(html.includes('aria-hidden="true"')); assert.ok(!html.includes('Campus exhibit')); }
  assert.ok(leader.includes('id="create-invite"')); assert.ok(!member.includes('id="create-invite"'));
  assert.ok(leader.includes('Leader workspace')); assert.ok(member.includes('Member workspace'));
});
test('signed-out Apple chrome never renders retained private project or account context', () => {
  const s = { ...state('LEADER'), user: null, form: 'login' };const html = markup(s);
  assert.ok(html.includes('id="active-form"'));assert.ok(html.includes('id="field-password"'));
  assert.ok(!html.includes('Project &lt;script&gt;'));assert.ok(!html.includes('&lt;Alex &amp; sample&gt;'));
  assert.ok(!html.includes('id="nav-tasks"'));assert.ok(!html.includes('Leader workspace'));
});
