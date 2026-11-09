import { randomUUID } from 'node:crypto';
import { action, nodeLabel, validateTarget, type Create, type Next, type NextResponse, type Usage, type Action } from './protocol.js';
import { GatewayFailure, type Gateway, type HistoryEntry } from './gateway.js';
import { containsSensitiveData, manualBoundary, mockPlan } from './planner.js';

export class HttpError extends Error {
  constructor(public status: number, public code: string) { super(code); }
}
type Outcome = { value: NextResponse } | { error: HttpError };
type Entry = { sequence: number; promise: Promise<Outcome> };
type Session = {
  id: string; deviceId: string; command: string; mode: 'mock' | 'live'; expires: number;
  sequence: number; steps: number; records: Map<string, Entry>; snapshots: Set<string>;
  pending: boolean; stopped: boolean; controller?: AbortController; timer: NodeJS.Timeout;
  clarifications: string[]; usage: Usage; fixtureSendProposed: boolean;
  /** Last few proposals with the phone's reported result: labels only, transient, cleared with the session. */
  history: HistoryEntry[];
};
/** How many earlier actions the model sees. */
const historySize = 6;
/** One inference may be asked again once; both attempts together stay inside Fastify's 50 s request timeout. */
const inferenceTimeoutMs = 45_000;
function historyEntry(a: Action, request: Next): HistoryEntry {
  const nodes = request.observation.nodes;
  const label = (id: string) => { const n = nodes.find(n => n.id === id); return n ? nodeLabel(nodes, n) : ''; };
  return {
    action: a.type === 'scroll' ? `scroll ${a.direction}` : a.type,
    target: a.type === 'launch' ? a.packageName : a.targetId ? label(a.targetId) : '',
    typed: a.type === 'set_text' ? a.text.slice(0, 80) : a.type === 'ask_user' ? a.question.slice(0, 120) : '',
    then: a.thenClickId ? label(a.thenClickId) : '',
    result: '',
  };
}
export class Sessions {
  private sessions = new Map<string, Session>();
  private deviceId?: string;
  private liveRequests = 0;
  private activeRequests = 0;
  private liveDeadline?: number;
  constructor(private options: { liveEnabled: boolean; gateway?: Gateway; now?: () => number; ttlMs?: number; liveMaxRequests?: number; liveWindowMs?: number }) {}
  // Defaults keep the original bounded allowance; the operator can raise them explicitly via environment.
  private get liveMax() { return this.options.liveMaxRequests ?? 15; }
  private get liveWindow() { return this.options.liveWindowMs ?? 600_000; }
  private now() { return this.options.now?.() ?? Date.now(); }
  create(input: Create) {
    this.sweep();
    if (this.deviceId && this.deviceId !== input.deviceId) throw new HttpError(401, 'device_mismatch');
    if (input.mode === 'live' && (!this.options.liveEnabled || !this.options.gateway)) throw new HttpError(403, 'live_disabled');
    if (this.sessions.size >= 32) throw new HttpError(429, 'session_limit');
    this.deviceId = input.deviceId;
    const id = randomUUID();
    const ttl = Math.min(this.options.ttlMs ?? 600_000, 600_000);
    const timer = setTimeout(() => this.remove(id), ttl); timer.unref();
    this.sessions.set(id, { id, deviceId: input.deviceId, command: input.command, mode: input.mode, expires: this.now() + ttl,
      sequence: 0, steps: 0, records: new Map(), snapshots: new Set(), pending: false, stopped: false, timer,
      clarifications: [], usage: { inputTokens: 0, outputTokens: 0 }, fixtureSendProposed: false, history: [] });
    return { sessionId: id, maxSteps: 15, mode: input.mode };
  }
  private get(id: string): Session {
    const s = this.sessions.get(id);
    if (!s || s.stopped || s.expires <= this.now() || s.deviceId !== this.deviceId) {
      if (s) this.remove(id);
      throw new HttpError(404, 'session_not_found');
    }
    return s;
  }
  stop(id: string) { this.get(id); this.remove(id); return { stopped: true }; }
  private remove(id: string) {
    const s = this.sessions.get(id);
    if (!s) return;
    s.stopped = true; s.controller?.abort(); clearTimeout(s.timer);
    s.command = ''; s.clarifications = []; s.history = []; s.records.clear(); s.snapshots.clear();
    this.sessions.delete(id);
  }
  private sweep() { for (const s of this.sessions.values()) if (s.expires <= this.now()) this.remove(s.id); }
  close() { for (const id of this.sessions.keys()) this.remove(id); }
  async next(id: string, input: Next): Promise<NextResponse> {
    const s = this.get(id);
    const prior = s.records.get(input.requestId);
    if (prior) {
      if (prior.sequence !== input.sequence) throw new HttpError(409, 'request_conflict');
      return this.unwrap(await prior.promise);
    }
    if (s.pending || this.activeRequests >= 1 || input.sequence !== s.sequence) throw new HttpError(409, 'sequence_conflict');
    if (s.steps >= 15) throw new HttpError(429, 'step_limit');
    if (s.snapshots.has(input.observation.snapshotId)) throw new HttpError(409, 'snapshot_reused');
    const boundary = containsSensitiveData(s.command, [...s.clarifications, ...input.clarifications], input)
      ? action('manual', { message: 'Potential sensitive or authentication data detected. Continue manually; do not transmit secrets.' })
      : manualBoundary(input);
    const willInfer = s.mode === 'live' && !boundary;
    if (willInfer && (this.liveRequests >= this.liveMax || (this.liveDeadline !== undefined && this.now() >= this.liveDeadline))) throw new HttpError(429, 'live_budget_exhausted');
    s.pending = true; this.activeRequests++; s.steps++; s.sequence++; s.snapshots.add(input.observation.snapshotId);
    for (const answer of input.clarifications) if (!s.clarifications.includes(answer)) s.clarifications.push(answer);
    while (s.clarifications.length > 16 || s.clarifications.join('').length > 12000) s.clarifications.shift();
    // The phone's previousResult reports what happened after the last proposal: attach it to that entry.
    const last = s.history.at(-1);
    if (last && !last.result) last.result = (input.previousResult ?? 'no result reported').slice(0, 300);
    if (willInfer) { this.liveRequests++; this.liveDeadline ??= this.now() + this.liveWindow; }
    const controller = new AbortController(); s.controller = controller;
    // Install the replay record before execution begins, including synchronous/mock results.
    const promise = Promise.resolve().then(async (): Promise<Outcome> => {
      try {
        this.get(id);
        let usage: Usage = { inputTokens: 0, outputTokens: 0 };
        let proposed: Action;
        if (boundary) proposed = boundary;
        else if (s.mode === 'mock') {
          proposed = mockPlan(s.command, input);
          if (s.fixtureSendProposed && proposed.type === 'click' && input.observation.nodes.some(n => n.id === proposed.targetId && n.resourceId === 'dev.personal.phoneagent:id/fixture_send')) proposed = action('manual', { message: 'Fixture Send was already proposed. Inspect the result manually rather than retrying.' });
        } else {
          const result = await this.infer(s, input, controller);
          usage = result.usage; this.addUsage(s, usage);
          proposed = result.action;
        }
        this.get(id);
        const checked = validateTarget(proposed, input);
        if (checked.type === 'click' && input.observation.nodes.some(n => n.id === checked.targetId && n.resourceId === 'dev.personal.phoneagent:id/fixture_send')) s.fixtureSendProposed = true;
        s.history.push(historyEntry(checked, input));
        while (s.history.length > historySize) s.history.shift();
        const value: NextResponse = { requestId: input.requestId, sequence: input.sequence, proposal: { ...checked, actionId: randomUUID(), snapshotId: input.observation.snapshotId }, usage };
        return { value };
      } catch (err) {
        if (err instanceof GatewayFailure) this.addUsage(s, err.usage);
        return { error: err instanceof HttpError ? err : new HttpError(502, 'gateway_failure') };
      } finally { s.pending = false; this.activeRequests--; s.controller = undefined; }
    });
    s.records.set(input.requestId, { sequence: input.sequence, promise });
    return this.unwrap(await promise);
  }
  private async infer(s: Session, request: Next, controller: AbortController) {
    let timeout: NodeJS.Timeout | undefined;
    let onAbort: (() => void) | undefined;
    try {
      const cancelled = new Promise<never>((_, reject) => {
        onAbort = () => reject(s.stopped ? new HttpError(404, 'session_not_found') : (this.now() >= this.liveDeadline! ? new HttpError(429, 'live_budget_exhausted') : new HttpError(502, 'gateway_failure')));
        controller.signal.addEventListener('abort', onAbort, { once: true });
        if (controller.signal.aborted) onAbort();
        timeout = setTimeout(() => controller.abort(), Math.max(1, Math.min(inferenceTimeoutMs, this.liveDeadline! - this.now()))); timeout.unref();
      });
      // A second attempt (unusable first reply) only if the live allowance still has room; it counts like any other.
      const maxAttempts = this.liveMax - this.liveRequests >= 1 ? 2 : 1;
      const input = { command: s.command, clarifications: [...s.clarifications], request, history: s.history.map(h => ({ ...h })), maxAttempts,
        onAttempt: (attempt: number) => { if (attempt > 1) this.liveRequests++; } };
      return await Promise.race([this.options.gateway!.propose(input, controller.signal), cancelled]);
    } finally {
      if (timeout) clearTimeout(timeout);
      if (onAbort) controller.signal.removeEventListener('abort', onAbort);
    }
  }
  private addUsage(s: Session, usage: Usage) { s.usage.inputTokens += usage.inputTokens; s.usage.outputTokens += usage.outputTokens; }
  private unwrap(outcome: Outcome): NextResponse { if ('error' in outcome) throw outcome.error; return outcome.value; }
}
