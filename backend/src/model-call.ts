import Anthropic from '@anthropic-ai/sdk';

/** Opus 5.5 rejects forced tool_choice, so the system prompts ask for the tool and sendForTool checks it was called. */
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

const diag = (text: string) => { if (process.env.PHONE_AGENT_DIAGNOSTICS === 'true') console.error(`[diag] ${text}`); };

/**
 * Sends one model request. Retries up to 3 times when an account is out of usage (other errors are not retried), and
 * asks once more if the model answered in prose, which tool_choice auto allows. The callers still validate the reply.
 */
export async function sendForTool(create: () => PromiseLike<Anthropic.Message>, signal: AbortSignal, label: string): Promise<Anthropic.Message> {
  let attempt = 0;
  let askedAgain = false;
  for (;;) {
    try {
      const message = await create();
      const answeredInProse = message.stop_reason === 'end_turn' && !message.content.some(block => block.type === 'tool_use');
      if (!answeredInProse || askedAgain || signal.aborted) return message;
      askedAgain = true;
      diag(`${label}: no tool call; asking once more`);
    } catch (error) {
      if (attempt >= 3 || !exhausted(error, signal)) throw error;
      attempt++;
      diag(`${label}: gateway credential exhausted; retry ${attempt}/3`);
    }
  }
}
