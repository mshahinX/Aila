import Fastify from 'fastify';
import { createHash, timingSafeEqual } from 'node:crypto';
import { z } from 'zod';
import { createSchema, nextSchema } from './protocol.js';
import { Sessions, HttpError } from './sessions.js';
import type { Config } from './config.js';
import type { Gateway } from './gateway.js';
import { botTurnRequestSchema, mockBotTurn, mockPlan, planRequestSchema, BrainFailure, type BotBrain } from './bots.js';

export function createApp(config: Config, gateway?: Gateway, options: { now?: () => number; ttlMs?: number; brain?: BotBrain } = {}) {
  const { brain, ...sessionOptions } = options;
  // connectionTimeout is Node's socket idle timeout: it must outlast a slow model call (gateway retries), else the
  // socket closes mid-request and the phone sees "unexpected end of stream" (2026-11-08, ~10 s live step).
  const app = Fastify({ logger: false, bodyLimit: 128 * 1024, requestTimeout: 50_000, connectionTimeout: 60_000, keepAliveTimeout: 5000, routerOptions: { maxParamLength: 64 } });
  const sessions = new Sessions({ liveEnabled: config.liveEnabled, gateway, liveMaxRequests: config.liveMaxRequests, liveWindowMs: config.liveWindowMs, ...sessionOptions });
  const expected = createHash('sha256').update(`Bearer ${config.token}`).digest();
  app.addHook('onRequest', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    if (request.method === 'GET' && request.url === '/healthz') return;
    const header = request.headers.authorization;
    if (!header || !timingSafeEqual(createHash('sha256').update(header).digest(), expected)) throw new HttpError(401, 'unauthorized');
  });
  if (process.env.PHONE_AGENT_DIAGNOSTICS === 'true') app.addHook('onResponse', async (request, reply) => {
    console.error(`[diag] ${new Date().toISOString().slice(11, 23)} ${request.method} ${request.routeOptions.url ?? '?'} -> ${reply.statusCode} ${Math.round(reply.elapsedTime)}ms`);
  });
  app.setErrorHandler((error, request, reply) => {
    // Content-free diagnostics: route + code only. Never input, screen text, headers or SDK bodies.
    if (error instanceof HttpError) {
      if (process.env.PHONE_AGENT_DIAGNOSTICS === 'true') console.error(`[diag] ${request.method} ${request.routeOptions.url ?? '?'} -> ${error.status} ${error.code}`);
      return reply.code(error.status).send({ error: error.code });
    }
    // Never serialize errors, Zod issues, SDK bodies, URLs, request headers or input.
    const status = typeof error === 'object' && error !== null && 'statusCode' in error ? error.statusCode : undefined;
    if (status === 400 || status === 413 || status === 415) return reply.code(400).send({ error: 'invalid_request' });
    return reply.code(500).send({ error: 'internal_error' });
  });
  // No keep-alive: the phone's pooled connections can go stale over the USB tunnel ("Broken pipe").
  // A fresh loopback connection per request costs ~1 ms.
  app.addHook('onSend', async (_request, reply, payload) => { reply.header('connection', 'close'); return payload; });
  app.setNotFoundHandler((_request, reply) => reply.code(404).send({ error: 'not_found' }));
  const sessionId = (params: unknown) => {
    const parsed = z.object({ id: z.string().uuid() }).safeParse(params);
    if (!parsed.success) throw new HttpError(404, 'session_not_found');
    return parsed.data.id;
  };
  app.get('/healthz', async () => ({ ok: true }));
  app.post('/v1/sessions', async request => {
    const parsed = createSchema.safeParse(request.body);
    if (!parsed.success) throw new HttpError(400, 'invalid_request');
    return sessions.create(parsed.data);
  });
  app.post('/v1/sessions/:id/next', async request => {
    const parsed = nextSchema.safeParse(request.body);
    if (!parsed.success) {
      // Field paths and issue codes only (e.g. observation.nodes.12.text too_big), never values.
      if (process.env.PHONE_AGENT_DIAGNOSTICS === 'true') console.error('[diag] next invalid_request:', parsed.error.issues.slice(0, 5).map(i => `${i.path.join('.')} ${i.code}`).join('; '));
      throw new HttpError(400, 'invalid_request');
    }
    return sessions.next(sessionId(request.params), parsed.data);
  });
  // v2 chatbot-first endpoints. Stateless: the phone keeps the plan/transcript and decides every action itself.
  const think = async <T>(mode: 'mock' | 'live', mock: () => T, live: (signal: AbortSignal) => Promise<T>): Promise<T> => {
    if (mode === 'mock') return mock();
    if (!config.liveEnabled || !brain) throw new HttpError(403, 'live_disabled');
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 45_000); timer.unref();
    try { return await live(controller.signal); }
    catch (error) { if (error instanceof BrainFailure) throw new HttpError(502, 'gateway_failure'); throw error; }
    finally { clearTimeout(timer); }
  };
  app.post('/v1/plan', async request => {
    const parsed = planRequestSchema.safeParse(request.body);
    if (!parsed.success) throw new HttpError(400, 'invalid_request');
    const { command, mode } = parsed.data;
    return think(mode, () => ({ ...mockPlan(command), usage: { inputTokens: 0, outputTokens: 0 } }),
      async signal => { const r = await brain!.plan(command, signal); return { ...r.plan, usage: r.usage }; });
  });
  app.post('/v1/bot-turn', async request => {
    const parsed = botTurnRequestSchema.safeParse(request.body);
    if (!parsed.success) {
      if (process.env.PHONE_AGENT_DIAGNOSTICS === 'true') console.error('[diag] bot-turn invalid_request:', parsed.error.issues.slice(0, 5).map(i => `${i.path.join('.')} ${i.code}`).join('; '));
      throw new HttpError(400, 'invalid_request');
    }
    const r = parsed.data;
    return think(r.mode, () => ({ turn: mockBotTurn(r), usage: { inputTokens: 0, outputTokens: 0 } }), signal => brain!.turn(r, signal));
  });
  app.delete('/v1/sessions/:id', async request => sessions.stop(sessionId(request.params)));
  app.addHook('onClose', async () => sessions.close());
  return app;
}
