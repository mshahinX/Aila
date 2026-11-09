# Phone Agent

A personal, **installed Android chat app** with native phone controls and a separate reasoning backend. You type a task in chat; Android observes supported screens, obtains one proposed action at a time, independently checks it, and shows progress or asks you for clarification/approval. The backend does not control the phone directly.

**Prototype status:** debug APK is installed on the user's Android 12 phone, and the actual chat UI was launched and visually verified. All three synthetic device instrumentation tests pass, including encrypted one-shot USB pairing. Backend build/18 synthetic tests, all 30 shared safety tests, and all 30 Android unit tests pass (the two native test runs exercise the same cases, not 60 distinct tests). Lint passes with 0 errors and 17 non-blocking warnings, mainly localization and pinned test-dependency update notices. SDK 35/build-tools 35.0.0 are installed after user license review. Initial phone-side installation rejection was resolved after the user reconnected/authorized the device; no restriction was bypassed. Device pairing and explicitly approved screen-text consent are configured. Private USB bootstrap-file removal and encrypted storage were verified without exposing credentials in process arguments or logs. Accessibility enablement and full agent-loop validation still require the user's phone confirmation; its exact permission page is open. Live gateway compatibility is unverified and live remains disabled. Mock-only backend health and USB reverse transport are verified on loopback 8787.

## v2 — app chatbot-first (in progress, 2026-11-08)

Köməkçi now talks to each app's **own AI assistant** (ABB "AI-dan soruş", myGov "Süni intellektdən soruş", WhatsApp Meta AI) and falls back to the UI loop only where a bot can't do the job (ABB top-up/transfer: form filled, owner taps final confirm). Design, calibrated entry paths and risk rules: [docs/chatbot-channels.md](docs/chatbot-channels.md). Backend: `POST /v1/plan`, `POST /v1/bot-turn`; phone: `agent/Channels.kt`, `agent/BotRunner.kt`, `adapters/AbbAdapter.kt`. Requests mentioning ABB/myGov/Meta AI/“soruş”/“öyrən” use v2.

## Köməkçi redesign — accessibility-first, Azerbaijani (hackathon build)

Design canvas: https://claude.ai/artifact/4HvmSwMnUhBiZhtK9VCuUN (v2, 7 phone screens). **v5 native-Android layout (2026-11-08):** https://claude.ai/artifact/Ep3Xv4czcvBdiMhi3MG8kW (12 screens, Material 3 metrics; rules in `~/.claude/skills/komekci-android-design` + `mobile-app-ui-design`). **v3 look (2026-11-08):** https://claude.ai/artifact/LWKP52Ay1yvMttmHUQPgtQ — cool paper + Xəzər blue, saffron voice pill in the thumb zone, timeline plan, dimmed approval sheet. Rules: `~/.claude-account-b/skills/komekci-android-design/SKILL.md`. **Enterprise redesign (2026-11-08):** day/night tokens in `res/values*/colors.xml`, component library in `ui/Ui.kt`, edge-to-edge, adaptive icon + splash, strings in `strings.xml`, v2 channel plans drawn as a timeline, debug-only `PreviewActivity` for safe screenshots of every state.

- **One sentence → a plan.** `agent/RequestPlanner.kt` splits a spoken/typed Azerbaijani request into service steps on the phone (no network): money (amount, currency, dative recipient “Anama” → “Anam”), WhatsApp message (resolves “ona”, drafts “Salam, sənə 50 manat göndərdim.”), reminder (sabah/birigün, time), myGov, or a plain agent task. 10 unit tests (`PlannerTest`).
- **Each step uses an existing safe path:** bank → prefilled local checklist, then Kapital Bank opens and the user confirms there; message → agent task with the one-use on-screen approval; reminder → the phone's calendar with title/time filled, user taps Save; myGov → opened for the user. Handoff steps ask “Did you do it?” and are labelled **Siz etdiniz** (user did it), never “verified”. A money-dependent message is auto-skipped if the user says the money was not sent. The summary uses **Yoxlanıldı** only with on-screen evidence.
- **Voice in / voice out:** phone speech recognizer (`en-US`) and English text-to-speech; if no English voice is installed the app says so instead of reading with another language. Requests typed in Azerbaijani still work.
- **Accessibility:** 20sp+ body text with an in-app size setting (up to 1.5×, on top of Android font size), 56–76dp targets, spoken labels on icon buttons, Stop always visible while anything runs, redesigned approval overlay with large “Bəli, göndər / Xeyr, göndərmə”.
- **Demo mode:** with Live off, the message step runs in the synthetic fixture, and the mock backend types the plan's own draft (`draft: "…"` in the fixture command). No real message is sent. Restart the backend after `npm run build` to pick this up.

**Always on (2026-11-09):** Live mode is on at every app start; Settings can turn it off until the next restart. In debug builds phone control also switches itself back on when the app starts (`accessibility/PhoneControlKeeper.kt`), after a one-time `adb shell pm grant dev.personal.phoneagent android.permission.WRITE_SECURE_SETTINGS` (undo with `pm revoke`; lost on uninstall). Approvals before messages and final taps in banks are unchanged.

**On-device testing (debug builds):** `adb shell am broadcast -a dev.personal.phoneagent.REQUEST -p dev.personal.phoneagent --es text "…" --ez live true`, then `adb shell am start -n dev.personal.phoneagent/.ui.MainActivity` (MIUI blocks Köməkçi from bringing itself to the front). The broadcast requires `DUMP`, which only `adb shell` holds. Don't run `uiautomator dump` during a task: it disconnects Köməkçi's accessibility service and stops the task.

Demo script: unlock the phone → open Köməkçi → tap the example card (or say the sentence) → **Başla** → bank checklist → open bank / answer → **Mesajı hazırla** → approve in the overlay → choose a reminder time → calendar → summary.

## v9 UI — one English chat screen (2026-11-08)

The app is now a single chat screen in English (design: canvas page "v9" of https://claude.ai/artifact/Ep3Xv4czcvBdiMhi3MG8kW). Empty state asks "What would you like to do?"; the plan, "Your turn" steps, permission and the result appear as cards in the conversation; the bottom bar is a text field + 64dp microphone (send while typing) or, while working, status + red **Stop**. Header: Köməkçi, status, **New chat**, read aloud, settings. Tokens in `res/values(-night)/colors.xml`, components in `ui/Ui.kt`, font Onest in `res/font` (SIL Open Font License 1.1, https://fonts.google.com/specimen/Onest).

Language: all user-facing text is English, voice input is `en-US` (`agent/SpokenEnglish.kt` tidies what the recogniser writes; the on-phone matchers read English and typed Azerbaijani), and replies are read aloud in English. Deliberately still Azerbaijani: messages Köməkçi drafts for your contacts, prompts to the cloud model and to ABB/myGov assistants, and the other apps' button labels the adapters match ("Köçürmə et", "Yüklə", "AI-dan soruş"). Mock-backend replies are also still Azerbaijani.

## Current capabilities and boundaries

| App / feature | Implemented scope | Verification / limits |
| --- | --- | --- |
| Native chat | Requests, task progress, questions, Stop, connection/privacy settings | Installed chat UI launch visually verified; full agent loop pending manual setup; in-memory conversation only |
| Safe demo | Synthetic Alex Example contact, draft hello, local approval, simulated delivery | No real messages or money; mock backend only supports this fixture |
| WhatsApp | Resource-ID-based search/contact navigation, recipient confirmation, Unicode drafting, preserve existing drafts, one-use local send approval, conservative status evidence | Native device integration/live reasoning not yet tested; unsupported layouts stop |
| mygov | Launch and manual handoff | Real screen recognition/read-only extraction is **not implemented/verified**; unknown controls cannot be clicked |
| myGov → WhatsApp document | “myGov-dan şəxsiyyət vəsiqəmi yüklə və WhatsApp-da Rəhmana göndər” (or *ümumvətəndaş pasportu*): myGov card → menu → **Yüklə** → share sheet → WhatsApp app → “Send to…” search (Azerbaijani spellings first, `NameSearch`) → verified personal contact → owner taps **Bəli, göndər** → Köməkçi presses send | Verified end to end on the owner's phone 2026-11-08 (ID card → Rəhman). `agent/PassportShare.kt`, `runPassportShare`. WhatsApp's picker rows offer no click action, so this flow taps the verified row with a gesture (`TAP_TARGETS` only; the model and backend protocol cannot request taps). myGov PIN stays with the owner. |
| Kapital Bank | Local checklists for bills, card-to-card, and own-account transfers; explicit opening of bank for the user to operate | **No bank form entry or automated transaction.** Real-bank capture disabled; no money moved or financial status verified |
| Other apps | Adapter registry for future support | Unsupported apps cannot be controlled |

Authentication, passwords, PINs, CVVs, OTPs, biometrics, government submissions, deletion, attachments, and final banking confirmation are not automated. No root, runtime ADB requirement, arbitrary shell/coordinate actions, or background jobs.

## Project layout

- `android/`: Kotlin/native Views APK, manually enabled Accessibility service, local execution/policy/approval and encrypted pairing settings.
- `backend/`: Node 22+/TypeScript/Fastify, loopback HTTP service, official SDK gateway facade, strict protocol, default synthetic mock mode. See [backend setup](backend/README.md).
- `policy-tests/`: SDK-independent JVM harness compiling the **same** native policy/adapter sources and unit tests, not a second implementation.
- `docs/protocol.md`: Android/backend v1 wire contract.

## Build tools

Pinned versions: JDK 17, Gradle 8.11.1 (checksum-pinned wrapper), Android Gradle Plugin 8.9.2, Kotlin 2.2.10, compile/target SDK 35, build-tools 35.0.0, min SDK 31. The connected development phone is Android 12/API 31.

On this development host, tools are installed locally without root:

```sh
export JAVA_HOME='/home/admin_test/.local/share/phone-agent-toolchain/jdk-extract/jdk-17.0.21+9'
export ANDROID_HOME='/home/admin_test/.local/share/phone-agent-toolchain/android-sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
```

### SDK licenses — user review required

Run interactively and read the terms yourself. **Do not pipe `yes`.**

```sh
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
```

On this host the newer `cmdline-tools/latest` is revision 23 and delegates its CLI differently: its `--licenses` path did not display/accept terms. Use the verified revision 19.0 command above. No licenses were silently accepted. On this development host the user subsequently reviewed/accepted the terms, and platform 35/build-tools 35.0.0 were installed successfully.

Only **after** accepting the required licenses:

```sh
"$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;35.0.0'
```

### Build and test

From the project root with the environment above:

```sh
# Backend; no inference or phone interaction
npm --prefix backend ci
npm --prefix backend run build
npm --prefix backend test

# Shared native policies, no Android SDK needed
./android/gradlew -p policy-tests test

# Requires SDK/licenses; never auto-download SDK packages from Gradle
./android/gradlew -p android -Pandroid.builder.sdkDownload=false testDebugUnitTest lintDebug assembleDebug
```

Built APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

SHA-256 of the current debug build: `9dae9e055a430b039f15e8f9b9b8b9daea94b12b9d1b5b190f2d255e1e0d9bb3`. Instrumentation tests are synthetic UI/storage smoke checks, not evidence of real WhatsApp/banking support:

```sh
./android/gradlew -p android -Pandroid.builder.sdkDownload=false connectedDebugAndroidTest
```

Those tests open only the synthetic fixture and use isolated test preferences for the crash journal. They do not enable Accessibility or approve a real message. This host currently uses system `/usr/bin/adb` rather than an SDK-local platform-tools install, so the built instrumentation APK can instead be run directly:

```sh
adb -s 1011cf380411 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s 1011cf380411 install -r -t android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 1011cf380411 shell am instrument -w -r dev.personal.phoneagent.test/androidx.test.runner.AndroidJUnitRunner
```

All three device tests now pass, including private bootstrap encryption, one-shot deletion, and malformed-consent rejection. The first UI test attempt timed out when launching the internal fixture directly; the test now opens the normal exported launcher, then opens the fixture from the foreground app. No production activity was made public and no background-launch permission was changed. The journal test uses isolated preferences; neither test approves a real message or grants Accessibility/consent. If installation is rejected on another run, approve the phone's USB-install prompt (or review MIUI's Install via USB setting) before deliberately retrying; never bypass an installation restriction.

## Development install and connection

After a successful APK build, with the user's own authorized/unlocked USB phone:

```sh
adb devices -l
adb -s 1011cf380411 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s 1011cf380411 reverse tcp:8787 tcp:8787
```

Use your actual device serial on another phone. Installation does not grant Accessibility permission.

Start the backend in a separate terminal, explicitly mock-only:

```sh
cd backend
PHONE_AGENT_LIVE_ENABLED=false npm start
```

This listens on `127.0.0.1:8787`; it does not make generating gateway calls. In another **private local terminal**, intentionally display the dedicated pairing token:

```sh
cd backend
npm run --silent show-pairing
```

Do not paste the token into chat or logs. This is a device token, **not** the gateway credential. Startup generates it under `backend/.local/` with directory mode 0700/file mode 0600; it is ignored by Git.

On the phone:

1. Open **Phone Agent → Settings**.
2. Set debug backend URL to `http://127.0.0.1:8787` and enter the dedicated pairing token.
3. Read the cloud-data disclosure. Enable screen-text consent only if you agree; save.
4. Tap **Enable phone controls** and manually enable **Phone Agent controls** in Android Accessibility settings. On Android versions that restrict sideloaded apps, review the OS's restricted-settings explanation rather than attempting to bypass it.
5. Return to chat. Keep **Live gateway** off and start **Safe demo**.
6. Watch synthetic search/drafting. **Reject** the first send approval: no send should occur.
7. Start a fresh Safe demo, review its exact synthetic recipient/message, and approve on the phone. Expected result is explicitly **simulated Delivered**, never a real message.
8. Exercise Stop. Starting a new task waits for the previous task's cleanup. Rejection, stale screen, timeout, unknown outcome, or service interruption must never trigger a blind resend.

The reusable app runs its own native actions. USB is only the current development transport: disconnecting it removes access to this loopback backend. Independent daily use needs a user-approved, authenticated **reachable HTTPS deployment**. No public/LAN deployment is included or started automatically.

### Optional private USB pairing for this debug app

After explicit setup authorization, `scripts/bootstrap-debug-pairing.py` can send the dedicated token over an anonymous stdin pipe and authorized USB connection into a mode-0600 file in this debuggable app's private directory. It does not put the token in command arguments, APK assets, public phone storage, or logs. It does not use test execution to configure the app. On startup, native code validates and deletes that temporary file, encrypts the token with Android Keystore, and verifies decryption locally. The import is disabled in release builds and has no exported component or model tool.

```sh
# Leave off --approved-cloud-consent unless the user explicitly authorized enabling it.
# Restarts this debug app; finish/stop any task first.
python3 -I scripts/bootstrap-debug-pairing.py \
  --serial 1011cf380411 \
  --token-file backend/.local/device-token \
  --approved-cloud-consent
```

The user explicitly authorized enabling the consent setting during this session. Encrypted storage and removal were verified on the phone. Gateway credentials are never read by the helper. Accessibility and per-message approval remain manual; this helper cannot grant them. Live mode remains disabled.

## Live gateway — still disabled and unverified

The selected existing configuration is kept unchanged: Anthropic-compatible base `http://127.0.0.1:8317`, configured model alias `gpt-6.1-sol`, existing bearer credential. Model-list retrieval does not establish Messages/tool-use compatibility. No provider/model is silently substituted.

Gateway secrets stay in the backend environment; the phone never receives them. Environment files are not automatically loaded. See [backend live opt-in](backend/README.md#explicit-live-opt-in-requires-separate-user-consent).

**Before the first generating probe/live task, obtain explicit user approval** for the allowance and cloud data flow. Proposed initial ceiling: 15 total inference attempts including failures/probes, 4096 generated tokens/request, ten-minute live allowance, no automatic SDK retries. Limits are **not a monetary cap** because gateway pricing is unknown. Starting a new session cannot replenish the process-wide allowance; restarting is not permission to spend more.

Only after that approval, enable live in the backend process and phone UI. Begin with one synthetic compatibility probe, then a fresh user-selected WhatsApp draft. Every real send still requires separate on-phone approval of the exact recipient/message. Historical demo approval is never reusable. Do not perform real-money development tests.

## Privacy and safety implementation

- Only user-started tasks capture supported application windows. No passive event logging, screenshots, raw-tree logs, analytics, or disk chat history.
- Cloud requests may include selected accessible private text from supported apps. Gateway/upstream retention has **not** been verified. UI trees are untrusted data, never authorization.
- Password/credential screens, lock screen, all real-bank content, and unrelated apps stop observation/upload. This is best-effort recognition, not a guarantee that arbitrary private text can always be identified.
- Native capture is bounded to 150 nodes; long/incomplete/oversized observations stop rather than uploading partial screens. Numeric identifiers are masked. Full card/account/security details do not belong in chat or preparation checklists.
- Own chat/settings/approval UI is excluded from model-controlled nodes. Pairing token is encrypted using Android Keystore; token and banking setup screens block screenshots.
- One proposal per fresh snapshot; package/window/layout/text changes invalidate pending actions. No click-ancestor or coordinate fallback.
- WhatsApp send approval is a local, expiring, one-use capability. It binds exact message/recipient, task/action, and screen fingerprint. Backend/model approval claims have no authority.
- A synchronous hashed journal is saved **before** send dispatch. An uncertain result blocks future tasks until the user inspects WhatsApp and acknowledges it in Settings. Never resend automatically.
- Status reporting requires visible local evidence; an accepted click or model claim is not proof of delivery/payment. A status from a neighbouring message cannot certify a new message.
- Stop cancels pending transport/approval and future actions, but cannot undo a click already dispatched.
- Bank preparation is a local nickname/amount/currency/provider checklist, not bank evidence or a transfer. Verify recipient, actual account, amount, currency, and fees yourself inside Kapital Bank; operate and confirm it manually.

## Remaining acceptance work

- Manually enable Accessibility; pairing and separately approved consent are already configured. Run reject/approve/Stop/stale-approval fixture scenarios through the actual agent loop. Installation, visible chat launch, SDK setup, compilation, native unit tests, lint, encrypted pairing verification, and all three synthetic device tests are complete.
- Explicit bounded live allowance, then gateway compatibility probe. Handle incompatibility honestly rather than switching providers.
- User-selected real WhatsApp draft and independent per-message approval/device verification.
- mygov and bank screen calibration require user participation; current app does not implement their real form automation/extraction.
- HTTPS deployment for use without the development computer/USB.

No commits, pushes, production release, public hosting, unsolicited messages, or real-money tests have been performed.
