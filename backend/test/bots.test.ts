import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { mockPlan, mockBotTurn, planSchema, toolInput, BrainFailure, createBrain, type BotBrain } from '../src/bots.js';
import { headers, token } from './helpers.js';

const demo = 'ABB-dən soruş, balans artırmanın komissiyası var? Sonra nömrəmə 1 manat yüklə. myGov-dan şəxsiyyət vəsiqəsini necə yeniləyəcəyimi öyrən və cavabı Anama WhatsApp-da göndər.';

test('mock plan turns "<Name>e de" into a WhatsApp step that uses the myGov answer', () => {
  const { steps } = mockPlan('mygovdan ne qeder pensiya kapitali yigildigimi Metine de');
  assert.deepEqual(steps.map(s => [s.app, s.channel, s.usesResultOf]), [['mygov', 'bot', ''], ['whatsapp', 'ui', 's1']]);
  assert.equal(steps[0]!.goal, 'mygovdan ne qeder pensiya kapitali yigildigimi');
  assert.match(steps[1]!.goal, /^Metine de/);
  // Lower-case words before "de" are not names: no message step invented.
  assert.equal(mockPlan('mygovdan bunu de').steps.length, 1);
});

test('plan steps carry an intent; mygov intent is left to the phone in mock mode', () => {
  assert.equal(planSchema.safeParse({ steps: [{ id: 's1', app: 'mygov', channel: 'ui', goal: 'x', usesResultOf: '' }] }).success, false);
  assert.equal(planSchema.safeParse({ steps: [{ id: 's1', app: 'mygov', channel: 'ui', goal: 'x', usesResultOf: '', intent: 'read' }] }).success, true);
  assert.deepEqual(mockPlan('mygovdan ne qeder pensiya kapitali yigildigimi Metine de').steps.map(s => s.intent), ['unclear', 'action']);
});

test('mock plan splits the demo sentence into bot/ui steps with result dependency', () => {
  const { steps } = mockPlan(demo);
  assert.deepEqual(steps.map(s => [s.app, s.channel, s.usesResultOf]), [
    ['abb', 'bot', ''], ['abb', 'ui', ''], ['mygov', 'bot', ''], ['whatsapp', 'ui', 's3'],
  ]);
  assert.match(steps[1]!.goal, /1 manat yüklə/);
  assert.match(steps[3]!.goal, /Anama/);
});

test('plan schema rejects forward references and bots for apps without one', () => {
  assert.equal(planSchema.safeParse({ steps: [{ id: 's1', app: 'whatsapp', channel: 'ui', goal: 'x', usesResultOf: 's2', intent: 'action' }, { id: 's2', app: 'abb', channel: 'bot', goal: 'y', usesResultOf: '', intent: 'info' }] }).success, false);
  assert.equal(planSchema.safeParse({ steps: [{ id: 's1', app: 'azercell', channel: 'bot', goal: 'x', usesResultOf: '', intent: 'info' }] }).success, false);
});

test('mock bot turn asks once, then reports the bot answer', () => {
  const base = { mode: 'mock' as const, app: 'abb' as const, goal: 'ABB-dən soruş, balans artırmanın komissiyası var?', priorResults: [], clarifications: [] };
  const first = mockBotTurn({ ...base, transcript: [] });
  assert.equal(first.type, 'say'); assert.match(first.text, /^balans artırmanın komissiyası var\?$/);
  const done = mockBotTurn({ ...base, transcript: [{ role: 'agent', text: first.text }, { role: 'bot', text: 'Komissiya tutulmur.' }] });
  assert.deepEqual(done, { type: 'done', text: '', summary: 'Komissiya tutulmur.' });
});

test('toolInput needs exactly one named tool call', () => {
  const usage = { input_tokens: 1, output_tokens: 2 };
  assert.deepEqual(toolInput({ content: [{ type: 'text', text: 'hi' }, { type: 'tool_use', name: 'bot_turn', id: 'x', input: { a: 1 } }], usage }, 'bot_turn').input, { a: 1 });
  assert.throws(() => toolInput({ content: [{ type: 'text', text: 'hi' }], usage }, 'bot_turn'));
  assert.throws(() => toolInput({ content: [{ type: 'tool_use', name: 'other', id: 'x', input: {} }], usage }, 'bot_turn'));
});

test('routes: auth, mock plan/turn, live gated, gateway failure sanitized', async () => {
  const off = createApp({ token, liveEnabled: false });
  try {
    assert.equal((await off.inject({ method: 'POST', url: '/v1/plan', payload: { command: demo, mode: 'mock' } })).statusCode, 401);
    const plan = await off.inject({ method: 'POST', url: '/v1/plan', headers, payload: { command: demo, mode: 'mock' } });
    assert.equal(plan.statusCode, 200); assert.equal(plan.json().steps.length, 4);
    assert.equal((await off.inject({ method: 'POST', url: '/v1/plan', headers, payload: { command: demo, mode: 'live' } })).statusCode, 403);
    const turn = await off.inject({ method: 'POST', url: '/v1/bot-turn', headers, payload: { mode: 'mock', app: 'mygov', goal: 'x', transcript: [], priorResults: [], clarifications: [] } });
    assert.equal(turn.json().turn.type, 'say');
    assert.equal((await off.inject({ method: 'POST', url: '/v1/bot-turn', headers, payload: { mode: 'mock', app: 'kapital', goal: 'x', transcript: [], priorResults: [], clarifications: [] } })).statusCode, 400);
  } finally { await off.close(); }
  const failing: BotBrain = { plan: async () => { throw new BrainFailure(); }, turn: async () => ({ turn: { type: 'done', text: '', summary: 'ok' }, usage: { inputTokens: 3, outputTokens: 4 } }) };
  const live = createApp({ token, liveEnabled: true }, undefined, { brain: failing });
  try {
    const failed = await live.inject({ method: 'POST', url: '/v1/plan', headers, payload: { command: demo, mode: 'live' } });
    assert.equal(failed.statusCode, 502); assert.deepEqual(failed.json(), { error: 'gateway_failure' });
    const turn = await live.inject({ method: 'POST', url: '/v1/bot-turn', headers, payload: { mode: 'live', app: 'abb', goal: 'x', transcript: [], priorResults: [], clarifications: [] } });
    assert.deepEqual(turn.json(), { turn: { type: 'done', text: '', summary: 'ok' }, usage: { inputTokens: 3, outputTokens: 4 } });
  } finally { await live.close(); }
});

test('brain retries (max 3) only when a rotated gateway credential reports exhausted usage', async () => {
  const exhausted = () => Response.json({ type: 'error', error: { type: 'invalid_request_error', message: "You're out of usage. Switch to Auto or Composer 2.5" } }, { status: 400 });
  const planned = () => Response.json({ id: 'm', type: 'message', role: 'assistant', model: 'x', stop_reason: 'tool_use', usage: { input_tokens: 1, output_tokens: 1 },
    content: [{ type: 'tool_use', id: 't', name: 'make_plan', input: { steps: [{ id: 's1', app: 'mygov', channel: 'ui', goal: 'pensiya kapitalım', usesResultOf: '', intent: 'read' }] } }] });
  const run = async (replies: (() => Response)[]) => {
    let calls = 0;
    const fakeFetch: typeof fetch = async () => replies[Math.min(calls++, replies.length - 1)]!();
    const brain = createBrain({ baseURL: 'http://127.0.0.1:8317', authToken: 'synthetic-gateway-token', model: 'm' }, fakeFetch);
    try { return { plan: (await brain.plan('x', new AbortController().signal)).plan, calls }; } catch (error) { return { error, calls }; }
  };
  const recovered = await run([exhausted, exhausted, planned]);
  assert.equal(recovered.calls, 3); assert.equal(recovered.plan?.steps[0]?.intent, 'read');
  const always = await run([exhausted]);
  assert.equal(always.calls, 4); assert.ok(always.error instanceof BrainFailure);
  const other = await run([() => Response.json({ type: 'error', error: { type: 'invalid_request_error', message: 'bad schema' } }, { status: 400 }), planned]);
  assert.equal(other.calls, 1); assert.ok(other.error instanceof BrainFailure);
});
