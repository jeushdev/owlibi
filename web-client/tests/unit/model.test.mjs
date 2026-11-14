import test from 'node:test';
import assert from 'node:assert/strict';
import { visibleTasks, pendingTasks, actionsFor, Generations, boardNeighbor, validate, defaultFilters, editDraft, agreementChanged, editDirty } from '../../src/model.js';
import { escapeText } from '../../src/ui.js';
const task = (id, status = 'TODO', accepted = false) => ({ id, status, title: `Task ${id}`, description: '多行\nUnicode', assigneeIds: ['one', 'two'], currentUserAcceptedAgreement: accepted, assigneeAcceptances: [{ userId: 'one', displayName: 'Same name', accepted }, { userId: 'two', displayName: 'Same name', accepted }] });
test('shared filters, terminal pending exclusion and stable selection use IDs', () => {
  const tasks = [task('a'), task('b', 'DONE'), task('c', 'CANCELLED')];
  assert.deepEqual(visibleTasks(tasks, defaultFilters(), 'one').map(t => t.id), ['a', 'b']);
  assert.deepEqual(pendingTasks(tasks, 'one').map(t => t.id), ['a']);
  assert.deepEqual(visibleTasks(tasks, { assignment: 'mine', status: 'CANCELLED', search: 'same NAME' }, 'one').map(t => t.id), ['c']);
  assert.equal(visibleTasks(tasks, { ...defaultFilters(), search: '多行' }, 'third').length, 2);
});
test('permission/state/all-current acceptance matrix never enables zero or partial assignees', () => {
  assert.equal(actionsFor(task('a'), 'MEMBER', 'third').length, 0);
  assert.equal(actionsFor(task('a'), 'MEMBER', 'one')[0].enabled, false);
  const partial = task('a', 'REVIEW', true); partial.assigneeAcceptances[1].accepted = false;
  assert.equal(actionsFor(partial, 'LEADER', 'one').find(a => a.action === 'APPROVE').enabled, false);
  for (const status of ['TODO', 'IN_PROGRESS', 'REVIEW', 'DONE']) {
    const t = task('a', status, true);
    assert.ok(actionsFor(t, 'LEADER', 'one').every(a => a.enabled));
    assert.ok(!actionsFor(t, 'MEMBER', 'one').some(a => ['APPROVE', 'RETURN', 'CANCEL', 'REOPEN'].includes(a.action)));
    t.assigneeIds = [];
    assert.ok(actionsFor(t, 'LEADER', 'one').filter(a => !['RETURN', 'CANCEL'].includes(a.action)).every(a => !a.enabled));
  }
  assert.equal(actionsFor(task('a', 'CANCELLED'), 'LEADER', 'one').length, 0);
});
test('older same-view reads and session/project/view replies cannot survive a mutation', () => {
  const g = new Generations(); const old = g.ticket(); const current = g.ticket();
  assert.ok(!g.current(old)); assert.ok(g.current(current));
  g.change('read'); assert.ok(!g.current(current));
  for (const kind of ['session', 'project', 'view']) { const ticket = g.ticket(); g.change(kind); assert.ok(!g.current(ticket)); }
});
test('Board arrows skip empty columns and clamp rows without selecting by name', () => {
  const cols = [['a', 'b'], [], ['c'], ['d', 'e']];
  assert.equal(boardNeighbor(cols, 'b', 'ArrowRight'), 'c');
  assert.equal(boardNeighbor(cols, 'c', 'ArrowLeft'), 'a');
  assert.equal(boardNeighbor(cols, 'd', 'End'), 'e');
  assert.equal(boardNeighbor(cols, 'a', 'ArrowUp'), 'a');
});
test('Unicode/decimal/date validation supplements service and DOM values are escaped', () => {
  const fields = { title: '🦉'.repeat(200), description: '', category: '', dueDate: null, effortPoints: '99999999.99', assigneeIds: ['one'] };
  assert.deepEqual(validate(fields, 'task'), {});
  assert.ok(validate({ ...fields, title: fields.title + 'a', dueDate: '2026-02-30', effortPoints: '1.001', assigneeIds: [] }, 'task').title);
  assert.ok(validate({ password: '🦉'.repeat(19), displayName: 'x', email: 'x' }, 'register').password);
  assert.deepEqual(validate({ password: ' '.repeat(12), displayName: 'x', email: 'x' }, 'register'), {});
  assert.deepEqual(validate({ password: ' '.repeat(12), email: 'x' }, 'login'), {});
  assert.equal(escapeText('<script>"&\''), '&lt;script&gt;&quot;&amp;&#39;');
});

test('edit drafts copy assignments and distinguish terms from metadata and unchanged saves', () => {
  const base = { title: 'Task', description: '', category: '', dueDate: null, effortPoints: '12.30', assigneeIds: ['one', 'two'] };
  const draft = editDraft(base); draft.assigneeIds.reverse(); draft.title = ' Task '; draft.effortPoints = '12.3';
  assert.equal(editDirty(base, draft), false); draft.category = 'metadata'; assert.equal(editDirty(base, draft), true); assert.equal(agreementChanged(base, draft), false);
  draft.description = ' '; assert.equal(agreementChanged(base, draft), true); assert.deepEqual(base.assigneeIds, ['one', 'two']);
});
test('agreement comparison preserves Unicode and nonbreaking spaces and treats reassignment as terms', () => {
  const base = { title: 'Task', description: 'é', category: '', dueDate: null, effortPoints: '1.00', assigneeIds: ['one'] };
  assert.equal(agreementChanged(base, { ...editDraft(base), title: '\u00a0Task\u00a0' }), true);
  assert.equal(agreementChanged(base, { ...editDraft(base), description: 'e\u0301' }), true);
  assert.equal(agreementChanged(base, { ...editDraft(base), assigneeIds: ['two'] }), true);
});
