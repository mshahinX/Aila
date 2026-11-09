import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtemp, stat, rm, readFile, chmod, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { createApp } from '../src/app.js';
import { pairingToken, loadConfig } from '../src/config.js';
import { create, headers, token, next, result } from './helpers.js';

test('health is safe; every session endpoint authenticated; live disabled; device separation', async () => {
  const app = createApp({ token, liveEnabled: false });
  try {
    assert.deepEqual((await app.inject('/healthz')).json(), { ok: true });
    for (const method of ['POST', 'DELETE'] as const) {
      for (const url of ['/v1/sessions', `/v1/sessions/${randomUUID()}/next`, `/v1/sessions/${randomUUID()}`]) {
        const response = await app.inject({ method, url, payload: method === 'POST' ? {} : undefined });
        assert.equal(response.statusCode, 401); assert.deepEqual(response.json(), { error: 'unauthorized' });
      }
    }
    const disabled = await create(app, 'live'); assert.equal(disabled.response.statusCode, 403);
    const mock = await create(app); assert.equal(mock.response.statusCode, 200);
    assert.equal((await create(app, 'mock')).response.statusCode, 401);
    assert.equal((await create(app, 'mock', mock.deviceId)).response.statusCode, 200);
    const bad = await app.inject({ method: 'POST', url: '/v1/sessions', headers: { authorization: `Bearer ${token}x` }, payload: {} }); assert.equal(bad.statusCode, 401);
  } finally { await app.close(); }
});

test('bounds, JSON errors, sensitive/truncated screen, unsupported packages and raw errors never echo input', async () => {
  let calls = 0;
  const app = createApp({ token, liveEnabled: true }, { propose: async () => { calls++; throw new Error('synthetic PRIVATE gateway-secret and tree-text'); } });
  try {
    const { id } = await create(app, 'live');
    const malformed = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers: { ...headers, 'content-type': 'application/json' }, payload: '{"synthetic PRIVATE' });
    assert.equal(malformed.statusCode, 400); assert.deepEqual(malformed.json(), { error: 'invalid_request' });
    for (const observation of [
      { ...next().observation, sensitive: true }, { ...next().observation, truncated: true },
      { ...next().observation, packageName: 'evil.package' },
      { ...next().observation, fingerprint: 'bad' },
    ]) {
      const response = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers, payload: { ...next(), observation } });
      assert.equal(response.statusCode, 400); assert.deepEqual(response.json(), { error: 'invalid_request' });
    }
    assert.equal(calls, 0);
    const big = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers: { ...headers, 'content-type': 'application/json' }, payload: JSON.stringify({ secret: 'x'.repeat(140000) }) });
    assert.equal(big.statusCode, 400); assert.deepEqual(big.json(), { error: 'invalid_request' });
    const response = await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers, payload: next() });
    assert.equal(response.statusCode, 502); assert.deepEqual(response.json(), { error: 'gateway_failure' }); assert.equal(calls, 1);
    assert.equal(response.headers['cache-control'], 'no-store');
    const unknown = await app.inject({ method: 'POST', url: `/v1/sessions/${randomUUID()}/next`, headers, payload: next() }); assert.equal(unknown.statusCode, 404);
    const del = await app.inject({ method: 'DELETE', url: `/v1/sessions/${id}`, headers }); assert.deepEqual(del.json(), { stopped: true });
  } finally { await app.close(); }
});

test('strict request shapes reject arbitrary extra fields, oversize clarification and duplicate nodes', async () => {
  const app = createApp({ token, liveEnabled: false });
  try {
    const { id } = await create(app);
    const n = { id: 'x', resourceId: '', text: '', description: '', className: '', clickable: false, editable: false, scrollable: false, enabled: true };
    for (const payload of [{ ...next(), approval: true }, { ...next(), clarifications: ['x'.repeat(1001)] }, { ...next(), observation: { ...next().observation, nodes: [n, n] } }]) {
      assert.equal((await app.inject({ method: 'POST', url: `/v1/sessions/${id}/next`, headers, payload })).statusCode, 400);
    }
  } finally { await app.close(); }
});

test('pairing token separate, stable, restrictive and symlink resistant; no private files', async () => {
  const dir = await mkdtemp(`${tmpdir()}/phone-agent-test-`);
  try {
    const value = await pairingToken({}, `${dir}/local`);
    assert.equal(value.length, 64); assert.equal(await pairingToken({}, `${dir}/local`), value);
    assert.equal((await stat(`${dir}/local`)).mode & 0o777, 0o700);
    assert.equal((await stat(`${dir}/local/device-token`)).mode & 0o777, 0o600);
    await chmod(`${dir}/local/device-token`, 0o644);
    await pairingToken({}, `${dir}/local`); assert.equal((await stat(`${dir}/local/device-token`)).mode & 0o777, 0o600);
    await symlink(`${dir}/local`, `${dir}/linked`);
    await assert.rejects(pairingToken({}, `${dir}/linked`));
    assert.equal((await readFile(`${dir}/local/device-token`, 'utf8')).trim(), value);
    assert.equal(await pairingToken({ PHONE_AGENT_DEVICE_TOKEN: token }, `${dir}/unused`), token);
    await assert.rejects(pairingToken({ PHONE_AGENT_DEVICE_TOKEN: 'short' }, `${dir}/unused`));
    assert.deepEqual(await loadConfig({ PHONE_AGENT_DEVICE_TOKEN: token, ANTHROPIC_BASE_URL: 'not a url' }), { token, liveEnabled: false });
    await assert.rejects(loadConfig({ PHONE_AGENT_DEVICE_TOKEN: token, PHONE_AGENT_LIVE_ENABLED: 'true' }));
    const env = { PHONE_AGENT_DEVICE_TOKEN: token, PHONE_AGENT_LIVE_ENABLED: 'true', ANTHROPIC_BASE_URL: 'http://127.0.0.1:8317', ANTHROPIC_AUTH_TOKEN: 'synthetic-gateway-secret', ANTHROPIC_MODEL: 'gpt-6.1-sol' };
    const config = await loadConfig(env); assert.equal(config.gateway?.model, env.ANTHROPIC_MODEL); assert.equal(config.gateway?.baseURL, env.ANTHROPIC_BASE_URL);
    await assert.rejects(loadConfig({ ...env, ANTHROPIC_AUTH_TOKEN: token }));
  } finally { await rm(dir, { recursive: true, force: true }); }
});
