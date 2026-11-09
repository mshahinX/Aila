# Aila

**Say it. Stay in control.**

Aila is an Android helper for people who find phone apps hard, especially older people and people with disabilities. You type one request in Azerbaijani, or say it in English, and Aila does the taps inside myGov, ABB and WhatsApp. You always enter the PIN and press the final button yourself.

Built by Team Outliers for YUNEX 2026.

## What it does

We recorded Aila doing three real jobs on a real phone, with nothing mocked:

| Request | What Aila does | What you do |
| --- | --- | --- |
| Check my pension | Opens myGov, asks myGov's own AI assistant, and reads the amount on the phone | Enter your myGov PIN |
| Send my ID card to Rahman on WhatsApp | Downloads the ID card in myGov, opens WhatsApp's share screen, finds the right person (not a group) and attaches the file | Approve the recipient |
| Send 1 manat through ABB | Asks ABB's assistant, then fills in the transfer form | Check the card digits, enter the PIN, press **Köçürmə et** |

## How it works

```
 you ──"one sentence"──▶  Android app  ──sanitized screen──▶  backend  ──▶  cloud AI model
                           │  on-phone planner                (Node, TypeScript)
                           │  app adapters (myGov, ABB, WhatsApp)
                           │  safety policy + approval
                           ▼
                     Accessibility Service reads the screen and taps
```

1. **On-phone planner.** `RequestPlanner.kt` and `LocalPlan.kt` split a request into steps with no network call: which app, what amount, which person. Azerbaijani names are handled on the phone: "Rəhmana" becomes "Rəhman", and ə may be typed as a or e.
2. **Known tasks run as tested steps.** Adapters in `adapters/` match each app's real screens. Where an app has its own AI assistant (ABB "AI-dan soruş", myGov "Süni intellektdən soruş"), Aila asks that assistant first (`BotRunner.kt`, `Channels.kt`).
3. **New screens: one action at a time.** If a screen isn't known, the backend asks a cloud AI model for one proposed action. Code on the phone checks every action (`ActionPolicy.kt`) before it happens.
4. **You keep the final say.** The AI can't press a bank's final button. `AbbAdapter` blocks it even when the button also shows the amount ("Köçürmə et • 1.00 ₼"). WhatsApp sends need a one-use approval on the phone that is tied to the exact message and recipient.

## Safety and privacy

- Aila reads the screen only while a task you started is running. There are no screenshots, no stored chat history and no analytics.
- Login, PIN and password screens pause cloud capture. Numbers such as card and ID numbers are masked before anything leaves the phone.
- Only with your consent does the cloud get the task and a sanitized screen. Known tasks, like reading the pension amount, run on the phone.
- PINs, passwords, OTPs, biometrics and final banking confirmation are never automated.
- Unknown layouts stop instead of guessing. If a result is uncertain, Aila never resends on its own.

## Repository layout

| Path | What's inside |
| --- | --- |
| `android/` | Kotlin Android app (min SDK 31): chat screen, Accessibility Service, planner, app adapters, safety policy, approval overlay |
| `backend/` | Node 22 + TypeScript + Fastify service that talks to the AI model through the official `@anthropic-ai/sdk` |
| `policy-tests/` | Runs the Android safety and adapter tests on a plain JVM, with no Android SDK needed |
| `docs/` | Wire protocol, and design notes for app assistants, myGov and card transfers |
| `scripts/` | Optional helper for pairing a debug phone over USB |

## Build and test

Requirements: JDK 17, Android SDK platform 35 with build-tools 35.0.0, and Node 22 or newer.

```sh
# Backend: build and tests (no AI calls, no phone)
npm --prefix backend ci
npm --prefix backend run build
npm --prefix backend test

# Safety policies on a plain JVM
./android/gradlew -p policy-tests test

# Android app: unit tests and debug APK
./android/gradlew -p android testDebugUnitTest assembleDebug
```

On 9 November 2026, all 40 backend tests and 128 Android unit tests passed.

## Run it on a phone

1. Install `android/app/build/outputs/apk/debug/app-debug.apk` and connect the phone over USB.
2. Start the backend. It listens only on `127.0.0.1:8787` and runs in mock mode unless live mode is turned on.
   ```sh
   adb reverse tcp:8787 tcp:8787
   cd backend && npm start
   ```
3. Show the pairing token in a private terminal with `npm run --silent show-pairing`, then enter it in Aila's settings.
4. Turn on **Aila phone control** in Android's Accessibility settings.

For live mode, set `ANTHROPIC_BASE_URL`, `ANTHROPIC_AUTH_TOKEN`, `ANTHROPIC_MODEL` and `PHONE_AGENT_LIVE_ENABLED=true` in the backend's environment. `.env` files are never committed. See [backend/README.md](backend/README.md) for details.

## Known limits

- Voice input uses the phone's US English recognizer, and spoken replies are in English. Typed Azerbaijani requests work. Azerbaijani voice replies are on the roadmap.
- No older user has tried Aila yet. That's the first goal of a bank pilot.
- When an app updates its screens, its adapter may need an update. Until then, Aila stops rather than guessing.
- Aila isn't a Google Play app today. Play limits AI agents that use Accessibility, so the pilot installs through a bank partner.

## Team Outliers

Banovsha Rahimova (team captain) · Rahman Aghazada (business analyst) · Matin Masimli (data scientist) · Shahin Mammadzada (software engineer)
