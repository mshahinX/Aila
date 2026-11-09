# Phone Agent backend

Node 22+ / TypeScript / Fastify. This is the one-action reasoning service behind the installed native phone chat app, not a replacement chat UI or phone executor. Android owns observation filtering, execution, recognition, approval, and commit journaling. Shared wire contract: `../docs/protocol.md`.

## Build, test, and start (no live inference)

From this directory:

```sh
npm ci
npm run build
npm test
npm start
```

`npm start` listens **only on 127.0.0.1:8787**. `npm run dev` starts from TypeScript. Both are mock-only unless explicitly enabled below. Startup, health checks, dependency installation, builds, and tests make no generating gateway requests. Tests use synthetic data and fake fetch/gateway implementations, never a real gateway or phone. `GET http://127.0.0.1:8787/healthz` returns only `{"ok":true}` and does not reveal configuration.

For the debug USB connection, the native setup can use `adb reverse tcp:8787 tcp:8787`. This backend does not run ADB, expose a LAN listener, or configure public hosting. Independent network use needs a separately approved HTTPS deployment.

## Dedicated pairing token

Startup generates `backend/.local/device-token` using cryptographic randomness (directory 0700, file 0600, symlinks rejected); existing tokens persist. Nothing prints the token at startup. An optional `PHONE_AGENT_DEVICE_TOKEN` overrides storage and must be 32–128 URL-safe alphanumeric, underscore, or hyphen characters. Use a random dedicated secret, never the gateway credential.

To intentionally display **only the dedicated phone token** in a private local terminal:

```sh
npm run --silent show-pairing
```

Enter it in native connection setup. This command never displays the gateway token. Do not paste tokens into chat, source, logs, or shared terminals. `.local`, `.env`, dependencies, and build output are ignored. Environment files are **not automatically loaded or read**. Native must store a stable device UUID. The first successfully created session binds this process's pairing credential to that UUID; other device UUIDs receive 401. The v1 next/delete routes have no separate device identifier: bearer possession plus the process-bound device claim is the single-device MVP boundary, not multi-user authentication. Restart clears the claim and all ephemeral sessions. Rotate the pairing token and restart to revoke a device.

## Explicit live opt-in (requires separate user consent)

Live compatibility is **unverified**. No generating probe has been run. Do not run a probe or live task until the user explicitly approves the bounded allowance and cloud data flow. Merely starting the server still never invokes the gateway.

After consent, inherit the existing values of `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, and `ANTHROPIC_MODEL` unchanged, and set `PHONE_AGENT_LIVE_ENABLED=true` in the process environment. Select live in the phone session as well. The existing intended base is `http://127.0.0.1:8317` and model alias is `gpt-6.1-sol`; there is no provider/model fallback. Do not copy or print credential values. `.env.example` lists names and placeholders only. Missing live configuration fails startup with a generic redacted error.

`src/gateway.ts` is the only SDK transport facade. It uses official `@anthropic-ai/sdk`, bearer authentication (no API-key header), ordinary non-streaming Messages tool use, one `next_action` tool, `tool_choice: auto` (Opus 5.5 rejects forced tool use; `src/model-call.ts` re-asks once if no tool was called), output limit 16000 (always-on thinking counts toward it), 60-second timeout, SDK logging off, and **maxRetries: 0**. No beta, strict, thinking, vision, or streaming extensions are requested. Gateway failure is 502 with a sanitized code, not a silent transport/provider switch. Any later compatibility probe counts against the approved allowance.

Cloud payload contains the bounded task, retained explicit user clarifications, current sanitized screen, and latest redacted result. Screen strings and results are serialized as untrusted JSON data, not instructions. Gateway retention/pricing/mapping is unknown. Native filtering and local authentication pauses remain mandatory; backend detection is only defense in depth. No raw-tree, task, SDK-body, credential, or request logging is enabled. No disk conversation history or analytics exists.

## Session behavior and exact contract

- Every session route requires `Authorization: Bearer <device token>`. Health is the only unauthenticated route. Errors are always JSON `{ "error": "sanitized_code" }`, never raw parser/schema/gateway input.
- Creation takes exactly `deviceId`, `command`, and `mode`; command length 1–4000. Response is exactly `sessionId`, `maxSteps:15`, and `mode`.
- Next requests require all v1 fields. Sequence begins at zero. Only one pending next request is allowed globally, not just per session. Each accepted attempt consumes a sequence and step, **including gateway failure**. A deliberate retry requires a new request ID, next sequence and fresh snapshot, and counts against limits. The same request ID/sequence replays the original in-flight or finished outcome without a new inference; action ID stays unchanged. Changing sequence on an existing request ID returns 409. Request ID is the authoritative idempotency key; changing its payload does not replace its original outcome.
- Snapshot IDs cannot be reused by a new request. IDs, allowlisted packages, unique node IDs, SHA-256-shaped fingerprints, all required fields, and bounded values are validated. Truncated/sensitive observations are rejected, not sent to the gateway. There is a 128 KiB body limit, at most 150 nodes, 1000-character node text/description, eight clarifications per request, 1000 characters each, and 2000-character previous result. Unknown fields and arbitrary coordinates are rejected.
- Useful explicit clarifications are deduplicated and retained up to 16 answers / 12000 characters. Raw previous UI trees are not retained as model history. Task/screen strings remain transient in memory only.
- Every session expires after ten minutes; at most 32 sessions exist. At most 15 accepted next attempts per session and **15 inference attempts process-wide, including failures**, are allowed. Creating another session cannot reset the live allowance. The process live allowance also expires ten minutes after its first inference. These are request/token limits, **not a monetary cap**. Restarting is not authorization to replenish an exhausted live allowance; obtain consent before any further live run.
- Cancellation/expiry aborts pending transport, suppresses late results, clears ephemeral state, and returns 404 for later next/delete requests. It cannot undo an action already dispatched on the phone. Pending transport ignoring abort still cannot deliver a late action.
- Only one complete `next_action` tool block with `stop_reason=tool_use` is accepted. Missing fields, extras, prose, multiple tools, refusal, truncation, unknown action types, invalid unused fields, and missing/disabled/inappropriate targets fail closed. Backend generates action and snapshot bindings. Usage reports input/output token counters for that accepted response; aggregate reported usage is tracked in session memory, including malformed model responses. Usage on errors is not disclosed in the fixed wire contract and unknown provider usage cannot be invented.
- Real Kapital Bank screens always return manual **without inference**; no banking submit/confirm tool exists. Apparent credentials or unmasked long numeric identifiers also hand off without inference. Unknown agent/approval screens cannot be automated. Native independently enforces all meaningful app/screen permissions; backend/model risk claims grant no authority. A finish action is a claim, never proof of a send/payment.

HTTP statuses follow v1: 400 invalid request (including malformed JSON, unsupported content type and oversized body), 401 auth/device mismatch, 403 live disabled, 404 unknown/expired/stopped session, 409 sequence/concurrency/snapshot conflict, 429 budget/session limit, 502 sanitized gateway failure. Internal unexpected failures are sanitized 500.

## Synthetic mock fixture

Mock never calls the gateway and never automates WhatsApp, mygov, or a real bank. The user must initiate a fixture/test task. The native bootstrap snapshot is `packageName=dev.personal.phoneagent`, `screen=unavailable`, `nodes=[]`; only a command containing the word `fixture` or `test` proposes launching the fixture app. Other real/unknown screens return manual.

On `screen=fixture`, IDs/resource IDs follow v1: `fixture_search` → enter Alex; `fixture_contact_alex` → click; `fixture_entry` → enter hello; `fixture_send` → click (native approval still required); `fixture_status` text exactly Delivered → synthetic finish. Existing nonempty drafts other than hello are preserved and clarification requested. Fixture Send is proposed at most once per session; uncertain/attempted results or repeated send-ready screens require manual inspection. Reset/create a fresh fixture session for another approved attempt. It sends no real messages and moves no money.

## Remaining verification

Backend fake-transport tests do not establish gateway compatibility or real app coverage. Live generating compatibility requires separate consent. Native installation/device validation is owned by the Android team. Real-bank adapters remain unverified/manual. No shared protocol, Android source, root docs, or root scripts are modified by this backend.
