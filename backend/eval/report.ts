/**
 * Compares eval result files side by side: pass rate, latency (median / p90 / mean) and tokens per kind.
 *   tsx eval/report.ts <name>=<results.jsonl> [<name>=<results.jsonl> ...]
 */
import { readFileSync } from 'node:fs';

type Row = { id: string; kind: string; pass: boolean; ms: number; calls: number; usage?: { inputTokens: number; outputTokens: number }; error?: string };
const runs = process.argv.slice(2).map(a => { const [name, file] = a.split('='); return { name: name!, rows: readFileSync(file!, 'utf8').trim().split('\n').filter(Boolean).map(l => JSON.parse(l) as Row) }; });
const pct = (v: number[], p: number) => { const s = [...v].sort((a, b) => a - b); return s.length ? s[Math.min(s.length - 1, Math.ceil(p * s.length) - 1)]! : 0; };
const mean = (v: number[]) => v.length ? Math.round(v.reduce((a, b) => a + b, 0) / v.length) : 0;
const kinds = [...new Set(runs.flatMap(r => r.rows.map(x => x.kind)))];
console.log('run | kind | pass | median ms | p90 ms | mean ms | calls | mean in tok | mean out tok');
for (const kind of kinds) for (const { name, rows } of runs) {
  const r = rows.filter(x => x.kind === kind);
  if (!r.length) continue;
  const ms = r.map(x => x.ms);
  console.log([name, kind, `${r.filter(x => x.pass).length}/${r.length}`, pct(ms, 0.5), pct(ms, 0.9), mean(ms), r.reduce((a, x) => a + x.calls, 0),
    mean(r.map(x => x.usage?.inputTokens ?? 0)), mean(r.map(x => x.usage?.outputTokens ?? 0))].join(' | '));
}
const all = new Set(runs.flatMap(r => r.rows.map(x => `${x.kind}:${x.id}`)));
console.log('\ncase | ' + runs.map(r => r.name).join(' | '));
for (const key of [...all].sort()) console.log(`${key} | ` + runs.map(({ rows }) => {
  const x = rows.find(r => `${r.kind}:${r.id}` === key);
  return x ? `${x.pass ? 'pass' : 'FAIL'} ${x.ms}ms${x.calls > 1 ? ` x${x.calls}` : ''}${x.error ? ' (error)' : ''}` : '-';
}).join(' | '));
