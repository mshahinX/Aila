# Card transfer: WhatsApp card → ABB (design, 2026-11-08)

Request: "Metinin WhatsApp mesajından kartı götür, ABB-dən 20 manat göndər". Live mode only.
Code: `agent/Transfer.kt`, `AgentCoordinator.runCardTransfer`, `SnapshotCollector.rawChatTexts / rawFieldText`, `AbbAdapter`.

1. **WhatsApp**: the model opens the named person's chat (Azerbaijani case endings are ambiguous on names, so the
   model searches; `contactGuess` is only a hint). WhatsAppAdapter asks when several contacts match.
2. **Card, on the phone only**: `rawChatTexts` reads the open chat locally; `CardFinder` takes the newest Luhn-valid
   16-digit number. Snapshots sent to the cloud keep masking it; it is never logged (trace shows last 4 only).
3. **Owner approval #1**: overlay shows chat header name, full card (grouped), amount, the message, and a warning when
   several cards are on screen. Reject → nothing happens.
4. **ABB**: the owner enters the PIN (that screen is not readable). The home screen IS readable once loaded
   (2026-11-08: MainPageActivity, `transfer` shortcut; an older note calling it hidden was a capture during loading),
   so Köməkçi opens Köçürmələr → Digər karta itself. The form is readable (`cell_edit_text`). The phone types the card
   itself and verifies it locally. ABB editable fields with a card are masked, not treated as a stop.
5. **Rest of the form**: UI loop. `AbbAdapter`: numbers typed must be the owner's own (no invented/changed amount),
   never overwrites a filled field, never taps Köçür/Təsdiqlə/Göndər/Ödə.
6. **Amount, source card, handover (2026-11-08)**: "Davam et" opens the amount screen (loads ~1 s, then readable).
   The phone types the owner's amount (one empty field) or the UI loop does (AbbAdapter: owner's amount only). Köməkçi
   reads the owner's masked cards on screen and asks which one pays ("…1234"), taps it, then checks amount + recipient
   last 4 + chosen card in ABB's raw screen text. **"Köçürmə et" is the owner's tap** (owner's choice); it is in
   AbbAdapter.finalAction, so the AI loop can never press it. PIN/OTP are always the owner's.
