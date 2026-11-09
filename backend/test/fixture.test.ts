import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createApp } from '../src/app.js';
import { create, headers, next, node, token } from './helpers.js';

// Real Accessibility snapshots use path IDs and Android's package:id/name syntax.
const nativeNode = (id: string, resource: string, text = '') => node(id, {
  resourceId: `dev.personal.phoneagent:id/${resource}`, text,
});

test('HTTP mock fixture completes using native snapshot-path IDs and exact Android resource IDs', async () => {
  let gatewayCalls = 0;
  const app = createApp({ token, liveEnabled: false }, { propose: async () => {
    gatewayCalls++; throw new Error('Mock must never call a gateway');
  } });
  try {
    const { id, response } = await create(app, 'mock', undefined, 'Run fixture test');
    assert.equal(response.statusCode, 200);
    const states = [
      { screen: 'unavailable' as const, nodes: [], type: 'launch', target: '', text: '' },
      { screen: 'fixture' as const, nodes: [nativeNode('0.1', 'fixture_search')], type: 'set_text', target: '0.1', text: 'Alex' },
      { screen: 'fixture' as const, nodes: [nativeNode('0.1', 'fixture_search', 'Alex'), nativeNode('0.2', 'fixture_contact_alex', 'Alex Example')], type: 'click', target: '0.2', text: '' },
      { screen: 'fixture' as const, nodes: [nativeNode('0.3', 'fixture_entry'), nativeNode('0.4', 'fixture_send', 'Send')], type: 'set_text', target: '0.3', text: 'hello' },
      { screen: 'fixture' as const, nodes: [nativeNode('0.3', 'fixture_entry', 'hello'), nativeNode('0.4', 'fixture_send', 'Send')], type: 'click', target: '0.4', text: '' },
      { screen: 'fixture' as const, nodes: [nativeNode('0.5', 'fixture_status', 'Delivered')], type: 'finish', target: '', text: '' },
    ];
    for (const [sequence, state] of states.entries()) {
      const request = next(sequence, { packageName: 'dev.personal.phoneagent', screen: state.screen, nodes: state.nodes });
      const reply = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers, payload: request });
      assert.equal(reply.statusCode, 200, reply.body);
      const body = reply.json();
      assert.equal(body.requestId, request.requestId);
      assert.equal(body.sequence, sequence);
      assert.equal(body.proposal.snapshotId, request.observation.snapshotId);
      assert.equal(body.proposal.type, state.type);
      assert.equal(body.proposal.targetId, state.target);
      assert.equal(body.proposal.text, state.text);
      assert.deepEqual(body.usage, { inputTokens: 0, outputTokens: 0 });
      if (state.type === 'launch') assert.equal(body.proposal.packageName, 'dev.personal.phoneagent');
      if (state.type === 'click' && state.target === '0.4') {
        const replay = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers, payload: request });
        assert.deepEqual(replay.json(), body);
      }
    }
    assert.equal(gatewayCalls, 0);
  } finally { await app.close(); }
});

test('fixture resource recognition rejects foreign namespaces, suffix lookalikes and bare ID spoofing', async () => {
  const app = createApp({ token, liveEnabled: false });
  try {
    const { id } = await create(app);
    const impostors = [
      node('0.1', { resourceId: 'other.package:id/fixture_search' }),
      node('0.1', { resourceId: 'dev.personal.phoneagent/id/fixture_search' }),
      node('fixture_search', { resourceId: '' }),
      node('0.1', { resourceId: 'fixture_search' }),
      node('0.1', { resourceId: 'prefix/dev.personal.phoneagent:id/fixture_search' }),
    ];
    for (const [sequence, impostor] of impostors.entries()) {
      const request = next(sequence, { packageName: 'dev.personal.phoneagent', screen: 'fixture', nodes: [impostor] });
      const reply = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers, payload: request });
      assert.equal(reply.statusCode, 200);
      assert.equal(reply.json().proposal.type, 'manual');
    }
  } finally { await app.close(); }
});

test('mock fixture uses a quoted synthetic demo draft from the command', async () => {
  const { mockPlan } = await import('../src/planner.js');
  const command = 'Run the safe fixture test: draft: "Salam, sənə 50 manat göndərdim." to Alex Example and request approval.';
  const empty = next(0, { packageName: 'dev.personal.phoneagent', screen: 'fixture', nodes: [nativeNode('0.3', 'fixture_entry'), nativeNode('0.4', 'fixture_send', 'Send')] });
  const typed = mockPlan(command, empty as never);
  assert.equal(typed.type, 'set_text');
  assert.equal(typed.text, 'Salam, sənə 50 manat göndərdim.');
  const filled = next(1, { packageName: 'dev.personal.phoneagent', screen: 'fixture', nodes: [nativeNode('0.3', 'fixture_entry', 'Salam, sənə 50 manat göndərdim.'), nativeNode('0.4', 'fixture_send', 'Send')] });
  assert.equal(mockPlan(command, filled as never).type, 'click');
  const other = next(2, { packageName: 'dev.personal.phoneagent', screen: 'fixture', nodes: [nativeNode('0.3', 'fixture_entry', 'hello'), nativeNode('0.4', 'fixture_send', 'Send')] });
  assert.equal(mockPlan(command, other as never).type, 'ask_user');
});
