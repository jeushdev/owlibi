import { statuses, labels, actionLabels, actionsFor, pendingTasks, visibleTasks, validate, validateRules, projectTemplateCurrent, projectStartingRules } from './model.js';
// All server/user strings pass through this boundary before entering markup.
export const escapeText = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const e = escapeText;
export const isActionConfirmation = message => /^(Task created|Task edit confirmed|Current agreement acceptance confirmed|Transition confirmed|Project rules saved|Project created|Project joined|Invitation created|Token copied)/.test(message);
const button = (id, label, disabled = false, extra = '') => `<button type="button" id="${e(id)}" ${disabled ? 'disabled' : ''} ${extra}>${e(label)}</button>`;
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
  logout: '<path d="M9 4H4v16h5m4-13 5 5-5 5m-4-5h9"/>'
};
const icon = key => `<svg class="ui-icon" aria-hidden="true" focusable="false" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${iconPaths[key] || iconPaths.tasks}</svg>`;
const navigationLabel = view => ({ tasks: 'Tasks', workload: 'Workload', completed: 'Completed points', activity: 'Activity', members: 'Members', settings: 'Project settings', projects: 'Projects' })[view] || 'Workspace';
const navigationButton = (id, label, symbol, disabled = false, extra = '') => `<button type="button" id="${e(id)}" ${disabled ? 'disabled' : ''} ${extra}>${icon(symbol)}<span>${e(label)}</span></button>`;
const badge = status => `<span class="tag ${e(status.toLowerCase())}">${e(labels[status])}</span>`;
const textBlock = text => `<p class="multiline">${e(text)}</p>`;
const time = value => value ? `${new Intl.DateTimeFormat('en-PH', { timeZone: 'Asia/Manila', dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))} (Asia/Manila)` : '—';
const date = value => value || '—';
const renderedStructure = new WeakMap();
const connectionState = s => s.busy ? 'Waiting for server confirmation…' : s.loading ? 'Refreshing…' : s.stale && s.user ? 'Stale / offline: writes disabled.' : s.user ? `Last confirmed: ${time(s.confirmedAt)}` : s.logoutUnconfirmed ? 'Could not confirm your session.' : '';
function pageHeading(title, description, actions = '') {
  return `<header class="page-heading"><div><h1 id="page-title" tabindex="-1">${title}</h1>
    <p>${description}</p></div>${actions ? `<div class="actions">${actions}</div>` : ''}</header>`;
}
const pointNumber = value => String(value).replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
function field(s, name, label, { type = 'text', area = false, note = '', required = false } = {}) {
  const err = s.errors[name];
  return `<label for="field-${e(name)}">${e(label)}</label>${area ? `<textarea id="field-${e(name)}" name="${e(name)}"` : `<input id="field-${e(name)}" name="${e(name)}" type="${e(type)}" value="${e(s.draft[name])}"`} ${required ? 'required' : ''} ${err ? 'aria-invalid="true"' : ''} aria-describedby="note-${e(name)} error-${e(name)}" ${s.busy ? 'disabled' : ''}>${area ? `${e(s.draft[name])}</textarea>` : ''}<small id="note-${e(name)}">${e(note)}</small><span class="field-error" id="error-${e(name)}">${e(err)}</span>`;
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
  return `<dl class="rules-facts"><dt>Effort scale</dt><dd>${e(rules.effortScaleName)}</dd><dt>Overload threshold</dt><dd>Above ${e(rules.overloadThreshold)} points</dd><dt>Near-due window</dt><dd>${e(rules.nearDueDays)} days, inclusive</dd><dt>Time zone</dt><dd>${e(rules.projectZone)}</dd>${rules.settingsVersion == null ? '' : `<dt>Rules version</dt><dd>${e(rules.settingsVersion)}</dd>`}</dl>
    <h3>Effort points</h3>${effortPointList(rules.effortScaleExamples)}`;
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
      heading = s.form === 'login' ? 'Sign in' : 'Create account'; submit = heading;
      if (s.form === 'register') content += field(s, 'displayName', 'Display name', { required: true, note: 'Choose the name your teammates will see.' });
      content += field(s, 'email', 'Email', { type: 'email', required: true, note: s.form === 'register' ? 'Use the email address you’ll sign in with.' : 'Enter the email address for your account.' });
      content += field(s, 'password', 'Password', { type: s.showPassword ? 'text' : 'password', required: true, note: s.form === 'register' ? 'Create a password or passphrase. Spaces are allowed.' : 'Use the password you created for this account.' });
      content += button('show-password', s.showPassword ? 'Hide password' : 'Show password', false, `aria-pressed="${!!s.showPassword}"`);
      break;
    case 'settings':
      heading = s.settingsBase?.configured ? 'Edit project rules' : 'Set up project rules'; submit = 'Save project rules';
      content = `<p>Reviewed settings version ${e(s.settingsBase?.settingsVersion)} · current version ${e(s.resources.settings.data?.settingsVersion)}. Existing task agreements are preserved.</p>${!rulesAllowed ? '<p>Your draft is retained. Inspect any unknown write, then review the latest rules before saving deliberately.</p>' : ''}${button('review-settings', 'Review latest project rules', s.busy || s.loading || !!s.unknown)}<details id="current-rules" ${s.settingsNeedsReview ? 'open' : ''}><summary>Compare current project rules</summary>${rulesDetail(s.resources.settings.data)}</details>` + ruleFields(s); break;
    case 'join':
      heading = 'Join a project'; submit = 'Join project';
      content = '<p class="note">Enter the invitation token from your project leader.</p>' + field(s, 'token', 'Invitation token', { required: true }); break;
    case 'task': case 'edit':
      heading = s.form === 'edit' ? 'Edit task' : 'New task'; submit = s.form === 'edit' ? 'Save changes' : 'Create task';
      const effort = field(s, 'effortPoints', 'Agreed effort points', { required: true, note: 'Estimate task size. Use up to two decimal places (0.01–99999999.99).' });
      const due = field(s, 'dueDate', 'Due date (optional)', { type: 'date', note: 'Choose a date or leave it blank. Past dates are allowed.' });
      const examples = s.resources.settings.data?.effortScaleExamples;
      content = `<div class="form-group">${field(s, 'title', 'Task title', { required: true, note: 'Up to 200 characters.' })}
        ${field(s, 'description', 'Description (optional)', { area: true, note: 'Up to 4,000 characters.' })}
        ${field(s, 'category', 'Category (optional)', { note: 'Up to 100 characters.' })}</div>
        <fieldset id="field-assigneeIds" tabindex="-1" aria-describedby="assignee-help error-assigneeIds" ${s.errors.assigneeIds ? 'aria-invalid="true"' : ''}>
          <legend>Assignees</legend>
          <p class="note" id="assignee-help">Choose 1–6 members. Each assignee must accept the task and points.</p>
          <div class="assignee-picker">${s.board.members.map((m, i) => `<label class="check" for="assignee-${i}"><input type="checkbox" name="assigneeIds" id="assignee-${i}" value="${e(m.id)}" ${s.draft.assigneeIds?.includes(m.id) ? 'checked' : ''} ${s.busy ? 'disabled' : ''}><span>${e(m.displayName)}<small>${m.role === 'LEADER' ? 'Leader' : 'Member'} · Member ${i + 1}</small></span></label>`).join('')}</div>
          <span class="field-error" id="error-assigneeIds">${e(s.errors.assigneeIds)}</span>
        </fieldset>
        <div class="form-pair task-estimate-fields"><div class="form-group">${effort}</div><div class="form-group">${due}</div></div>
        ${examples?.length ? `<details><summary>Effort scale guide</summary>${effortPointList(examples)}</details>` : ''}`;
      if (s.form === 'edit') {
        const current = s.board.tasks.find(t => t.id === s.editBase.id);
        content += field(s, 'reason', 'Reason for agreement change', { area: true, note: 'Required if you change the title, description, points, or assignees after the task has started. Up to 1,000 characters.' });
        content += `<p>Reviewed task version ${e(s.editBase.version)} · current version ${e(current?.version)}. Saving preserves status. Real agreement changes invalidate every current assignee’s acceptance.</p>${!editAllowed ? '<p>Your draft is retained. Inspect an unknown outcome first, then review the latest terms before deliberately saving. Done must be reopened; Cancelled is immutable.</p>' : ''}${button('review-edit', 'Review latest task terms', s.busy || s.loading || !!s.unknown)}<details open><summary>Current server terms — compare with your draft</summary>${current ? `<p>${e(current.title)} · ${e(labels[current.status])} · ${e(current.effortPoints)} points · due ${e(date(current.dueDate))}</p>${textBlock(current.description)}<p>Category: ${e(current.category || '—')}</p><p>Assignees: ${e(current.assigneeAcceptances.map(a => `${a.displayName} · member ${s.board.members.findIndex(m => m.id === a.userId) + 1}`).join(', '))}</p>` : '<p>Task unavailable.</p>'}</details>`;
      } break;
    case 'reason':
      heading = actionLabels[s.reasonAction]; submit = `Confirm ${actionLabels[s.reasonAction].toLowerCase()}`;
      content = `<p>${e(s.reasonTask.title)} · ${e(labels[s.reasonTask.status])} → ${e(s.reasonAction === 'CANCEL' ? 'Cancelled' : 'In progress')} · reviewed task version ${e(s.reasonTask.version)}</p>${!reasonAllowed ? '<p>This draft cannot be confirmed against the current task. Use Refresh to review its current state/version; close the form if this action is no longer available.</p>' : ''}` + field(s, 'reason', 'Reason', { area: true, required: true, note: 'Maximum 1000 Unicode code points after trimming.' });
      if (s.reasonAction === 'CANCEL') content += '<p>Cancelled tasks earn no completed points and leave open workload. There is no Cancelled recovery.</p>';
      break;
  }
  const auth = ['login', 'register'].includes(s.form);
  const headingTag = auth ? 'h1' : 'h2';
  return `<section class="form-panel form-${s.form}" aria-labelledby="form-title"><${headingTag} id="form-title">${e(heading)}</${headingTag}><form id="active-form" novalidate>${content}<div class="actions"><button class="primary" type="submit" id="submit-form" ${s.busy || s.unknown || !reasonAllowed || !editAllowed || !rulesAllowed || (!auth && !canWrite) || s.rateUntil > Date.now() ? 'disabled' : ''}>${s.busy ? 'Waiting for server…' : e(submit)}</button>${!auth ? button('close-form', 'Cancel', s.busy) : button('auth-switch', s.form === 'login' ? 'Create account instead' : 'Back to sign in', s.busy || !!s.unknown)}</div></form></section>`;
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
  return `<ul class="project-list project-directory">${s.projects.map(project => `<li>
    <button type="button" id="project-${e(project.id)}" data-project="${e(project.id)}">
      <span class="project-row-icon">${icon('projects')}</span>
      <span class="project-row-copy"><strong>${e(project.name)}</strong>${project.description ? `<span class="multiline">${e(project.description)}</span>` : ''}</span>
      <span class="project-role">${e(project.role === 'LEADER' ? 'Leader' : 'Member')}</span>
      <span class="project-arrow" aria-hidden="true">›</span>
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
    ${s.projects.length ? projectList(s) : s.form === 'join' ? '' : empty ? `<section class="projects-empty"><span class="empty-project-icon">${icon('projects')}</span><h2>No projects yet</h2><p>Create a new project or join one using an invitation.</p>${button('new-project', 'Create project', !canWrite, 'class="primary"')}</section>` : `<p class="projects-list-status">${s.loading ? 'Loading projects…' : 'Refresh to load your projects.'}</p>`}
  </section>`;
}
function templateStep(s) {
  const { catalogue } = s.resources;
  const selected = s.selectedTemplate;
  const hasCurrentVersion = catalogue.data?.items.some(item => item.id === selected?.id);
  return `<div class="wizard-template-layout">
    <section class="template-picker">
      <h3 id="template-options-title">Available templates</h3>
      <fieldset class="template-options" id="field-template" tabindex="-1" aria-labelledby="template-options-title" aria-describedby="catalogue-status error-template">
      <div id="catalogue-status" class="note">${catalogue.loading ? 'Loading templates…' : catalogue.error ? 'Catalogue unavailable. Reload templates to continue.' : catalogue.stale ? 'Reload templates to confirm the available versions.' : ''}</div>
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
        ${selected.starterTasks.length ? `<details id="template-starters-${e(selected.id)}"><summary>Starter tasks (${selected.starterTasks.length})</summary><ol>${selected.starterTasks.map(task => `<li><strong>${e(task.title)}</strong> · ${e(task.category)} · ${e(task.effortPoints)} points${textBlock(task.description)}</li>`).join('')}</ol></details>` : '<p>No starter tasks. Add your own after creating the project.</p>'}
        <p class="note">${selected.starterTasks.length ? 'Starter tasks are assigned to you in To do and have no due dates. ' : ''}Assignees must accept the assignment and points before work starts.</p>
        <details id="template-suggestions"><summary>Starting project rules</summary>${rulesDetail({ ...projectStartingRules(selected), configured: true, projectZone: 'Asia/Manila' })}</details>
        ${!projectTemplateCurrent(s) && !catalogue.loading && !catalogue.stale && hasCurrentVersion ? `<p>Review and choose the current version to continue.</p>${button('review-template', 'Choose current version', s.busy)}` : ''}
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
    <label class="check wizard-agreement" for="field-settingsReviewed"><input type="checkbox" id="field-settingsReviewed" name="settingsReviewed" ${s.draft.settingsReviewed ? 'checked' : ''} aria-describedby="error-settingsReviewed" ${s.busy ? 'disabled' : ''}>I have reviewed and agreed to these project rules.</label>
    <span class="field-error" id="error-settingsReviewed">${e(s.errors.settingsReviewed)}</span>
    ${projectTemplateCurrent(s) ? '' : '<p class="note">Go back to Choose a template to confirm the current version.</p>'}`;
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
            `<button class="primary" type="submit" id="submit-form" ${!ready || !canWrite || s.rateUntil > Date.now() ? 'disabled' : ''}>${s.busy ? 'Creating project…' : 'Create project'}</button>` :
            `<button class="primary" type="submit" id="project-next" ${s.busy || s.unknown || (step === 2 && !current) ? 'disabled' : ''}>Next</button>`}
        </div>
      </form>
    </section>
    ${s.projectListInspected ? `<section class="wizard-inspected"><h2>Projects from your last check</h2>${s.projects.length ? projectList(s) : '<p>No projects were found.</p>'}</section>` : ''}
  </section>`;
}
function taskTable(s, tasks) {
  return `<div class="table-scroll" tabindex="0" role="region" aria-label="Task table, scroll for all columns"><table class="compact"><thead><tr>${['Task', 'Status', 'Assignees', 'Points', 'Due'].map(c => `<th scope="col"${c === 'Points' ? ' class="numeric"' : ''}>${c}</th>`).join('')}</tr></thead><tbody>${tasks.map(t => `<tr class="${s.selected === t.id ? 'selected' : ''}"><td><button type="button" class="task-title" id="task-${e(t.id)}" data-task="${e(t.id)}" aria-label="Open task ${e(t.title)}">${e(t.title)}</button><small>${t.status === 'REVIEW' ? 'Waiting for leader review' : t.assigneeAcceptances.some(a => !a.accepted) ? 'Agreement pending' : 'Current agreement accepted'}</small></td><td>${badge(t.status)}</td><td>${e(t.assigneeAcceptances.map(a => a.displayName).join(', '))}</td><td class="numeric">${e(pointNumber(t.effortPoints))}</td><td>${e(t.dueDate || 'No due date')}</td></tr>`).join('')}</tbody></table></div>`;
}
