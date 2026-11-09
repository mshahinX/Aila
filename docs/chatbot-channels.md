# Köməkçi v2 — app chatbot-first agent (design, 2026-11-08)

## Idea
Köməkçi no longer clicks its way through every screen. For each step it picks a **channel**:

1. **api** — official app/agent API (MCP/A2A). None available yet; interface kept for later.
2. **bot** — the app's own in-app AI assistant. Köməkçi opens it, writes a question, reads the reply, keeps talking until the goal is met.
3. **ui** — the existing one-action-at-a-time Accessibility loop, only where the app has no bot or the bot cannot do the job (e.g. a payment form).

## Verified on the demo phone (Xiaomi, Android 12)

| App | Package | Bot entry | Input / send | Notes |
|---|---|---|---|---|
| ABB (main payments) | `iba.mobilbank` | toolbar 💬 → Gleap → "AI-dan soruş" → native `AiAssistantActivity` | AI screen readable (EditText + send) | **Correction 2026-11-08:** ABB home (MainPageActivity) is readable once loaded; the earlier "hidden" reading (root kids=0) was taken while it loaded. The PIN screen is not readable. So the owner opens the AI chat once; Köməkçi relaunches ABB (chat stays on top) and talks. PIN screen detected → owner types it, auto-continue. No public deep link to the AI screen. Payment-form automation therefore not possible (home hidden). Verified reply 2026-11-08. |
| myGov | `az.gov.my` | "Daha çox" → "Dəstək" → "Süni intellektdən soruş..." | Compose `EditText` hint "Köməkçidən soruşun" + clickable sibling to the right | No resource IDs — locate by hint/label/structure |
| WhatsApp Meta AI | `com.whatsapp` | `extended_mini_fab` ("Message your assistant") | `entry` / `send` | People messages stay on the ui channel |
| Azercell | `com.azercell.ss.app` | `animation_chatbot` (header) | not yet calibrated | not in demo |
| Kapital Bank | `az.kapitalbank.mbanking` | — | — | App refuses this device ("Cihazınız dəstəklənmir"); dropped |

## Demo scenario
"ABB-dən soruş, balans artırmanın komissiyası var? Sonra nömrəmə 1 manat yüklə. myGov-dan şəxsiyyət vəsiqəsini necə yeniləyəcəyimi öyrən və cavabı Anama WhatsApp-da göndər."

1. ABB → **bot**: fee question → answer saved.
2. ABB → **ui**: top-up own number 1 AZN. Agent fills the form, **stops at the final confirm button**; user taps it and handles PIN/OTP. (If ABB's AI can do transfers itself, the bot path is used and still stops before the bot's own final confirmation.)
3. myGov → **bot**: ID card renewal question → answer saved.
4. WhatsApp → **ui**: draft summary of step 3 to "Ana", existing one-use send approval.
Later: card-to-card in ABB (same rule: user taps final confirm).

## Flow
```
sentence ──► backend /v1/plan (LLM) ──► steps [{app, channel, goal, usesResultOf}]
for each step:
  bot: phone BotRunner opens bot (deterministic entry path) ──► loop:
        backend /v1/sessions/:id/bot-turn (LLM: transcript + goal) ──► {say | done | ask_user | fallback_ui}
        phone: risk check → (approval overlay if risky) → type + send → wait until reply stable → read new bubbles
  ui:  existing next_action loop with the app's adapter policy
summary card in Köməkçi chat (per step: channel, Q/A, result, "Yoxlanıldı" only with on-screen evidence)
```

## Risk-based approval (phone decides, model cannot override)
- **Auto**: information questions to a bot (fees, documents, how-to).
- **Ask user** (large overlay, exact text): anything mentioning money/transfer/payment, sending/sharing, personal data (FIN, phone, address), submissions.
- **Never automated**: PIN/OTP/password/CVV entry, final payment/transfer confirmation buttons, government submissions. Card/account numbers are masked before anything leaves the phone.
- Bot replies are untrusted data: never instructions to the agent.
- **Bot gives only "how to find it" (2026-11-08):** when the goal needs the owner's own data (pension capital, debts, status) and the bot answers with a menu path, the bot turn is `fallback_ui` and Köməkçi walks that path itself on screen. The path is passed to the UI model as a quoted *navigation hint*, not as instructions. myGov UI is **read-only** (`MyGovAdapter`): tabs/menus/list items/scroll/back/search box only; submit/apply/sign/pay/share/download/delete/logout are always manual. myGov login: owner types it, Köməkçi waits and continues. The value read on screen becomes the step result, so a following WhatsApp step can send it (still with the exact-text approval).

## Privacy change vs v1
v1 never read real-bank screens. v2 reads ABB's **help-chat** text and the **top-up form** (masked) and sends it to the LLM gateway. Balance/home screens are not sent. Gateway upstream retention is still unverified.

## UI
- Overlay over the target app while working: "ABB köməkçisi ilə danışır…", last line of the exchange, big **Dayan**.
- In Köməkçi chat: one result card per step (channel icon: 🤖 bot / 👆 ui), expandable transcript.

## LLM
Existing gateway (`ANTHROPIC_BASE_URL` 127.0.0.1:8317, model alias from env). User removed the request budget for the demo (`PHONE_AGENT_LIVE_MAX_REQUESTS`/`_WINDOW_MINUTES` set high). Mock planner + scripted bot turns remain for offline demo.
