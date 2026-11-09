import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createGateway, compactScreen, GatewayFailure, type GatewayResult, type PlannerInput } from '../src/gateway.js';
import { Sessions } from '../src/sessions.js';
import { action, validateTarget } from '../src/protocol.js';
import { createBrain, mockPlan, planSchema } from '../src/bots.js';
import { next } from './helpers.js';

const config = { baseURL: 'http://127.0.0.1:8317', authToken: 'synthetic-gateway-token', model: 'claude-opus-5-5' };
const wa = (id: string, rid: string, values: Record<string, unknown> = {}) => ({ id, resourceId: `com.whatsapp:id/${rid}`, text: '', description: '', className: 'android.widget.TextView', clickable: true, editable: false, scrollable: false, enabled: true, ...values });
const screen = () => next(0, { nodes: [
  wa('0.1', 'search_input', { className: 'android.widget.EditText', editable: true, text: '' }),
  wa('0.2', 'search_go', { description: 'Search' }),
  wa('0.3', 'send', { description: 'Send' }),
  wa('0.4', 'confirm_btn', { text: 'Göndər' }),
  wa('0.5', 'row', { className: 'android.view.ViewGroup' }), wa('0.5.0', 'row_name', { clickable: false, text: 'Synthetic Contact' }),
] });
const reply = (input: unknown, stop_reason = 'tool_use') => () => Response.json({ id: 'm', type: 'message', role: 'assistant', model: 'x', stop_reason, stop_sequence: null,
  usage: { input_tokens: 100, output_tokens: 20 }, content: [{ type: 'thinking', thinking: '', signature: 's' }, ...(input === undefined ? [{ type: 'text', text: 'prose' }] : [{ type: 'tool_use', id: 't', name: 'next_action', input }])] });
const raw = (type: string, fields: Record<string, unknown> = {}) => ({ type, targetId: '', text: '', packageName: '', question: '', message: '', direction: 'forward', durationMs: 0, thenClickId: '', ...fields });

async function propose(replies: (() => Response)[], input: Partial<PlannerInput> = {}) {
  const bodies: Record<string, any>[] = [];
  const fakeFetch: typeof fetch = async (i, init) => { bodies.push(await (i instanceof Request ? i : new Request(i, init)).json() as Record<string, any>); return replies[Math.min(bodies.length - 1, replies.length - 1)]!(); };
  const gateway = createGateway(config, fakeFetch);
  try { return { result: await gateway.propose({ command: 'synthetic', clarifications: [], request: screen(), ...input }, new AbortController().signal), bodies }; }
  catch (error) { return { error, bodies }; }
}

test('an unusable reply is asked again once with a content-free note; usage of both attempts is counted', async () => {
  for (const bad of [raw('click', { targetId: '9.9' }), raw('click', { targetId: '0.5.0' }), raw('click'), undefined]) {
    const r = await propose([reply(bad, bad === undefined ? 'end_turn' : 'tool_use'), reply(raw('click', { targetId: '0.2' }))]);
    assert.equal(r.bodies.length, 2); assert.equal(r.result?.action.targetId, '0.2');
    assert.equal(r.result?.usage.inputTokens, 200);
    const note = r.bodies[1]!.messages[0].content[1].text as string;
    assert.match(note, /could not be used/); assert.doesNotMatch(note, /Synthetic Contact/);
  }
  const stubborn = await propose([reply(raw('click', { targetId: '9.9' }))]);
  assert.equal(stubborn.bodies.length, 2); assert.ok(stubborn.error instanceof GatewayFailure); assert.equal((stubborn.error as GatewayFailure).usage.inputTokens, 200);
  const once = await propose([reply(raw('click', { targetId: '9.9' }))], { maxAttempts: 1 });
  assert.equal(once.bodies.length, 1);
  for (const stop of ['refusal', 'max_tokens']) assert.equal((await propose([reply(undefined, stop)])).bodies.length, 1);
});

test('follow-up taps: only with set_text, never on send/pay/confirm controls, dropped (not failed) when unusable', () => {
  const r = screen();
  assert.equal(validateTarget(raw('set_text', { targetId: '0.1', text: 'Synthetic', thenClickId: '0.2' }), r).thenClickId, '0.2');
  for (const commit of ['0.3', '0.4', '9.9', '0.5.0']) {
    const a = validateTarget(raw('set_text', { targetId: '0.1', text: 'Synthetic', thenClickId: commit }), r);
    assert.equal(a.thenClickId, ''); assert.equal(a.type, 'set_text');
  }
  assert.throws(() => validateTarget(raw('click', { targetId: '0.2', thenClickId: '0.5' }), r));
  assert.throws(() => validateTarget(raw('set_text', { targetId: '0.1', text: 'x', thenClickId: '0.1' }), r));
});

test('compact screen keeps ids and meaning, drops protocol fields, app prefix, layout classes and false flags', () => {
  const c = compactScreen(screen().observation);
  assert.equal(c.app, 'com.whatsapp');
  assert.deepEqual(c.nodes[0], { id: '0.1', rid: 'search_input', cls: 'EditText', flags: 'click edit' });
  assert.deepEqual(c.nodes[4], { id: '0.5', rid: 'row', flags: 'click' });
  assert.deepEqual(c.nodes[5], { id: '0.5.0', rid: 'row_name', text: 'Synthetic Contact', cls: 'TextView' });
});

test('session keeps a short action log with the phone results and passes it to the model; cleared on stop', async () => {
  const seen: PlannerInput[] = [];
  const queue: GatewayResult[] = [
    { action: action('set_text', { targetId: '0.1', text: 'Synthetic', thenClickId: '0.2' }), usage: { inputTokens: 1, outputTokens: 1 } },
    { action: action('click', { targetId: '0.5' }), usage: { inputTokens: 1, outputTokens: 1 } },
    { action: action('wait', { durationMs: 100 }), usage: { inputTokens: 1, outputTokens: 1 } },
  ];
  const sessions = new Sessions({ liveEnabled: true, gateway: { propose: async input => { seen.push(input); return queue.shift()!; } } });
  try {
    const id = sessions.create({ deviceId: randomUUID(), command: 'Synthetic', mode: 'live' }).sessionId;
    await sessions.next(id, screen());
    await sessions.next(id, { ...screen(), sequence: 1, previousResult: 'Text entry verified in the selected field.' });
    await sessions.next(id, { ...screen(), sequence: 2, previousResult: 'Screen changed after the action; inspect the new observation.' });
    assert.deepEqual(seen[0]!.history, []);
    assert.deepEqual(seen[1]!.history, [{ action: 'set_text', target: 'search_input', typed: 'Synthetic', then: 'Search', result: 'Text entry verified in the selected field.' }]);
    assert.deepEqual(seen[2]!.history?.[1], { action: 'click', target: 'Synthetic Contact', typed: '', then: '', result: 'Screen changed after the action; inspect the new observation.' });
    assert.equal(seen[0]!.maxAttempts, 2);
    sessions.stop(id);
  } finally { sessions.close(); }
});

test('a re-ask counts against the live allowance, and is not offered when the allowance is used up', async () => {
  const seen: PlannerInput[] = [];
  const sessions = new Sessions({ liveEnabled: true, liveMaxRequests: 2, gateway: { propose: async input => {
    seen.push(input); input.onAttempt?.(1); input.onAttempt?.(2); return { action: action('wait', { durationMs: 100 }), usage: { inputTokens: 1, outputTokens: 1 } };
  } } });
  try {
    const id = sessions.create({ deviceId: randomUUID(), command: 'Synthetic', mode: 'live' }).sessionId;
    await sessions.next(id, screen());
    assert.equal(seen[0]!.maxAttempts, 2);
    await assert.rejects(sessions.next(id, { ...screen(), sequence: 1 }), (e: any) => e.status === 429);
  } finally { sessions.close(); }
});

test('plan carries the first chatbot message for bot steps only', async () => {
  const steps = mockPlan('ABB-dən soruş, balans artırmanın komissiyası var? Sonra nömrəmə 1 manat yüklə.').steps;
  assert.equal(steps[0]!.firstMessage, 'balans artırmanın komissiyası var?');
  assert.equal(steps[1]!.firstMessage, '');
  assert.equal(planSchema.parse({ steps: [{ id: 's1', app: 'mygov', channel: 'ui', goal: 'x', usesResultOf: '', intent: 'read' }] }).steps[0]!.firstMessage, '');
  const step = { id: 's1', app: 'abb', channel: 'ui', goal: '1 manat yüklə', usesResultOf: '', intent: 'action', firstMessage: 'should be dropped' };
  const planned = (steps: unknown[]) => Response.json({ id: 'm', type: 'message', role: 'assistant', model: 'x', stop_reason: 'tool_use', usage: { input_tokens: 1, output_tokens: 1 },
    content: [{ type: 'tool_use', id: 't', name: 'make_plan', input: { steps } }] });
  let calls = 0;
  const replies = [planned([{ ...step, usesResultOf: 's2' }]), planned([step])];
  const brain = createBrain(config, async () => replies[Math.min(calls++, 1)]!);
  const { plan } = await brain.plan('x', new AbortController().signal);
  assert.equal(calls, 2, 'invalid plan (forward reference) is asked again once');
  assert.equal(plan.steps[0]!.firstMessage, '');
});
