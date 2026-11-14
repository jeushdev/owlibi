export class ApiError extends Error {
  constructor(code, message, { status = 0, fieldErrors = {}, retryAfter = 0, unknown = false } = {}) {
    super(message); Object.assign(this, { code, status, fieldErrors, retryAfter, unknown });
  }
}
export class Api {
  constructor({ fetcher = (...args) => globalThis.fetch(...args), now = Date.now, wait = ms => new Promise(resolve => setTimeout(resolve, ms)), jitter = () => Math.random() * 200, reportNow = () => performance.now(), later = (fn, ms) => setTimeout(fn, ms), cancelTimer = id => clearTimeout(id) } = {}) {
    Object.assign(this, { fetcher, now, wait, jitter, reportNow, later, cancelTimer }); this.blocked = new Map();
  }
  async request(path, { method = 'GET', body, csrf, epoch, valid = () => true, retry = true, activity = false, signal } = {}) {
    if (!path.startsWith('/api/v1/') || path.includes('://')) throw new Error('Relative API path required');
    const key = `${method} ${path.split('?')[0]}`, remaining = (this.blocked.get(key) ?? 0) - this.now();
    if (remaining > 0) throw new ApiError('RATE_LIMITED', 'Wait before trying this request again.', { status: 429, retryAfter: Math.ceil(remaining / 1000) });
    const unsafe = method !== 'GET';
    // Render Free may need a minute to wake up. Only safe session bootstrap reads wait longer.
    const sessionRead = !unsafe && ['/api/v1/auth/sessions', '/api/v1/auth/sessions/current'].includes(path);
    const deadline = this.now() + (sessionRead ? 90000 : unsafe && !activity ? 15000 : 10000);
    for (let attempt = 0; ; attempt++) {
      if (!valid() || signal?.aborted) throw new ApiError('OBSOLETE', 'Request belongs to an earlier view.');
      try {
        const headers = { Accept: 'application/json' };
        if (body !== undefined) headers['Content-Type'] = 'application/json';
        if (unsafe) {
          if (!csrf?.token || csrf.headerName !== 'X-CSRF-TOKEN') throw new ApiError('CSRF_INVALID', 'Request verification needs refreshing.');
          headers[csrf.headerName] = csrf.token;
          if (epoch) headers['X-Session-Epoch'] = epoch;
        }
        const timeout = AbortSignal.timeout(Math.max(1, deadline - this.now()));
        const response = await this.fetcher(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin', cache: 'no-store', signal: signal ? AbortSignal.any([signal, timeout]) : timeout });
        if (!valid()) throw new ApiError('OBSOLETE', 'Reply belongs to an earlier view.');
        const replyEpoch = response.headers.get('X-Session-Epoch');
        if (epoch && replyEpoch && replyEpoch !== epoch) throw new ApiError('SESSION_CHANGED', 'The shared browser session changed.', { status: 409 });
        const data = response.status === 204 ? null : await response.json();
        if (!valid()) throw new ApiError('OBSOLETE', 'Reply belongs to an earlier view.');
        if (!response.ok) {
          const retryHeader = response.headers.get('Retry-After');
          const retryAfter = /^\d+$/.test(retryHeader ?? '') ? Math.max(1, Number(retryHeader)) : 0;
          if (retryAfter) this.blocked.set(key, this.now() + retryAfter * 1000);
          throw new ApiError(data.code ?? 'SERVICE_UNAVAILABLE', data.message ?? 'Request failed.', { status: response.status, fieldErrors: data.fieldErrors ?? {}, retryAfter, unknown: unsafe && response.status >= 500 && !activity });
        }
        return data;
      } catch (cause) {
        if (signal?.aborted || !valid()) throw new ApiError('OBSOLETE', 'Cancelled read belongs to an earlier view.');
        const error = cause instanceof ApiError ? cause : new ApiError('NETWORK', 'Could not confirm the server response.', { unknown: unsafe && !activity });
        if (!unsafe && retry && attempt === 0 && (error.status === 503 || ['NETWORK', 'SERVICE_UNAVAILABLE'].includes(error.code)) && valid()) { const delay = 1000 + this.jitter();
          if (this.now() + delay >= deadline) throw error;
          await this.wait(delay); continue; }
        throw error;
      }
    }
  }
  async professorReport(project, { epoch, valid = () => true, signal, progress = () => {} } = {}) {
    if (!UUID.test(project) || !UUID.test(epoch || '')) throw reportInvalid();
    const path = `/api/v1/projects/${project}/professor-report`, key = `GET ${path}`;
    const remaining = (this.blocked.get(key) ?? 0) - this.now();
    if (remaining > 0) throw new ApiError('REPORT_BUSY', 'Another report is still being generated. Try again deliberately after the wait.', { status: 429, retryAfter: Math.ceil(remaining / 1000) });
    const controller = new AbortController(), deadline = this.reportNow() + REPORT_TIMEOUT;
    const abort = () => controller.abort(signal.reason instanceof ApiError ? signal.reason : new ApiError('OBSOLETE', 'Export belongs to an earlier context.'));
    const timer = this.later(() => controller.abort(new ApiError('REPORT_CLIENT_TIMEOUT', 'Report receipt exceeded 45 seconds. Server generation may continue until cleanup.')), REPORT_TIMEOUT);
    if (signal?.aborted) abort(); else signal?.addEventListener('abort', abort, { once: true });
    const check = () => {
      if (!valid()) throw new ApiError('OBSOLETE', 'Export belongs to an earlier context.');
      if (controller.signal.aborted) throw controller.signal.reason;
      if (this.reportNow() >= deadline) throw new ApiError('REPORT_CLIENT_TIMEOUT', 'Report receipt exceeded 45 seconds. Server generation may continue until cleanup.');
    };
    // Race each operation too: a fetch/read adapter ignoring abort must not offer late bytes.
    const boundedWait = async work => {
      check(); let rejectAbort;
      const stopped = new Promise((_, reject) => { rejectAbort = () => reject(controller.signal.reason); controller.signal.addEventListener('abort', rejectAbort, { once: true }); });
      try { const result = await Promise.race([work(), stopped]); check(); return result; }
      finally { controller.signal.removeEventListener('abort', rejectAbort); }
    };
    let response, reader; const chunks = []; let size = 0;
    try {
      response = await boundedWait(() => {
        const pending = this.fetcher(path, { method: 'GET', headers: { Accept: `${REPORT_MIME}, application/json` }, credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: controller.signal });
        Promise.resolve(pending).then(late => { if (controller.signal.aborted && !response) late.body?.cancel().catch(() => {}); }, () => {});
        return pending;
      });
      const replyEpoch = response.headers.get('X-Session-Epoch');
      if (replyEpoch && replyEpoch !== epoch) throw new ApiError('SESSION_CHANGED', 'The shared browser session changed.', { status: 409 });
      let metadata;
      if (response.ok) {
        if (response.status !== 200 || response.headers.get('Content-Type') !== REPORT_MIME || replyEpoch !== epoch || response.headers.get('Cache-Control') !== 'no-store' || response.headers.get('X-Content-Type-Options') !== 'nosniff') throw reportInvalid();
        const snapshot = response.headers.get('X-Owlibi-Snapshot-At'), token = response.headers.get('X-Owlibi-State-Token');
        if (!UTC.test(snapshot || '') || !Number.isFinite(Date.parse(snapshot)) || new Date(snapshot).toISOString().slice(0, 19) !== snapshot.slice(0, 19) || !/^[a-f0-9]{64}$/.test(token || '')) throw reportInvalid();
        const stamp = snapshot.slice(0, 19).replace(/[-:]/g, '') + 'Z', filename = `owlibi-${project}-${stamp}.xlsx`;
        if (response.headers.get('Content-Disposition') !== `attachment; filename="${filename}"`) throw reportInvalid();
        metadata = { filename, snapshotAtUtc: snapshot, stateToken: token };
      } else if (!/^application\/json(?:\s*;.*)?$/i.test(response.headers.get('Content-Type') || '')) throw reportInvalid();
      const rawLength = response.headers.get('Content-Length'); let length = null;
      if (rawLength !== null) {
        if (!/^(0|[1-9][0-9]*)$/.test(rawLength)) throw reportInvalid();
        length = Number(rawLength); if (!Number.isSafeInteger(length) || length <= 0 || length > (response.ok ? REPORT_BYTES : 65536)) throw reportInvalid();
      }
      reader = response.body?.getReader(); if (!reader) throw reportInvalid();
      for (;;) {
        const { done, value } = await boundedWait(() => reader.read()); if (done) break;
        if (!(value instanceof Uint8Array) || value.byteLength > (response.ok ? REPORT_BYTES : 65536) - size) throw reportInvalid();
        size += value.byteLength; chunks.push(value); progress(size, Math.max(0, deadline - this.reportNow()));
      }
      check(); if (!size || (length !== null && length !== size)) throw reportInvalid();
      if (!response.ok) {
        let data; try { data = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(await boundedWait(() => new Blob(chunks).arrayBuffer()))); } catch (error) { if (error instanceof ApiError) throw error; throw reportInvalid(); }
        if (!data || typeof data.code !== 'string' || typeof data.message !== 'string' || !data.fieldErrors || typeof data.fieldErrors !== 'object' || Array.isArray(data.fieldErrors)) throw reportInvalid();
        const message = REPORT_ERRORS[data.code]; if (!message) throw reportInvalid();
        const retry = response.headers.get('Retry-After'); const retryAfter = /^\d+$/.test(retry || '') ? Math.min(86400, Number(retry)) : 0;
        if (retryAfter) this.blocked.set(key, this.now() + retryAfter * 1000);
        throw new ApiError(data.code, message, { status: response.status, retryAfter });
      }
      const signature = new Uint8Array(4); let offset = 0;
      for (const chunk of chunks) { const count = Math.min(4 - offset, chunk.length); signature.set(chunk.subarray(0, count), offset); offset += count; if (offset === 4) break; }
      if (offset !== 4 || signature.some((b, i) => b !== [0x50, 0x4b, 0x03, 0x04][i])) throw reportInvalid();
      check(); return { ...metadata, blob: new Blob(chunks, { type: REPORT_MIME }), bytes: size };
    } catch (error) {
      if (error instanceof ApiError) throw error;
      check(); throw new ApiError('REPORT_NETWORK', 'Report receipt failed or was interrupted. No download was offered; retry deliberately.');
    } finally {
      this.cancelTimer(timer); signal?.removeEventListener('abort', abort); controller.abort();
      if (reader) { try { await reader.cancel(); } catch { /* Transport may already be closed. */ } try { reader.releaseLock(); } catch { /* Pending transport completion owns its reader. */ } }
      else { try { await response?.body?.cancel(); } catch { /* Transport may already be closed. */ } }
      chunks.length = 0;
    }
  }
}

export const REPORT_BYTES = 16 * 1024 * 1024, REPORT_TIMEOUT = 45000;
export const REPORT_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const UTC = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z$/;
const reportInvalid = () => new ApiError('REPORT_INVALID', 'Report response was incomplete, oversized or malformed. No download was offered; retry deliberately.');
const REPORT_ERRORS = {
  SETTINGS_REQUIRED: 'The leader must set up project rules before exporting.', REPORT_TOO_LARGE: 'The complete report exceeds the approved local export limits. History was not truncated.',
  REPORT_BUSY: 'Another report is still being generated. Wait, then retry deliberately.', REPORT_TIMEOUT: 'Server report time budget exceeded. Retry deliberately.', REPORT_UNAVAILABLE: 'Report temporarily unavailable. Retry deliberately.',
  UNAUTHENTICATED: 'Sign in is required.', SESSION_CHANGED: 'The shared browser session changed.', SESSION_CONTEXT_REQUIRED: 'Recheck the current browser session.', FORBIDDEN: 'Current leader permission is required.', NOT_FOUND: 'Project unavailable.', MALFORMED_REQUEST: 'Report request unavailable.',
};

/** Browser handoff only; neither a click nor this lease establishes a disk save. */
export class ReportDownload {
  constructor({ document = globalThis.document, urls = globalThis.URL, later = (fn, ms) => setTimeout(fn, ms), cancelTimer = id => clearTimeout(id) } = {}) { Object.assign(this, { document, urls, later, cancelTimer }); this.leases = new Map(); }
  offer(report, valid) {
    if (!valid()) return false;
    const url = this.urls.createObjectURL(report.blob), link = this.document.createElement('a');
    const dispose = () => { const timer = this.leases.get(url); if (timer !== undefined) this.cancelTimer(timer); this.leases.delete(url); this.urls.revokeObjectURL(url); link.remove(); };
    try {
      link.href = url; link.download = report.filename; link.hidden = true; this.document.body.append(link);
      if (!valid()) { dispose(); return false; }
      link.click(); link.remove(); this.leases.set(url, this.later(dispose, 1000)); return true;
    } catch (error) { dispose(); throw error; }
  }
  clear() { for (const [url, timer] of this.leases) { this.cancelTimer(timer); this.urls.revokeObjectURL(url); } this.leases.clear(); }
}
