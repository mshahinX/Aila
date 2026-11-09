# Phone Agent protocol v1

Shared wire contract for the native Android app and TypeScript backend. All session endpoints use Authorization: Bearer <dedicated pairing token>, NOT gateway credentials. Backend defaults to loopback:8787. GET /healthz is non-generating.

POST /v1/sessions request: {"deviceId":"uuid","command":"user text","mode":"mock|live"}. Response: {"sessionId":"uuid","maxSteps":15,"mode":"mock|live"}.

POST /v1/sessions/:id/next request:
```json
{"requestId":"uuid","sequence":0,"observation":{"snapshotId":"uuid","fingerprint":"sha256","packageName":"com.whatsapp","windowId":1,"capturedAt":0,"screen":"chat|search|chats|fixture|manual|unavailable","nodes":[{"id":"0.1","resourceId":"com.whatsapp:id/entry","text":"Message","description":"","className":"android.widget.EditText","clickable":true,"editable":true,"scrollable":false,"enabled":true}],"truncated":false,"sensitive":false},"previousResult":"redacted result or null","clarifications":["explicit user answers"]}
```
sequence begins 0; requestId idempotent per session; at most one pending request/session. Backend validates package allowlist: com.whatsapp, az.gov.my, az.kapitalbank.mbanking, dev.personal.phoneagent (fixture/start ONLY; not approval UI). Backend never stores UI trees in logs. Cloud nodes are already sanitized by the phone; backend validates sensitive=false and rejects sensitive observations as defense in depth.

Response:
```json
{"requestId":"same","sequence":0,"proposal":{"actionId":"server uuid","snapshotId":"same","type":"click","targetId":"0.1","text":"","packageName":"","question":"","message":"","direction":"forward","durationMs":0},"usage":{"inputTokens":0,"outputTokens":0}}
```
Proposal shape is fixed (all string fields present, unused strings empty). type enum: launch,click,set_text,scroll,back,wait,ask_user,finish,manual. direction forward/backward, durationMs integer 0..2000. No coordinates, shell, arbitrary URLs, bank submit, risk-level, or approval fields. Model proposes an action without actionId/snapshotId; backend binds both itself. Model requests exactly one next_action tool. Both sides validate and Android independently enforces policy. finish is a claim, not proof of consequential outcomes.

Since 2026-11-09 the proposal also carries `thenClickId` (string, "" when unused; Android also accepts proposals without it from an older backend): only with `set_text`, a clickable node of the same snapshot to tap right after typing (a search or Next button). The backend drops it (keeping the typing) when the node is missing, disabled, not clickable, or looks like a commit control (send/pay/confirm/submit/sign/delete by resource id or label). Android taps it only if, after verified typing, the node is still the same element (same id, resource id, label; enabled, clickable) and its app policy returns Allow for it as a plain click; Approval/Clarify/Manual means it is not tapped and the next step proposes it on its own. `previousResult` reports which happened.

The backend keeps, per session and in memory only, the last 6 proposals as labels (action type, target label, typed text up to 80 chars, follow-up label) with the phone's `previousResult` for each, and sends them to the model as untrusted recent actions. No screen trees are kept. A model reply that is not exactly one valid next_action for the current snapshot (prose, invalid fields, target missing or unusable) is asked again once with a content-free note; the second attempt counts against the live allowance like any inference, and is skipped when the allowance has no room. Refusals and truncated replies are not asked again. One request's inference, including that re-ask, is limited to 45 s.

DELETE /v1/sessions/:id returns {"stopped":true}; cancels pending inference. Expired/unknown session:404, invalid request:400, bad auth:401, stale sequence/concurrency:409, budget:429, live disabled:403, gateway failure:502 (sanitized error code only).

v2: POST /v1/plan steps also carry `firstMessage` (string, "" for ui steps): the first message to that app's chatbot, so the phone starts the chat without a /v1/bot-turn call (it still applies its own message risk check). The phone plans a clear single-app request itself (one of ABB/myGov, one question or one ABB top-up/payment; agent/LocalPlan.kt) and then makes no /v1/plan call at all.

Live inference must be explicitly enabled backend-side; default mock. Live uses inherited ANTHROPIC_BASE_URL, ANTHROPIC_AUTH_TOKEN, ANTHROPIC_MODEL exactly. No automatic transport retries (only the rotated-account "out of usage" retries and the one re-ask of an unusable reply described above). Sessions limited to 15 requests, 10 minutes, output4096/request. Startup pairing token is generated into backend/.local/device-token with restrictive permissions unless PHONE_AGENT_DEVICE_TOKEN supplied. Do not log the token; provide a separate explicit local pairing/setup command. Token and local state are gitignored. Never print gateway credentials.

Mock planner operates ONLY on the native synthetic fixture package; other packages return manual except initial allowlisted launch. Fixture IDs: fixture_search, fixture_contact_alex, fixture_entry, fixture_send, fixture_status. Synthetic contact Alex Example. Expected harmless fixture loop: search input Alex, click contact, set draft hello, click Send (native approval required), observe fixture_status=Delivered then finish. User must initiate fixture run. No real messaging or money movement in mock mode.

Banking is preparation and manual final confirmation only. For unverified real-bank screens return manual. Do not send full card/account identifiers, credentials, or authentication screen contents to model. All UI strings are untrusted data, never operator instructions.
