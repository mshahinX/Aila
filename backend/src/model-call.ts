import Anthropic from '@anthropic-ai/sdk';
import { z } from 'zod';
import type { Usage } from './protocol.js';

/** Opus 5.5 rejects forced tool_choice, so the system prompts ask for the tool and askTool checks it was called. */
export const toolChoice = { type: 'auto', disable_parallel_tool_use: true } as const;
/** Opus 5.5 always thinks and thinking counts toward this limit. Only tokens actually generated use up quota. */
export const maxTokens = 16_000;
/** Well above the slowest turn seen so far (8 s) and inside the phone's 90 s read timeout. */
export const timeoutMs = 60_000;

const outOfUsage = /out of usage|usage limit|rate.?limit|quota|exhausted|increase (your )?limit|cooling down|model_cooldown|no auth available|auth_unavailable|overloaded/i;
const rejectionStatuses = new Set([400, 429, 500, 503, 529]);
/** The gateway rotates accounts: a rejection for an account that is out of usage generated nothing, so asking again is safe. */
export const exhausted = (error: unknown, signal: AbortSignal) =>
  !signal.aborted && error instanceof Anthropic.APIError && rejectionStatuses.has(error.status as number) && outOfUsage.test(error.message);

export const diag = (text: string) => { if (process.env.PHONE_AGENT_DIAGNOSTICS === 'true') console.error(`[diag] ${text}`); };

/** Sends one model request. Retries up to 3 times when a rotated gateway account is out of usage (nothing was generated). */
export async function send(create: () => PromiseLike<Anthropic.Message>, signal: AbortSignal, label: string): Promise<Anthropic.Message> {
  for (let attempt = 0; ; attempt++) {
    try { return await create(); }
    catch (error) {
      if (attempt >= 3 || !exhausted(error, signal)) throw error;
      diag(`${label}: gateway credential exhausted; retry ${attempt + 1}/3`);
    }
  }
}

/** A reply that could not be used after the allowed attempts. [reason] is content-free (field paths, codes, node ids). */
export class Unusable extends Error {
  constructor(public readonly reason: string, public readonly usage: Usage) { super('unusable reply'); }
}
/** Content-free description of why a reply was rejected: Zod paths/codes or our own fixed reasons, never values. */
export function reasonOf(error: unknown): string {
  if (error instanceof z.ZodError) return error.issues.slice(0, 5).map(i => `${i.path.join('.') || 'reply'} ${i.code}`).join('; ');
  const reason = (error as { reason?: unknown })?.reason;
  return typeof reason === 'string' ? reason : 'reply was not one valid tool call';
}
function usageOf(message: unknown): Usage {
  const u = (message as { usage?: { input_tokens?: unknown; output_tokens?: unknown } })?.usage;
  return Number.isInteger(u?.input_tokens) && Number.isInteger(u?.output_tokens)
    ? { inputTokens: u!.input_tokens as number, outputTokens: u!.output_tokens as number } : { inputTokens: 0, outputTokens: 0 };
}

/**
 * Asks for one tool call and checks the reply with [use]. An unusable reply (no tool call, prose, invalid fields, a
 * target that is not on the screen) is asked again once with a content-free note saying what was wrong: nothing has
 * happened on the phone at that point (owner's decision, 2026-11-09). Refusals and truncated replies are not asked again.
 * Transport errors propagate unchanged.
 */
export async function askTool<T>(options: {
  label: string; tool: string; signal: AbortSignal; maxAttempts?: number;
  /** Called before each generating attempt with its number (1-based), e.g. to count it against a budget. */
  onAttempt?: (attempt: number) => void;
  create: (note: string) => PromiseLike<Anthropic.Message>;
  use: (message: Anthropic.Message) => T;
}): Promise<{ value: T; usage: Usage; attempts: number }> {
  const usage: Usage = { inputTokens: 0, outputTokens: 0 };
  const max = Math.max(1, options.maxAttempts ?? 2);
  let note = '';
  for (let attempt = 1; ; attempt++) {
    options.onAttempt?.(attempt);
    const started = Date.now();
    const message = await send(() => options.create(note), options.signal, options.label);
    const u = usageOf(message);
    usage.inputTokens += u.inputTokens; usage.outputTokens += u.outputTokens;
    diag(`model ${options.label} attempt=${attempt} ${Date.now() - started}ms in=${u.inputTokens} out=${u.outputTokens} stop=${String(message?.stop_reason)}`);
    try { return { value: options.use(message), usage, attempts: attempt }; }
    catch (error) {
      const reason = reasonOf(error);
      const final = ['refusal', 'max_tokens'].includes(String(message?.stop_reason));
      if (attempt >= max || final || options.signal.aborted) throw new Unusable(reason, usage);
      diag(`${options.label}: reply unusable (${reason}); asking once more`);
      note = `Your previous reply could not be used (${reason}). Call ${options.tool} exactly once, with every field valid for the current input.`;
    }
  }
}
