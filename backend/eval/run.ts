/**
 * Replay eval: sends recorded cases through one version of the backend's model code and scores the answers.
 *
 *   tsx eval/run.ts --src <backend src dir> --cases <dir> --out <results.jsonl> [--only next,plan,turn] [--ids a,b]
 *
 * Every case is ONE real gateway request (plus a re-ask if that version makes one): it spends gateway quota.
 * Needs ANTHROPIC_BASE_URL, ANTHROPIC_AUTH_TOKEN and ANTHROPIC_MODEL in the environment; never prints them.
 * Cases and results stay in backend/.local/eval (git-ignored): captured screens are private even when scrubbed.
 *
 * Case files (*.json):
 *   { "id", "kind": "next", "command", "clarifications"?, "previousResult"?, "history"?, "observation",
 *     "expect": [Match...], "forbid"?: [Match...] }            Match = { type, rid?, label?, text?, message? } (regex strings)
 *   { "id", "kind": "plan", "command", "expect": { "steps": [{ app, channel, usesResultOf? }], "firstMessage"?: boolean } }
 *   { "id", "kind": "turn", "request": BotTurnRequest, "expect": { "type": [..] } }
 */
import { readFileSync, readdirSync, appendFileSync, writeFileSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { pathToFileURL } from 'node:url';

type Match = { type: string; id?: string; rid?: string; label?: string; text?: string; message?: string };
type Node = { id: string; resourceId: string; text: string; description: string };
const args = new Map<string, string>();
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i]!.replace(/^--/, ''), process.argv[i + 1] ?? '');
const src = resolve(args.get('src') ?? 'src');
const casesDir = resolve(args.get('cases') ?? '.local/eval/cases');
const out = resolve(args.get('out') ?? '.local/eval/results.jsonl');
const only = new Set((args.get('only') ?? 'next,plan,turn').split(','));
const ids = args.get('ids') ? new Set(args.get('ids')!.split(',')) : undefined;
const { ANTHROPIC_BASE_URL: baseURL, ANTHROPIC_AUTH_TOKEN: authToken, ANTHROPIC_MODEL: model } = process.env;
if (!baseURL || !authToken || !model) throw new Error('gateway environment missing');

const load = (name: string) => import(pathToFileURL(join(src, `${name}.ts`)).href);
const gw = await load('gateway');
const protocol = await load('protocol');
const bots = await load('bots');
let calls: { ms: number; status: number }[] = [];
/** Counts and times each gateway request (a re-ask shows up as a second call). */
const timedFetch: typeof fetch = async (input, init) => {
  const started = performance.now();
  const response = await fetch(input, init);
  calls.push({ ms: Math.round(performance.now() - started), status: response.status });
  return response;
};
const config = { baseURL, authToken, model };
const gateway = gw.createGateway(config, timedFetch);
const brain = bots.createBrain(config, timedFetch);

const label = (nodes: Node[], n: Node) => protocol.nodeLabel ? protocol.nodeLabel(nodes, n)
  : ([n.text, n.description].find(v => v.trim()) ?? nodes.filter(c => c.id.startsWith(n.id + '.')).flatMap(c => [c.text, c.description]).find(v => v.trim()) ?? '');
function matches(m: Match, a: Record<string, string>, nodes: Node[]): boolean {
  if (m.type !== a.type) return false;
  const n = nodes.find(n => n.id === a.targetId);
  if (m.id && !new RegExp(m.id).test(a.targetId ?? '')) return false;
  if (m.rid &&!(n && new RegExp(`(^|/)(${m.rid})$`).test(n.resourceId))) return false;
  if (m.label && !(n && new RegExp(m.label, 'i').test(label(nodes, n)))) return false;
  if (m.text && !new RegExp(m.text, 'i').test(a.text ?? '')) return false;
  if (m.message && !new RegExp(m.message, 'i').test(`${a.message ?? ''} ${a.question ?? ''}`)) return false;
  return true;
}

const files = readdirSync(casesDir).filter(f => f.endsWith('.json')).sort();
writeFileSync(out, '');
for (const file of files) {
  const c = JSON.parse(readFileSync(join(casesDir, file), 'utf8'));
  if (!only.has(c.kind) || (ids && !ids.has(c.id))) continue;
  calls = [];
  const started = performance.now();
  const row: Record<string, unknown> = { id: c.id, kind: c.kind };
  const signal = AbortSignal.timeout(90_000);
  try {
    if (c.kind === 'next') {
      const request = protocol.nextSchema.parse({ requestId: crypto.randomUUID(), sequence: 0, previousResult: c.previousResult ?? null,
        clarifications: c.clarifications ?? [], observation: { ...c.observation, snapshotId: crypto.randomUUID() } });
      const result = await gateway.propose({ command: c.command, clarifications: c.clarifications ?? [], request, history: c.history ?? [] }, signal);
      // What production does next: the session validates the target (the old code only here; a failure = task stops).
      const a = protocol.validateTarget(result.action, request) as Record<string, string>;
      const nodes = request.observation.nodes as Node[];
      const n = nodes.find(n => n.id === a.targetId);
      Object.assign(row, { usage: result.usage, action: { type: a.type, rid: n?.resourceId.split(':id/').pop(), label: n ? label(nodes, n) : undefined,
        text: a.text || undefined, then: a.thenClickId || undefined, message: (a.message || a.question || '').slice(0, 200) || undefined } });
      const forbidden = (c.forbid ?? []).some((m: Match) => matches(m, a, nodes));
      row.pass = !forbidden && (c.expect as Match[]).some(m => matches(m, a, nodes));
      if (forbidden) row.forbidden = true;
    } else if (c.kind === 'plan') {
      const { plan, usage } = await brain.plan(c.command, signal);
      const steps = plan.steps as { app: string; channel: string; usesResultOf: string; firstMessage?: string }[];
      const want = c.expect.steps as { app: string; channel: string; usesResultOf?: string }[];
      Object.assign(row, { usage, steps: steps.map(s => `${s.app}/${s.channel}${s.usesResultOf ? `<${s.usesResultOf}` : ''}${s.firstMessage ? '+msg' : ''}`) });
      row.pass = steps.length === want.length && want.every((w, i) => steps[i]!.app === w.app && steps[i]!.channel === w.channel && (w.usesResultOf === undefined || steps[i]!.usesResultOf === w.usesResultOf));
      row.firstMessages = steps.filter(s => s.channel === 'bot').every(s => (s.firstMessage ?? '').trim().length > 0);
    } else if (c.kind === 'turn') {
      const { turn, usage } = await brain.turn(c.request, signal);
      Object.assign(row, { usage, turn: { type: turn.type, text: turn.text.slice(0, 200) || undefined, summary: turn.summary.slice(0, 200) || undefined } });
      row.pass = (c.expect.type as string[]).includes(turn.type);
    }
  } catch (error) {
    row.pass = false;
    row.error = (error as Error)?.message ?? 'error';
  }
  row.ms = Math.round(performance.now() - started);
  row.calls = calls.length;
  row.callMs = calls.map(c => c.ms);
  appendFileSync(out, JSON.stringify(row) + '\n');
  console.log(`${row.pass ? 'PASS' : 'FAIL'} ${c.kind} ${c.id} ${row.ms}ms calls=${calls.length}${row.error ? ` error=${row.error}` : ''}`);
}
