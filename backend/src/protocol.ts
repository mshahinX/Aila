import { z } from 'zod';

export const packages = ['com.whatsapp', 'az.gov.my', 'az.kapitalbank.mbanking', 'iba.mobilbank', 'dev.personal.phoneagent'] as const;
const uuid = z.string().uuid();
const short = z.string().max(1000);
export const createSchema = z.strictObject({ deviceId: uuid, command: z.string().trim().min(1).max(4000), mode: z.enum(['mock', 'live']) });
const nodeSchema = z.strictObject({
  id: z.string().min(1).max(128), resourceId: z.string().max(256), text: short,
  description: short, className: z.string().max(256), clickable: z.boolean(),
  editable: z.boolean(), scrollable: z.boolean(), enabled: z.boolean(),
});
export const nextSchema = z.strictObject({
  requestId: uuid, sequence: z.number().int().min(0).max(1000000),
  observation: z.strictObject({
    snapshotId: uuid, fingerprint: z.string().regex(/^[a-fA-F0-9]{64}$/),
    packageName: z.enum(packages), windowId: z.number().int().min(-1).max(2147483647),
    capturedAt: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    screen: z.enum(['chat', 'search', 'chats', 'fixture', 'manual', 'unavailable', 'browse']),
    nodes: z.array(nodeSchema).max(150), truncated: z.literal(false), sensitive: z.literal(false),
  }).superRefine((o, ctx) => {
    if (new Set(o.nodes.map(n => n.id)).size !== o.nodes.length) ctx.addIssue({ code: 'custom', message: 'duplicate nodes' });
  }),
  previousResult: z.string().max(2000).nullable(), clarifications: z.array(short).max(8),
});
export const actionSchema = z.strictObject({
  type: z.enum(['launch', 'click', 'set_text', 'scroll', 'back', 'wait', 'ask_user', 'finish', 'manual']),
  targetId: z.string().max(128), text: z.string().max(4000), packageName: z.string().max(100),
  question: short, message: z.string().max(2000), direction: z.enum(['forward', 'backward']),
  durationMs: z.number().int().min(0).max(2000),
  /** set_text only: a clickable node in the same snapshot to tap right after typing (search, Next). Never a commit control. */
  thenClickId: z.string().max(128),
}).superRefine((a, ctx) => {
  const fail = () => ctx.addIssue({ code: 'custom', message: 'invalid action fields' });
  if (a.thenClickId && (a.type !== 'set_text' || a.thenClickId === a.targetId)) fail();
  if ((['click', 'set_text', 'scroll'].includes(a.type)) !== Boolean(a.targetId)) fail();
  if (a.type !== 'set_text' && a.text !== '') fail();
  if (a.type === 'launch' ? !(packages as readonly string[]).includes(a.packageName) : a.packageName !== '') fail();
  if (a.type === 'ask_user' ? !a.question.trim() : a.question !== '') fail();
  if (!['finish', 'manual'].includes(a.type) && a.message !== '') fail();
  if (['finish', 'manual'].includes(a.type) && !a.message.trim()) fail();
  if (a.type === 'wait' ? a.durationMs < 1 : a.durationMs !== 0) fail();
  if (a.type !== 'scroll' && a.direction !== 'forward') fail();
});
export type Action = z.infer<typeof actionSchema>;
export type Next = z.infer<typeof nextSchema>;
export type Create = z.infer<typeof createSchema>;
export type Usage = { inputTokens: number; outputTokens: number };
export type Proposal = Action & { actionId: string; snapshotId: string };
export type NextResponse = { requestId: string; sequence: number; proposal: Proposal; usage: Usage };
export function action(type: Action['type'], fields: Partial<Action> = {}): Action {
  return actionSchema.parse({ type, targetId: '', text: '', packageName: '', question: '', message: '', direction: 'forward', durationMs: 0, thenClickId: '', ...fields });
}
type Node = Next['observation']['nodes'][number];
/** Visible label of a node: its own text/description, else the first one inside it, else its resource name. */
export function nodeLabel(nodes: Node[], n: Node): string {
  const own = [n.text, n.description].find(v => v.trim());
  const inner = own ?? nodes.filter(c => c.id.startsWith(n.id + '.')).flatMap(c => [c.text, c.description]).find(v => v.trim());
  return (inner ?? n.resourceId.split(':id/').pop() ?? '').trim().replace(/\s+/g, ' ').slice(0, 60);
}
/**
 * Controls that commit something (send, pay, confirm, submit, sign, delete) never ride along as a follow-up tap:
 * they always need their own step, where the phone's approval and adapter rules apply. Defense in depth; the phone
 * re-checks the follow-up tap against its own policy before performing it.
 */
const commitId = /(^|[/_])(send|fixture_send|confirm|submit|pay|delete)([_]|$)/i;
const commitLabel = /\b(send|pay|confirm|submit|sign|delete|remove|transfer|buy|order|apply|log ?out)\b|göndər|ödə|təsdiq|imzala|\bsil\b|köçür|tamamla|müraciət|çıxış|sifariş/i;
export function isCommitControl(nodes: Node[], n: Node): boolean {
  return commitId.test(n.resourceId) || commitLabel.test(nodeLabel(nodes, n));
}
/** A proposal that does not fit the snapshot. [reason] holds node ids and action types only, never screen text. */
export class InvalidTarget extends Error { constructor(public readonly reason: string) { super('invalid target'); } }
/** Checks the target against the snapshot. An unusable follow-up tap is dropped (the typing stays valid), never sent. */
export function validateTarget(value: unknown, request: Next): Action {
  const a = actionSchema.parse(value);
  const nodes = request.observation.nodes;
  if (a.targetId) {
    const n = nodes.find(n => n.id === a.targetId);
    if (!n) throw new InvalidTarget(`target ${a.targetId} is not on the current screen`);
    if (!n.enabled || (a.type === 'click' && !n.clickable) || (a.type === 'set_text' && !n.editable) || (a.type === 'scroll' && !n.scrollable))
      throw new InvalidTarget(`target ${a.targetId} cannot be used for ${a.type}`);
  }
  if (a.thenClickId) {
    const t = nodes.find(n => n.id === a.thenClickId);
    if (!t?.enabled || !t.clickable || isCommitControl(nodes, t)) return { ...a, thenClickId: '' };
  }
  return a;
}
