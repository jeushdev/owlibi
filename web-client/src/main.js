import { Api, ApiError, ReportDownload, REPORT_TIMEOUT } from './api.js';
import { Activity } from './session.js';
import { Generations, defaultFilters, pendingTasks, visibleTasks, reasonActions, boardNeighbor, statuses, validate, editDraft, agreementChanged, editDirty, rulesDraft, validateRules, rulesPatch, summaryMatches, workloadExpiryDelay, ReadState, projectTemplateCurrent, projectStartingRules } from './model.js';
import { render, capturePosition, restorePosition, isActionConfirmation } from './ui.js';
const root = document.querySelector('#app'), api = new Api(), generations = new Generations(), downloads = new ReportDownload();
const emptyPrivate = () => ({ resources: { catalogue: new ReadState(), settings: new ReadState(), contribution: new ReadState(), workload: new ReadState() }, selectedTemplate: null, templateNeedsReview: false, projectStep: 1, projectListInspected: false, settingsBase: null, settingsNeedsReview: false, formInitial: null, user: null, csrf: null, page: 'projects', navOpen: false, projectId: null, projects: [], projectCursor: null, board: null, events: [], eventCursor: null, selected: null, boardFocus: null, form: 'login', draft: {}, errors: {}, invitation: null, unknown: null, editBase: null, editNeedsReview: false, reasonTask: null, reasonAction: null, loading: false, busy: false, stale: true, confirmedAt: null, revalidating: false, filters: defaultFilters(), rateUntil: 0, readFailureNotice: null });
const s = { ...emptyPrivate(), notice: '', theme: 'light', mode: 'table', showPassword: false, logoutUnconfirmed: false };
const emptyExport = () => ({ running: false, message: '', bytes: 0, remaining: REPORT_TIMEOUT / 1000, retryUntil: 0 });
s.exportState = emptyExport();
let exportRun = 0, exportController = null, exportTimer = null;
let dayTimer = null;
let poll = null, reading = null, lifecycle = 0, channel = null, returnFocus = null, pendingPrivate = null, pendingFocus = null, pendingReturnFocus = null;
const draw = (preserveUnchanged = false) => render(root, s, { backgroundRefresh: reading?.background === true, preserveUnchanged });
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
function cancelReads() { clearTimeout(dayTimer); dayTimer = null; for (const state of Object.values(s.resources)) state.invalidate(); }
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
    const clock = document.getElementById('report-time'); if (clock) clock.textContent = `${s.exportState.remaining} seconds remaining for receipt`;
  }, 1000);
  try {
    let report = await api.professorReport(project, { epoch, valid, signal, progress: bytes => { if (valid()) s.exportState.bytes = bytes; } });
    if (!valid()) return;
    // A cookie can change in another tab without BroadcastChannel. Check identity again before handoff.
    await api.request('/api/v1/auth/sessions/current', { epoch, valid, signal, retry: false });
    if (!valid()) return;
    if (!downloads.offer(report, valid)) return;
    report = null; s.exportState.message = 'Report received; download offered. The browser manages saving and overwrite prompts.'; s.notice = s.exportState.message;
  } catch (error) {
    if (!valid() || error.code === 'OBSOLETE' || error.code === 'REPORT_CANCELLED') return;
    s.exportState.message = `${error.code}: ${error.message}`;
    if (error.code === 'REPORT_BUSY') { s.exportState.retryUntil = Date.now() + (error.retryAfter || 5) * 1000; s.exportState.message += ` Wait ${error.retryAfter || 5} seconds, then retry deliberately.`; }
    if (['UNAUTHENTICATED', 'SESSION_CHANGED', 'SESSION_CONTEXT_REQUIRED'].includes(error.code)) { await handleError(error); return; }
    s.notice = s.exportState.message;
    if (error.status === 403) { s.board.project.role = 'MEMBER'; s.stale = true; s.notice += ' Refresh to confirm current permissions.'; }
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
  Object.assign(s, emptyPrivate(), { notice: message, showPassword: false }); draw();
}
function broadcast(kind, epoch) { channel?.postMessage({ kind, epoch }); }
try {
  channel = new BroadcastChannel('owlibi-session-lifecycle');
  channel.onmessage = event => {
    const { kind, epoch } = event.data ?? {};
    if (!['login', 'logout', 'expiry'].includes(kind) || typeof epoch !== 'string') return;
    if (kind === 'login' && epoch !== s.user?.sessionEpoch) { clearPrivate('The shared browser session changed. Checking identity…'); void revalidate(false); }
    else if (['logout', 'expiry'].includes(kind) && (!s.user || s.user.sessionEpoch === epoch)) clearPrivate(kind === 'logout' ? 'Sign-out requested in another tab. Recheck to confirm the server session.' : 'Session ended in another tab. Sign in again.');
  };
} catch { /* Current-session checks remain authoritative when tab notices are unavailable. */ }
async function bootstrap(valid) {
  const own = lifecycle; valid ??= () => lifecycle === own;
  const token = await api.request('/api/v1/auth/sessions', { epoch: s.user?.sessionEpoch, valid: () => valid() && isVisible() });
  if (valid()) s.csrf = token;
}
async function revalidate(preserve = true) {
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
  Object.assign(s, emptyPrivate(), { revalidating: true, notice: 'Checking the current browser session… A sleeping demo server may take about a minute to wake up.' }); draw();
  const valid = () => own === lifecycle && isVisible();
  try {
    const current = await api.request('/api/v1/auth/sessions/current', { valid });
    if (!valid()) return;
    identityConfirmed = true; pendingPrivate = null; pendingFocus = null;
    if (!previous || current.id !== previous.user.id || current.sessionEpoch !== expected) activity.reset();
    if (previous && current.id === previous.user.id && current.sessionEpoch === expected) Object.assign(s, previous);
    else { Object.assign(s, emptyPrivate()); returnFocus = null; api.blocked.clear(); }
    s.user = current; s.logoutUnconfirmed = false; s.revalidating = false; s.form = previous && current.sessionEpoch === expected ? previous.form : null;
    s.notice = previous && current.sessionEpoch === expected ? 'Session revalidated.' : 'Signed in.';
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
      clearPrivate('Shared session changed during verification. Old drafts were discarded.');
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
      s.notice = 'Session could not be confirmed. Protected state is hidden. Recheck the server session.'; s.logoutUnconfirmed = true;
    } else {
      s.stale = true; s.notice = 'Identity confirmed, but request verification could not be refreshed. Writes remain disabled; recheck the server session.'; s.logoutUnconfirmed = true;
    }
    draw();
  }
}
async function handleError(error, { auth = false, activityReport = false } = {}) {
  if (error.code === 'OBSOLETE') return;
  if (error.code === 'UNAUTHENTICATED') {
    if (!auth && s.user) { const epoch = s.user.sessionEpoch; clearPrivate('Your session expired. Sign in again.'); broadcast('expiry', epoch); return; }
    // Pre-auth cookies can also expire. Never replay login/registration.
    s.csrf = null; s.notice = 'Please try again.';
  } else if (error.code === 'SESSION_CHANGED' || error.code === 'SESSION_CONTEXT_REQUIRED') {
    clearPrivate('Shared session changed. Old intent was discarded. Checking current identity…'); await revalidate(false); return;
  } else if (error.code === 'CSRF_INVALID') {
    s.notice = 'Request verification changed. The operation stopped; it was not replayed.';
    if (s.user) { await revalidate(true); s.notice = 'Request verification refreshed. Review your draft and submit deliberately.'; }
    else { await bootstrap().catch(() => {}); }
  } else if (error.code === 'TEMPLATE_UNAVAILABLE') {
    s.templateNeedsReview = true; s.draft.settingsReviewed = false; s.projectStep = 2; s.notice = 'Selected template version is unavailable. Your draft is retained. Reload the catalogue and deliberately choose its current version.';
  } else if (error.code === 'STALE_SETTINGS_VERSION') {
    s.settingsNeedsReview = true; s.resources.settings.invalidate('Rules changed; inspect current settings.'); invalidateSummaries('Settings changed; summaries need refresh.');
    s.notice = 'Project rules changed. Your draft is retained. Review the latest rules before saving deliberately.';
  } else if (['STALE_VERSION', 'STALE_AGREEMENT'].includes(error.code)) {
    s.stale = true; if (s.form === 'edit') s.editNeedsReview = true;
    s.notice = error.code === 'STALE_AGREEMENT' ? 'Agreement changed. Refresh and read the current terms before accepting deliberately.' : 'Task version changed. Refresh and review the current state before acting deliberately.';
  } else if (error.status === 429) {
    s.rateUntil = Date.now() + error.retryAfter * 1000;
    s.notice = `Rate limited. Wait ${error.retryAfter || 'the server’s required'} seconds, then try deliberately.`;
    const own = lifecycle;
    setTimeout(() => { if (lifecycle === own) draw(); }, Math.max(1000, error.retryAfter * 1000));
  } else if (error.unknown) {
    s.stale = true; if (s.form === 'settings') s.settingsNeedsReview = true; if (s.form === 'edit') s.editNeedsReview = true; s.unknown = s.page === 'members' ? 'invitation' : s.form || 'task';
    s.notice = 'Write outcome unknown. It may have committed. No automatic retry was sent.';
    if (auth) s.logoutUnconfirmed = true;
  } else if (['NETWORK', 'SERVICE_UNAVAILABLE'].includes(error.code)) {
    s.stale = true; s.notice = activityReport ? 'Human-activity report failed and was abandoned. A later real interaction may report again. Refresh to restore writes.' : 'Server state could not be confirmed. Retained data is stale; refresh before writing.';
    s.readFailureNotice = s.notice;
  } else {
    s.notice = `${error.code}: ${error.message}`; s.errors = error.fieldErrors;
    if (s.form === 'project' && Object.keys(s.errors).length) {
      s.projectStep = s.errors.name || s.errors.description ? 1 : s.errors.template ? 2 : 3;
      s.notice = 'Please correct the highlighted fields.';
    }
    if (error.status === 404 && s.user && s.projectId) {
      cancelReads(); s.board = null; s.events = []; s.invitation = null; s.draft = {}; s.form = null; s.stale = true;
    }
  }
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
        if (result.data && !summaryMatches(result.data, s.board, data)) { result.stale = true; result.reason = 'Tasks or project rules changed. Refresh the summary.'; }
      }
      if (s.form === 'settings' && s.settingsBase && data.settingsVersion !== s.settingsBase.settingsVersion) s.settingsNeedsReview = true;
      if (review && s.form === 'settings') { s.settingsBase = structuredClone(data); s.settingsNeedsReview = false; s.notice = 'Latest rules displayed. Compare with your retained draft and save deliberately only if still needed.'; }
    }
    if (['workload', 'contribution'].includes(key)) {
      if (!summaryMatches(data, s.board, s.resources.settings.stale ? null : s.resources.settings.data)) { state.stale = true; state.reason = 'This summary may use older tasks or rules. Refresh to check.'; }
      if (key === 'workload') {
        clearTimeout(dayTimer); const delay = workloadExpiryDelay(data, performance.now() - started);
        dayTimer = setTimeout(() => { if (own !== lifecycle || s.projectId !== project || state.data !== data) return; state.invalidate('Server date basis may have expired; refresh to confirm.'); draw(); if (s.page === 'workload') void refresh(); }, Math.min(2147483647, delay));
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
  if (!values) { announce('Suggested rules unavailable. Retry the catalogue before setup.'); return; }
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
    s.stale = false; s.confirmedAt = new Date().toISOString();
    if (s.readFailureNotice && s.notice === s.readFailureNotice) s.notice = 'Connection restored. Server state confirmed.';
    s.readFailureNotice = null;
    if (s.form === 'reason' && (inspect || reviewReason)) {
      s.reasonTask = s.board?.tasks.find(t => t.id === s.reasonTask.id) ?? s.reasonTask;
      if (!inspect) s.notice = 'Current task state inspected. Review its state and version before confirming your retained reason deliberately.';
    }
    if (s.form === 'edit' && reviewEdit && !s.unknown) {
      const current = s.board?.tasks.find(t => t.id === s.editBase.id);
      if (current && !['DONE', 'CANCELLED'].includes(current.status)) {
        s.editBase = structuredClone(current); s.editNeedsReview = false;
        s.notice = 'Latest task terms are displayed below. Your draft is retained. Compare every field and save deliberately only if still needed.';
      }
    }
    if (inspect) {
      if (s.form === 'project') s.projectListInspected = true;
      s.unknown = null;
      s.notice = s.form === 'project' ? 'Projects inspected. A matching name does not prove which request created it. Compare the list and choose deliberately whether creation is still needed; no request was replayed.' : 'Authoritative state inspected. Compare it with your retained draft; submit again only if still needed. Lost invitation tokens cannot be recovered.';
    }
  } catch (error) {
    if (valid()) {
      s.stale = true;
      if (s.projectId) { s.resources.settings.invalidate('Project refresh failed; inspect the rules again.'); invalidateSummaries('Project refresh failed. Try again.'); }
      await handleError(error);
    }
  }
  finally { if (reading === own) { s.loading = false; draw(background); reading = null; restoreFormFocus(); if (s.page === 'workload' && s.resources.workload.reason.startsWith('Server date basis')) setTimeout(() => { if (s.user && isVisible() && s.page === 'workload') void refresh({ background: true }); }, 0); } }
}
