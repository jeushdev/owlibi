// No timer itself establishes human presence. Only visible trusted input queues a report.
export class Activity {
  constructor({ send, allowed, now = Date.now, later = (fn, ms) => setTimeout(fn, ms), cancel = id => clearTimeout(id) }) {
    Object.assign(this, { send, allowed, now, later, cancel }); this.reset();
  }
  reset() { if (this.timer) this.cancel(this.timer); this.timer = null; this.last = -Infinity; this.pending = false; this.running = false; this.generation = (this.generation ?? 0) + 1; }
  input(event, visible) {
    if (!visible || !event.isTrusted || !['keydown', 'pointerdown', 'wheel', 'touchstart'].includes(event.type) || !this.allowed()) return;
    this.pending = true; this.schedule();
  }
  schedule() {
    if (!this.pending || this.running || this.timer || !this.allowed()) return;
    const delay = Math.max(0, 10000 - (this.now() - this.last));
    if (delay) this.timer = this.later(() => { this.timer = null; this.schedule(); }, delay);
    else void this.report();
  }
  async report() {
    if (!this.allowed()) { this.pending = false; return; }
    const generation = this.generation;
    this.pending = false; this.running = true; this.last = this.now();
    try { await this.send(); } catch { if (generation === this.generation) this.pending = false; }
    finally { if (generation === this.generation) { this.running = false; this.schedule(); } }
  }
  pause() { if (this.timer) this.cancel(this.timer); this.timer = null; this.pending = false; }
}
