import { constants } from 'node:fs';
import { chmod, mkdir, open, lstat } from 'node:fs/promises';
import { randomBytes } from 'node:crypto';
import { fileURLToPath } from 'node:url';

export type Config = { token: string; liveEnabled: boolean; gateway?: { baseURL: string; authToken: string; model: string }; liveMaxRequests?: number; liveWindowMs?: number };
const local = fileURLToPath(new URL('../.local/', import.meta.url));
// dist/src/config.js is one level deeper than src/config.ts.
const tokenDir = import.meta.url.includes('/dist/src/') ? fileURLToPath(new URL('../../.local/', import.meta.url)) : local;
export async function pairingToken(env: NodeJS.ProcessEnv = process.env, directory = tokenDir): Promise<string> {
  if (env.PHONE_AGENT_DEVICE_TOKEN !== undefined) return checkToken(env.PHONE_AGENT_DEVICE_TOKEN);
  await mkdir(directory, { mode: 0o700, recursive: true });
  const dir = await lstat(directory);
  if (!dir.isDirectory() || dir.isSymbolicLink() || dir.uid !== process.getuid?.()) throw new Error('pairing storage unavailable');
  await chmod(directory, 0o700);
  const path = `${directory}/device-token`;
  try {
    const file = await open(path, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try { await file.writeFile(randomBytes(32).toString('hex') + '\n'); } finally { await file.close(); }
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== 'EEXIST') throw new Error('pairing storage unavailable');
  }
  const file = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await file.stat();
    if (!stat.isFile() || stat.uid !== process.getuid?.() || stat.size > 256 || stat.nlink !== 1) throw new Error('pairing storage unavailable');
    await file.chmod(0o600);
    return checkToken((await file.readFile('utf8')).trim());
  } finally { await file.close(); }
}
function checkToken(value: string): string {
  if (!/^[A-Za-z0-9_\-]{32,128}$/.test(value)) throw new Error('invalid pairing configuration');
  return value;
}
export async function loadConfig(env: NodeJS.ProcessEnv = process.env): Promise<Config> {
  const token = await pairingToken(env);
  if (token === env.ANTHROPIC_AUTH_TOKEN) throw new Error('pairing and gateway credentials must differ');
  const liveEnabled = env.PHONE_AGENT_LIVE_ENABLED === 'true';
  if (!liveEnabled) return { token, liveEnabled };
  const { ANTHROPIC_BASE_URL: baseURL, ANTHROPIC_AUTH_TOKEN: authToken, ANTHROPIC_MODEL: model } = env;
  if (!baseURL || !authToken || !model) throw new Error('missing gateway configuration');
  let url: URL;
  try { url = new URL(baseURL); } catch { throw new Error('invalid gateway configuration'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('invalid gateway configuration');
  if (token === authToken) throw new Error('pairing and gateway credentials must differ');
  // Optional operator overrides for the live allowance (defaults: 15 requests / 10 minutes).
  const positive = (v: string | undefined) => { const n = Number(v); return v && Number.isInteger(n) && n > 0 ? n : undefined; };
  return { token, liveEnabled, gateway: { baseURL, authToken, model },
    liveMaxRequests: positive(env.PHONE_AGENT_LIVE_MAX_REQUESTS), liveWindowMs: positive(env.PHONE_AGENT_LIVE_WINDOW_MINUTES) && positive(env.PHONE_AGENT_LIVE_WINDOW_MINUTES)! * 60_000 };
}
