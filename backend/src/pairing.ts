import { pairingToken } from './config.js';
// This command intentionally prints ONLY the dedicated local device token.
try {
  const token = await pairingToken();
  if (token === process.env.ANTHROPIC_AUTH_TOKEN) throw new Error('invalid pairing token');
  process.stdout.write(`${token}\n`);
} catch {
  process.stderr.write('Pairing setup unavailable (details redacted).\n');
  process.exitCode = 1;
}
