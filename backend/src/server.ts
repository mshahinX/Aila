import { loadConfig } from './config.js';
import { createGateway } from './gateway.js';
import { createApp } from './app.js';
import { createBrain } from './bots.js';

async function main() {
  const config = await loadConfig();
  const app = createApp(config, config.gateway ? createGateway(config.gateway) : undefined, { brain: config.gateway ? createBrain(config.gateway) : undefined });
  const shutdown = async () => { await app.close(); };
  process.once('SIGINT', shutdown); process.once('SIGTERM', shutdown);
  const port = Number(process.env.PHONE_AGENT_PORT ?? 8787);
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('invalid port');
  await app.listen({ host: '127.0.0.1', port });
  process.stdout.write(`Phone Agent backend listening on http://127.0.0.1:${port}; pairing token hidden; live requests require explicit enablement.\n`);
}
main().catch(() => { process.stderr.write('Backend startup failed (details redacted). Check configuration and local port/storage.\n'); process.exitCode = 1; });
