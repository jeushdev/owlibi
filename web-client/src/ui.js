import { editFields, editFieldChanged, editDraft, ruleFields as ruleFieldKeys, ruleFieldChanged, rulesDraft, statuses, labels, actionLabels, actionsFor, pendingTasks, visibleTasks, validate, validateRules, projectTemplateCurrent, projectStartingRules } from './model.js';
// All server/user strings pass through this boundary before entering markup.
export const escapeText = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const e = escapeText;
export const isActionConfirmation = message => /^(Task created|Task updated|You accepted this task|Project rules saved|Project created|Project joined|Invite created|Token copied|Report ready)/.test(message);
// Only the control that started the current write shows the spinner (set once per markup call).
let busyControl = null;
const spinner = '<span class="spinner" aria-hidden="true"></span>';
const button = (id, label, disabled = false, extra = '') => {
  const busy = busyControl === id;
  return `<button type="button" id="${e(id)}" ${disabled ? 'disabled' : ''} ${busy ? 'aria-busy="true"' : ''} ${extra}>${busy ? spinner : ''}${e(label)}</button>`;
};
const iconPaths = {
  tasks: '<rect x="4" y="4" width="16" height="16" rx="2"/><path d="M9 4v16M4 10h16M4 15h16"/>',
  workload: '<path d="M5 19V9m7 10V4m7 15v-7"/>',
  completed: '<circle cx="12" cy="12" r="8"/><path d="m8 12 3 3 5-6"/>',
  activity: '<path d="M4 6v5h5M4 11a8 8 0 1 1 2 7M12 8v5l3 2"/>',
  members: '<circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2m1-15a3 3 0 0 1 0 6m2 4a5 5 0 0 1 3 5"/>',
  settings: '<path d="M4 7h16M4 17h16"/><circle cx="9" cy="7" r="3"/><circle cx="15" cy="17" r="3"/>',
  projects: '<path d="M3 7h7l2 2h9v11H3zM3 7V4h7l2 3"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  up: '<path d="M12 19V5m-6 6 6-6 6 6"/>',
  down: '<path d="M12 5v14m-6-6 6 6 6-6"/>',
  remove: '<path d="M5 7h14m-12 0 1 13h8l1-13M9 7V4h6v3"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  logout: '<path d="M9 4H4v16h5m4-13 5 5-5 5m-4-5h9"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.5-4M4 4v4h4M4 13a8 8 0 0 0 14.5 4M20 20v-4h-4"/>',
  clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l2.5 2"/>',
  arrow: '<path d="M5 12h14m-6-6 6 6-6 6"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>',
  alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5.5M12 16.5v.01"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5.5M12 7.5v.01"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  wifi: '<path d="M2 8.5a15 15 0 0 1 20 0M5.5 12a10 10 0 0 1 13 0M9 15.5a5 5 0 0 1 6 0M12 19v.01"/>'
};
const icon = key => `<svg class="ui-icon" aria-hidden="true" focusable="false" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${iconPaths[key] || iconPaths.tasks}</svg>`;
const navigationLabel = view => ({ tasks: 'Tasks', workload: 'Workload', completed: 'Completed points', activity: 'Activity', members: 'Members', settings: 'Project settings', projects: 'Projects' })[view] || 'Workspace';
const navigationButton = (id, label, symbol, disabled = false, extra = '', count = null) => `<button type="button" id="${e(id)}" ${disabled ? 'disabled' : ''} ${extra}>${icon(symbol)}<span>${e(label)}</span>${count == null ? '' : `<span class="nav-count" aria-label="${e(`${count} total`)}">${e(count)}</span>`}</button>`;
const badge = status => `<span class="tag ${e(status.toLowerCase())}">${e(labels[status])}</span>`;
const textBlock = text => `<p class="multiline">${e(text)}</p>`;
// Times are shown in the project zone (Asia/Manila); pages say so once instead of on every row.
const timeFormat = new Intl.DateTimeFormat('en-PH', { timeZone: 'Asia/Manila', dateStyle: 'medium', timeStyle: 'short' });
const time = value => value ? timeFormat.format(new Date(value)) : '—';
const manilaDay = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Manila', year: 'numeric', month: '2-digit', day: '2-digit' });
const manilaToday = () => manilaDay.format(new Date());
// Due dates are calendar dates (YYYY-MM-DD); format them in UTC so no zone shifts the day.
const shortDate = new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', weekday: 'short', month: 'short', day: 'numeric' });
const yearDate = new Intl.DateTimeFormat('en-US', { timeZone: 'UTC', month: 'short', day: 'numeric', year: 'numeric' });
const date = value => {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return value || '—';
  const day = new Date(`${value}T00:00:00Z`);
  return value.slice(0, 4) === manilaToday().slice(0, 4) ? shortDate.format(day) : yearDate.format(day);
};
// Visual hint only: the server's workload summary stays the authority for overdue/near-due counts.
function dueState(task, rules) {
  if (!task.dueDate || ['DONE', 'CANCELLED'].includes(task.status)) return '';
  const today = manilaToday();
  if (task.dueDate < today) return 'overdue';
  const days = (Date.parse(task.dueDate) - Date.parse(today)) / 86400000;
  return days <= Number(rules?.nearDueDays ?? 3) ? 'near' : '';
}
const dueChip = (task, rules) => {
  if (!task.dueDate) return '<span class="due-chip">No due date</span>';
  const state = dueState(task, rules);
  return `<span class="due-chip${state ? ` due-${state}` : ''}">${icon('clock')}${state === 'overdue' ? 'Overdue · ' : state === 'near' ? 'Due soon · ' : ''}${e(date(task.dueDate))}</span>`;
};
const renderedStructure = new WeakMap();
export function connectionStatus(s) {
  const detail = s.confirmedAt ? `Last checked ${time(s.confirmedAt)} (Manila time).` : 'Not checked yet.';
  if (s.busy) return { state: 'saving', label: 'Saving…', title: 'Saving your change.' };
  if (s.checking || (s.loading && s.stale && !s.offline)) return { state: 'checking', label: 'Checking…', title: `Checking for the latest changes. ${detail}` };
  if (s.offline) return { state: 'offline', label: 'Offline', title: `Can't reach the server. Editing is paused. ${detail}` };
  if (s.stale || s.logoutUnconfirmed) return { state: 'paused', label: 'Paused', title: `Refresh to see the latest and continue editing. ${detail}` };
  return { state: 'live', label: 'Live', title: `Up to date. ${detail}` };
}
function pageHeading(title, description, actions = '') {
  return `<header class="page-heading"><div><h1 id="page-title" tabindex="-1">${title}</h1>
    ${description ? `<p>${description}</p>` : ''}</div>${actions ? `<div class="actions">${actions}</div>` : ''}</header>`;
}
const skeletonLines = (count = 3) => `<div class="skeleton-block" aria-hidden="true">${Array.from({ length: count }, (_, i) => `<span class="skeleton skeleton-line${i === count - 1 ? ' is-short' : ''}"></span>`).join('')}</div>`;
const loadingRegion = (label, body) => `<div class="loading-region" role="status" aria-live="polite"><span class="sr-only">${e(label)}</span>${body}</div>`;
const emptyState = (symbol, title, text, action = '') => `<div class="empty-state"><span class="empty-icon" aria-hidden="true">${icon(symbol)}</span><h2>${e(title)}</h2><p>${e(text)}</p>${action}</div>`;
const avatars = (people, max = 3) => `<span class="avatar-stack">${people.slice(0, max).map(p => `<span class="avatar" title="${e(p.displayName)}" aria-hidden="true">${e(initial(p.displayName))}</span>`).join('')}${people.length > max ? `<span class="avatar avatar-more" aria-hidden="true">+${people.length - max}</span>` : ''}<span class="sr-only">${e(people.map(p => p.displayName).join(', '))}</span></span>`;
// Names alone can repeat; add the roster position only when two members share a name.
function memberNames(members, ids, nameOf = m => m.displayName, idOf = m => m.userId) {
  return ids.map(id => {
    const index = members.findIndex(m => idOf(m) === id);
    if (index < 0) return 'Former member';
    const name = nameOf(members[index]);
    return members.filter(m => nameOf(m) === name).length > 1 ? `${name} (${index + 1})` : name;
  });
}
const noticeHidden = ['Signed in.'];
// A notice's kind is stored by main.js for errors; otherwise inferred from the confirmed-action wording.
export function noticeKind(s) {
  const text = s.notice;
  if (!text) return '';
  if (s.noticeFor === text && s.noticeKind) return s.noticeKind;
  if (noticeHidden.includes(text)) return 'hidden';
  if (isActionConfirmation(text) || /^(You’ve signed out|Signed out|Account created|You’re back online|Report ready|Loaded the latest|Your draft now matches|Used the latest|No changes|No rule changes|Project rules saved)/.test(text)) return 'success';
  if (/^(Correct the marked|Please correct)/.test(text)) return 'error';
  return 'info';
}
function toast(s) {
  const kind = noticeKind(s);
  if (!kind || kind === 'hidden') return '';
  return `<div class="toast toast-${kind}"><span class="toast-icon" aria-hidden="true">${icon(kind === 'success' ? 'check' : kind === 'info' ? 'info' : 'alert')}</span><p class="toast-text">${e(s.notice)}</p><button type="button" id="dismiss-notice" class="icon-button ghost" aria-label="Dismiss message">${icon('close')}</button></div>`;
}
// One format for every displayed point value: drop trailing zeros ("20.00" → "20", "18.0" → "18", "2.50" → "2.5").
// Agreed points keep up to two decimals so the shown value is never a rounded version of the agreement.
const pointNumber = value => { const text = String(value ?? '').trim(); return /^-?\d+(\.\d+)?$/.test(text) ? text.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '') : text; };
const nearDueText = days => Number(days) === 0 ? 'Due today' : `Due today or within ${days} ${Number(days) === 1 ? 'day' : 'days'}`;
// Roster position is shown only to tell apart members who share a display name.
const duplicateTag = (members, name, index, nameOf = m => m.displayName) => members.filter(m => nameOf(m) === name).length > 1 ? ` (${index + 1})` : '';
function field(s, name, label, { type = 'text', area = false, note = '', required = false, trailing = '', placeholder = '' } = {}) {
  const err = s.errors[name];
  const control = `${area ? `<textarea id="field-${e(name)}" name="${e(name)}"` : `<input id="field-${e(name)}" name="${e(name)}" type="${e(type)}" value="${e(s.draft[name])}"${placeholder ? ` placeholder="${e(placeholder)}"` : ''}`} ${required ? 'required' : ''} ${err ? 'aria-invalid="true"' : ''} aria-describedby="note-${e(name)} error-${e(name)}" ${s.busy ? 'disabled' : ''}>${area ? `${e(s.draft[name])}</textarea>` : ''}`;
  return `<label for="field-${e(name)}">${e(label)}${required ? ' <span class="required-mark" aria-hidden="true">*</span>' : ''}</label>${trailing ? `<div class="input-wrap">${control}${trailing}</div>` : control}<small id="note-${e(name)}">${e(note)}</small><span class="field-error" id="error-${e(name)}">${e(err)}</span>`;
}
function effortPointFields(s, wizard = false) {
  const rows = s.draft.effortScaleExamples || [];
  const error = s.errors.effortScaleExamples;
  const rowErrors = wizard && error ? validateRules(s.draft).exampleErrors : [];
  return `<fieldset id="field-effortScaleExamples" tabindex="-1" ${error ? 'aria-invalid="true"' : ''} aria-describedby="error-effortScaleExamples" ${wizard ? 'aria-labelledby="wizard-effort-points-title"' : ''}>
    ${wizard ? '' : '<legend>Effort points</legend>'}
    ${wizard ? '<p class="note">Choose how many points match each task size.</p>' : ''}
    ${wizard ? `<span class="field-error" id="error-effortScaleExamples">${e(error)}</span>` : ''}
    ${rows.map((example, i) => {
      const number = i + 1;
      const disabled = s.busy ? 'disabled' : '';
      const action = (key, text, unavailable) => {
        const id = `example-${key}-${i}`;
        const label = key === 'remove' ? `Remove point row ${number}` : `Move point row ${number} ${key}`;
        if (!wizard) return button(id, label, s.busy || unavailable);
        return `<button type="button" id="${id}" aria-label="${label}" title="${text}" ${s.busy || unavailable ? 'disabled' : ''}>${icon(key)}</button>`;
      };
      const notes = rowErrors[i];
      const rowField = (key, label) => {
        const id = `example-${key}-${i}`;
        const message = notes?.[key];
        return `<label for="field-${id}">${label}</label>
          <input id="field-${id}" name="${id}" value="${e(example[key])}" ${disabled} ${key === 'points' ? 'inputmode="decimal"' : ''} ${wizard ? `aria-label="${key === 'points' ? 'Points' : 'Task size'}, row ${number}"` : ''} ${message ? `aria-invalid="true" aria-describedby="error-${id}"` : ''}>
          ${message ? `<span class="field-error" id="error-${id}" data-example-field="${id}">${e(message)}</span>` : ''}`;
      };
      return `<div class="example-row">
        ${rowField('points', wizard ? 'Points' : `Points, row ${number}`)}
        ${rowField('label', wizard ? 'Task size' : `Task size, row ${number}`)}
        <div class="actions">${action('up', 'Move up', i === 0)}${action('down', 'Move down', i === rows.length - 1)}${action('remove', 'Remove', rows.length <= 1)}</div>
      </div>`;
    }).join('')}
    ${wizard ? '' : `<span class="field-error" id="error-effortScaleExamples">${e(error)}</span>`}
    ${button('add-example', 'Add point value', s.busy || rows.length >= 10)}
  </fieldset>`;
}
function ruleFields(s) {
  return `<div class="form-group">${field(s, 'effortScaleName', 'Effort scale name', { required: true, note: 'Name the scale your team uses to estimate task size. Up to 100 characters.' })}</div>
    <section class="wizard-effort-points"><h3 id="wizard-effort-points-title">Effort points</h3>${effortPointFields(s, true)}</section>
    <div class="form-pair"><div class="form-group">${field(s, 'overloadThreshold', 'Overload threshold (points)', { required: true, note: 'Show a warning above this value. Use up to two decimal places (0.01–99999999.99).' })}</div>
    <div class="form-group">${field(s, 'nearDueDays', 'Near-due window (days)', { required: true, note: 'Use 0–30 whole days. A zero-day window covers tasks due today.' })}</div></div>
    <p class="note">Project time zone · Asia/Manila</p>`;
}
function pointAmount(points) {
  const value = pointNumber(points);
  return `${value} ${value === '1' ? 'point' : 'points'}`;
}
function effortPointList(rows) {
  return `<ul class="effort-point-list">${rows.map(row => `<li><strong>${e(pointAmount(row.points))}</strong><span>${e(row.label)}</span></li>`).join('')}</ul>`;
}
function rulesDetail(rules) {
  if (!rules?.configured) return '<p>The project leader can set up rules in Project settings.</p>';
  return `<dl class="rules-facts"><dt>Effort scale</dt><dd>${e(rules.effortScaleName)}</dd><dt>Overload threshold</dt><dd>Above ${e(pointAmount(rules.overloadThreshold))}</dd><dt>Near-due window</dt><dd>${e(nearDueText(rules.nearDueDays))}</dd><dt>Time zone</dt><dd>${e(rules.projectZone)}</dd>${''}</dl>
    <h3>Effort points</h3>${effortPointList(rules.effortScaleExamples)}`;
}
// Always-visible conflict card; shown only when the server copy moved past the draft's base.
const callout = (tone, title, body, action = '') => `<section class="callout callout-${tone}" role="group" aria-label="${e(title)}"><span class="callout-icon" aria-hidden="true">${icon(tone === 'info' ? 'info' : 'alert')}</span><div><h3>${e(title)}</h3>${body}${action ? `<div class="actions">${action}</div>` : ''}</div></section>`;
// After "Load the latest", list each field where the draft still differs from the latest server value.
function differences(s, title, rows) {
  if (!rows.length) return '';
  return callout('info', title, `<p>Fields you didn’t edit now match the latest version. Saving keeps the values you entered here:</p>
    <ul class="diff-list">${rows.map(([key, label, mine, latest]) => `<li><div><strong>${e(label)}</strong><span class="diff-values"><span class="diff-mine">${e(mine)}</span> <span aria-hidden="true">→</span><span class="sr-only">latest:</span> <span class="diff-latest">${e(latest)}</span></span></div>${button(`use-latest-${key}`, 'Use latest', s.busy, 'class="ghost"')}</li>`).join('')}</ul>`,
    button('use-latest', 'Use latest values', s.busy));
}
function editDifferences(s) {
  const latest = s.editBase, fresh = editDraft(latest), names = ids => memberNames(s.board.members, ids || [], m => m.displayName, m => m.id).join(', ') || 'None';
  const show = { title: v => v || '—', description: v => v || '—', category: v => v || '—', dueDate: v => v ? date(v) : 'No due date', effortPoints: v => pointAmount(v), assigneeIds: names };
  const labelsFor = { title: 'Title', description: 'Description', category: 'Category', dueDate: 'Due date', effortPoints: 'Points', assigneeIds: 'Assignees' };
  return differences(s, 'Your edits differ from the latest version', editFields.filter(key => editFieldChanged(latest, s.draft, key)).map(key => [key, labelsFor[key], show[key](s.draft[key]), show[key](fresh[key])]));
}
function rulesDifferences(s) {
  if (!s.settingsBase?.configured) return '';
  const fresh = rulesDraft(s.settingsBase);
  const show = { effortScaleName: v => v || '—', effortScaleExamples: rows => (rows || []).map(r => `${pointNumber(r.points)} ${r.label}`).join(', '), overloadThreshold: v => `Above ${pointAmount(v)}`, nearDueDays: v => nearDueText(v) };
  const labelsFor = { effortScaleName: 'Effort scale name', effortScaleExamples: 'Effort points', overloadThreshold: 'Overload threshold', nearDueDays: 'Near-due window' };
  return differences(s, 'Your edits differ from the latest rules', ruleFieldKeys.filter(key => ruleFieldChanged(fresh, s.draft, key)).map(key => [key, labelsFor[key], show[key](s.draft[key]), show[key](fresh[key])]));
}
function effortPicker(s, examples) {
  const value = s.draft.effortPoints;
  const chosen = value === undefined || value === '' ? null : examples.find(x => Number(x.points) === Number(value));
  return `<fieldset class="effort-picker" aria-describedby="effort-example">
    <legend>Pick a size</legend>
    <div class="chip-group">${examples.map((x, i) => `<label class="chip" for="effort-preset-${i}"><input type="radio" id="effort-preset-${i}" name="effortPreset" value="${e(pointNumber(x.points))}" data-label="${e(x.label)}" ${chosen === x ? 'checked' : ''} ${s.busy ? 'disabled' : ''}><span><strong>${e(pointNumber(x.points))}</strong>${e(x.label)}</span></label>`).join('')}</div>
    <p class="note" id="effort-example">${chosen ? `${e(chosen.label)} · ${e(pointAmount(chosen.points))}` : 'Pick a size, or type any number of points below.'}</p>
  </fieldset>`;
}
function form(s, canWrite) {
  let content = '', heading = '', submit = '';
  const currentReasonTask = s.form === 'reason' ? s.board?.tasks.find(t => t.id === s.reasonTask.id) : null;
  const reasonAllowed = s.form !== 'reason' || !!currentReasonTask && currentReasonTask.version === s.reasonTask.version && actionsFor(currentReasonTask, s.board.project.role, s.user.id).some(a => a.action === s.reasonAction && a.enabled);
  const currentEditTask = s.form === 'edit' ? s.board?.tasks.find(t => t.id === s.editBase.id) : null;
  const editAllowed = s.form !== 'edit' || !!currentEditTask && !s.editNeedsReview && currentEditTask.version === s.editBase.version && !['DONE', 'CANCELLED'].includes(currentEditTask.status);
  const rulesAllowed = s.form !== 'settings' || !!s.settingsBase && !s.settingsNeedsReview && !s.resources.settings.stale && s.resources.settings.data?.settingsVersion === s.settingsBase.settingsVersion && s.board.project.role === 'LEADER';
  switch (s.form) {
    case 'login': case 'register':
      heading = s.form === 'login' ? 'Welcome back.' : 'Create your account.'; submit = s.form === 'login' ? 'Sign in' : 'Create account';
      if (s.form === 'register') content += field(s, 'displayName', 'Display name', { required: true, note: 'Choose the name your teammates will see.' });
      content += field(s, 'email', 'Email', { type: 'email', required: true, placeholder: 'you@example.com', note: s.form === 'register' ? 'Use the email address you’ll sign in with.' : 'Enter the email address for your account.' });
      const toggle = `<button type="button" id="show-password" class="inline-toggle" aria-pressed="${!!s.showPassword}">${icon('eye')}<span>${s.showPassword ? 'Hide password' : 'Show password'}</span></button>`;
      content += field(s, 'password', 'Password', { type: s.showPassword ? 'text' : 'password', required: true, trailing: toggle, placeholder: 'Enter your password', note: s.form === 'register' ? 'Create a password or passphrase. Spaces are allowed.' : 'Use the password you created for this account.' });
      break;
    case 'settings':
      heading = s.settingsBase?.configured ? 'Edit project rules' : 'Set up project rules'; submit = 'Save project rules';
      {
        const currentRules = s.resources.settings;
        const changed = !!s.settingsBase && (s.settingsNeedsReview || (!!currentRules.data && currentRules.data.settingsVersion !== s.settingsBase.settingsVersion));
        const review = label => button('review-settings', label, s.busy || s.loading || !!s.unknown);
        content = changed ? callout('warning', 'These rules were changed by someone else', `<p>Your draft is kept. Load the latest rules before saving.</p>${!currentRules.stale && currentRules.data ? `<div id="current-rules" class="callout-values">${rulesDetail(currentRules.data)}</div>` : ''}`, review('Load the latest rules'))
          : !rulesAllowed && !s.unknown ? callout('info', 'Checking the latest rules', '<p>Saving is paused until the latest rules load.</p>', review('Check again'))
          : s.reviewedLatest ? rulesDifferences(s) : '';
        content += ruleFields(s);
      } break;
    case 'join':
      heading = 'Join a project'; submit = 'Join project';
      content = '<p class="note">Enter the invitation token from your project leader.</p>' + field(s, 'token', 'Invitation token', { required: true }); break;
    case 'task': case 'edit':
      heading = s.form === 'edit' ? 'Edit task' : 'New task'; submit = s.form === 'edit' ? 'Save changes' : 'Create task';
      const effort = field(s, 'effortPoints', 'Agreed effort points', { required: true, note: 'Estimate task size. Use up to two decimal places (0.01–99999999.99).', type: 'text' });
      const due = field(s, 'dueDate', 'Due date (optional)', { type: 'date', note: 'Choose a date or leave it blank. Past dates are allowed.' });
      const examples = s.resources.settings.data?.effortScaleExamples;
      content = `<div class="form-group">${field(s, 'title', 'Task title', { required: true, note: 'Up to 200 characters.' })}
        ${field(s, 'description', 'Description (optional)', { area: true, note: 'Up to 4,000 characters.' })}
        ${field(s, 'category', 'Category (optional)', { note: 'Up to 100 characters.' })}</div>
        <fieldset id="field-assigneeIds" tabindex="-1" aria-describedby="assignee-help error-assigneeIds" ${s.errors.assigneeIds ? 'aria-invalid="true"' : ''}>
          <legend>Assignees</legend>
          <p class="note" id="assignee-help">Choose 1–6 members. Each assignee must accept the task and points.</p>
          <div class="assignee-picker">${s.board.members.map((m, i) => `<label class="check" for="assignee-${i}"><input type="checkbox" name="assigneeIds" id="assignee-${i}" value="${e(m.id)}" ${s.draft.assigneeIds?.includes(m.id) ? 'checked' : ''} ${s.busy ? 'disabled' : ''}><span>${e(m.displayName)}${e(duplicateTag(s.board.members, m.displayName, i))}<small>${m.role === 'LEADER' ? 'Leader' : 'Member'}</small></span></label>`).join('')}</div>
          <span class="field-error" id="error-assigneeIds">${e(s.errors.assigneeIds)}</span>
        </fieldset>
        ${examples?.length ? effortPicker(s, examples) : ''}
        <div class="form-pair task-estimate-fields"><div class="form-group">${effort}</div><div class="form-group">${due}</div></div>`;
      if (s.form === 'edit') {
        const current = s.board.tasks.find(t => t.id === s.editBase.id);
        const changed = !!current && (s.editNeedsReview || current.version !== s.editBase.version);
        const review = button('review-edit', 'Load the latest version', s.busy || s.loading || !!s.unknown);
        const values = current && current.version !== s.editBase.version ? `<dl class="callout-values"><dt>Title</dt><dd>${e(current.title)}</dd><dt>Status</dt><dd>${e(labels[current.status])}</dd><dt>Points</dt><dd>${e(pointAmount(current.effortPoints))}</dd><dt>Due</dt><dd>${e(current.dueDate ? date(current.dueDate) : 'No due date')}</dd><dt>Category</dt><dd>${e(current.category || '—')}</dd><dt>Assignees</dt><dd>${e(memberNames(s.board.members, current.assigneeIds, m => m.displayName, m => m.id).join(', '))}</dd><dt>Description</dt><dd class="multiline">${e(current.description || '—')}</dd></dl>` : '<p>Load the latest version to see what changed.</p>';
        content = (!current ? callout('warning', 'This task is no longer available', '<p>Close this form and refresh the board.</p>')
          : changed ? callout('warning', 'This task was changed by someone else', `<p>Your draft is kept. Check the current version, then load it before saving.</p>${values}`, review)
          : ['DONE', 'CANCELLED'].includes(current.status) ? callout('info', current.status === 'DONE' ? 'Reopen this task to edit it' : 'Cancelled tasks can’t be edited', '<p>Your draft is kept, but it can’t be saved.</p>')
          : s.reviewedLatest ? editDifferences(s) : '') + content;
        content += field(s, 'reason', 'Reason for agreement change', { area: true, note: 'Required if you change the title, description, points, or assignees after the task has started. Up to 1,000 characters.' });
        content += '<p class="note">Changing the title, description, points or assignees asks every assignee to accept again.</p>';
      } break;
    case 'reason':
      heading = actionLabels[s.reasonAction]; submit = `Confirm ${actionLabels[s.reasonAction].toLowerCase()}`;
      content = `<p>${e(s.reasonTask.title)} · ${e(labels[s.reasonTask.status])} → ${e(s.reasonAction === 'CANCEL' ? 'Cancelled' : 'In progress')}</p>${!reasonAllowed ? '<p>This task has changed since you opened this form. Refresh to see the latest, or close the form if the action is no longer available.</p>' : ''}` + field(s, 'reason', 'Reason', { area: true, required: true, note: 'Up to 1000 characters.' });
      if (s.reasonAction === 'CANCEL') content += '<p>Cancelled tasks earn no completed points and leave open workload. There is no Cancelled recovery.</p>';
      break;
  }
  const auth = ['login', 'register'].includes(s.form);
  const headingTag = auth ? 'h1' : 'h2';
  const submitBusy = s.busy && busyControl === 'submit-form';
  const submitButton = `<button class="primary" type="submit" id="submit-form" ${s.busy || s.unknown || !reasonAllowed || !editAllowed || !rulesAllowed || (!auth && !canWrite) || s.rateUntil > Date.now() ? 'disabled' : ''} ${submitBusy ? 'aria-busy="true"' : ''}>${submitBusy ? spinner : ''}${s.busy ? ({ login: 'Signing in…', register: 'Creating account…', join: 'Joining…', task: 'Creating task…', edit: 'Saving changes…', settings: 'Saving rules…' }[s.form] || 'Saving…') : e(submit)}</button>`;
  if (auth) {
    const lede = s.form === 'login' ? 'Sign in to your team’s project workspace.' : 'Create a profile to join or start a project.';
    const switchText = s.form === 'login' ? ['New to Owlibi?', 'Create an account'] : ['Already have an account?', 'Sign in'];
    return `<section class="form-panel form-${s.form} auth-panel" aria-labelledby="form-title"><${headingTag} id="form-title">${e(heading)}</${headingTag}><p class="auth-lede">${lede}</p><form id="active-form" novalidate>${content}<div class="actions">${submitButton}</div></form><p class="auth-switch">${switchText[0]} ${button('auth-switch', switchText[1], s.busy || !!s.unknown, 'class="link-button"')}</p></section>`;
  }
  return `<section class="form-panel form-${s.form}" aria-labelledby="form-title"><${headingTag} id="form-title">${e(heading)}</${headingTag}><form id="active-form" novalidate>${content}<div class="actions">${submitButton}${button('close-form', 'Cancel', s.busy)}</div></form></section>`;
}
const projectSteps = [
  ['Project details', 'Enter your project’s basic information.'],
  ['Choose a template', 'Select the type of project you want to create.'],
  ['Project settings', 'Set how tasks are measured and when to show warnings.'],
  ['Review & create', 'Review your project before creating it.']
];
const templatePresentation = {
  blank: ['Blank', 'Start with an empty workspace.'],
  coding: ['Coding', 'Plan, build, test, and deliver software.'],
  written: ['Writing', 'Research, outline, draft, and revise.'],
  'reporting-presentation': ['Reports & Presentations', 'Organize findings and prepare to present.'],
  'filming-video': ['Filming & Video', 'Plan, shoot, and edit a video.']
};
const templateName = template => templatePresentation[template?.id]?.[0] || template?.name || '';
function projectList(s) {
  return `<h2 class="list-heading">Your projects</h2><ul class="project-list project-directory">${s.projects.map(project => `<li>
    <button type="button" id="project-${e(project.id)}" data-project="${e(project.id)}">
      <span class="project-row-icon">${icon('projects')}</span>
      <span class="project-row-copy"><strong>${e(project.name)}</strong>${project.description ? `<span class="multiline">${e(project.description)}</span>` : ''}</span>
      <span class="project-role">${e(project.role === 'LEADER' ? 'Leader' : 'Member')}</span>
      <span class="project-arrow" aria-hidden="true">${icon('arrow')}</span>
    </button>
  </li>`).join('')}</ul>${s.projectCursor ? button('more-projects', 'Load more projects', s.loading) : ''}`;
}
function projectsPage(s, canWrite) {
  const empty = !s.projects.length && !s.loading && !s.stale;
  return `<section class="projects-screen">
    <header class="projects-heading">
      <div><h1 id="page-title" tabindex="-1">Projects</h1><p>Open a project or start one with your team.</p></div>
      <div class="actions">${s.form === 'join' ? '' : button('join-project', 'Join project', !canWrite)}${empty ? '' : button('new-project', 'Create project', !canWrite, 'class="primary"')}</div>
    </header>
    ${s.form === 'join' ? form(s, canWrite) : ''}
    ${s.projects.length ? projectList(s) : s.form === 'join' ? '' : empty ? `<section class="projects-empty"><span class="empty-project-icon">${icon('projects')}</span><h2>No projects yet</h2><p>Create a new project or join one using an invitation.</p>${button('new-project', 'Create project', !canWrite, 'class="primary"')}</section>` : s.loading || (s.stale && !s.offline) ? loadingRegion('Loading projects', `<div class="project-directory">${Array.from({ length: 3 }, () => '<span class="skeleton skeleton-row"></span>').join('')}</div>`) : `<p class="projects-list-status">Your projects couldn’t be loaded. ${button('refresh-projects', 'Try again', s.busy || s.loading)}</p>`}
  </section>`;
}
function starterList(tasks) {
  return `<ul class="starter-list">${tasks.map(task => `<li title="${e(task.description)}"><span class="starter-title">${e(task.title)}</span>${task.category ? `<span class="pill" title="${e(task.category)}">${e(task.category)}</span>` : ''}<span class="points-badge">${e(pointNumber(task.effortPoints))}<span class="sr-only"> points</span></span></li>`).join('')}</ul>`;
}
function rulesSummary(rules) {
  const points = rules.effortScaleExamples.map(x => pointNumber(x.points));
  return `<p class="note rules-summary">Starting rules: ${e(rules.effortScaleName)} scale (${e(points.join(', '))} points) · warning above ${e(pointNumber(rules.overloadThreshold))} points · near due within ${e(rules.nearDueDays)} days. You can change these in the next step.</p>`;
}
function templateStep(s) {
  const { catalogue } = s.resources;
  const selected = s.selectedTemplate;
  const hasCurrentVersion = catalogue.data?.items.some(item => item.id === selected?.id);
  return `<div class="wizard-template-layout">
    <section class="template-picker">
      <h3 id="template-options-title">Available templates</h3>
      <fieldset class="template-options" id="field-template" tabindex="-1" aria-labelledby="template-options-title" aria-describedby="catalogue-status error-template">
      <div id="catalogue-status" class="note">${catalogue.loading ? 'Loading templates…' : catalogue.error ? 'Couldn’t load templates. Reload to continue.' : catalogue.stale ? 'Reload the templates to see the latest versions.' : ''}</div>
      ${!catalogue.data && !catalogue.error ? loadingRegion('Loading templates', Array.from({ length: 4 }, () => '<span class="skeleton skeleton-option"></span>').join('')) : ''}
      ${(catalogue.data?.items || []).map(template => {
        const id = `template-${template.id}`;
        const checked = selected?.id === template.id && selected.version === template.version && !s.templateNeedsReview;
        return `<label class="template-option" for="${e(id)}">
          <input type="radio" id="${e(id)}" name="project-template" value="${e(template.id)}" ${checked ? 'checked' : ''} ${catalogue.loading || catalogue.stale || s.busy ? 'disabled' : ''} aria-describedby="description-${e(id)}">
          <span><strong>${e(templateName(template))}</strong><small id="description-${e(id)}">${e(templatePresentation[template.id]?.[1] || `${template.starterTasks.length} starter tasks`)}</small></span>
        </label>`;
      }).join('')}
      <span class="field-error" id="error-template">${e(s.errors.template)}</span>
      ${button('retry-catalogue', 'Reload templates', s.busy || catalogue.loading)}
      </fieldset>
    </section>
    <section class="template-details" id="template-preview" aria-labelledby="template-details-title">
      <h3 id="template-details-title">Template details</h3>
      <div class="template-details-body">
      ${selected ? `<p class="template-version"><strong>${e(templateName(selected))}</strong> · selected version ${e(selected.version)}</p>
        <p><strong>Categories:</strong> ${e(selected.categories.join(', ') || 'None')}</p>
        ${selected.starterTasks.length ? `<h4 class="starter-heading">Starter tasks (${selected.starterTasks.length})</h4>${starterList(selected.starterTasks)}` : '<p>No starter tasks. Add your own after creating the project.</p>'}
        <p class="note">${selected.starterTasks.length ? 'Starter tasks are assigned to you in To do and have no due dates. ' : ''}Assignees must accept the assignment and points before work starts.</p>
        ${rulesSummary(projectStartingRules(selected))}
        ${!projectTemplateCurrent(s) && !catalogue.loading && !catalogue.stale && hasCurrentVersion ? `<p>This template was updated. Choose the current version to continue.</p>${button('review-template', 'Choose current version', s.busy)}` : ''}
      ` : '<p>Select a template to see its categories, starter tasks, and rules.</p>'}
      </div>
    </section>
  </div>`;
}
function settingsStep(s) {
  return `<div class="wizard-settings">
    <div class="wizard-settings-basics">
      <section class="wizard-scale"><h3>Effort scale</h3>${field(s, 'effortScaleName', 'Scale name', { required: true, note: 'Estimate task size, not time.' })}</section>
      <section class="wizard-limits"><h3>Task limits</h3>
        ${field(s, 'overloadThreshold', 'Overload threshold', { required: true, note: 'Flag workloads above this point limit.' })}
        ${field(s, 'nearDueDays', 'Near-due deadline (days)', { required: true, note: 'Number of days before a task is considered nearly due. Use 0–30.' })}
        <h3>Project time zone</h3><p>Asia/Manila</p>
      </section>
    </div>
    <section class="wizard-effort-points"><h3 id="wizard-effort-points-title">Effort points</h3>${effortPointFields(s, true)}</section>
    <section class="wizard-rule-note"><h3>Project rules</h3><p>Use this scale as a guide. Your team can agree to other point values for individual tasks.</p></section>
  </div>`;
}
function reviewStep(s, values) {
  const fields = [
    ['Project name', s.draft.name?.trim()], ['Description', s.draft.description || 'No description provided'],
    ['Selected template', templateName(s.selectedTemplate)], ['Template version', s.selectedTemplate?.version],
    ['Effort scale', values.effortScaleName], ['Overload threshold', `${values.overloadThreshold} points`],
    ['Near-due deadline', `${values.nearDueDays} days`], ['Project time zone', 'Asia/Manila']
  ];
  return `<dl class="wizard-review">${fields.map(([label, value]) => `<div><dt>${e(label)}</dt><dd class="multiline">${e(value)}</dd></div>`).join('')}</dl>
    <section class="wizard-review-points"><h3>Effort points</h3>${effortPointList(values.effortScaleExamples)}</section>
    ${s.selectedTemplate?.starterTasks?.length ? `<section class="wizard-review-points"><h3>Starter tasks (${s.selectedTemplate.starterTasks.length})</h3><p class="note">These tasks will be created and assigned to you.</p>${starterList(s.selectedTemplate.starterTasks)}</section>` : ''}
    <label class="check wizard-agreement" for="field-settingsReviewed"><input type="checkbox" id="field-settingsReviewed" name="settingsReviewed" ${s.draft.settingsReviewed ? 'checked' : ''} aria-describedby="error-settingsReviewed" ${s.busy ? 'disabled' : ''}>I have reviewed and agreed to these project rules.</label>
    <span class="field-error" id="error-settingsReviewed">${e(s.errors.settingsReviewed)}</span>
    ${projectTemplateCurrent(s) || s.busy ? '' : '<p class="note">Go back to Choose a template to pick the current version.</p>'}`;
}
function projectWizard(s, canWrite) {
  const step = s.projectStep || 1;
  const [title, description] = projectSteps[step - 1];
  const rules = step === 4 ? validateRules(s.draft) : null;
  const current = projectTemplateCurrent(s);
  const ready = step === 4 && current && !!s.draft.settingsReviewed && !Object.keys(validate(s.draft, 'project')).length && !Object.keys(rules.errors).length;
  const content = step === 1 ? `<div class="wizard-fields">${field(s, 'name', 'Project name', { required: true })}${field(s, 'description', 'Description (optional)', { area: true })}</div>` :
    step === 2 ? templateStep(s) : step === 3 ? settingsStep(s) : reviewStep(s, rules.values);
  return `<section class="project-wizard">
    <h1 id="page-title" tabindex="-1">Create project</h1>
    <ol class="project-stepper" aria-label="Project creation progress">${projectSteps.map(([label], i) => {
      const number = i + 1;
      return `<li ${step === number ? 'aria-current="step"' : ''} class="${number < step ? 'step-complete' : ''}"><span class="step-number" aria-hidden="true">${number < step ? icon('check') : number}</span><span>${e(label)}</span></li>`;
    }).join('')}</ol>
    <section class="wizard-panel" aria-labelledby="project-step-title">
      <header><h2 id="project-step-title" tabindex="-1">${e(title)}</h2><p>${e(description)}</p></header>
      <form id="active-form" novalidate>
        ${content}
        <div class="wizard-actions">
          <div class="actions">${step > 1 ? button('project-back', 'Back', s.busy || !!s.unknown) : ''}${step === 1 || step === 4 ? button('close-form', 'Cancel', s.busy) : ''}</div>
          ${step === 4 ?
            `<button class="primary" type="submit" id="submit-form" ${!ready || !canWrite || s.rateUntil > Date.now() ? 'disabled' : ''} ${s.busy ? 'aria-busy="true"' : ''}>${s.busy ? `${spinner}Creating project…` : 'Create project'}</button>` :
            `<button class="primary" type="submit" id="project-next" ${s.busy || s.unknown || (step === 2 && !current) ? 'disabled' : ''}>Next</button>`}
        </div>
      </form>
    </section>
    ${s.projectListInspected ? `<section class="wizard-inspected"><h2>Projects from your last check</h2>${s.projects.length ? projectList(s) : '<p>No projects found.</p>'}</section>` : ''}
  </section>`;
}
function taskTable(s, tasks) {
  return `<div class="table-scroll" tabindex="0" role="region" aria-label="Task table, scroll for all columns"><table class="compact"><thead><tr>${['Task', 'Status', 'Assignee', 'Points', 'Due date'].map(c => `<th scope="col"${c === 'Points' ? ' class="numeric"' : ''}>${c}</th>`).join('')}<th scope="col" class="row-arrow"><span class="sr-only">Open</span></th></tr></thead><tbody>${tasks.map(t => `<tr class="${s.selected === t.id ? 'selected' : ''}"><td><button type="button" class="task-title" id="task-${e(t.id)}" data-task="${e(t.id)}" aria-label="Open task ${e(t.title)}">${e(t.title)}</button><small${t.status !== 'REVIEW' && t.assigneeAcceptances.some(a => !a.accepted) ? ' class="agreement-pending"' : ''}>${t.status === 'REVIEW' ? 'Waiting for leader review' : t.assigneeAcceptances.some(a => !a.accepted) ? 'Needs acceptance' : 'Accepted by everyone'}</small></td><td>${badge(t.status)}</td><td><span class="assignee-list">${t.assigneeAcceptances.map(a => `<span class="assignee-chip"><span class="person-initial" aria-hidden="true">${e(initial(a.displayName))}</span>${e(a.displayName)}</span>`).join('')}</span></td><td class="numeric">${e(pointNumber(t.effortPoints))}</td><td>${dueChip(t, s.resources?.settings?.data)}</td><td class="row-arrow" aria-hidden="true">${icon('chevron')}</td></tr>`).join('')}</tbody></table></div>`;
}
const laneEmpty = { TODO: 'No tasks to do', IN_PROGRESS: 'No tasks in progress', REVIEW: 'Nothing to review', DONE: 'Nothing done yet' };
function board(s, tasks) {
  const columns = statuses.map(status => tasks.filter(t => t.status === status));
  const all = columns.flat();
  const roving = all.some(t => t.id === s.boardFocus) ? s.boardFocus : all[0]?.id;
  const rules = s.resources?.settings?.data;
  return `<p id="board-help" class="sr-only">Use arrow keys to move, Home or End to jump within a column, and Enter or Space to open a task.</p>
    <div class="board-scroll" tabindex="0" role="region" aria-label="Task Board, scroll for all columns">
      <div class="board" aria-describedby="board-help">${columns.map((items, column) => {
        const status = statuses[column];
        return `<section class="board-lane lane-${status.toLowerCase()}" aria-label="${labels[status]}">
          <h2><span class="lane-dot" aria-hidden="true"></span>${labels[status]}<small class="lane-count">${items.length}</small></h2>
          <div class="lane-cards">${items.length ? items.map(task => `<button type="button" class="card ${s.selected === task.id ? 'selected' : ''}"
            id="task-${e(task.id)}" data-task="${e(task.id)}" data-card="true" tabindex="${task.id === roving ? 0 : -1}" aria-label="Open task ${e(task.title)}">
            <span class="card-top">${task.category ? `<span class="pill" title="${e(task.category)}">${e(task.category)}</span>` : '<span></span>'}<span class="points-badge" title="${e(pointAmount(task.effortPoints))}">${e(pointNumber(task.effortPoints))}</span></span>
            <strong>${e(task.title)}</strong>
            <span class="card-bottom">${avatars(task.assigneeAcceptances)}${dueChip(task, rules)}</span>
            ${task.assigneeAcceptances.some(a => !a.accepted) ? '<small class="needs-accept">Needs acceptance</small>' : ''}
          </button>`).join('') : `<p class="lane-empty">${e(laneEmpty[status])}</p>`}</div>
        </section>`;
      }).join('')}</div>
    </div>
    ${tasks.some(t => t.status === 'CANCELLED') ? `<h2>Cancelled tasks</h2>${taskTable(s, tasks.filter(t => t.status === 'CANCELLED'))}` : ''}`;
}

function inspector(s, visible, canWrite) {
  if (s.form && ['task', 'edit', 'reason'].includes(s.form)) return form(s, canWrite);
  const task = s.board.tasks.find(t => t.id === s.selected);
  if (!task) return '';
  const index = visible.findIndex(t => t.id === task.id);
  const leader = s.board.project.role === 'LEADER';
  const actions = actionsFor(task, s.board.project.role, s.user.id);
  const canAccept = task.assigneeIds.includes(s.user.id) && !task.currentUserAcceptedAgreement && task.status !== 'CANCELLED';
  const accepted = task.assigneeAcceptances.filter(a => a.accepted).length;
  return `<section aria-labelledby="inspector-title">
    <div class="inspector-top"><h2 class="inspector-label">Task details</h2><button type="button" id="close-inspector" class="icon-button" aria-label="Close inspector">${icon('close')}</button></div>
    <header class="inspector-heading"><h2 id="inspector-title" tabindex="-1">${e(task.title)}</h2>${badge(task.status)}</header>
    ${textBlock(task.description || 'No description.')}
    <section class="inspector-section"><h3>Details</h3>
      <dl><dt>Category</dt><dd>${e(task.category || '—')}</dd><dt>Assignees</dt><dd>${e(task.assigneeAcceptances.map(a => a.displayName).join(', '))}</dd>
        <dt>Points</dt><dd>${e(pointNumber(task.effortPoints))}</dd><dt>Due</dt><dd>${dueChip(task, s.resources?.settings?.data)}</dd></dl>
    </section>
    <section class="agreement inspector-section">
      <div class="section-line"><h3>Assignment agreement</h3></div>
      <p class="agreement-progress"><strong>${accepted} of ${task.assigneeAcceptances.length} accepted</strong><span>All current assignees must accept.</span></p>
      <ul class="agreement-register">${task.assigneeAcceptances.map((a, i) => `<li><span class="person-initial" aria-hidden="true">${e(initial(a.displayName))}</span><div><strong>${e(a.displayName)}</strong>${a.accepted && a.acceptedAtUtc ? `<small>Accepted ${e(time(a.acceptedAtUtc))}</small>` : ''}</div><span class="${a.accepted ? 'acceptance-ok' : 'agreement-pending'}">${a.accepted ? 'Accepted' : 'Pending'}</span></li>`).join('')}</ul>
      ${canAccept ? button('accept-task', 'Accept current assignment and points', !canWrite, 'class="primary"') : ''}
    </section>
    ${leader || actions.length ? `<section class="inspector-section"><h3>Task actions</h3>
      <div class="action-stack">${actions.map((action, i) => {
        const extra = action.action === 'CANCEL' ? 'class="danger-action"' : i === 0 && !canAccept ? 'class="primary"' : '';
        return `<div>${button(`action-${action.action}`, actionLabels[action.action], !canWrite || !action.enabled, extra)}${action.explanation ? `<small>${e(action.explanation)}</small>` : ''}</div>`;
      }).join('')}
      ${leader ? !['DONE', 'CANCELLED'].includes(task.status) ? button('edit-task', 'Edit task', !canWrite) : `<p class="note">${task.status === 'DONE' ? 'Reopen Done before editing.' : 'Cancelled tasks are immutable.'}</p>` : ''}</div></section>` : ''}
    <div class="actions inspector-paging">${button('previous-task', 'Previous task', index <= 0)}<span class="note">${index < 0 ? 'Outside current filters' : `${index + 1} of ${visible.length}`}</span>${button('next-task', 'Next task', index < 0 || index >= visible.length - 1)}</div>
  </section>`;
}
const initial = name => Array.from(name || '?')[0].toUpperCase();

function tasksPage(s, canWrite) {
  const tasks = visibleTasks(s.board.tasks, s.filters, s.user.id);
  const pending = pendingTasks(s.board.tasks, s.user.id);
  const leader = s.board.project.role === 'LEADER';
  const filtered = s.filters.assignment !== 'all' || s.filters.status !== 'active' || !!s.filters.search;
  const empty = !s.board.totalTasks
    ? emptyState('tasks', 'No tasks yet', leader ? 'Create the first task and assign it to your team.' : 'Your project leader will add tasks here.', leader ? button('new-task-empty', 'Create your first task', !canWrite, 'class="primary"') : '')
    : emptyState('tasks', 'No matching tasks', 'Try a different search or clear the filters.', button('clear-filters-empty', 'Clear filters', false, 'class="ghost"'));
  return `<header class="page-heading tasks-heading"><div><h1 id="page-title" tabindex="-1">Tasks</h1><p id="task-count" class="note">${tasks.length} of ${s.board.totalTasks} tasks</p></div>${leader ? `<div class="actions">${button('new-task', 'New task', !canWrite, 'class="primary"')}</div>` : ''}</header>
    <div class="task-toolbar">
      <div class="toolbar" role="group" aria-label="Task view">${button('view-table', 'Table', false, `aria-pressed="${s.mode === 'table'}"`)}${button('view-board', 'Board', false, `aria-pressed="${s.mode === 'board'}"`)}</div>
      <label class="search"><span class="sr-only">Search tasks</span><input type="search" id="search" value="${e(s.filters.search)}" placeholder="Search title, description or assignee"></label>
      <label class="inline-select"><span>Assignee</span><select id="assignment"><option value="all">All assignees</option><option value="mine" ${s.filters.assignment === 'mine' ? 'selected' : ''}>Assigned to me</option>${s.board.members.map((m, i) => `<option value="${e(m.id)}" ${s.filters.assignment === m.id ? 'selected' : ''}>${e(m.displayName)}${e(duplicateTag(s.board.members, m.displayName, i))}</option>`).join('')}</select></label>
      <label class="inline-select"><span>Status</span><select id="status"><option value="active" ${s.filters.status === 'active' ? 'selected' : ''}>All except Cancelled</option><option value="all" ${s.filters.status === 'all' ? 'selected' : ''}>All including Cancelled</option>${[...statuses, 'CANCELLED'].map(st => `<option value="${st}" ${s.filters.status === st ? 'selected' : ''}>${labels[st]}</option>`).join('')}</select></label>
      ${filtered ? button('clear-filters', 'Clear filters', false, 'class="ghost"') : ''}
    </div>
    ${pending.length ? `<button type="button" id="pending" class="pending-banner">${icon('clock')}<strong>${pending.length} ${pending.length === 1 ? 'task needs' : 'tasks need'} your acceptance</strong><span>Review ${icon('arrow')}</span></button>` : ''}
    ${!tasks.length ? empty : s.mode === 'table' ? taskTable(s, tasks) : board(s, tasks)}`;
}
function members(s, canWrite) {
  const members = s.board.members;
  const leader = s.board.project.role === 'LEADER';
  const actions = leader ? button('create-invite', 'Create invitation', !canWrite || members.length >= 6, 'class="primary"') : '';
  return `${pageHeading('Members', 'See who is on this project and who leads it.', actions)}
    ${s.invitation ? `<section class="invitation-display"><h2>Project invitation</h2>
      <p>Share this token. It works once. It expires ${e(time(s.invitation.expiresAtUtc))} (Manila time).</p>
      <label for="invitation-token">Invitation token</label><input id="invitation-token" readonly value="${e(s.invitation.token)}">
      <div class="actions">${button('copy-invite', 'Copy invitation token')}${button('clear-invite', 'Clear token display')}</div></section>` : ''}
    <section class="content-panel" aria-labelledby="roster-title">
      <div class="section-line"><h2 id="roster-title">Project team</h2><span class="note">${members.length} of 6 places filled</span></div>
      <ul class="member-list">${members.map((member, i) => `<li>
        <span class="person-initial" aria-hidden="true">${e(initial(member.displayName))}</span>
        <div><strong>${e(member.displayName)}${e(duplicateTag(members, member.displayName, i))}${member.id === s.user.id ? ' (you)' : ''}</strong></div>
        <span class="role-label">${member.role === 'LEADER' ? 'Leader' : 'Member'}</span></li>`).join('')}</ul>
      ${leader ? members.length === 6 ? '<p class="note">This project has reached its member limit.</p>' : '' : '<p class="note">The project leader manages invitations.</p>'}
    </section>`;
}

const eventTitles = {
  TASK_CREATED: 'Task created', TASK_EDITED: 'Task updated', START: 'Task started', SUBMIT_REVIEW: 'Review submitted',
  APPROVE: 'Task approved', RETURN: 'Returned for changes', CANCEL: 'Task cancelled', REOPEN: 'Task reopened',
  AGREEMENT_ACCEPTED: 'Assignment accepted', PROJECT_CREATED: 'Project created', SETTINGS_CHANGED: 'Project rules updated',
  MEMBER_JOINED: 'Member joined', INVITATION_CREATED: 'Invitation created'
};
const changedFieldLabels = {
  title: 'Title', description: 'Description', category: 'Category', dueDate: 'Due date', effortPoints: 'Effort points',
  assigneeIds: 'Assignees', effortScaleName: 'Effort scale', effortScaleExamples: 'Effort examples',
  overloadThreshold: 'Workload threshold', nearDueDays: 'Near-due window'
};
function activity(s) {
  const taskTitles = new Map(s.board.tasks.map(task => [task.id, task.title]));
  return `${pageHeading('Activity', 'Track task updates, agreements, and project changes.')}
    <section class="content-panel" aria-labelledby="activity-feed-title">
      <div class="section-line"><h2 id="activity-feed-title">Project history</h2><span class="note">Newest first · Manila time</span></div>
      ${s.events.length ? `<ol class="activity">${s.events.map(item => {
        const record = item.changeSummary || {};
        const task = item.taskId ? record.after?.title || taskTitles.get(item.taskId) : null;
        const previousStatus = labels[record.before?.status];
        const currentStatus = labels[record.after?.status];
        const changed = record.changedFields?.map(field => changedFieldLabels[field] || field) || [];
        const title = eventTitles[item.eventType] || item.eventType.toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());
        return `<li><span class="activity-icon">${icon(item.taskId ? 'tasks' : 'activity')}</span><div>
          <div class="activity-heading"><h3>${e(title)}</h3><time datetime="${e(item.occurredAtUtc)}">${e(time(item.occurredAtUtc))}</time></div>
          <p class="note">${e(item.actorName)}${task ? ` · ${e(task)}` : ''}</p>
          ${previousStatus && currentStatus && previousStatus !== currentStatus ? `<p>${e(previousStatus)} → ${e(currentStatus)}</p>` : ''}
          ${changed.length ? `<p>Updated ${e(changed.join(', '))}</p>` : ''}
          ${item.reason ? textBlock(`Reason: ${item.reason}`) : ''}
        </div></li>`;
      }).join('')}</ol>` : s.loading || (s.stale && !s.offline) ? loadingRegion('Loading activity', Array.from({ length: 4 }, () => '<div class="skeleton-activity"><span class="skeleton skeleton-icon"></span>' + skeletonLines(2) + '</div>').join('')) : emptyState('activity', 'No activity yet', 'Task updates, acceptances and rule changes will show up here.')}
      ${s.eventCursor ? button('older-activity', 'Load older activity', s.loading) : ''}
    </section>`;
}
function resourceStatus(s, key) {
  const state = s.resources[key];
  const settingsRequired = state.error?.code === 'SETTINGS_REQUIRED';
  const name = { settings: 'project rules', contribution: 'completed points', workload: 'workload' }[key];
  // Never-loaded results show a skeleton (rendered by the page), not an error, until a first attempt fails.
  if (!state.data && !state.error && !s.offline) return `<div class="result-state sr-only" id="state-${key}" role="status" aria-live="polite">${e(`Loading ${name}…`)}</div>`;
  let message = 'Up to date.';
  if (state.loading) message = state.data ? `Updating ${name}…` : `Loading ${name}…`;
  else if (settingsRequired) message = s.board.project.role === 'LEADER'
    ? 'Set up rules in Project settings to view Workload.'
    : 'The project leader must set up project rules before Workload is available.';
  else if (state.error) message = `Could not load ${name}.${state.data ? ' Showing the last result, which may be out of date.' : ''}`;
  else if (s.offline) message = state.data ? 'Showing your last saved view. Can’t reach the server right now.' : `Couldn’t load ${name}.`;
  else if (state.stale) message = state.data ? `Out of date. ${state.reason || 'Refresh to check for updates.'}` : `${name[0].toUpperCase()}${name.slice(1)} unavailable. Refresh to try again.`;
  // Offline with data: the banner already says so visibly; screen readers still hear the real state.
  const quiet = message === 'Up to date.' || (s.offline && !!state.data);
  return `<div class="result-state${quiet ? ' sr-only' : ''}" id="state-${key}" role="status" aria-live="polite">${e(message)}${!quiet && !state.loading && !settingsRequired && (state.error || state.stale) ? button(`retry-resource-${key}`, 'Try again', s.busy, 'class="ghost"') : ''}</div>`;
}
function settingsPage(s, canWrite) {
  const state = s.resources.settings;
  const rules = state.data;
  const leader = s.board.project.role === 'LEADER';
  const actions = leader && s.form !== 'settings' ? button('edit-rules', rules?.configured ? 'Edit rules' : 'Set up rules', !canWrite || state.stale || state.loading, 'class="primary"') : '';
  return `${pageHeading('Project settings', 'Set the effort scale, workload warnings, and due-date rules.', actions)}
    ${resourceStatus(s, 'settings')}
    ${!leader ? '<p class="note">Read-only project rules</p>' : ''}
    ${s.form === 'settings' ? form(s, canWrite) : !rules && !state.error && !s.offline ? loadingRegion('Loading project rules', `<div class="settings-overview"><section class="content-panel">${skeletonLines(4)}</section><section class="content-panel">${skeletonLines(4)}</section></div>`) : rules?.configured ? `<div class="settings-overview">
      <section class="content-panel"><h2>Effort scale</h2><p class="note">${e(rules.effortScaleName)} · Estimate task size</p>
        ${effortPointList(rules.effortScaleExamples)}</section>
      <section class="content-panel"><h2>Workload and deadlines</h2><dl class="settings-facts">
        <dt>Overload threshold</dt><dd>Above ${e(pointAmount(rules.overloadThreshold))}</dd>
        <dt>Near-due window</dt><dd>${e(nearDueText(rules.nearDueDays))}</dd>
        <dt>Project time zone</dt><dd>${e(rules.projectZone)}</dd>
      </dl></section>
    </div>` : rules ? `<section class="content-panel">${rulesDetail(rules)}</section>` : ''}`;
}

const workloadTabs = [['all', 'All'], ['open', 'Open'], ['overdue', 'Overdue'], ['near', 'Near due'], ['later', 'Later'], ['review', 'In review']];
const workloadFilter = {
  all: () => true, open: t => t.status !== 'REVIEW', overdue: t => t.dateState === 'OVERDUE', near: t => t.dateState === 'NEAR_DUE',
  later: t => t.dateState === 'LATER' || t.dateState === 'NONE' || !t.dueDate, review: t => t.status === 'REVIEW'
};
function workloadMetrics(counts, tab) {
  // Each card is a shortcut to the matching breakdown tab below.
  return `<div class="workload-metrics" role="group" aria-label="Project totals">${[
    ['open', 'Open tasks', counts.openTasks, 'open'], ['review', 'In review', counts.reviewTasks, 'review'],
    ['overdue', 'Overdue', counts.overdueTasks, 'overdue'], ['near', 'Near due', counts.nearDueTasks, 'near']
  ].map(([target, label, count, tone]) => `<button type="button" id="metric-${tone}" data-workload-tab="${target}" class="metric metric-${tone}${Number(count) > 0 ? ' has-items' : ''}" aria-controls="result-tasks" ${tab === target ? 'aria-pressed="true"' : ''}><span class="metric-label">${label}</span><span class="metric-value">${e(count)}</span><span class="metric-hint">Show tasks ${icon('arrow')}</span></button>`).join('')}</div>`;
}

function workloadGauge(member, threshold) {
  const total = Number(member.remainingPoints);
  const limit = Number(threshold);
  return `<span class="workload-value">${e(pointNumber(member.remainingPointsDisplay))}
    <meter class="workload-meter${member.overloaded ? ' is-over' : ''}" aria-hidden="true" min="0" max="${e(limit)}" value="${e(Math.min(total, limit))}"></meter></span>`;
}

function reportPanel(s, canWrite) {
  return `<section class="report-panel" aria-label="Professor report"><div class="report-copy"><h2>Professor report</h2><p>Export project details, tasks, completed points, and activity to Excel.</p></div>${s.board.project.role === 'LEADER' ? `<div class="actions">${button('export-report', 'Export professor report (.xlsx)', !canWrite || s.exportState?.running, 'aria-describedby="report-status report-help" class="primary"')}${s.exportState?.running ? button('cancel-report', 'Cancel export', false, 'aria-describedby="report-help" class="ghost"') : ''}</div>` : '<p>Only the project leader can export.</p>'}<p id="report-status">${s.exportState?.running ? spinner : ''}${e(s.exportState?.message || (s.board.project.role === 'LEADER' ? 'Ready to export.' : ''))}</p>${s.exportState?.running ? `<p id="report-time">${s.exportState.remaining} seconds left</p>` : ''}${s.board.project.role === 'LEADER' ? '<p class="note" id="report-help">Your browser will ask where to save the file.</p>' : ''}</section>`;
}
function summarySkeleton(completed) {
  return loadingRegion(`Loading ${completed ? 'completed points' : 'workload'}`, `${completed ? '<span class="skeleton skeleton-total"></span>' : `<div class="workload-metrics">${'<span class="skeleton skeleton-metric"></span>'.repeat(4)}</div>`}
    <div class="content-panel">${Array.from({ length: 3 }, () => '<span class="skeleton skeleton-row"></span>').join('')}</div>`);
}
function allocationTable(data) {
  if (!data.allocations.length) return '';
  return `<section class="content-panel breakdown-panel" id="result-tasks" aria-labelledby="allocations-title"><div class="section-line"><h2 id="allocations-title">Completed task allocations</h2><span class="note">${data.allocations.length} approved ${data.allocations.length === 1 ? 'task' : 'tasks'}</span></div>
    <div class="table-scroll" tabindex="0" role="region" aria-label="Completed task allocations, scroll for all columns"><table class="summary-table breakdown-table"><thead><tr><th scope="col">Task</th><th scope="col">Assignees</th><th scope="col" class="numeric">Points</th><th scope="col" class="numeric">Per assignee</th></tr></thead><tbody>
    ${data.allocations.map(task => `<tr><th scope="row">${e(task.title)}</th><td>${e(memberNames(data.members, task.assigneeIds).join(', '))}</td><td class="numeric">${e(pointNumber(task.effortPoints))}</td><td class="numeric">${e(pointNumber(task.perAssigneePoints))}</td></tr>`).join('')}
    </tbody></table></div></section>`;
}
function workloadBreakdown(s, data) {
  const tab = workloadFilter[s.workloadTab] ? s.workloadTab : 'all';
  const rows = data.tasks.filter(workloadFilter[tab]);
  const dateLabels = { OVERDUE: 'Overdue', NEAR_DUE: 'Near due', LATER: 'Due later', NONE: 'No due date' };
  const tone = { OVERDUE: ' due-overdue', NEAR_DUE: ' due-near' };
  return `<section class="content-panel breakdown-panel" id="result-tasks" aria-labelledby="breakdown-title"><div class="section-line"><h2 id="breakdown-title">Task breakdown</h2></div>
    <div class="toolbar segmented" role="group" aria-label="Show tasks">${workloadTabs.map(([key, label]) => button(`workload-tab-${key}`, `${label} ${data.tasks.filter(workloadFilter[key]).length}`, false, `aria-pressed="${tab === key}"`)).join('')}</div>
    ${rows.length ? `<div class="table-scroll" tabindex="0" role="region" aria-label="Task breakdown, scroll for all columns"><table class="summary-table breakdown-table"><thead><tr><th scope="col">Task</th><th scope="col">Status</th><th scope="col">Due</th><th scope="col">Assignees</th><th scope="col" class="numeric">Points each</th></tr></thead><tbody>
      ${rows.map(task => `<tr><th scope="row">${e(task.title)}</th><td>${badge(task.status)}</td><td><span class="due-chip${tone[task.dateState] || ''}">${e(task.dueDate ? `${date(task.dueDate)} · ${dateLabels[task.dateState] || ''}` : 'No due date')}</span></td><td>${e(memberNames(data.members, task.assigneeIds).join(', '))}</td><td class="numeric">${task.status === 'REVIEW' ? '—' : e(pointNumber(task.perAssigneeRemainingPoints))}</td></tr>`).join('')}
    </tbody></table></div>` : `<p class="empty-inline">${data.tasks.length ? 'No tasks in this group.' : 'No open tasks or tasks in Review.'}</p>`}</section>`;
}

function summaryPage(s, key, canWrite) {
  const state = s.resources[key], data = state.data, completed = key === 'contribution';
  const title = completed ? 'Completed points' : 'Workload';
  const actions = s.board.project.role === 'LEADER'
    ? button('edit-rules', data?.rules ? 'Edit rules' : 'Set up rules', !canWrite || s.resources.settings.stale)
    : '';
  const description = completed ? 'See each member’s share of effort points from approved tasks.' : 'Track remaining points and upcoming or overdue tasks.';
  let content = `${pageHeading(title, description, data ? actions : '')}${resourceStatus(s, key)}`;
  if (!data) return content + (!state.error && !s.offline ? summarySkeleton(completed) : '') + (completed ? reportPanel(s, canWrite) : '') + (s.form === 'settings' ? form(s, canWrite) : '');
  content += `<p class="note">Updated <time data-result-time="${key}" datetime="${e(data.snapshot.snapshotAtUtc)}">${e(time(data.snapshot.snapshotAtUtc))}</time> (Manila time)${completed ? '' : ` · Due dates as of ${e(date(data.projectToday))}`}</p>`;
  if (completed) {
    content += `<p class="completed-total"><span>Total completed points</span><strong>${e(pointNumber(data.totalCompletedPointsDisplay))}</strong></p>`;
  } else {
    content += workloadMetrics(data.projectCounts, s.workloadTab || 'all');
    content += `<p class="note">Warning above ${e(pointAmount(data.rules.overloadThreshold))} per member, before rounding.</p>`;
  }
  if (completed && data.empty) content += emptyState('completed', 'No approved tasks yet', 'Points appear here when the leader approves a finished task.');
  else content += `<div class="table-scroll" tabindex="0" role="region" aria-label="${title} table, scroll for all columns"><table class="summary-table${completed ? ' contribution-table' : ''}"><caption>${completed ? 'Completed effort-points share' : 'Workload by member'}</caption><thead><tr>${(completed ? ['Member', 'Completed points', 'Completed effort-points share'] : ['Member', 'Remaining points', 'Overdue', 'Near due', 'Points warning']).map((label, i) => `<th scope="col"${i > 0 && (completed ? i === 1 : i < 4) ? ' class="numeric"' : ''}>${label}</th>`).join('')}</tr></thead><tbody>${data.members.map((m, i) => `<tr data-member="${e(m.userId)}"><th scope="row"><span class="member-cell"><span class="avatar" aria-hidden="true">${e(initial(m.displayName))}</span><span>${e(m.displayName)}${e(duplicateTag(data.members, m.displayName, i))}</span></span></th>${completed ? `<td class="numeric">${e(pointNumber(m.completedPointsDisplay))}</td><td><span class="share-bar"><meter class="share-meter" aria-hidden="true" min="0" max="100" value="${e(m.sharePercentDisplay)}"></meter><span class="share-value">${e(pointNumber(m.sharePercentDisplay))}%</span></span></td>` : `<td class="numeric">${workloadGauge(m, data.rules.overloadThreshold)}</td><td class="numeric">${e(m.overdueCount)}</td><td class="numeric">${e(m.nearDueCount)}</td><td>${m.overloaded ? '<span class="tag over-tag">Over threshold</span>' : 'None'}</td>`}</tr>`).join('')}</tbody></table></div>`;
  if (completed) {
    content += allocationTable(data);
    content += reportPanel(s, canWrite);
  } else {
    content += workloadBreakdown(s, data);
  }
  if (s.form === 'settings') content += form(s, canWrite);
  return content;
}
// Decorative sign-in illustration: inline, same-origin, no external assets.
const authArt = `<svg viewBox="0 0 640 900" preserveAspectRatio="xMidYMid slice" focusable="false">
  <defs><pattern id="art-dots" width="26" height="26" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r="1.2" fill="#8d79d6" opacity=".45"/></pattern></defs>
  <rect width="640" height="900" fill="url(#art-dots)"/>
  <g fill="none" stroke="#a796e0" stroke-width="1.4" opacity=".9">
    <path d="M20 250C180 150 460 150 620 280"/><path d="M20 285C180 190 460 190 620 315"/><path d="M20 320C180 235 460 235 620 345"/>
    <path d="M20 640C180 740 460 740 620 620"/><path d="M20 675C180 775 460 775 620 655"/><path d="M20 710C180 810 460 810 620 690"/>
    <circle cx="320" cy="450" r="215"/><circle cx="320" cy="450" r="245" stroke-dasharray="3 6"/>
  </g>
  <g stroke="#5538bf" stroke-width="3">
    <circle cx="228" cy="455" r="86" fill="#f8f5ff"/><circle cx="412" cy="455" r="86" fill="#f8f5ff"/>
    <path d="M140 330C160 250 280 250 320 335C360 250 480 250 500 330" fill="none"/>
  </g>
  <circle cx="232" cy="460" r="42" fill="#6a4cc4"/><circle cx="416" cy="460" r="42" fill="#6a4cc4"/>
  <circle cx="246" cy="446" r="12" fill="#fff"/><circle cx="430" cy="446" r="12" fill="#fff"/>
  <path d="M302 505h36l-18 26z" fill="#5538bf"/>
  <g fill="#8d79d6"><path d="M560 215l8 22 22 8-22 8-8 22-8-22-22-8 22-8z"/><path d="M110 610l6 16 16 6-16 6-6 16-6-16-16-6 16-6z"/><circle cx="590" cy="285" r="5"/><circle cx="130" cy="560" r="5"/></g>
</svg>`;
const navCount = (s, view) => view === 'tasks' && s.board?.tasks ? s.board.tasks.filter(t => t.status !== 'CANCELLED').length : view === 'members' && s.board?.members ? s.board.members.length : null;
function headerStatus(s) {
  const status = connectionStatus(s);
  return `<span class="status-dot" aria-hidden="true"></span><span class="status-label">${e(status.label)}</span><span class="sr-only">. ${e(status.title)}</span>`;
}
function bootScreen(s) {
  const body = s.bootFailed
    ? `<p class="boot-status">Can’t reach the server right now.</p><p class="note">Check your connection, then try again.</p>${button('refresh', 'Try again', false, 'class="primary"')}`
    : `<span class="spinner spinner-lg" aria-hidden="true"></span><p class="boot-status">Connecting…</p>${s.bootSlow ? '<p class="note">Waking up the server, this can take up to a minute.</p>' : ''}`;
  return `<main class="workspace boot-screen" id="main-content" tabindex="-1"><h1 id="page-title" class="sr-only" tabindex="-1">Owlibi</h1><div class="boot-card"><img class="boot-logo" src="/brand-logo.png" width="80" height="80" alt=""><p class="brand">Owlibi</p>${body}</div></main>`;
}
function boardSkeleton() {
  return `<header class="page-heading"><div><h1 id="page-title" tabindex="-1"><span class="sr-only">Loading project…</span><span class="skeleton skeleton-title" aria-hidden="true"></span></h1></div></header>
    ${loadingRegion('Loading project', `<span class="skeleton skeleton-toolbar"></span><div class="board skeleton-board">${statuses.map(() => `<div class="board-lane"><span class="skeleton skeleton-line is-short"></span>${'<span class="skeleton skeleton-card"></span>'.repeat(2)}</div>`).join('')}</div>`)}`;
}
function banners(s) {
  let html = '';
  if (s.user && s.offline) html += `<div class="banner banner-warning"><span class="banner-icon" aria-hidden="true">${icon('wifi')}</span><p>Can’t reach the server. You’re seeing your last saved view; editing is paused until the connection is back.</p><button type="button" id="retry-connection" ${s.checking || s.loading ? 'disabled aria-busy="true"' : ''}>${s.checking || s.loading ? spinner : ''}Retry</button></div>`;
  if (!s.user && s.logoutUnconfirmed) html += `<div class="banner banner-warning"><span class="banner-icon" aria-hidden="true">${icon('alert')}</span><p>We couldn’t confirm that you’re signed in.</p>${button('refresh', 'Check sign-in', s.busy || s.loading || s.revalidating, 'class="ghost"')}</div>`;
  if (s.unknown) html += `<div class="banner banner-warning"><span class="banner-icon" aria-hidden="true">${icon('alert')}</span><p>We couldn’t confirm your last change. ${s.unknown === 'invitation' ? 'If the invite didn’t come through, create a new one.' : 'Check whether it saved before trying again.'}</p>${button('inspect-unknown', 'Check if it saved', s.loading)}</div>`;
  return html ? `<div class="banners">${html}</div>` : '';
}
export function markup(s) {
  busyControl = s.busy ? s.busyId ?? null : s.exportState?.running ? 'export-report' : null;
  if (!s.user && (s.booting || s.revalidating)) return bootScreen(s);
  const canWrite = !!s.user && !s.stale && !s.busy && !s.loading && !s.revalidating && !s.unknown;
  const side = `<aside class="sidebar"><div class="brand-group"><img class="brand-mark" src="/brand-logo.png" width="80" height="80" alt=""><p class="brand">Owlibi</p>${s.board ? navigationButton('workspace-menu', 'Menu', 'menu', false, `aria-expanded="${s.navOpen}" aria-controls="project-navigation"`) : ''}</div>${s.user ? `<div class="project-context">${navigationButton('projects', 'All projects', 'projects', false, s.page === 'projects' ? 'aria-current="page"' : '')}${s.board ? `<p class="project-name">${e(s.board.project.name)}</p>` : ''}</div>${s.board ? `<nav id="project-navigation" aria-label="Project navigation"${s.navOpen ? ' class="is-open"' : ''}>${['tasks', 'workload', 'completed', 'activity', 'members', 'settings'].map(view => navigationButton(`nav-${view}`, navigationLabel(view), view, false, s.page === view ? 'aria-current="page"' : '', navCount(s, view))).join('')}</nav>` : ''}<div class="account-block"><span class="account-avatar" aria-hidden="true">${e(initial(s.user.displayName))}</span><span class="account-text"><span id="account-context">${e(s.user.displayName)}</span><span id="role-context">${s.board && s.page !== 'projects' ? (s.board.project.role === 'LEADER' ? 'Project leader' : 'Project member') : 'Your account'}</span></span></div>${navigationButton('logout', 'Sign out', 'logout', s.busy)}` : ''}<div class="theme-controls">${button('theme-light', 'Light', false, `aria-pressed="${s.theme === 'light'}"`)}${button('theme-dark', 'Dark', false, `aria-pressed="${s.theme === 'dark'}"`)}</div></aside>`;
  const place = !s.user ? '' : s.page === 'projects' && s.form === 'project' ? '<a class="breadcrumb-link" aria-hidden="true">Projects</a><span class="breadcrumb-separator" aria-hidden="true">›</span><span class="breadcrumb-current">Create project</span>' : s.page === 'projects' || !s.board ? '<span class="breadcrumb-current">Projects</span>' : `<span class="breadcrumb-link" aria-hidden="true">Projects</span><span class="breadcrumb-separator" aria-hidden="true">›</span><span class="breadcrumb-current breadcrumb-project">${e(s.board.project.name)}</span>`;
  const status = s.user ? `<div class="header-status"><p id="connection-state" class="status-pill" data-state="${connectionStatus(s).state}" title="${e(connectionStatus(s).title)}">${headerStatus(s)}</p><button type="button" id="refresh" class="icon-button ghost" aria-label="Refresh" ${s.busy || s.loading || s.revalidating || s.checking ? 'disabled' : ''}>${icon('refresh')}</button></div>` : '';
  const chrome = `<header class="app-header" aria-label="Workspace context"><div class="workspace-location">${place}</div><span class="workspace-mode sr-only">${s.user && s.board ? s.board.project.role === 'LEADER' ? 'Leader workspace' : 'Member workspace' : 'Project workspace'}</span>${status}</header>`;
  let content = '', detail = '';
  if (!s.user) content = form(s, true);
  else if (s.page === 'projects') {
    content = s.form === 'project' ? projectWizard(s, canWrite) : projectsPage(s, canWrite);
  } else if (!s.board) content = s.projectUnavailable || (s.offline && !s.loading) ? `<header class="page-heading"><div><h1 id="page-title" tabindex="-1">Project unavailable</h1><p>${s.projectUnavailable ? 'This project may have been removed, or you may no longer be a member.' : 'This project couldn’t be loaded right now.'}</p></div></header>` : boardSkeleton();
  else {
    content = s.page === 'tasks' ? tasksPage(s, canWrite) : s.page === 'members' ? members(s, canWrite) : s.page === 'settings' ? settingsPage(s, canWrite) : s.page === 'workload' ? summaryPage(s, 'workload', canWrite) : s.page === 'completed' ? summaryPage(s, 'contribution', canWrite) : activity(s);
    if (s.page === 'tasks') detail = inspector(s, visibleTasks(s.board.tasks, s.filters, s.user.id), canWrite);
  }
  return `${side}${chrome}<main class="workspace" id="main-content" tabindex="-1">${banners(s)}${content}</main>${detail ? `<aside class="inspector" aria-label="Task inspector">${detail}</aside>` : ''}${!s.user ? `<aside class="auth-art" aria-hidden="true">${authArt}</aside>` : ''}<div class="toast-region">${toast(s)}</div>`;
}
export function capturePosition(root) {
  return { document: [window.scrollX, window.scrollY], regions: [...root.querySelectorAll('.workspace,.inspector,.table-scroll,.board-scroll')].map(el => [el.className, el.scrollTop, el.scrollLeft]) };
}
export function restorePosition(root, position) {
  if (!position) return;
  for (const [cls, top, left] of position.regions) { const el = [...root.querySelectorAll('[class]')].find(el => el.className === cls); if (el) { el.scrollTop = top; el.scrollLeft = left; } }
  window.scrollTo(...position.document);
}
// Header status updates in place so background checks never re-render the page.
export function renderStatus(root, s) {
  const connection = root.querySelector('#connection-state');
  if (connection) {
    const status = connectionStatus(s), html = headerStatus(s);
    if (connection.dataset.state !== status.state) connection.dataset.state = status.state;
    if (connection.title !== status.title) connection.title = status.title;
    if (connection.innerHTML !== html) connection.innerHTML = html;
  }
  const refresh = root.querySelector('#refresh.icon-button');
  if (refresh) refresh.disabled = s.busy || s.loading || s.revalidating || s.checking;
}
// Spinners rotate through the Web Animations API, and only when the user allows motion.
function spin(root) {
  if (typeof matchMedia !== 'function' || !matchMedia('(prefers-reduced-motion: no-preference)').matches) return;
  for (const el of root.querySelectorAll('.spinner')) el.animate?.([{ transform: 'rotate(0turn)' }, { transform: 'rotate(1turn)' }], { duration: 800, iterations: Infinity });
}
export function render(root, s, { backgroundRefresh = false, preserveUnchanged = false } = {}) {
  // Unchanged polls retain DOM identity, including VoiceOver's reading cursor.
  // Writes still wait in main.js; stale/permission gates remain authoritative.
  const view = backgroundRefresh ? { ...s, loading: false, resources: Object.fromEntries(Object.entries(s.resources).map(([key, value]) => [key, { ...value, loading: value.data ? false : value.loading }])) } : s;
  // Draft inputs already update their live DOM values without a full render.
  const structure = markup({ ...view, confirmedAt: null, checking: false, draft: {}, resources: Object.fromEntries(Object.entries(view.resources).map(([key, value]) => [key, { ...value, data: value.data?.snapshot ? { ...value.data, snapshot: { ...value.data.snapshot, snapshotAtUtc: null } } : value.data }])) });
  const updateConnection = () => {
    renderStatus(root, s);
    for (const el of root.querySelectorAll('[data-result-time]')) { const at = s.resources[el.dataset.resultTime].data?.snapshot.snapshotAtUtc; if (at) { el.textContent = time(at); el.dateTime = at; } }
  };
  if (backgroundRefresh && preserveUnchanged && renderedStructure.get(root) === structure) { updateConnection(); return; }
  const active = document.activeElement, focusId = active?.id;
  const selection = active && 'selectionStart' in active ? [active.selectionStart, active.selectionEnd] : null;
  const position = capturePosition(root);
  const announcement = document.getElementById('announcement');
  const message = s.busy ? 'Saving…' : s.loading && !backgroundRefresh ? 'Refreshing project data.' : s.notice || (s.user && s.offline ? 'Can’t reach the server. Editing is paused.' : '');
  if (announcement && announcement.textContent !== message) announcement.textContent = message;
  root.innerHTML = markup(view);
  renderedStructure.set(root, structure);
  updateConnection();
  spin(root);
  document.documentElement.dataset.theme = s.theme;
  const booting = !s.user && (s.booting || s.revalidating);
  root.classList.toggle('with-inspector', !!root.querySelector('.inspector'));
  root.classList.toggle('is-booting', booting);
  root.classList.toggle('is-signed-out', !s.user && !booting);
  restorePosition(root, position);
  const restored = focusId && document.getElementById(focusId);
  if (restored && !restored.disabled) { restored.focus({ preventScroll: true }); if (selection && typeof restored.setSelectionRange === 'function' && ['text', 'search', 'password', 'textarea'].includes(restored.type)) restored.setSelectionRange(...selection); }
}
