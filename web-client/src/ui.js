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
  logout: '<path d="M9 4H4v16h5m4-13 5 5-5 5m-4-5h9"/>',
  refresh: '<path d="M20 11a8 8 0 0 0-14.5-4M4 4v4h4M4 13a8 8 0 0 0 14.5 4M20 20v-4h-4"/>',
  clock: '<circle cx="12" cy="12" r="8"/><path d="M12 8v4l2.5 2"/>',
  arrow: '<path d="M5 12h14m-6-6 6 6-6 6"/>',
  chevron: '<path d="m9 6 6 6-6 6"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.8"/>'
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
      heading = s.form === 'login' ? 'Welcome back.' : 'Create your account.'; submit = s.form === 'login' ? 'Sign in' : 'Create account';
      if (s.form === 'register') content += field(s, 'displayName', 'Display name', { required: true, note: 'Choose the name your teammates will see.' });
      content += field(s, 'email', 'Email', { type: 'email', required: true, placeholder: 'you@example.com', note: s.form === 'register' ? 'Use the email address you’ll sign in with.' : 'Enter the email address for your account.' });
      const toggle = `<button type="button" id="show-password" class="inline-toggle" aria-pressed="${!!s.showPassword}">${icon('eye')}<span>${s.showPassword ? 'Hide password' : 'Show password'}</span></button>`;
      content += field(s, 'password', 'Password', { type: s.showPassword ? 'text' : 'password', required: true, trailing: toggle, placeholder: 'Enter your password', note: s.form === 'register' ? 'Create a password or passphrase. Spaces are allowed.' : 'Use the password you created for this account.' });
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
  const submitButton = `<button class="primary" type="submit" id="submit-form" ${s.busy || s.unknown || !reasonAllowed || !editAllowed || !rulesAllowed || (!auth && !canWrite) || s.rateUntil > Date.now() ? 'disabled' : ''}>${s.busy ? 'Waiting for server…' : e(submit)}</button>`;
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
  return `<div class="table-scroll" tabindex="0" role="region" aria-label="Task table, scroll for all columns"><table class="compact"><thead><tr>${['Task', 'Status', 'Assignee', 'Points', 'Due date'].map(c => `<th scope="col"${c === 'Points' ? ' class="numeric"' : ''}>${c}</th>`).join('')}<th scope="col" class="row-arrow"><span class="sr-only">Open</span></th></tr></thead><tbody>${tasks.map(t => `<tr class="${s.selected === t.id ? 'selected' : ''}"><td><button type="button" class="task-title" id="task-${e(t.id)}" data-task="${e(t.id)}" aria-label="Open task ${e(t.title)}">${e(t.title)}</button><small${t.status !== 'REVIEW' && t.assigneeAcceptances.some(a => !a.accepted) ? ' class="agreement-pending"' : ''}>${t.status === 'REVIEW' ? 'Waiting for leader review' : t.assigneeAcceptances.some(a => !a.accepted) ? 'Agreement pending' : 'Current agreement accepted'}</small></td><td>${badge(t.status)}</td><td><span class="assignee-list">${t.assigneeAcceptances.map(a => `<span class="assignee-chip"><span class="person-initial" aria-hidden="true">${e(initial(a.displayName))}</span>${e(a.displayName)}</span>`).join('')}</span></td><td class="numeric">${e(pointNumber(t.effortPoints))}</td><td>${e(t.dueDate || 'No due date')}</td><td class="row-arrow" aria-hidden="true">${icon('chevron')}</td></tr>`).join('')}</tbody></table></div>`;
}
function board(s, tasks) {
  const columns = statuses.map(status => tasks.filter(t => t.status === status));
  const all = columns.flat();
  const roving = all.some(t => t.id === s.boardFocus) ? s.boardFocus : all[0]?.id;
  return `<p id="board-help" class="sr-only">Use arrow keys to move, Home or End to jump within a column, and Enter or Space to open a task.</p>
    <div class="board-scroll" tabindex="0" role="region" aria-label="Task Board, scroll for all columns">
      <div class="board" aria-describedby="board-help">${columns.map((items, column) => {
        const status = statuses[column];
        return `<section class="board-lane lane-${status.toLowerCase()}" aria-label="${labels[status]}">
          <h2><span class="lane-dot" aria-hidden="true"></span>${labels[status]}<small>${items.length}</small></h2>
          ${items.length ? items.map(task => `<button type="button" class="card ${s.selected === task.id ? 'selected' : ''}"
            id="task-${e(task.id)}" data-task="${e(task.id)}" data-card="true" tabindex="${task.id === roving ? 0 : -1}" aria-label="Open task ${e(task.title)}">
            <strong>${e(task.title)}</strong>
            <span class="card-assignees">${e(task.assigneeAcceptances.map(a => a.displayName).join(', '))}</span>
            <span class="card-metadata"><span>${e(pointAmount(task.effortPoints))}</span><span>${e(task.dueDate ? `Due ${task.dueDate}` : 'No due date')}</span></span>
            ${task.assigneeAcceptances.some(a => !a.accepted) ? '<small class="agreement-pending">Agreement pending</small>' : ''}
          </button>`).join('') : '<p class="empty">No tasks in this stage.</p>'}
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
        <dt>Points</dt><dd>${e(pointNumber(task.effortPoints))}</dd><dt>Due</dt><dd>${e(task.dueDate || 'No due date')}</dd><dt>Task version</dt><dd>${e(task.version)}</dd></dl>
    </section>
    <section class="agreement inspector-section">
      <div class="section-line"><h3>Assignment agreement</h3><span class="note">Version ${e(task.agreementVersion)}</span></div>
      <p class="agreement-progress"><strong>${accepted} of ${task.assigneeAcceptances.length} accepted</strong><span>All current assignees must accept.</span></p>
      <ul class="agreement-register">${task.assigneeAcceptances.map((a, i) => `<li><span class="person-initial" aria-hidden="true">${e(initial(a.displayName))}</span><div><strong>${e(a.displayName)}</strong><small>Assignee ${i + 1}${a.accepted ? ` · ${e(time(a.acceptedAtUtc))}` : ''}</small></div><span class="${a.accepted ? 'acceptance-ok' : 'agreement-pending'}">${a.accepted ? 'Accepted' : 'Pending'}</span></li>`).join('')}</ul>
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
  return `${pageHeading('Tasks', 'Manage assignments, track progress, and review team work.', leader ? button('new-task', 'New task', !canWrite, 'class="primary"') : '')}
    <div class="task-controls">
      <div class="task-view-row">
        <span class="toolbar-label" aria-hidden="true">Task view</span><div class="toolbar" role="group" aria-label="Task view">${button('view-table', 'Table', false, `aria-pressed="${s.mode === 'table'}"`)}${button('view-board', 'Board', false, `aria-pressed="${s.mode === 'board'}"`)}</div>
      </div>
      <div class="filters"><label>Assignee<select id="assignment"><option value="all">All assignees</option><option value="mine" ${s.filters.assignment === 'mine' ? 'selected' : ''}>Assigned to me</option>${s.board.members.map((m, i) => `<option value="${e(m.id)}" ${s.filters.assignment === m.id ? 'selected' : ''}>${e(m.displayName)} · member ${i + 1}</option>`).join('')}</select></label><label>Status<select id="status"><option value="active" ${s.filters.status === 'active' ? 'selected' : ''}>All except Cancelled</option><option value="all" ${s.filters.status === 'all' ? 'selected' : ''}>All including Cancelled</option>${[...statuses, 'CANCELLED'].map(st => `<option value="${st}" ${s.filters.status === st ? 'selected' : ''}>${labels[st]}</option>`).join('')}</select></label><label class="search">Search<input type="search" id="search" value="${e(s.filters.search)}" placeholder="Title, description or assignee"></label>${button('clear-filters', 'Clear filters')}</div>
    </div>
    ${pending.length ? `<button type="button" id="pending" class="pending-banner">${icon('clock')}<strong>${pending.length} ${pending.length === 1 ? 'assignment' : 'assignments'} awaiting your acceptance</strong><span>Review assignments ${icon('arrow')}</span></button>` : ''}
    <p id="task-count" class="note">${tasks.length} / ${s.board.totalTasks} tasks</p>
    ${!tasks.length ? `<div class="empty">${s.board.totalTasks ? 'No tasks match these filters.' : 'No tasks yet. The leader can create the first task.'}</div>` : s.mode === 'table' ? taskTable(s, tasks) : board(s, tasks)}`;
}
function members(s, canWrite) {
  const members = s.board.members;
  const leader = s.board.project.role === 'LEADER';
  const actions = leader ? button('create-invite', 'Create invitation', !canWrite || members.length >= 6, 'class="primary"') : '';
  return `${pageHeading('Members', 'See who is on this project and who leads it.', actions)}
    ${s.invitation ? `<section class="invitation-display"><h2>Project invitation</h2>
      <p>Share this single-use token. It expires ${e(time(s.invitation.expiresAtUtc))}.</p>
      <label for="invitation-token">Invitation token</label><input id="invitation-token" readonly value="${e(s.invitation.token)}">
      <div class="actions">${button('copy-invite', 'Copy invitation token')}${button('clear-invite', 'Clear token display')}</div></section>` : ''}
    <section class="content-panel" aria-labelledby="roster-title">
      <div class="section-line"><h2 id="roster-title">Project team</h2><span class="note">${members.length} of 6 places filled</span></div>
      <ul class="member-list">${members.map((member, i) => `<li>
        <span class="person-initial" aria-hidden="true">${e(initial(member.displayName))}</span>
        <div><strong>${e(member.displayName)}${member.id === s.user.id ? ' (you)' : ''}</strong><small>Member ${i + 1}</small></div>
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
      }).join('')}</ol>` : `<p class="empty">${s.loading ? 'Loading activity…' : 'Project changes will appear here.'}</p>`}
      ${s.eventCursor ? button('older-activity', 'Load older activity', s.loading) : ''}
    </section>`;
}
function resourceStatus(s, key) {
  const state = s.resources[key];
  const settingsRequired = state.error?.code === 'SETTINGS_REQUIRED';
  const name = { settings: 'project rules', contribution: 'completed points', workload: 'workload' }[key];
  let message = 'Up to date.';
  if (state.loading) message = `Loading ${name}…`;
  else if (settingsRequired) message = s.board.project.role === 'LEADER'
    ? 'Set up rules in Project settings to view Workload.'
    : 'The project leader must set up project rules before Workload is available.';
  else if (state.error) message = `Could not load ${name}.${state.data ? ' Showing the last available result, which may be stale.' : ''}`;
  else if (state.stale) message = state.data ? `Stale result. ${state.reason || 'Refresh to check for updates.'}` : `${name[0].toUpperCase()}${name.slice(1)} unavailable. Refresh to try again.`;
  return `<div class="result-state${message === 'Up to date.' ? ' sr-only' : ''}" id="state-${key}" role="status" aria-live="polite">${e(message)}</div>${!state.loading && !settingsRequired && (state.error || state.stale) ? button(`retry-resource-${key}`, 'Try again', s.busy) : ''}`;
}
function settingsPage(s, canWrite) {
  const state = s.resources.settings;
  const rules = state.data;
  const leader = s.board.project.role === 'LEADER';
  const actions = leader && s.form !== 'settings' ? button('edit-rules', rules?.configured ? 'Edit rules' : 'Set up rules', !canWrite || state.stale || state.loading, 'class="primary"') : '';
  return `${pageHeading('Project settings', 'Set the effort scale, workload warnings, and due-date rules.', actions)}
    ${resourceStatus(s, 'settings')}
    ${!leader ? '<p class="note">Read-only project rules</p>' : ''}
    ${s.form === 'settings' ? form(s, canWrite) : rules?.configured ? `<div class="settings-overview">
      <section class="content-panel"><h2>Effort scale</h2><p class="note">${e(rules.effortScaleName)} · Estimate task size</p>
        ${effortPointList(rules.effortScaleExamples)}</section>
      <section class="content-panel"><h2>Workload and deadlines</h2><dl class="settings-facts">
        <dt>Overload threshold</dt><dd>Above ${e(pointAmount(rules.overloadThreshold))}</dd>
        <dt>Near-due window</dt><dd>${e(rules.nearDueDays)} days after today, plus today</dd>
        <dt>Project time zone</dt><dd>${e(rules.projectZone)}</dd>
        <dt>Rules version</dt><dd>${e(rules.settingsVersion)}</dd>
      </dl></section>
    </div>` : rules ? `<section class="content-panel">${rulesDetail(rules)}</section>` : ''}`;
}

function workloadMetrics(counts) {
  return `<dl class="workload-metrics" aria-label="Project totals">${[
    ['Open tasks', counts.openTasks], ['In review', counts.reviewTasks],
    ['Overdue', counts.overdueTasks], ['Near due', counts.nearDueTasks]
  ].map(([label, count]) => `<div><dt>${label}</dt><dd>${e(count)}</dd></div>`).join('')}</dl>`;
}

function workloadGauge(member, threshold) {
  const total = Number(member.remainingPoints);
  const limit = Number(threshold);
  return `<span class="workload-value">${e(member.remainingPointsDisplay)}
    <meter class="workload-meter${member.overloaded ? ' is-over' : ''}" aria-hidden="true" min="0" max="${e(limit)}" value="${e(Math.min(total, limit))}"></meter></span>`;
}

function reportPanel(s, canWrite) {
  return `<section class="report-panel" aria-label="Professor report"><div class="report-copy"><h2>Professor report</h2><p>Export project details, tasks, completed points, and activity to Excel.</p></div>${s.board.project.role === 'LEADER' ? `<div class="actions">${button('export-report', 'Export professor report (.xlsx)', !canWrite || s.exportState?.running, 'aria-describedby="report-status report-help"')}${s.exportState?.running ? button('cancel-report', 'Cancel report receipt', false, 'aria-describedby="report-help"') : ''}</div>` : '<p>Only the project leader can export.</p>'}<p id="report-status">${e(s.exportState?.message || (s.board.project.role === 'LEADER' ? 'Ready to export.' : ''))}</p>${s.exportState?.running ? `<p id="report-time">${s.exportState.remaining} seconds remaining for receipt</p>` : ''}${s.board.project.role === 'LEADER' ? '<p class="note" id="report-help">Your browser handles saving. If you cancel, report generation may continue on the server.</p>' : ''}</section>`;
}

function summaryPage(s, key, canWrite) {
  const state = s.resources[key], data = state.data, completed = key === 'contribution';
  const title = completed ? 'Completed points' : 'Workload';
  const actions = s.board.project.role === 'LEADER'
    ? button('edit-rules', data?.rules ? 'Edit rules' : 'Set up rules', !canWrite || s.resources.settings.stale)
    : '';
  const description = completed ? 'See each member’s share of effort points from approved tasks.' : 'Track remaining points and upcoming or overdue tasks.';
  let content = `${pageHeading(title, description, data && !completed ? actions : '')}${resourceStatus(s, key)}`;
  if (!data) return content + (completed ? reportPanel(s, canWrite) : '');
  content += `<p class="note">Updated <time data-result-time="${key}" datetime="${e(data.snapshot.snapshotAtUtc)}">${e(time(data.snapshot.snapshotAtUtc))}</time>${completed ? '' : ` · Due dates as of ${e(data.projectToday)}`}</p>`;
  if (completed) {
    content += `<p class="completed-total"><span>Total completed points</span><strong>${e(pointNumber(data.totalCompletedPointsDisplay))}</strong></p>`;
  } else {
    content += workloadMetrics(data.projectCounts);
    content += `<p class="note">Warning above ${e(pointAmount(data.rules.overloadThreshold))} per member, before rounding.</p>`;
  }
  if (completed && data.empty) content += '<p class="empty" id="no-completed">No completed tasks yet.</p>';
  else content += `<div class="table-scroll" tabindex="0" role="region" aria-label="${title} table, scroll for all columns"><table class="summary-table${completed ? ' contribution-table' : ''}"><caption>${completed ? 'Completed effort-points share' : 'Workload by member'}</caption><thead><tr>${(completed ? ['Member', 'Completed points', 'Completed effort-points share'] : ['Member', 'Remaining points', 'Overdue', 'Near due', 'Points warning']).map((label, i) => `<th scope="col"${i > 0 && (completed || i < 4) ? ' class="numeric"' : ''}>${label}</th>`).join('')}</tr></thead><tbody>${data.members.map((m, i) => `<tr data-member="${e(m.userId)}"><th scope="row">${e(m.displayName)}<small>Member ${i + 1}</small></th>${completed ? `<td class="numeric">${e(m.completedPointsDisplay)}</td><td class="numeric">${e(m.sharePercentDisplay)}% <meter aria-hidden="true" min="0" max="100" value="${e(m.sharePercentDisplay)}"></meter></td>` : `<td class="numeric">${workloadGauge(m, data.rules.overloadThreshold)}</td><td class="numeric">${e(m.overdueCount)}</td><td class="numeric">${e(m.nearDueCount)}</td><td>${m.overloaded ? '<span class="agreement-pending">Over threshold</span>' : 'None'}</td>`}</tr>`).join('')}</tbody></table></div>`;
  if (completed) {
    content += reportPanel(s, canWrite);
    content += `<details id="result-tasks"><summary>Completed task allocations</summary>
      ${data.allocations.length ? `<ul class="breakdown-list">${data.allocations.map(task => `<li>
        <div class="section-line"><strong>${e(task.title)}</strong><span class="note">${e(pointAmount(task.effortPoints))} total</span></div>
        <span>${e(task.assigneeIds.map(id => {
          const index = data.members.findIndex(member => member.userId === id);
          return index < 0 ? 'Member' : `${data.members[index].displayName} (Member ${index + 1})`;
        }).join(', '))}</span>
        <small>Each assignee receives ${e(task.perAssigneePoints)} points · Task ID ${e(task.taskId)}</small>
      </li>`).join('')}</ul>` : '<p>No completed task allocations.</p>'}</details>`;
  } else {
    const dateLabels = { OVERDUE: 'Overdue', NEAR_DUE: 'Near due', LATER: 'Due later' };
    content += `<details id="result-tasks"><summary>Task breakdown</summary>
      ${data.tasks.length ? `<ul class="breakdown-list">${data.tasks.map(task => `<li>
        <div class="section-line"><strong>${e(task.title)}</strong>${badge(task.status)}</div>
        <div class="breakdown-meta">
          <span>${task.dueDate ? `Due ${e(task.dueDate)} · ${e(dateLabels[task.dateState])}` : 'No due date'}</span>
          ${task.status === 'REVIEW' ? '' : `<span>${e(pointAmount(task.perAssigneeRemainingPoints))} per assignee</span>`}
        </div>
        <span>${e(task.assigneeIds.map(id => {
          const index = data.members.findIndex(member => member.userId === id);
          return index < 0 ? id : `${data.members[index].displayName} (Member ${index + 1})`;
        }).join(', '))}</span>
        <small>Task ID ${e(task.taskId)}</small>
      </li>`).join('')}</ul>` : '<p>No open tasks or tasks in Review.</p>'}</details>`;
  }
  if (completed && actions) content += actions;
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
export function markup(s) {
  const canWrite = !!s.user && !s.stale && !s.busy && !s.loading && !s.revalidating && !s.unknown;
  const side = `<aside class="sidebar"><div class="brand-group"><img class="brand-mark" src="/brand-logo.png" width="80" height="80" alt=""><p class="brand">Owlibi</p>${s.board ? navigationButton('workspace-menu', 'Menu', 'menu', false, `aria-expanded="${s.navOpen}" aria-controls="project-navigation"`) : ''}</div>${s.user ? `<div class="project-context">${navigationButton('projects', 'All projects', 'projects', false, s.page === 'projects' ? 'aria-current="page"' : '')}${s.board ? `<p class="project-name">${e(s.board.project.name)}</p>` : ''}</div>${s.board ? `<nav id="project-navigation" aria-label="Project navigation"${s.navOpen ? ' class="is-open"' : ''}>${['tasks', 'workload', 'completed', 'activity', 'members', 'settings'].map(view => navigationButton(`nav-${view}`, navigationLabel(view), view, false, s.page === view ? 'aria-current="page"' : '')).join('')}</nav>` : ''}<div class="account-block"><span class="account-avatar" aria-hidden="true">${e(initial(s.user.displayName))}</span><span class="account-text"><span id="account-context">${e(s.user.displayName)}</span><span id="role-context">${s.board && s.page !== 'projects' ? (s.board.project.role === 'LEADER' ? 'Project leader' : 'Project member') : 'Your account'}</span></span></div>${navigationButton('logout', 'Sign out', 'logout', s.busy)}` : ''}<div class="theme-controls">${button('theme-light', 'Light', false, `aria-pressed="${s.theme === 'light'}"`)}${button('theme-dark', 'Dark', false, `aria-pressed="${s.theme === 'dark'}"`)}</div></aside>`;
  const place = !s.user ? '' : s.page === 'projects' && s.form === 'project' ? '<a class="breadcrumb-link" aria-hidden="true">Projects</a><span class="breadcrumb-separator" aria-hidden="true">›</span><span class="breadcrumb-current">Create project</span>' : s.page === 'projects' || !s.board ? '<span class="breadcrumb-current">Projects</span>' : `<span class="breadcrumb-link" aria-hidden="true">Projects</span><span class="breadcrumb-separator" aria-hidden="true">›</span><span class="breadcrumb-current breadcrumb-project">${e(s.board.project.name)}</span>`;
  const status = s.user ? `<div class="header-status"><p id="connection-state" class="${s.stale || s.logoutUnconfirmed ? 'is-stale' : ''}">${e(connectionState(s))}</p><button type="button" id="refresh" class="icon-button" aria-label="Refresh" ${s.busy || s.loading || s.revalidating ? 'disabled' : ''}>${icon('refresh')}</button></div>` : '';
  const chrome = `<header class="app-header" aria-label="Workspace context"><div class="workspace-location">${place}</div><span class="workspace-mode sr-only">${s.user && s.board ? s.board.project.role === 'LEADER' ? 'Leader workspace' : 'Member workspace' : 'Project workspace'}</span>${status}</header>`;
  let content = '', detail = '';
  if (!s.user) content = s.revalidating ? '<h1 id="page-title" tabindex="-1">Checking session…</h1>' : form(s, true);
  else if (s.page === 'projects') {
    content = s.form === 'project' ? projectWizard(s, canWrite) : projectsPage(s, canWrite);
  } else if (!s.board) content = `<h1 id="page-title" tabindex="-1">${s.loading ? 'Loading project…' : 'Project unavailable'}</h1>`;
  else {
    content = s.page === 'tasks' ? tasksPage(s, canWrite) : s.page === 'members' ? members(s, canWrite) : s.page === 'settings' ? settingsPage(s, canWrite) : s.page === 'workload' ? summaryPage(s, 'workload', canWrite) : s.page === 'completed' ? summaryPage(s, 'contribution', canWrite) : activity(s);
    if (s.page === 'tasks') detail = inspector(s, visibleTasks(s.board.tasks, s.filters, s.user.id), canWrite);
  }
  return `${side}${chrome}<main class="workspace" id="main-content" tabindex="-1"><div class="connection"><p id="feedback"${['Signed in.', 'Session revalidated.'].includes(s.notice) ? ' class="sr-only"' : isActionConfirmation(s.notice) ? ' class="feedback-success"' : ''}>${e(s.notice)}</p>${!s.user && s.logoutUnconfirmed ? `<p id="connection-state">${e(connectionState(s))}</p>${button('refresh', 'Recheck server session', s.busy || s.loading || s.revalidating, 'class="quiet-button"')}` : ''}${s.unknown ? `<p>Write outcome unknown. Inspect the authoritative view before deliberate resubmission. ${s.unknown === 'invitation' ? 'A lost invitation token cannot be recovered; choose deliberately whether to create another.' : ''}</p>${button('inspect-unknown', 'Inspect authoritative state', s.loading)}` : ''}</div>${content}</main>${detail ? `<aside class="inspector" aria-label="Task inspector">${detail}</aside>` : ''}${!s.user ? `<aside class="auth-art" aria-hidden="true">${authArt}</aside>` : ''}`;
}
export function capturePosition(root) {
  return { document: [window.scrollX, window.scrollY], regions: [...root.querySelectorAll('.workspace,.inspector,.table-scroll,.board-scroll')].map(el => [el.className, el.scrollTop, el.scrollLeft]) };
}
export function restorePosition(root, position) {
  if (!position) return;
  for (const [cls, top, left] of position.regions) { const el = [...root.querySelectorAll('[class]')].find(el => el.className === cls); if (el) { el.scrollTop = top; el.scrollLeft = left; } }
  window.scrollTo(...position.document);
}
export function render(root, s, { backgroundRefresh = false, preserveUnchanged = false } = {}) {
  // Unchanged polls retain DOM identity, including VoiceOver's reading cursor.
  // Writes still wait in main.js; stale/permission gates remain authoritative.
  const view = backgroundRefresh ? { ...s, loading: false, resources: Object.fromEntries(Object.entries(s.resources).map(([key, value]) => [key, { ...value, loading: value.data ? false : value.loading }])) } : s;
  // Draft inputs already update their live DOM values without a full render.
  const structure = markup({ ...view, confirmedAt: null, draft: {}, resources: Object.fromEntries(Object.entries(view.resources).map(([key, value]) => [key, { ...value, data: value.data?.snapshot ? { ...value.data, snapshot: { ...value.data.snapshot, snapshotAtUtc: null } } : value.data }])) });
  const updateConnection = () => {
    const connection = document.getElementById('connection-state');
    if (connection && connection.textContent !== connectionState(s)) connection.textContent = connectionState(s);
    const refresh = document.getElementById('refresh');
    if (refresh) refresh.disabled = s.busy || s.loading || s.revalidating;
    for (const el of root.querySelectorAll('[data-result-time]')) { const at = s.resources[el.dataset.resultTime].data?.snapshot.snapshotAtUtc; if (at) { el.textContent = time(at); el.dateTime = at; } }
  };
  if (backgroundRefresh && preserveUnchanged && renderedStructure.get(root) === structure) { updateConnection(); return; }
  const active = document.activeElement, focusId = active?.id;
  const selection = active && 'selectionStart' in active ? [active.selectionStart, active.selectionEnd] : null;
  const position = capturePosition(root);
  const openDetails = [...root.querySelectorAll('details[id][open]')].map(el => el.id);
  const announcement = document.getElementById('announcement');
  const message = s.busy ? 'Waiting for server confirmation.' : s.loading && !backgroundRefresh ? 'Refreshing project data.' : s.notice;
  if (announcement && announcement.textContent !== message) announcement.textContent = message;
  root.innerHTML = markup(view);
  for (const id of openDetails) { const el = document.getElementById(id); if (el?.tagName === 'DETAILS') el.open = true; }
  renderedStructure.set(root, structure);
  updateConnection();
  document.documentElement.dataset.theme = s.theme;
  root.classList.toggle('with-inspector', !!root.querySelector('.inspector'));
  root.classList.toggle('is-signed-out', !s.user);
  restorePosition(root, position);
  const restored = focusId && document.getElementById(focusId);
  if (restored && !restored.disabled) { restored.focus({ preventScroll: true }); if (selection && typeof restored.setSelectionRange === 'function' && ['text', 'search', 'password', 'textarea'].includes(restored.type)) restored.setSelectionRange(...selection); }
}
