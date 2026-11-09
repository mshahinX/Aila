import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Sessions } from '../src/sessions.js';
import { GatewayFailure, type GatewayResult } from '../src/gateway.js';
import { action } from '../src/protocol.js';
import { next, result, deferred, node } from './helpers.js';
const input = () => ({ deviceId: randomUUID(), command: 'Draft hello', mode: 'live' as const });
const isStatus = (status: number) => (e: any) => e.status === status;

test('device bound session, unknown/stale sequences and snapshots, request replay in-flight and completed', async () => {
  let calls = 0; const pending = deferred<GatewayResult>(); const started = deferred<void>();
  const sessions = new Sessions({ liveEnabled: true, gateway: { propose: async () => { calls++; started.resolve(); return pending.promise; } } });
  try {
    const i = input(); const s = sessions.create(i);
    assert.throws(() => sessions.create({ ...i, deviceId: randomUUID() }), isStatus(401));
    await assert.rejects(sessions.next(randomUUID(), next()), isStatus(404));
    await assert.rejects(sessions.next(s.sessionId, next(1)), isStatus(409));
    const request = next();
    const first = sessions.next(s.sessionId, request); await started.promise;
    const duplicate = sessions.next(s.sessionId, request);
    await assert.rejects(sessions.next(s.sessionId, next()), isStatus(409));
    const secondSession = sessions.create(i);
    await assert.rejects(sessions.next(secondSession.sessionId, next()), isStatus(409));
    pending.resolve(result());
    const value = await first;
    assert.deepEqual(await duplicate, value);
    assert.deepEqual(await sessions.next(s.sessionId, request), value);
    assert.equal(calls, 1);
    await assert.rejects(sessions.next(s.sessionId, { ...request, sequence: 1 }), isStatus(409));
    await assert.rejects(sessions.next(s.sessionId, { ...request, requestId: randomUUID(), sequence: 1 }), isStatus(409));
    assert.equal(value.proposal.snapshotId, request.observation.snapshotId);
    assert.match(value.proposal.actionId, /^[0-9a-f-]{36}$/);
  } finally { sessions.close(); }
});

test('delete cancels inference even with transport ignoring abort; no late proposal', async () => {
  const pending = deferred<GatewayResult>(); const started = deferred<void>(); let signal!: AbortSignal;
  const sessions = new Sessions({ liveEnabled: true, gateway: { propose: async (_i, s) => { signal = s; started.resolve(); return pending.promise; } } });
  const id = sessions.create(input()).sessionId;
  const inference = sessions.next(id, next()); await started.promise;
  assert.deepEqual(sessions.stop(id), { stopped: true });
  await assert.rejects(inference, isStatus(404)); assert.equal(signal.aborted, true);
  pending.resolve(result()); await assert.rejects(sessions.next(id, next()), isStatus(404)); sessions.close();
});

test('expiry invalidates and aborts pending work', async () => {
  let now = 0; const started = deferred<void>(); const pending = deferred<GatewayResult>(); let signal!: AbortSignal;
  const sessions = new Sessions({ liveEnabled: true, now: () => now, ttlMs: 100, gateway: { propose: async (_i, s) => { signal = s; started.resolve(); return pending.promise; } } });
  const id = sessions.create(input()).sessionId;
  const inference = sessions.next(id, next()); await started.promise; now = 101;
  await assert.rejects(sessions.next(id, next()), isStatus(404));
  await assert.rejects(inference, isStatus(404)); assert.equal(signal.aborted, true); pending.resolve(result()); sessions.close();
});

test('expiry timer cancels an in-flight request without needing another client request', async () => {
  const started = deferred<void>(); const pending = deferred<GatewayResult>();
  const sessions = new Sessions({ liveEnabled: true, ttlMs: 30, gateway: { propose: async () => { started.resolve(); return pending.promise; } } });
  const keepAlive = setTimeout(() => {}, 1000);
  try {
    const id = sessions.create(input()).sessionId;
    const inference = sessions.next(id, next()); await started.promise;
    await assert.rejects(inference, isStatus(404)); pending.resolve(result());
  } finally { clearTimeout(keepAlive); sessions.close(); }
});

test('failed attempts consume step/global allowance; retries are cached; new sessions cannot reset live budget', async () => {
  let calls = 0;
  const sessions = new Sessions({ liveEnabled: true, gateway: { propose: async () => { calls++; throw new GatewayFailure({ inputTokens: 5, outputTokens: 2 }); } } });
  try {
    const i = input(); const id = sessions.create(i).sessionId;
    const request = next();
    await assert.rejects(sessions.next(id, request), isStatus(502));
    await assert.rejects(sessions.next(id, request), isStatus(502)); assert.equal(calls, 1);
    for (let sequence = 1; sequence < 15; sequence++) await assert.rejects(sessions.next(id, next(sequence)), isStatus(502));
    await assert.rejects(sessions.next(id, next(15)), isStatus(429));
    const newId = sessions.create(i).sessionId;
    await assert.rejects(sessions.next(newId, next()), isStatus(429)); assert.equal(calls, 15);
  } finally { sessions.close(); }
});

test('clarifications retained and bounded; target is validated independently; current request usage only', async () => {
  let count = 0;
  const sessions = new Sessions({ liveEnabled: true, gateway: { propose: async i => {
    count++;
    if (count === 2) assert.ok(i.clarifications.includes('Alex Example'));
    assert.ok(i.clarifications.join('').length <= 12000); assert.ok(i.clarifications.length <= 16);
    return result(count === 1 ? action('click', { targetId: 'missing' }) : action('wait', { durationMs: 1 }));
  } } });
  try {
    const id = sessions.create(input()).sessionId;
    await assert.rejects(sessions.next(id, { ...next(), clarifications: ['Alex Example'] }), isStatus(502));
    const value = await sessions.next(id, next(1)); assert.deepEqual(value.usage, { inputTokens: 12, outputTokens: 8 });
    for (let seq = 2; seq < 15; seq++) await sessions.next(id, { ...next(seq), clarifications: Array.from({ length: 8 }, (_, i) => `answer-${seq}-${i}-`.padEnd(1000, 'x')) });
  } finally { sessions.close(); }
});

test('banking and credential/identifier data never reach gateway; manual and agent approval boundaries', async () => {
  let calls = 0;
  const sessions = new Sessions({ liveEnabled: true, gateway: { propose: async () => { calls++; return result(); } } });
  try {
    const id = sessions.create(input()).sessionId;
    const observations = [
      { packageName: 'az.kapitalbank.mbanking' as const },
      { screen: 'manual' as const },
      { packageName: 'dev.personal.phoneagent' as const, screen: 'chat' as const },
      { nodes: [node('x', { text: '4111 1111 1111 1111' })] },
      { nodes: [node('x', { resourceId: 'app:id/password' })] },
    ];
    for (let seq = 0; seq < observations.length; seq++) assert.equal((await sessions.next(id, next(seq, observations[seq]))).proposal.type, 'manual');
    assert.equal(calls, 0);
  } finally { sessions.close(); }
});

test('mock fixture full sequence only synthetic; draft preserved; Send is not reproposed', async () => {
  const sessions = new Sessions({ liveEnabled: false });
  try {
    const i = { ...input(), mode: 'mock' as const, command: 'Run fixture test' }; const id = sessions.create(i).sessionId;
    const fixture = (seq: number, nodes: ReturnType<typeof node>[]) => next(seq, { packageName: 'dev.personal.phoneagent', screen: 'fixture', nodes });
    assert.equal((await sessions.next(id, next(0, { packageName: 'dev.personal.phoneagent', screen: 'unavailable' }))).proposal.type, 'launch');
    assert.equal((await sessions.next(id, fixture(1, [node('fixture_search')]))).proposal.text, 'Alex');
    assert.equal((await sessions.next(id, fixture(2, [node('fixture_search', { text: 'Alex' }), node('fixture_contact_alex')]))).proposal.targetId, 'fixture_contact_alex');
    assert.equal((await sessions.next(id, fixture(3, [node('fixture_entry', { text: 'existing draft' })]))).proposal.type, 'ask_user');
    assert.equal((await sessions.next(id, fixture(4, [node('fixture_entry')]))).proposal.text, 'hello');
    const ready = [node('fixture_entry', { text: 'hello' }), node('fixture_send')];
    assert.equal((await sessions.next(id, fixture(5, ready))).proposal.targetId, 'fixture_send');
    assert.equal((await sessions.next(id, fixture(6, ready))).proposal.type, 'manual');
    assert.equal((await sessions.next(id, fixture(7, [node('fixture_status', { text: 'Delivered' })]))).proposal.type, 'finish');
    assert.equal((await sessions.next(id, next(8))).proposal.type, 'manual');
  } finally { sessions.close(); }
});

test('ten minute task expiry and process allowance deadline; session count bounded', async () => {
  let now = 0;
  const sessions = new Sessions({ liveEnabled: true, now: () => now, gateway: { propose: async () => result() } });
  try {
    const i = input(); const id = sessions.create(i).sessionId; await sessions.next(id, next());
    now = 599999;
    const later = sessions.create(i).sessionId; now = 600001;
    await assert.rejects(sessions.next(id, next(1)), isStatus(404));
    await assert.rejects(sessions.next(later, next()), isStatus(429));
    for (let x = 0; x < 31; x++) sessions.create(i);
    assert.throws(() => sessions.create(i), isStatus(429));
  } finally { sessions.close(); }
});
