import { test } from 'node:test';
import assert from 'node:assert/strict';
import { BrainFailure, createBrain } from '../src/bots.js';

const config = { baseURL: 'http://127.0.0.1:8317', authToken: 'synthetic-gateway-token', model: 'claude-opus-5-5' };
const reply = (content: unknown[], stop_reason: string) => () => Response.json({ id: 'm', type: 'message', role: 'assistant', model: 'x', stop_reason, stop_sequence: null, usage: { input_tokens: 1, output_tokens: 1 }, content });
const prose = reply([{ type: 'thinking', thinking: '', signature: 's' }, { type: 'text', text: 'Here is the plan.' }], 'end_turn');
const planned = reply([{ type: 'thinking', thinking: '', signature: 's' }, { type: 'tool_use', id: 't', name: 'make_plan', input: { steps: [{ id: 's1', app: 'mygov', channel: 'ui', goal: 'my pension capital', usesResultOf: '', intent: 'read' }] } }], 'tool_use');
const rejected = (status: number, message: string) => () => Response.json({ type: 'error', error: { type: 'api_error', message } }, { status });

async function run(replies: (() => Response)[]) {
  const bodies: Record<string, any>[] = [];
  const fakeFetch: typeof fetch = async (input, init) => {
    bodies.push(await (input instanceof Request ? input : new Request(input, init)).json() as Record<string, any>);
    return replies[Math.min(bodies.length - 1, replies.length - 1)]!();
  };
  const brain = createBrain(config, fakeFetch);
  try { return { plan: (await brain.plan('x', new AbortController().signal)).plan, bodies }; } catch (error) { return { error, bodies }; }
}

test('requests never force a tool and leave room for thinking', async () => {
  const { bodies, plan } = await run([planned]);
  assert.deepEqual(bodies[0]!.tool_choice, { type: 'auto', disable_parallel_tool_use: true });
  assert.equal(bodies[0]!.max_tokens, 16000);
  assert.equal(bodies[0]!.thinking, undefined);
  assert.equal(plan?.steps[0]?.intent, 'read');
});

test('a reply in prose is asked again once, then fails safely', async () => {
  const recovered = await run([prose, planned]);
  assert.equal(recovered.bodies.length, 2); assert.equal(recovered.plan?.steps[0]?.intent, 'read');
  const stubborn = await run([prose]);
  assert.equal(stubborn.bodies.length, 2); assert.ok(stubborn.error instanceof BrainFailure);
});

test('a refusal or truncated reply is not asked again', async () => {
  for (const stop of ['refusal', 'max_tokens']) {
    const result = await run([reply([{ type: 'text', text: '' }], stop)]);
    assert.equal(result.bodies.length, 1); assert.ok(result.error instanceof BrainFailure);
  }
});

test('the gateway\'s own out-of-accounts messages are retried; unrelated server errors are not', async () => {
  for (const [status, message] of [[429, 'All credentials for model claude-opus-5-5 are cooling down'], [503, 'no auth available'], [529, 'Overloaded']] as const) {
    const result = await run([rejected(status, message), planned]);
    assert.equal(result.bodies.length, 2, message); assert.equal(result.plan?.steps[0]?.intent, 'read');
  }
  const other = await run([rejected(500, 'internal parser bug'), planned]);
  assert.equal(other.bodies.length, 1); assert.ok(other.error instanceof BrainFailure);
});
