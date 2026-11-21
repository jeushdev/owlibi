import { Api, ApiError, ReportDownload, REPORT_TIMEOUT } from './api.js';
import { Activity } from './session.js';
import { editFields, editFieldChanged, rebaseEdit, ruleFields, ruleFieldChanged, rebaseRules, Generations, defaultFilters, pendingTasks, visibleTasks, reasonActions, boardNeighbor, statuses, validate, editDraft, agreementChanged, editDirty, rulesDraft, validateRules, rulesPatch, summaryMatches, workloadExpiryDelay, ReadState, projectTemplateCurrent, projectStartingRules } from './model.js';
import { render, renderStatus, noticeKind, capturePosition, restorePosition, isActionConfirmation } from './ui.js';
const root = document.querySelector('#app'), api = new Api(), generations = new Generations(), downloads = new ReportDownload();
const emptyPrivate = () => ({ resources: { catalogue: new ReadState(), settings: new ReadState(), contribution: new ReadState(), workload: new ReadState() }, selectedTemplate: null, templateNeedsReview: false, projectStep: 1, projectListInspected: false, settingsBase: null, settingsNeedsReview: false, formInitial: null, user: null, csrf: null, page: 'projects', navOpen: false, projectId: null, projects: [], projectCursor: null, board: null, events: [], eventCursor: null, selected: null, boardFocus: null, form: 'login', draft: {}, errors: {}, invitation: null, unknown: null, editBase: null, editNeedsReview: false, reasonTask: null, reasonAction: null, loading: false, busy: false, stale: true, confirmedAt: null, revalidating: false, filters: defaultFilters(), rateUntil: 0, readFailureNotice: null, checking: false, offline: false, projectUnavailable: false, busyId: null, workloadTab: 'all', reviewedLatest: false });
// Default to the OS theme. The choice is kept in memory only: public modules never touch browser storage.
const preferredTheme = () => { try { return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'; } catch { return 'light'; } };
const s = { ...emptyPrivate(), notice: '', noticeKind: '', noticeFor: '', theme: preferredTheme(), mode: 'table', showPassword: false, logoutUnconfirmed: false, booting: true, bootSlow: false, bootFailed: false };
const emptyExport = () => ({ running: false, message: '', bytes: 0, remaining: REPORT_TIMEOUT / 1000, retryUntil: 0 });
s.exportState = emptyExport();
let exportRun = 0, exportController = null, exportTimer = null;
let dayTimer = null;
let poll = null, reading = null, lifecycle = 0, channel = null, returnFocus = null, pendingPrivate = null, pendingFocus = null, pendingReturnFocus = null;
let toastTimer = null, toastVersion = 0, bootTimer = null, lastInvoker = null, signedOutCheckedAt = 0;
// Every assignment counts as a new notice (even the same text), so a repeated message restarts its timer.
let noticeText = s.notice, noticeVersion = 0;
Object.defineProperty(s, 'notice', { get: () => noticeText, set: value => { noticeText = value; noticeVersion++; }, enumerable: true, configurable: true });
// Success/info toasts fade out on their own; errors and warnings wait for the close button.
function scheduleToast() {
  if (noticeVersion === toastVersion) return;
  toastVersion = noticeVersion; clearTimeout(toastTimer); toastTimer = null;
  if (!s.notice || !['success', 'info'].includes(noticeKind(s))) return;
  const version = noticeVersion;
  toastTimer = setTimeout(() => { if (noticeVersion === version) { s.notice = ''; toastVersion = noticeVersion; root.querySelector('.toast-region')?.replaceChildren(); } }, 4500);
}
const clearProblemNotice = () => { if (['error', 'warning'].includes(noticeKind(s))) s.notice = ''; };
const draw = (preserveUnchanged = false) => { scheduleToast(); render(root, s, { backgroundRefresh: reading?.background === true, preserveUnchanged }); };
// Re-renders only when visible structure changed, so background checks never flash the page.
const quietDraw = () => { scheduleToast(); render(root, s, { backgroundRefresh: true, preserveUnchanged: true }); };
function setNotice(text, kind) { s.notice = text; s.noticeFor = text; s.noticeKind = kind; }
function scheduleBootHint() {
  clearTimeout(bootTimer); s.bootSlow = false;
  bootTimer = setTimeout(() => { if (!s.user && (s.booting || s.revalidating) && !s.bootFailed) { s.bootSlow = true; draw(); } }, 3000);
}
const focus = id => document.getElementById(id)?.focus();
const fallbackFocus = () => document.getElementById('close-inspector') ? 'close-inspector' : 'page-title';
const isVisible = () => document.visibilityState === 'visible';
const announce = text => { s.notice = text; draw(); };
function dirty() {
  if (s.form === 'settings') {
    if (!s.settingsBase) return true;
    const patch = rulesPatch(s.settingsBase, s.draft);
    return !patch || Object.keys(patch).length > 1;
  }
  if (s.form === 'project') {
    return !!s.draft.name || !!s.draft.description || s.draft.settingsReviewed ||
      (s.selectedTemplate && s.selectedTemplate.id !== 'blank') ||
      (s.formInitial && JSON.stringify(s.draft) !== s.formInitial);
  }
  if (s.form === 'edit') return editDirty(s.editBase, s.draft);
  return !!s.form && Object.values(s.draft).some(v => Array.isArray(v) ? v.length : !!v);
}
const guardNavigation = () => !dirty() || window.confirm('Discard this unsaved form?');
function stopPoll() { clearTimeout(poll); poll = null; }
function startPoll() {
  stopPoll();
  if (!s.user || !isVisible() || s.revalidating || s.busy || s.exportState.running) return;
  poll = setTimeout(async () => { await refresh({ background: true }); startPoll(); }, 10000);
}
function cancelReads({ keepCatalogue = false } = {}) { clearTimeout(dayTimer); dayTimer = null; for (const [key, state] of Object.entries(s.resources)) if (!keepCatalogue || key !== 'catalogue') state.invalidate(); }
// Hiding the tab only stops reads that are in flight; finished results stay current.
function pauseReads() { for (const state of Object.values(s.resources)) if (state.loading) state.invalidate(); }
function cancelExport(message = '') {
  exportRun++; exportController?.abort(new ApiError(message ? 'REPORT_CANCELLED' : 'OBSOLETE', message || 'Export context ended.'));
  exportController = null; clearInterval(exportTimer); exportTimer = null; downloads.clear();
  s.exportState = { ...emptyExport(), message };
}
async function exportReport() {
  if (s.exportState.running || !s.user || !s.board || s.board.project.role !== 'LEADER' || s.stale || s.loading || s.revalidating || s.busy || s.unknown) return;
  const run = ++exportRun, own = lifecycle, project = s.projectId, epoch = s.user.sessionEpoch, invoker = document.activeElement?.id;
  const valid = () => run === exportRun && own === lifecycle && s.user?.sessionEpoch === epoch && s.projectId === project && s.page === 'completed' && s.board?.project.role === 'LEADER' && isVisible();
  exportController = new AbortController(); const signal = exportController.signal, start = performance.now();
  stopPoll(); s.exportState = { ...emptyExport(), running: true, message: 'Generating report…' }; s.notice = s.exportState.message; draw(); focus('cancel-report');
  exportTimer = setInterval(() => {
    if (!valid()) return;
    s.exportState.remaining = Math.max(0, Math.ceil((REPORT_TIMEOUT - (performance.now() - start)) / 1000));
    const clock = document.getElementById('report-time'); if (clock) clock.textContent = `${s.exportState.remaining} seconds left`;
  }, 1000);
  try {
    let report = await api.professorReport(project, { epoch, valid, signal, progress: bytes => { if (valid()) s.exportState.bytes = bytes; } });
    if (!valid()) return;
    // A cookie can change in another tab without BroadcastChannel. Check identity again before handoff.
    await api.request('/api/v1/auth/sessions/current', { epoch, valid, signal, retry: false });
    if (!valid()) return;
    if (!downloads.offer(report, valid)) return;
    report = null; s.exportState.message = 'Report ready — your browser will save it.'; s.notice = s.exportState.message;
  } catch (error) {
    if (!valid() || error.code === 'OBSOLETE' || error.code === 'REPORT_CANCELLED') return;
    s.exportState.message = error.message || 'Couldn’t export the report. Try again.';
    if (error.code === 'REPORT_BUSY') { s.exportState.retryUntil = Date.now() + (error.retryAfter || 5) * 1000; s.exportState.message += ` Try again in ${error.retryAfter || 5} seconds.`; }
    if (['UNAUTHENTICATED', 'SESSION_CHANGED', 'SESSION_CONTEXT_REQUIRED'].includes(error.code)) { await handleError(error); return; }
    setNotice(s.exportState.message, 'error');
    if (error.status === 403) { s.board.project.role = 'MEMBER'; s.stale = true; s.notice += ' Refresh to check your permissions.'; }
    if (error.status === 404) { await handleError(error); return; }
  } finally {
    if (run === exportRun) {
      clearInterval(exportTimer); exportTimer = null; exportController = null; s.exportState.running = false;
      const returnToInvoker = document.activeElement?.id === 'cancel-report' || document.activeElement === document.body;
      draw(); if (returnToInvoker) focus(document.getElementById(invoker)?.disabled === false ? invoker : 'page-title'); startPoll();
    }
  }
}
function invalidateSummaries(reason) { clearTimeout(dayTimer); for (const key of ['contribution', 'workload']) s.resources[key].invalidate(reason); }
function clearPrivate(message) {
  cancelExport();
  cancelReads();
  lifecycle++; generations.change('session'); stopPoll(); activity.reset(); reading = null; pendingPrivate = null; pendingFocus = null; returnFocus = null; pendingReturnFocus = null; api.blocked.clear();
  Object.assign(s, emptyPrivate(), { notice: message, showPassword: false, booting: false, bootFailed: false }); draw();
}
function broadcast(kind, epoch) { channel?.postMessage({ kind, epoch }); }
try {
  channel = new BroadcastChannel('owlibi-session-lifecycle');
  channel.onmessage = event => {
    const { kind, epoch } = event.data ?? {};
    if (!['login', 'logout', 'expiry'].includes(kind) || typeof epoch !== 'string') return;
    if (kind === 'login' && epoch !== s.user?.sessionEpoch) { clearPrivate('Your account changed in another tab. Checking who you are…'); void revalidate(false); }
    else if (['logout', 'expiry'].includes(kind) && (!s.user || s.user.sessionEpoch === epoch)) clearPrivate(kind === 'logout' ? 'You signed out in another tab. Sign in again to continue.' : 'Your session ended in another tab. Sign in again.');
  };
} catch { /* Current-session checks remain authoritative when tab notices are unavailable. */ }
async function bootstrap(valid) {
  const own = lifecycle; valid ??= () => lifecycle === own;
  const token = await api.request('/api/v1/auth/sessions', { epoch: s.user?.sessionEpoch, valid: () => valid() && isVisible() });
  if (valid()) s.csrf = token;
}
const confirmedRecently = () => !!s.confirmedAt && Date.now() - Date.parse(s.confirmedAt) < 30000;
function pageNeedsRead() {
  if (!s.projectId) return false;
  const key = s.page === 'completed' ? 'contribution' : s.page === 'workload' ? 'workload' : null;
  return s.resources.settings.stale || (!!key && s.resources[key].stale);
}
// Tab return: keep the current view on screen and check in the background. Only a server answer
// that the session is gone or changed clears private state; network trouble marks the view stale.
async function silentRevalidate({ force = false } = {}) {
  if (!s.user || s.checking || s.revalidating || !isVisible()) return;
  if (!force && !s.stale && confirmedRecently() && !pageNeedsRead()) { startPoll(); return; }
  if (s.busy && !force) return;
  const own = lifecycle, epoch = s.user.sessionEpoch;
  const valid = () => own === lifecycle && isVisible();
  s.checking = true; stopPoll(); activity.pause();
  if (s.offline) draw(); else renderStatus(root, s);
  try {
    await bootstrap(valid); if (!valid()) return;
    s.logoutUnconfirmed = false;
    reading = null; // A read started before the tab was hidden is superseded.
    await refresh({ background: true });
  } catch (error) {
    if (!valid() || error.code === 'OBSOLETE') return;
    s.checking = false;
    if (error.code === 'UNAUTHENTICATED') { clearPrivate('Your session expired. Sign in again.'); broadcast('expiry', epoch); return; }
    if (['SESSION_CHANGED', 'SESSION_CONTEXT_REQUIRED'].includes(error.code)) { clearPrivate('Your account changed in another tab, so your drafts were cleared.'); await revalidate(false); return; }
    s.stale = true; s.offline = true;
  } finally {
    if (own === lifecycle && s.user) { s.checking = false; quietDraw(); startPoll(); }
  }
}
// Signed-out tab return: look for a session made in another tab without touching the form.
async function quietSignedOutCheck() {
  if (s.user || s.busy || s.revalidating || s.unknown || Date.now() - signedOutCheckedAt < 30000) return;
  signedOutCheckedAt = Date.now(); const own = lifecycle;
  try {
    await api.request('/api/v1/auth/sessions/current', { valid: () => own === lifecycle && !s.user && isVisible(), retry: false });
    if (own === lifecycle && !s.user) await revalidate(false);
  } catch { /* No session or no network: the sign-in form stays as it is. */ }
}
async function revalidate(preserve = true, { silent = true } = {}) {
  if (preserve && silent && s.user) return silentRevalidate({ force: true });
  if (!isVisible() || s.revalidating) return;
  if (preserve && !s.user && s.unknown) return; // Public uncertain auth intent requires deliberate inspection.
  cancelExport();
  const previous = preserve ? (s.user ? { ...s } : pendingPrivate) : null;
  const active = document.activeElement;
  const restoreFocus = preserve ? (s.user ? { id: active?.id, selection: active && 'selectionStart' in active ? [active.selectionStart, active.selectionEnd] : null, position: capturePosition(root) } : pendingFocus) : null;
  const expected = previous?.user?.sessionEpoch;
  cancelReads();
  lifecycle++; const own = lifecycle; generations.change('session'); stopPoll(); activity.pause(); reading = null;
  let identityConfirmed = false;
  Object.assign(s, emptyPrivate(), { revalidating: true, bootFailed: false, notice: 'Connecting to Owlibi…' }); scheduleBootHint(); draw();
  const valid = () => own === lifecycle && isVisible();
  try {
    const current = await api.request('/api/v1/auth/sessions/current', { valid });
    if (!valid()) return;
    identityConfirmed = true; pendingPrivate = null; pendingFocus = null;
    if (!previous || current.id !== previous.user.id || current.sessionEpoch !== expected) activity.reset();
    if (previous && current.id === previous.user.id && current.sessionEpoch === expected) Object.assign(s, previous);
    else { Object.assign(s, emptyPrivate()); returnFocus = null; api.blocked.clear(); }
    s.user = current; s.logoutUnconfirmed = false; s.revalidating = false; s.booting = false; s.bootSlow = false; s.form = previous && current.sessionEpoch === expected ? previous.form : null;
    s.notice = 'Signed in.';
    s.busy = false; s.loading = false; s.stale = true;
    await bootstrap(valid); if (!valid()) return;
    draw(); await refresh(); startPoll();
    if (valid() && previous && current.sessionEpoch === expected) {
      restorePosition(root, restoreFocus?.position);
      const restored = document.getElementById(restoreFocus?.id);
      if (restored && !restored.disabled) {
        restored.focus({ preventScroll: true });
        if (restoreFocus.selection && typeof restored.setSelectionRange === 'function' && ['text', 'search', 'password', 'textarea'].includes(restored.type)) restored.setSelectionRange(...restoreFocus.selection);
      } else if (!s.form && s.selected) focus(returnFocus || 'close-inspector');
    } else if (valid()) focus('page-title');
  } catch (error) {
    if (!valid() || error.code === 'OBSOLETE') return;
    if (['SESSION_CHANGED', 'SESSION_CONTEXT_REQUIRED'].includes(error.code)) {
      clearPrivate('Your account changed in another tab, so your drafts were cleared.');
      await revalidate(false); return;
    }
    s.revalidating = false;
    if (error.code === 'UNAUTHENTICATED') {
      clearPrivate(expected ? 'Your session expired. Sign in again.' : '');
      if (expected) broadcast('expiry', expected);
    } else if (!identityConfirmed) {
      pendingPrivate = previous;
      pendingFocus = restoreFocus;
      Object.assign(s, emptyPrivate());
      if (s.booting) { s.bootFailed = true; s.notice = 'Can’t reach the server right now.'; }
      else { s.notice = 'Can’t reach the server. Your data is hidden until you check your sign-in again.'; s.logoutUnconfirmed = true; }
    } else {
      s.stale = true; s.notice = 'You’re signed in, but editing is paused. Try again.'; s.logoutUnconfirmed = true;
    }
    draw();
  }
}
async function handleError(error, { auth = false, activityReport = false } = {}) {
  if (error.code === 'OBSOLETE') return;
  let kind = 'error';
  if (error.code === 'UNAUTHENTICATED') {
    if (!auth && s.user) { const epoch = s.user.sessionEpoch; clearPrivate('Your session expired. Sign in again.'); broadcast('expiry', epoch); return; }
    // Pre-auth cookies can also expire. Never replay login/registration.
    s.csrf = null; s.notice = s.form === 'login' ? 'Email or password is incorrect.' : 'Please try again.';
  } else if (error.code === 'SESSION_CHANGED' || error.code === 'SESSION_CONTEXT_REQUIRED') {
    clearPrivate('Your account changed in another tab, so your last action was cancelled. Checking who you are…'); await revalidate(false); return;
  } else if (error.code === 'CSRF_INVALID') {
    s.notice = 'Your session was refreshed. Please submit again.';
    kind = 'warning';
    if (s.user) { await silentRevalidate({ force: true }); s.notice = 'Your session was refreshed. Check your draft and submit again.'; }
    else { await bootstrap().catch(() => {}); }
  } else if (error.code === 'TEMPLATE_UNAVAILABLE') {
    kind = 'warning'; s.templateNeedsReview = true; s.draft.settingsReviewed = false; s.projectStep = 2; s.notice = 'This template was updated. Your draft is kept. Pick the template again to use the latest version.';
  } else if (error.code === 'STALE_SETTINGS_VERSION') {
    kind = 'warning'; s.settingsNeedsReview = true; s.resources.settings.invalidate('Rules changed. Check the latest rules.'); invalidateSummaries('Rules changed. Refresh the summaries.');
    s.notice = 'Someone changed the project rules. Your draft is kept. Review the latest rules before saving.';
  } else if (['STALE_VERSION', 'STALE_AGREEMENT'].includes(error.code)) {
    kind = 'warning'; s.stale = true; if (s.form === 'edit') s.editNeedsReview = true;
    s.notice = error.code === 'STALE_AGREEMENT' ? 'This task’s terms changed. Refresh and read them before accepting.' : 'This task changed. Refresh and check it before you continue.';
  } else if (error.status === 429) {
    kind = 'warning'; s.rateUntil = Date.now() + error.retryAfter * 1000;
    s.notice = `Too many attempts. Try again in ${error.retryAfter || 'a few'} seconds.`;
    const own = lifecycle;
    setTimeout(() => { if (lifecycle === own) draw(); }, Math.max(1000, error.retryAfter * 1000));
  } else if (error.unknown) {
    s.stale = true; if (s.form === 'settings') s.settingsNeedsReview = true; if (s.form === 'edit') s.editNeedsReview = true; s.unknown = s.page === 'members' ? 'invitation' : s.form || 'task';
    s.notice = 'We lost connection while saving. Check whether your change went through.'; kind = 'hidden'; // The persistent banner explains it.
    if (auth) s.logoutUnconfirmed = true;
  } else if (['NETWORK', 'SERVICE_UNAVAILABLE'].includes(error.code)) {
    // Keep the last view; the offline banner (with Retry) explains why editing is paused.
    s.stale = true; s.offline = true; s.readFailureNotice = activityReport ? 'activity' : 'read';
    draw(); return;
  } else {
    s.notice = error.message || 'Something went wrong. Try again.'; s.errors = error.fieldErrors;
    if (s.form === 'project' && Object.keys(s.errors).length) {
      s.projectStep = s.errors.name || s.errors.description ? 1 : s.errors.template ? 2 : 3;
      s.notice = 'Please correct the highlighted fields.';
    }
    if (error.status === 404 && s.user && s.projectId) {
      cancelReads(); s.board = null; s.projectUnavailable = true; s.events = []; s.invitation = null; s.draft = {}; s.form = null; s.stale = true;
    }
  }
  s.noticeFor = s.notice; s.noticeKind = kind;
  draw();
}
async function readResource(key, path, validContext = () => true, { review = false } = {}) {
  const state = s.resources[key], ticket = state.begin(), epoch = s.user?.sessionEpoch, own = lifecycle, project = s.projectId;
  const valid = () => state.current(ticket) && own === lifecycle && s.user?.sessionEpoch === epoch && s.projectId === project && validContext() && isVisible();
  const started = performance.now(); draw(reading?.background);
  try {
    const data = await api.request(path, { epoch, valid, signal: state.controller.signal });
    if (!valid() || !state.resolve(ticket, data)) return;
    if (key === 'settings') {
      for (const summary of ['contribution', 'workload']) {
        const result = s.resources[summary];
        if (result.data && !summaryMatches(result.data, s.board, data)) { result.stale = true; result.reason = 'Tasks or rules changed. Refresh the summary.'; }
      }
      if (s.form === 'settings' && s.settingsBase && data.settingsVersion !== s.settingsBase.settingsVersion) s.settingsNeedsReview = true;
      if (review && s.form === 'settings') {
        s.draft = rebaseRules(s.settingsBase, data, s.draft);
        s.settingsBase = structuredClone(data); s.settingsNeedsReview = false; s.reviewedLatest = true;
        const fresh = data.configured ? rulesDraft(data) : null;
        const differing = fresh ? ruleFields.filter(key => ruleFieldChanged(fresh, s.draft, key)).length : 0;
        s.notice = differing ? `Loaded the latest rules. Rules you didn’t edit now match them; ${differing === 1 ? '1 of your edits differs' : `${differing} of your edits differ`} — see the list above the form.` : 'Loaded the latest rules. Your draft now matches them.';
      }
    }
    if (['workload', 'contribution'].includes(key)) {
      if (!summaryMatches(data, s.board, s.resources.settings.stale ? null : s.resources.settings.data)) { state.stale = true; state.reason = 'This summary may be out of date. Refresh to check.'; }
      if (key === 'workload') {
        clearTimeout(dayTimer); const delay = workloadExpiryDelay(data, performance.now() - started);
        dayTimer = setTimeout(() => { if (own !== lifecycle || s.projectId !== project || state.data !== data) return; state.invalidate('The date has changed. Refresh to update.'); draw(); if (s.page === 'workload') void refresh(); }, Math.min(2147483647, delay));
      }
    }
  } catch (error) {
    if (!valid() || error.code === 'OBSOLETE') return;
    state.fail(ticket, error);
    if ((['UNAUTHENTICATED', 'SESSION_CHANGED', 'SESSION_CONTEXT_REQUIRED'].includes(error.code) || error.status === 404)) await handleError(error);
  } finally { if (own === lifecycle && s.resources[key] === state) { draw(reading?.background); restoreFormFocus(); } }
}
async function loadCatalogue() {
  const form = s.form, draft = s.draft;
  await readResource('catalogue', '/api/v1/templates', () => s.form === form && s.draft === draft);
  if (s.form === 'project' && s.draft === draft && !s.selectedTemplate && !s.resources.catalogue.stale) chooseTemplate('blank');
}
function chooseTemplate(id) {
  const catalogue = s.resources.catalogue;
  if (s.form !== 'project' || s.busy || catalogue.stale || catalogue.loading) return;
  const template = catalogue.data?.items.find(item => item.id === id);
  if (!template) return;
  const previousRules = s.selectedTemplate && projectStartingRules(s.selectedTemplate);
  const hasCustomRules = previousRules && Object.keys(previousRules).some(key =>
    JSON.stringify(previousRules[key]) !== JSON.stringify(s.draft[key]));
  const first = !s.selectedTemplate;
  s.selectedTemplate = structuredClone(template);
  if (s.templateNeedsReview) s.notice = '';
  s.templateNeedsReview = false;
  if (!hasCustomRules) Object.assign(s.draft, projectStartingRules(template));
  s.draft.settingsReviewed = false;
  if (first && id === 'blank') s.formInitial = JSON.stringify({ ...s.draft, name: '', description: '' });
  s.errors = {};
  draw();
}
function showProjectStep(step, errors = {}) {
  s.projectStep = step;
  s.errors = errors;
  draw();
  const firstError = Object.keys(errors)[0];
  const rowError = firstError === 'effortScaleExamples' ? root.querySelector('[data-example-field]')?.dataset.exampleField : null;
  const target = document.getElementById(`field-${rowError || firstError}`);
  if (target) focus(target.id);
  else {
    root.querySelector('.project-wizard')?.scrollIntoView({ block: 'start' });
    document.getElementById('project-step-title')?.focus({ preventScroll: true });
  }
}
function advanceProject() {
  const step = s.projectStep;
  if (s.busy || s.unknown || step >= 4) return;
  const errors = step === 1 ? validate(s.draft, 'project') : step === 3 ? validateRules(s.draft).errors : {};
  if (Object.keys(errors).length) {
    s.notice = 'Please correct the highlighted fields.';
    showProjectStep(step, errors);
    return;
  }
  if (step === 2 && !projectTemplateCurrent(s)) return;
  if (s.notice === 'Please correct the highlighted fields.') s.notice = '';
  showProjectStep(step + 1);
  if (step === 1 && s.resources.catalogue.stale && !s.resources.catalogue.loading && !s.resources.catalogue.error) void loadCatalogue();
}
async function openSettings() {
  const own = lifecycle, project = s.projectId, page = s.page, originalForm = s.form;
  const invoker = document.activeElement?.id;
  const valid = () => own === lifecycle && s.projectId === project && s.page === page && s.form === originalForm;
  if (s.board?.project.role !== 'LEADER' || s.busy || !guardNavigation()) return;
  await readResource('settings', `/api/v1/projects/${s.projectId}/settings`);
  const base = s.resources.settings.data; if (!valid() || !base || s.resources.settings.stale) return;
  if (!base.configured && !s.resources.catalogue.data) await loadCatalogue();
  if (!valid()) return;
  const values = base.configured ? base : s.resources.catalogue.data?.items.find(t => t.id === 'blank')?.suggestedSettings;
  if (!values) { announce('Suggested rules aren’t available. Reload the templates and try again.'); return; }
  openForm('settings', rulesDraft(values), false); returnFocus = invoker; s.settingsBase = structuredClone(base); s.settingsNeedsReview = false; draw(); focus('field-effortScaleName');
}
async function refresh({ cursor = null, inspect = false, reviewReason = false, reviewEdit = false, background = false, reviewSettings = false } = {}) {
  if (reading || s.busy || s.revalidating || !s.user || !isVisible()) return;
  const own = { background }, ticket = generations.ticket(), epoch = s.user.sessionEpoch;
  const valid = () => reading === own && generations.current(ticket) && s.user?.sessionEpoch === epoch && isVisible();
  reading = own; s.loading = true; draw(background);
  try {
    // Every refresh first checks the shared cookie identity, including when BroadcastChannel is unavailable.
    await api.request('/api/v1/auth/sessions/current', { epoch, valid });
    if (s.page === 'projects') {
      const page = await api.request(`/api/v1/me/projects?limit=20${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { epoch, valid });
      if (!valid()) return;
      s.projects = cursor ? [...new Map([...s.projects, ...page.items].map(p => [p.id, p])).values()] : page.items;
      s.projectCursor = page.nextCursor;
    } else {
      const board = await api.request(`/api/v1/projects/${s.projectId}/board`, { epoch, valid });
      if (!valid()) return;
      if (!board.complete || board.tasks.length !== board.totalTasks) throw new ApiError('SERVICE_UNAVAILABLE', 'Complete project snapshot required.');
      s.board = board;
      for (const key of ['contribution', 'workload']) {
        const result = s.resources[key];
        if (result.data && result.data.snapshot.stateToken !== board.snapshot?.stateToken) { result.stale = true; result.reason = 'Project data changed. Refresh the summary.'; }
      }
      const extra = [readResource('settings', `/api/v1/projects/${s.projectId}/settings`, valid, { review: reviewSettings })];
      const key = s.page === 'completed' ? 'contribution' : s.page === 'workload' ? 'workload' : null;
      if (key) extra.push(readResource(key, `/api/v1/projects/${s.projectId}/${key === 'contribution' ? 'contribution' : 'workload'}-summary`, valid));
      await Promise.allSettled(extra); if (!valid()) return;
      if (s.page === 'activity') {
        const page = await api.request(`/api/v1/projects/${s.projectId}/activity?limit=50${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`, { epoch, valid });
        if (!valid()) return;
        s.events = cursor ? [...new Map([...s.events, ...page.items].map(item => [item.id, item])).values()] : page.items;
        s.eventCursor = page.nextCursor;
      }
    }
    if (!valid()) return;
    s.stale = false; s.confirmedAt = new Date().toISOString(); s.projectUnavailable = false;
    if (s.offline) { s.offline = false; if (s.readFailureNotice === 'read') s.notice = 'You’re back online.'; }
    s.readFailureNotice = null;
    if (s.form === 'reason' && (inspect || reviewReason)) {
      s.reasonTask = s.board?.tasks.find(t => t.id === s.reasonTask.id) ?? s.reasonTask;
      if (!inspect) s.notice = 'Here’s the latest version of this task. Check it before you confirm your reason.';
    }
    if (s.form === 'edit' && reviewEdit && !s.unknown) {
      const current = s.board?.tasks.find(t => t.id === s.editBase.id);
      if (current && !['DONE', 'CANCELLED'].includes(current.status)) {
        s.draft = rebaseEdit(s.editBase, current, s.draft);
        s.editBase = structuredClone(current); s.editNeedsReview = false; s.reviewedLatest = true;
        const differing = editFields.filter(key => editFieldChanged(current, s.draft, key)).length;
        s.notice = differing ? `Loaded the latest version. Fields you didn’t edit now match it; ${differing === 1 ? '1 of your edits differs' : `${differing} of your edits differ`} — see the list above the form.` : 'Loaded the latest version. Your draft now matches it.';
      }
    }
    if (inspect) {
      if (s.form === 'project') s.projectListInspected = true;
      s.unknown = null;
      s.notice = s.form === 'project' ? 'Here are your projects. If yours is listed, it was created.' : 'Here’s the latest. If your change isn’t there, try again.';
    }
  } catch (error) {
    if (valid()) {
      s.stale = true;
      if (s.projectId) { s.resources.settings.invalidate('Couldn’t refresh the project. Check the rules again.'); invalidateSummaries('Couldn’t refresh the project. Try again.'); }
      await handleError(error);
    }
  }
  finally { if (reading === own) { s.loading = false; draw(background); reading = null; restoreFormFocus(); if (s.page === 'workload' && s.resources.workload.reason.startsWith('The date has changed')) setTimeout(() => { if (s.user && isVisible() && s.page === 'workload') void refresh({ background: true }); }, 0); } }
}
async function mutate(path, body, onSuccess, { method = 'POST', auth = false } = {}) {
  if (!auth && (s.checking || (s.loading && reading?.background)) && !s.busy && !s.unknown && !s.stale) {
    announce(s.checking ? 'Still checking your connection. Your change wasn’t sent. Try again in a moment.' : 'Still refreshing. Your change wasn’t sent. Wait a moment, then submit again.'); return;
  }
  if (s.busy || s.unknown || (!auth && (s.stale || s.loading || s.checking || !s.user))) return;
  stopPoll(); activity.pause(); cancelReads({ keepCatalogue: true }); invalidateSummaries('Saving your change…'); s.busy = true; s.busyId = lastInvoker; s.errors = {}; generations.change('read');
  if (/^(Correct the marked|Please correct)/.test(s.notice)) s.notice = '';
  let succeeded = false;
  const own = lifecycle, epoch = s.user?.sessionEpoch, csrf = s.csrf, originFocus = document.activeElement?.id;
  const valid = () => own === lifecycle && (auth || s.user?.sessionEpoch === epoch);
  draw();
  try {
    if (!csrf) await bootstrap(valid);
    const result = await api.request(path, { method, body, csrf: s.csrf, epoch, valid });
    if (!valid()) return;
    await onSuccess(result); succeeded = true;
  } catch (error) { if (valid()) await handleError(error, { auth }); }
  finally {
    if (valid()) {
      s.busy = false; s.busyId = null;
      if (auth) { delete s.draft.password; s.showPassword = false; }
      draw();
      const first = Object.keys(s.errors)[0];
      if (first) focus(`field-${first}`);
      else {
        const origin = document.getElementById(originFocus), field = root.querySelector('#active-form input,#active-form textarea');
        focus(origin && !origin.disabled ? originFocus : s.form === 'project' ? 'project-step-title' : field ? field.id : s.user ? fallbackFocus() : 'field-email');
      }
      // A failed write that did not mark the view stale still re-reads what the attempt invalidated.
      if (succeeded ? ['settings', 'workload', 'completed'].includes(s.page) : !auth && s.user && !s.stale && !s.unknown) void refresh({ background: true });
      startPoll();
    }
  }
}
const activity = new Activity({
  allowed: () => !!s.user && !!s.csrf && isVisible() && !s.revalidating && !s.busy,
  send: async () => {
    const own = lifecycle, epoch = s.user.sessionEpoch;
    const valid = () => own === lifecycle && s.user?.sessionEpoch === epoch;
    try { await api.request('/api/v1/auth/sessions/current/activity', { method: 'POST', body: {}, csrf: s.csrf, epoch, valid, activity: true }); }
    catch (error) { if (valid()) { await handleError(error, { activityReport: true }); throw error; } }
  },
});
function navigate(page, projectId = s.projectId) {
  if (s.busy || !guardNavigation()) return;
  if (isActionConfirmation(s.notice) || s.notice === s.exportState.message) s.notice = '';
  clearProblemNotice(); // Errors and warnings belong to the page they were raised on.
  cancelExport();
  cancelReads();
  generations.change(page === s.page && projectId !== s.projectId ? 'project' : 'view');
  if (projectId !== s.projectId) generations.change('project');
  reading = null; s.loading = false; s.navOpen = false; s.page = page; s.form = null; s.draft = {}; s.settingsBase = null; s.settingsNeedsReview = false; s.errors = {}; s.invitation = null;
  if (projectId !== s.projectId || page === 'projects') { s.resources.settings = new ReadState(); s.resources.contribution = new ReadState(); s.resources.workload = new ReadState(); s.board = null; s.selected = null; s.filters = defaultFilters(); s.events = []; s.eventCursor = null; s.workloadTab = 'all'; s.projectUnavailable = false; }
  s.projectId = page === 'projects' ? null : projectId; s.stale = true; draw(); focus('page-title'); void refresh(); startPoll();
}
function openForm(form, values = {}, guard = true) {
  if (s.busy || (guard && !guardNavigation())) return;
  s.reviewedLatest = false; returnFocus = document.activeElement?.id; s.editBase = form === 'edit' ? structuredClone(s.board.tasks.find(t => t.id === s.selected)) : null; s.editNeedsReview = false; s.form = form; s.selectedTemplate = null; s.settingsBase = null; s.draft = values; s.formInitial = JSON.stringify(values); s.errors = {}; s.invitation = null;
  if (form === 'project') {
    s.projectStep = 1;
    s.projectListInspected = false;
    s.templateNeedsReview = false;
  }
  draw(); root.querySelector('#active-form input,#active-form textarea')?.focus();
}
function closeForm() {
  if (s.busy || !guardNavigation()) return;
  if (s.form === 'project') {
    s.resources.catalogue.invalidate();
    if (s.templateNeedsReview || s.notice === 'Please correct the highlighted fields.') s.notice = '';
  }
  s.settingsBase = null; s.settingsNeedsReview = false; s.selectedTemplate = null;
  s.form = s.user ? null : 'login'; s.draft = {}; s.errors = {}; s.editBase = null; s.editNeedsReview = false; s.reviewedLatest = false;
  pendingReturnFocus = { id: returnFocus || 'page-title', lifecycle }; draw(); restoreFormFocus();
}
function restoreFormFocus() {
  if (!pendingReturnFocus || s.loading || s.revalidating) return;
  const pending = pendingReturnFocus; pendingReturnFocus = null;
  if (pending.lifecycle !== lifecycle || s.form) return;
  // A later user focus choice takes precedence over the queued return.
  const active = document.activeElement;
  if (active && active !== document.body && active !== document.documentElement && active !== root && active.id !== pending.id) return;
  const target = document.getElementById(pending.id);
  focus(target && !target.disabled ? pending.id : fallbackFocus());
}
function selectTask(id) {
  if (s.busy || !guardNavigation()) return;
  returnFocus = document.activeElement?.id; s.form = null; s.draft = {}; s.selected = id; s.boardFocus = id; draw(); focus('close-inspector');
}
// Editing a field clears its error at once, without a full re-render.
function clearFieldError(name) {
  const key = /^example-(points|label)-\d+$/.test(name) ? 'effortScaleExamples' : name;
  if (s.errors[key]) delete s.errors[key];
  for (const id of [`error-${name}`, `error-${key}`]) { const el = document.getElementById(id); if (el) el.textContent = ''; }
  for (const id of [`field-${name}`, `field-${key}`]) document.getElementById(id)?.removeAttribute('aria-invalid');
}
function syncEffortChips(value) {
  let chosen = null;
  for (const radio of root.querySelectorAll('[name="effortPreset"]')) { radio.checked = value !== '' && Number(radio.value) === Number(value); if (radio.checked) chosen = radio; }
  const example = document.getElementById('effort-example');
  if (example) example.textContent = chosen ? `${chosen.dataset.label} · ${chosen.value} ${chosen.value === '1' ? 'point' : 'points'}` : 'Pick a size, or type any number of points below.';
}
root.addEventListener('input', event => {
  const input = event.target;
  if (input.id === 'search') { s.filters.search = input.value; draw(); return; }
  if (input.name === 'effortPreset') {
    if (!input.checked) return;
    s.draft.effortPoints = input.value; clearFieldError('effortPoints');
    const field = document.getElementById('field-effortPoints'); if (field) field.value = input.value;
    syncEffortChips(input.value); return;
  }
  if (input.closest('#active-form') && input.name) clearFieldError(input.name === 'project-template' ? 'template' : input.name);
  if (input.name === 'effortPoints') syncEffortChips(input.value);
  if (input.closest('#active-form') && input.name && !['assigneeIds', 'project-template'].includes(input.name)) {
    const example = /^example-(points|label)-(\d+)$/.exec(input.name);
    if (example) s.draft.effortScaleExamples[Number(example[2])][example[1]] = input.value;
    else s.draft[input.name] = input.type === 'checkbox' ? input.checked : input.value;
    if (s.form === 'project' && input.name !== 'settingsReviewed') s.draft.settingsReviewed = false;
  }
});
root.addEventListener('change', event => {
  if (event.target.id === 'assignment' || event.target.id === 'status') { s.filters[event.target.id] = event.target.value; draw(); }
  if (event.target.name === 'project-template' && event.target.checked) {
    chooseTemplate(event.target.value);
    focus(event.target.id);
  }
  if (event.target.id === 'field-settingsReviewed') {
    s.draft.settingsReviewed = event.target.checked;
    draw();
  }
  if (event.target.name === 'assigneeIds') clearFieldError('assigneeIds');
  if (event.target.name === 'assigneeIds') s.draft.assigneeIds = [...root.querySelectorAll('[name="assigneeIds"]:checked')].map(input => input.value);
});
root.addEventListener('submit', async event => {
  event.preventDefault(); if (event.target.id !== 'active-form') return;
  lastInvoker = event.submitter?.id || 'submit-form';
  const kind = s.form, draft = { ...s.draft };
  if (kind === 'project') {
    if (s.busy || s.unknown) return;
    if (s.projectStep < 4) { advanceProject(); return; }
  }
  if (kind === 'task' || kind === 'edit') Object.assign(draft, { description: draft.description || '', category: draft.category || '', dueDate: draft.dueDate || null, assigneeIds: draft.assigneeIds || [] });
  s.errors = validate(draft, kind === 'edit' ? 'task' : kind);
  if (event.target.elements.dueDate?.validity.badInput) s.errors.dueDate = 'Enter a complete date or clear the field.';
  if (['project', 'settings'].includes(kind)) Object.assign(s.errors, validateRules(draft).errors);
  if (kind === 'project' && !projectTemplateCurrent(s)) { showProjectStep(2); return; }
  if (kind === 'project' && !draft.settingsReviewed) s.errors.settingsReviewed = 'Review and agree to the project rules.';
  if (kind === 'edit' && s.editBase.firstStartedAtUtc && agreementChanged(s.editBase, draft) && !draft.reason?.trim()) s.errors.reason = 'Add a reason for changing the terms of a task that’s already been started.';
  if (Object.keys(s.errors).length) {
    if (kind === 'project') {
      s.notice = 'Please correct the highlighted fields.';
      const step = s.errors.name || s.errors.description ? 1 : s.errors.settingsReviewed && Object.keys(s.errors).length === 1 ? 4 : 3;
      showProjectStep(step, s.errors);
    } else {
      s.notice = 'Correct the marked fields.';
      draw();
      focus(`field-${Object.keys(s.errors)[0]}`);
    }
    return;
  }
  if (kind === 'register') await mutate('/api/v1/auth/accounts', draft, () => { s.form = 'login'; s.draft = { email: draft.email }; s.notice = 'Account created. Sign in to continue.'; }, { auth: true });
  if (kind === 'login') await mutate('/api/v1/auth/sessions', { email: draft.email, password: draft.password }, async result => {
    const epoch = result.sessionEpoch; s.draft = {}; s.user = result; s.form = null; generations.change('session');
    await bootstrap(); broadcast('login', epoch); s.notice = 'Signed in.';
    s.busy = false; void refresh(); // Sign-in is complete; a delayed read must not retain its mutation finalizer.
  }, { auth: true });
  if (kind === 'project') await mutate('/api/v1/projects', { name: draft.name, description: draft.description || '', template: { id: s.selectedTemplate.id, version: s.selectedTemplate.version }, settings: validateRules(draft).values }, result => { s.form = null; s.draft = {}; s.busy = false; navigate('tasks', result.id); s.notice = 'Project created. Assignees need to accept their tasks.'; });
  if (kind === 'settings') {
    if (!s.settingsBase || s.settingsNeedsReview || s.resources.settings.stale || s.resources.settings.data?.settingsVersion !== s.settingsBase.settingsVersion) { announce('Review the latest project rules before saving your draft.'); return; }
    await mutate(`/api/v1/projects/${s.projectId}/settings`, rulesPatch(s.settingsBase, draft), result => {
      const same = result.settingsVersion === s.settingsBase.settingsVersion;
      s.resources.settings.data = result; s.resources.settings.stale = false; s.settingsBase = null; s.form = null; s.draft = {}; s.settingsNeedsReview = false;
      s.notice = same ? 'No rule changes to save.' : 'Project rules saved. Refresh the summaries to use them.';
      s.board.snapshot = null; // Mutation response has no Board token; do not invent a token for locally patched data.
    }, { method: 'PATCH' });
  }
  if (kind === 'join') await mutate('/api/v1/invitations/accept', draft, result => { s.form = null; s.draft = {}; s.busy = false; navigate('tasks', result.projectId); s.notice = 'Project joined.'; });
  if (kind === 'task') await mutate(`/api/v1/projects/${s.projectId}/tasks`, draft, result => {
    s.board.snapshot = null; s.board.tasks.push(result); s.board.totalTasks++; s.selected = result.id; s.form = null; s.draft = {}; s.notice = 'Task created. Assignees need to accept it.';
  });
  if (kind === 'edit') {
    const current = s.board?.tasks.find(t => t.id === s.editBase.id);
    if (s.editNeedsReview || !current || current.version !== s.editBase.version || ['DONE', 'CANCELLED'].includes(current.status)) {
      announce('Review the latest task details before saving your draft.'); return;
    }
    await mutate(`/api/v1/projects/${s.projectId}/tasks/${s.editBase.id}`, { ...draft, version: s.editBase.version }, result => {
      const unchanged = result.version === s.editBase.version;
      replaceTask(result); s.form = null; s.draft = {}; s.editBase = null;
      s.notice = unchanged ? 'No changes to save.' : 'Task updated.';
    }, { method: 'PATCH' });
  }
  if (kind === 'reason') {
    const task = s.reasonTask, action = s.reasonAction;
    await mutate(`/api/v1/projects/${s.projectId}/tasks/${task.id}/transitions`, { action, version: task.version, reason: draft.reason }, result => { replaceTask(result); s.form = null; s.draft = {}; s.notice = 'Task updated.'; });
  }
});
function replaceTask(result) { s.board.snapshot = null; s.board.tasks = s.board.tasks.map(t => t.id === result.id ? result : t); s.confirmedAt = new Date().toISOString(); }
async function logout() {
  if (s.busy || !guardNavigation()) return;
  const epoch = s.user.sessionEpoch, csrf = s.csrf;
  clearPrivate('Sign-out requested. Waiting for server revocation confirmation…'); broadcast('logout', epoch);
  s.busy = true; const own = lifecycle; draw();
  try {
    await api.request('/api/v1/auth/sessions/current', { epoch, retry: false, valid: () => own === lifecycle });
    await api.request('/api/v1/auth/sessions/current', { method: 'DELETE', csrf, epoch, valid: () => own === lifecycle });
    if (own === lifecycle) { s.notice = 'You’ve signed out.'; s.logoutUnconfirmed = false; }
  } catch (error) {
    if (own !== lifecycle) return;
    s.logoutUnconfirmed = error.code !== 'UNAUTHENTICATED';
    s.notice = s.logoutUnconfirmed ? 'Signed out on this device. We couldn’t confirm it with the server.' : 'You’ve signed out.';
  } finally { if (own === lifecycle) { s.busy = false; draw(); focus('field-email'); } }
}
async function inspectUnknownAuth() {
  if (s.busy || s.user || !s.unknown) return;
  const own = lifecycle, email = s.draft.email, uncertain = s.unknown;
  const valid = () => own === lifecycle;
  s.busy = true; s.notice = 'Checking your sign-in…'; draw();
  try {
    await api.request('/api/v1/auth/sessions/current', { valid });
    if (valid()) await revalidate(false);
  } catch (error) {
    if (!valid()) return;
    if (error.code === 'UNAUTHENTICATED') {
      clearPrivate(uncertain === 'register' ? 'You’re not signed in. Your account may have been created. Try signing in before registering again.' : 'You’re not signed in. Try signing in again.');
      s.draft = { email }; s.logoutUnconfirmed = false; draw(); focus('field-email');
    } else await handleError(error);
  } finally { if (valid()) { s.busy = false; draw(); focus('field-email'); } }
}
root.addEventListener('click', async event => {
  const b = event.target.closest('button'); if (!b || b.disabled) return; pendingReturnFocus = null; lastInvoker = b.id || null;
  if (b.dataset.project) { navigate('tasks', b.dataset.project); return; }
  if (b.dataset.task) { selectTask(b.dataset.task); return; }
  const id = b.id;
  if (id.startsWith('theme-')) { s.theme = id.slice(6); draw(); }
  else if (id === 'logout') await logout();
  else if (id === 'export-report') await exportReport();
  else if (id === 'cancel-report') { cancelExport('Export cancelled.'); s.notice = s.exportState.message; draw(); focus('export-report'); startPoll(); }
  else if (id === 'workspace-menu') { s.navOpen = !s.navOpen; draw(); }
  else if (id === 'projects') navigate('projects');
  else if (id.startsWith('nav-')) navigate(id.slice(4));
  else if (id === 'refresh') { if (!s.user && s.unknown) await inspectUnknownAuth(); else if (!s.user || s.logoutUnconfirmed) await revalidate(true); else await refresh({ reviewReason: true }); }
  else if (id === 'inspect-unknown') { if (s.user) await refresh({ inspect: true }); else await inspectUnknownAuth(); }
  else if (id === 'more-projects') await refresh({ cursor: s.projectCursor });
  else if (id === 'older-activity') await refresh({ cursor: s.eventCursor });
  else if (id === 'show-password') { s.showPassword = !s.showPassword; draw(); }
  else if (id === 'auth-switch') { clearProblemNotice(); const email = s.draft.email || ''; openForm(s.form === 'login' ? 'register' : 'login', email ? { email } : {}, false); } // Switching sign-in modes is not navigation; keep the email.
  else if (id === 'dismiss-notice') { s.notice = ''; toastVersion = noticeVersion; root.querySelector('.toast-region')?.replaceChildren(); document.getElementById('page-title')?.focus({ preventScroll: true }); }
  else if (id === 'retry-connection') await silentRevalidate({ force: true });
  else if (id === 'use-latest' || id.startsWith('use-latest-')) {
    const only = id === 'use-latest' ? null : id.slice(11);
    if (s.busy || !['edit', 'settings'].includes(s.form)) return;
    const fresh = s.form === 'edit' ? editDraft(s.editBase) : s.settingsBase?.configured ? rulesDraft(s.settingsBase) : null;
    if (!fresh) return;
    for (const key of s.form === 'edit' ? editFields : ruleFields) if (!only || key === only) { s.draft[key] = structuredClone(fresh[key]); delete s.errors[key]; }
    s.notice = only ? 'Used the latest value for that field.' : 'Your draft now matches the latest version.';
    draw(); focus(only && document.getElementById(`field-${only}`) ? `field-${only}` : 'form-title');
  }
  else if (id === 'refresh-projects') await refresh();
  else if (id === 'new-task-empty') openForm('task', { assigneeIds: [] });
  else if (id === 'clear-filters-empty') { s.filters = defaultFilters(); draw(); focus('search'); }
  else if (b.dataset.workloadTab || id.startsWith('workload-tab-')) {
    s.workloadTab = b.dataset.workloadTab || id.slice(13); draw();
    if (b.dataset.workloadTab) { document.getElementById('result-tasks')?.scrollIntoView({ block: 'start' }); focus(`workload-tab-${s.workloadTab}`); }
  }
  else if (id === 'new-project') { openForm('project', { name: '', description: '', settingsReviewed: false }); if (s.form === 'project') await loadCatalogue(); }
  else if (id === 'join-project') openForm('join');
  else if (id === 'new-task') openForm('task', { assigneeIds: [] });
  else if (id === 'edit-task') { const t = s.board.tasks.find(t => t.id === s.selected); if (s.board.project.role === 'LEADER' && !['DONE', 'CANCELLED'].includes(t.status)) openForm('edit', editDraft(t)); }
  else if (id === 'review-edit') await refresh({ reviewEdit: true });
  else if (id === 'retry-catalogue') await loadCatalogue();
  else if (id === 'review-template') chooseTemplate(s.selectedTemplate?.id || 'blank');
  else if (id === 'project-back' && s.form === 'project' && s.projectStep > 1) {
    if (s.notice === 'Please correct the highlighted fields.') s.notice = '';
    showProjectStep(s.projectStep - 1);
  }
  else if (id === 'edit-rules') await openSettings();
  else if (id === 'review-settings') await refresh({ reviewSettings: true });
  else if (id.startsWith('retry-resource-')) {
    const key = id.slice(15); if (['settings', 'contribution', 'workload'].includes(key)) await refresh();
  } else if (id === 'add-example' || /^example-(remove|up|down)-/.test(id)) {
    if (s.busy) return;
    const rows = s.draft.effortScaleExamples;
    if (s.form === 'project') s.draft.settingsReviewed = false;
    if (id === 'add-example' && rows.length < 10) { rows.push({ points: '', label: '' }); draw(); focus(`field-example-points-${rows.length - 1}`); }
    else { const [, operation, raw] = /^example-(remove|up|down)-(\d+)$/.exec(id) || []; const index = Number(raw);
      if (operation === 'remove' && rows.length > 1) rows.splice(index, 1);
      const target = operation === 'up' ? index - 1 : index + 1;
      if (['up', 'down'].includes(operation) && target >= 0 && target < rows.length) [rows[index], rows[target]] = [rows[target], rows[index]];
      draw(); focus(`field-example-points-${Math.min(index, rows.length - 1)}`);
    }
  }
  else if (id === 'close-form') closeForm();
  else if (id === 'close-inspector') { if (guardNavigation()) { const target = returnFocus || `task-${s.selected}`; s.selected = null; s.form = null; s.draft = {}; draw(); focus(document.getElementById(target) ? target : 'page-title'); } }
  else if (id === 'view-table' || id === 'view-board') { s.mode = id.slice(5); draw(); }
  else if (id === 'clear-filters') { s.filters = defaultFilters(); draw(); focus('search'); }
  else if (id === 'pending') selectTask(pendingTasks(s.board.tasks, s.user.id)[0]?.id);
  else if (id === 'previous-task' || id === 'next-task') {
    const list = visibleTasks(s.board.tasks, s.filters, s.user.id), index = list.findIndex(t => t.id === s.selected);
    if (guardNavigation() && list[index + (id === 'previous-task' ? -1 : 1)]) {
      s.selected = list[index + (id === 'previous-task' ? -1 : 1)].id; draw();
      focus(document.getElementById(id)?.disabled ? 'close-inspector' : id);
    }
  } else if (id === 'accept-task') {
    const t = s.board.tasks.find(t => t.id === s.selected);
    await mutate(`/api/v1/projects/${s.projectId}/tasks/${t.id}/acceptance`, { agreementVersion: t.agreementVersion }, result => { replaceTask(result); s.notice = 'You accepted this task.'; });
  } else if (id.startsWith('action-')) {
    const action = id.slice(7), t = s.board.tasks.find(t => t.id === s.selected);
    if (reasonActions.has(action)) { s.reasonTask = { ...t }; s.reasonAction = action; openForm('reason'); }
    else await mutate(`/api/v1/projects/${s.projectId}/tasks/${t.id}/transitions`, { action, version: t.version }, result => { replaceTask(result); s.notice = 'Task updated.'; });
  } else if (id === 'create-invite') await mutate(`/api/v1/projects/${s.projectId}/invitations`, undefined, result => { s.invitation = result; s.notice = 'Invite created. Copy it now; it won’t be shown again.'; });
  else if (id === 'copy-invite') {
    try { await navigator.clipboard.writeText(s.invitation.token); announce('Token copied.'); }
    catch { announce('Couldn’t copy. Select the token and copy it yourself.'); }
  } else if (id === 'clear-invite') {
    s.invitation = null;
    if (isActionConfirmation(s.notice)) s.notice = '';
    draw(); focus('create-invite');
  }
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape') {
    if (s.navOpen && event.target.closest?.('.sidebar') && document.getElementById('workspace-menu')?.getClientRects().length) {
      s.navOpen = false; draw(); focus('workspace-menu');
    } else if (s.form && !['login', 'register'].includes(s.form)) { event.preventDefault(); closeForm(); }
    else if (s.selected) document.getElementById('close-inspector')?.click();
  }
});
root.addEventListener('keydown', event => {
  if (event.target.dataset.card && ['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) {
    event.preventDefault(); const tasks = visibleTasks(s.board.tasks, s.filters, s.user.id);
    s.boardFocus = boardNeighbor(statuses.map(st => tasks.filter(t => t.status === st).map(t => t.id)), event.target.dataset.task, event.key);
    draw(); focus(`task-${s.boardFocus}`);
  }
});
for (const type of ['keydown', 'pointerdown', 'wheel', 'touchstart']) document.addEventListener(type, event => activity.input(event, isVisible()), { passive: true, capture: true });
window.addEventListener('beforeunload', event => { if (dirty()) { event.preventDefault(); event.returnValue = ''; } });
window.addEventListener('pagehide', () => { cancelExport(); cancelReads(); stopPoll(); activity.pause(); generations.change('session'); root.replaceChildren(); });
// Visible again: signed-in views check silently; a first load or unconfirmed session uses the full check.
function resume({ restored = false } = {}) {
  if (restored && s.user) void revalidate(true, { silent: false }); // bfcache cleared the DOM on pagehide
  else if (s.user) void silentRevalidate();
  else if (s.booting || s.logoutUnconfirmed || pendingPrivate) void revalidate(true);
  else void quietSignedOutCheck();
}
window.addEventListener('pageshow', event => resume({ restored: event.persisted }));
document.addEventListener('visibilitychange', () => { if (!isVisible()) { cancelExport(); pauseReads(); stopPoll(); activity.pause(); } else resume(); });
window.addEventListener('offline', () => { cancelExport(); pauseReads(); s.stale = true; if (s.user) { s.offline = true; s.readFailureNotice = 'read'; } draw(); });
window.addEventListener('online', () => { if (s.user) void silentRevalidate({ force: true }); });
draw();
