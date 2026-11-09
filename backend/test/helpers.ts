import { randomUUID } from 'node:crypto';
import { action, nextSchema, type Action, type Next } from '../src/protocol.js';
export const token = 'synthetic-device-token-1234567890123456';
export const headers = { authorization: `Bearer ${token}` };
export function next(sequence = 0, overrides: Partial<Next['observation']> = {}): Next {
  return nextSchema.parse({ requestId: randomUUID(), sequence,
    observation: { snapshotId: randomUUID(), fingerprint: 'a'.repeat(64), packageName: 'com.whatsapp', windowId: 1, capturedAt: Date.now(), screen: 'chat', nodes: [], truncated: false, sensitive: false, ...overrides }, previousResult: null, clarifications: [] });
}
export function node(id: string, values = {}) {
  return { id, resourceId: `dev.personal.phoneagent:id/${id}`, text: '', description: '', className: 'android.widget.EditText', clickable: true, editable: true, scrollable: false, enabled: true, ...values };
}
export const result = (a: Action = action('ask_user', { question: 'Which synthetic contact?' })) => ({ action: a, usage: { inputTokens: 12, outputTokens: 8 } });
export async function create(app: ReturnType<typeof import('../src/app.js').createApp>, mode = 'mock', deviceId = randomUUID(), command = 'Run fixture test') {
  const response = await app.inject({ method: 'POST', url: '/v1/sessions', headers, payload: { deviceId, command, mode } });
  return { response, id: response.json().sessionId as string, deviceId };
}
export function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r; }); return { promise, resolve }; }
