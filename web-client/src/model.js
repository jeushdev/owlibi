// Presentation policy only. Java rechecks every permission, version and state.
export const statuses = ['TODO', 'IN_PROGRESS', 'REVIEW', 'DONE'];
export const labels = { TODO: 'To do', IN_PROGRESS: 'In progress', REVIEW: 'Review', DONE: 'Done', CANCELLED: 'Cancelled' };
export const actionLabels = { START: 'Start task', SUBMIT_REVIEW: 'Request leader review', APPROVE: 'Approve task', RETURN: 'Return for changes', CANCEL: 'Cancel task', REOPEN: 'Reopen task' };
export const reasonActions = new Set(['RETURN', 'CANCEL', 'REOPEN']);
export const defaultFilters = () => ({ assignment: 'all', status: 'active', search: '' });
export function pendingTasks(tasks, userId) {
  return tasks.filter(t => !['DONE', 'CANCELLED'].includes(t.status) && t.assigneeIds.includes(userId) && !t.currentUserAcceptedAgreement);
}
export function visibleTasks(tasks, filters, userId) {
  const search = filters.search.trim().toLocaleLowerCase();
  return tasks.filter(t => (filters.assignment === 'all' || t.assigneeIds.includes(filters.assignment === 'mine' ? userId : filters.assignment)) &&
    (filters.status === 'all' || (filters.status === 'active' ? t.status !== 'CANCELLED' : t.status === filters.status)) &&
    (!search || [t.title, t.description, ...t.assigneeAcceptances.map(a => a.displayName)].join('\n').toLocaleLowerCase().includes(search)));
}
export function actionsFor(task, role, userId) {
  const leader = role === 'LEADER', assigned = task.assigneeIds.includes(userId);
  const allAccepted = task.assigneeIds.length > 0 && task.assigneeIds.every(id => task.assigneeAcceptances.some(a => a.userId === id && a.accepted));
  const actions = [];
  const add = (action, needsAcceptance = true) => actions.push({ action, enabled: !needsAcceptance || allAccepted, explanation: needsAcceptance && !allAccepted ? 'Every current assignee must accept this agreement first.' : '' });
  if (task.status === 'TODO' && (leader || assigned)) add('START');
  if (task.status === 'IN_PROGRESS' && (leader || assigned)) add('SUBMIT_REVIEW');
  if (task.status === 'REVIEW' && leader) { add('APPROVE'); add('RETURN', false); }
  if (task.status === 'DONE' && leader) add('REOPEN');
  if (!['DONE', 'CANCELLED'].includes(task.status) && leader) add('CANCEL', false);
  return actions;
}
// A ticket covers identity, project, view AND read ordering; mutations invalidate all old reads.
export class Generations {
  session = 0; project = 0; view = 0; read = 0;
  ticket() { return [this.session, this.project, this.view, ++this.read]; }
  current(t) { return t.every((value, i) => value === [this.session, this.project, this.view, this.read][i]); }
  change(kind) { this[kind]++; this.read++; }
}
export function boardNeighbor(columns, id, key) {
  const c = columns.findIndex(items => items.includes(id));
  if (c < 0) return columns.flat()[0] ?? null;
  const r = columns[c].indexOf(id);
  if (key === 'Home') return columns[c][0];
  if (key === 'End') return columns[c].at(-1);
  if (key === 'ArrowUp' || key === 'ArrowDown') return columns[c][Math.max(0, Math.min(columns[c].length - 1, r + (key === 'ArrowUp' ? -1 : 1)))];
  const step = key === 'ArrowLeft' ? -1 : key === 'ArrowRight' ? 1 : 0;
  if (!step) return id;
  for (let next = c + step; next >= 0 && next < columns.length; next += step) {
    if (columns[next].length) return columns[next][Math.min(r, columns[next].length - 1)];
  }
  return id;
}
export function validate(fields, kind) {
  const errors = {}, count = text => [...text].length;
  const limits = { title: 200, name: 200, displayName: 100, description: 4000, category: 100, reason: 1000, email: 254 };
  for (const [key, limit] of Object.entries(limits)) {
    if (key in fields && count(['description', 'category'].includes(key) ? fields[key] : fields[key].trim()) > limit) errors[key] = `Use at most ${limit} Unicode code points.`;
  }
  for (const key of ({ register: ['displayName', 'email', 'password'], login: ['email', 'password'], project: ['name'], join: ['token'], task: ['title', 'effortPoints'], reason: ['reason'] }[kind] ?? [])) {
    const value = String(fields[key] ?? '');
    if (!(key === 'password' ? value : value.trim())) errors[key] = 'This field is required.';
  }
  if (kind === 'register') {
    const bytes = new TextEncoder().encode(fields.password).length;
    if (bytes < 12 || bytes > 72) errors.password = 'Use 12–72 UTF-8 bytes; spaces are preserved.';
  }
  if (kind === 'task') {
    if (!/^(?:0|[1-9]\d{0,7})(?:\.\d{1,2})?$/.test(fields.effortPoints) || Number(fields.effortPoints) < .01) errors.effortPoints = 'Use 0.01–99999999.99, at most two decimal places.';
    if (!fields.assigneeIds?.length) errors.assigneeIds = 'Select at least one current member.';
    if (fields.dueDate && (!/^\d{4}-\d{2}-\d{2}$/.test(fields.dueDate) || Number.isNaN(Date.parse(`${fields.dueDate}T00:00:00Z`)) || new Date(`${fields.dueDate}T00:00:00Z`).toISOString().slice(0, 10) !== fields.dueDate)) errors.dueDate = 'Use a calendar-valid YYYY-MM-DD date.';
  }
  return errors;
}

export function editDraft(task) {
  return { title: task.title, description: task.description, category: task.category, dueDate: task.dueDate || '', effortPoints: task.effortPoints, assigneeIds: [...task.assigneeIds], reason: '' };
}
// Mirrors Java String.strip rather than folding nonbreaking spaces or Unicode text.
const strip = text => String(text ?? '').replace(/^[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+|[\u0009-\u000d\u001c-\u0020\u1680\u2000-\u2006\u2008-\u200a\u2028\u2029\u205f\u3000]+$/gu, '');
export function agreementChanged(base, draft) {
  return strip(draft.title || '') !== base.title || (draft.description || '') !== base.description || Number(draft.effortPoints) !== Number(base.effortPoints) ||
    [...(draft.assigneeIds || [])].sort().join(',') !== [...base.assigneeIds].sort().join(',');
}
export function editDirty(base, draft) {
  return agreementChanged(base, draft) || (draft.category || '') !== base.category || (draft.dueDate || null) !== base.dueDate || !!draft.reason;
}

// Settings decimals remain text. This normalization supplies feedback; Java validates every write.
export function ruleDecimal(value) {
  if (!/^[0-9]+(?:\.[0-9]{1,2})?$/.test(value ?? '')) return null;
  const [whole, fraction = ''] = value.split('.');
  const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (cents < 1n || cents > 9999999999n) return null;
  return `${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`;
}
export function rulesDraft(values) {
  return { effortScaleName: values.effortScaleName, effortScaleExamples: values.effortScaleExamples.map(x => ({ ...x })), overloadThreshold: values.overloadThreshold, nearDueDays: String(values.nearDueDays) };
}
const projectEffortPoints = [
  { points: '1.00', label: 'Small Task' },
  { points: '3.00', label: 'Medium Task' },
  { points: '5.00', label: 'Large Task' },
  { points: '8.00', label: 'Very Large Task' }
];
export function projectStartingRules(template) {
  return {
    ...rulesDraft(template.suggestedSettings),
    effortScaleExamples: projectEffortPoints.map(row => ({ ...row }))
  };
}
export function validateRules(draft) {
  const errors = {}, name = strip(draft.effortScaleName), threshold = ruleDecimal(draft.overloadThreshold);
  if (!name || [...name].length > 100) errors.effortScaleName = 'Use a nonblank scale name of at most 100 Unicode code points.';
  if (!threshold) errors.overloadThreshold = 'Use decimal text from 0.01 to 99999999.99, at most two decimal places.';
  if (!/^(?:0|[1-9][0-9]?)$/.test(draft.nearDueDays ?? '') || Number(draft.nearDueDays) > 30) errors.nearDueDays = 'Use a whole number from 0 to 30; zero means today only.';
  const examples = draft.effortScaleExamples ?? [], seen = new Set(), normalized = [], exampleErrors = [];
  if (!examples.length || examples.length > 10) errors.effortScaleExamples = 'Add 1–10 point values.';
  for (const example of examples) {
    const points = ruleDecimal(example.points), label = strip(example.label);
    const pointError = !points ? 'Use a positive number with up to two decimal places.' : seen.has(points) ? 'Choose a different point value.' : '';
    const labelError = !label ? 'Enter a task size.' : [...label].length > 100 ? 'Use at most 100 characters.' : '';
    exampleErrors.push({ points: pointError, label: labelError });
    if (pointError || labelError) errors.effortScaleExamples = 'Use a different point value for each row and enter a task size of up to 100 characters.';
    seen.add(points); normalized.push({ points, label });
  }
  return { errors, exampleErrors, values: { effortScaleName: name, effortScaleExamples: normalized, overloadThreshold: threshold, nearDueDays: Number(draft.nearDueDays) } };
}
export function rulesPatch(base, draft) {
  const { errors, values } = validateRules(draft);
  if (Object.keys(errors).length) return null;
  const patch = { settingsVersion: base.settingsVersion };
  for (const key of ['effortScaleName', 'effortScaleExamples', 'overloadThreshold', 'nearDueDays']) {
    if (!base.configured || JSON.stringify(values[key]) !== JSON.stringify(base[key])) patch[key] = values[key];
  }
  return patch;
}
export function summaryMatches(data, board, settings) {
  return !!data && data.projectId === board?.project.id && data.snapshot?.stateToken === board.snapshot?.stateToken &&
    (!settings || (data.rules?.settingsVersion ?? '0') === settings.settingsVersion);
}
export function workloadExpiryDelay(data, requestElapsed = 0) {
  // Asia/Manila next midnight is this server-supplied date at 16:00 UTC. Browser wall time never classifies a task.
  return Math.max(0, Date.parse(`${data.projectToday}T16:00:00Z`) - Date.parse(data.snapshot.snapshotAtUtc) - requestElapsed);
}
export class ReadState {
  data = null; loading = false; stale = true; error = null; reason = ''; generation = 0; controller = null;
  begin() { this.controller?.abort(); this.controller = new AbortController(); this.loading = true; this.error = null; return ++this.generation; }
  current(ticket) { return ticket === this.generation && !this.controller?.signal.aborted; }
  resolve(ticket, data) { if (!this.current(ticket)) return false; this.data = data; this.loading = false; this.stale = false; this.reason = ''; return true; }
  fail(ticket, error) { if (!this.current(ticket)) return false; this.loading = false; this.stale = true; this.error = error; return true; }
  invalidate(reason = 'Inputs changed; refresh this result.') { this.controller?.abort(); this.generation++; this.loading = false; this.stale = true; this.reason = reason; }
}

// A selected version stays pinned until the user explicitly selects a current one.
export function projectTemplateCurrent(state) {
  const { selectedTemplate, templateNeedsReview, resources } = state;
  const catalogue = resources.catalogue;
  return !!selectedTemplate && !templateNeedsReview && !catalogue.loading && !catalogue.stale &&
    !!catalogue.data?.items.some(template => template.id === selectedTemplate.id && template.version === selectedTemplate.version);
}
