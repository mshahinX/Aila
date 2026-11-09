# myGov agent: classify → bot / read / prepare (design, 2026-11-08)

Decided with the owner in an interview on 2026-11-08. Code: `agent/GovAgent.kt`, `adapters/MyGovAdapter.kt`,
`AgentCoordinator.runGovStep / runGovScreen / walkRoute`, backend `bots.ts` (`intent` on plan steps).

## Classification
Every myGov step gets an intent:

| Intent | Example | Handling |
|---|---|---|
| `info` | "vəsiqəni necə yeniləyim?" | myGov chatbot. `GoalCheck` rejects directions-only replies → screen (read) |
| `read` | "pensiya kapitalım nə qədərdir" | never the bot: navigate and read on screen |
| `action` | "iş yeri üçün arayış sifariş et" | navigate, fill the form, **owner taps the final button** |
| `unclear` | "pensiyam haqqında" | ask the owner: Ümumi məlumat / Mənim məlumatım / Müraciət |

Who decides: the backend model sets `intent`; phone rules (`GovIntentRules`) re-check it. The rules win on
own-data/action signals (possessive forms such as *kapitalım, cərimələrim, yığıldığımı*; *sifariş et, müraciət et*),
so personal requests never go to the bot by mistake. Rule INFO vs. model READ/ACTION → ask the owner.
Mock mode: the backend sends `unclear` for myGov and the phone rules decide.

## Navigation
Prepared routes (`GovRoutes`): pension capital, fines, my documents, certificates. Each has a label path and the
labels that hold the answer.
- **Calibrated** route: deterministic walk (`walkRoute`: tap the furthest visible step, scroll to look, owner login
  when asked). For `read`, `GovReader` extracts the value **on the phone**, so the screen is not sent to the cloud.
- **Uncalibrated** route, or the walk fails: the AI navigator gets the path as a quoted hint (never instructions).
- **All routes are uncalibrated** until they are walked once on the owner's phone (logged in). Paths come from
  myGov's own assistant and the public app layout.

## Policy (`MyGovAdapter`, phone-side, model cannot override)
- `read`: tabs/menus/list items, scroll, back, search box. Anything that changes state is manual.
- `action`: additionally fills fields, but **only with words the owner said** (command or chat answers; otherwise
  it asks in chat). It never types into FİN/number/phone/code/password/email fields, never overwrites prefilled
  values, and stops at the final control (*Müraciət et, Göndər, Təsdiqlə, İmzala, Ödə…*): "Form hazırdır…".
- Login is always the owner's. Köməkçi waits and continues.

## Privacy
myGov snapshots mask FİN, document serials and phone numbers before anything leaves the capture
(`Privacy.maskIdentifiers`); amounts and dates stay readable. Calibrated `read` routes keep values on the phone.

## Next
1. Calibrate the 4 routes on the phone with the owner (logged in): record real labels, set `calibrated = true`.
2. Then move the same structure to ABB/Azercell.
