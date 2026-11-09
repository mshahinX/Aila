package dev.personal.phoneagent.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Native, deterministic mechanics of talking to an app's own chatbot: open it along a calibrated entry
 * path, type one message, press its send control, read what appeared afterwards. No model picks targets
 * here; the model only words the messages (and the phone's [BotRisk] decides which need approval).
 */
class BotRunner(
    /** deep = larger/deeper local read (WebView chats); slower, so only used when the shallow read lacks what we need. */
    private val capture: suspend (deep: Boolean) -> UiSnapshot,
    private val execute: (Proposal) -> Boolean,
    private val launch: (String) -> Boolean,
    private val back: () -> Boolean,
    /** Shown when the app wants its own login (PIN etc.); suspends until the owner says they are done. */
    private val waitForOwnerLogin: suspend (BotApp) -> Boolean,
    /** Shown when the app hides its screen from Accessibility (e.g. ABB's anti-fraud): the owner opens the chat. */
    private val askOwnerToOpen: suspend (BotApp) -> Unit = {},
    /**
     * Settled shallow reading after an action taken at [since] (clock of [now]; 0 = no action). Must not throw: on a
     * screen that never settles it returns the latest reading. Null = the original fixed-interval check.
     */
    private val settled: (suspend (since: Long) -> UiSnapshot)? = null,
    /** When [packageName] last changed its screen (clock of [now]), or null when unknown: then fixed waits are used. */
    private val lastChangeAt: (packageName: String) -> Long? = { null },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    class BotError(message: String) : Exception(message)

    private suspend fun settle(since: Long = 0L): UiSnapshot {
        settled?.let { return it(since) }
        if (since > 0L) delay(1200)
        var previous = capture(false)
        repeat(5) {
            delay(300)
            val next = capture(false)
            if (next.fingerprint == previous.fingerprint) return next
            previous = next
        }
        return previous
    }
    /** Waits up to [maxMs] for [pkg] to show a change after [since] and then go quiet briefly; just [maxMs] when unknown. */
    private suspend fun awaitReaction(pkg: String, since: Long, maxMs: Long) {
        while (now() - since < maxMs) {
            val changed = lastChangeAt(pkg)
            if (changed != null && changed > since && now() - changed >= 150) return
            delay(40)
        }
    }
    /** Shallow read; deep read only when the shallow one cannot see the bot's input or the next entry element. */
    private suspend fun look(app: BotApp, step: Int, since: Long = 0L): UiSnapshot {
        val s = settle(since)
        if (s.sensitive || s.packageName != app.packageName || ready(s, app)) return s
        if (app.entry.any { BotLocator.find(s, it) != null } && (step >= app.entry.size || BotLocator.find(s, app.entry[step]) != null)) return s
        return capture(true)
    }
    private suspend fun read(app: BotApp) = capture(app.deepChat)
    private fun proposal(s: UiSnapshot, type: String, target: UiNode, text: String = "") =
        Proposal(java.util.UUID.randomUUID().toString(), s.snapshotId, type, targetId = target.id, text = text)

    /** True when the bot's message box is on screen. */
    private fun ready(s: UiSnapshot, app: BotApp) = s.packageName == app.packageName && !s.sensitive && BotLocator.input(s) != null &&
        // WhatsApp's normal chats also have an input: require the Meta AI chat header for the Meta AI bot.
        (app != BotApp.META_AI || s.only("conversation_contact_name")?.text?.contains("Meta AI", true) == true)

    suspend fun open(app: BotApp): UiSnapshot {
        var s = settle()
        if (s.packageName != app.packageName) {
            if (!launch(app.packageName)) throw BotError("${app.label}: the app is not installed on this phone.")
            for (i in 0 until 20) { delay(500); s = capture(false); if (s.packageName == app.packageName) break }
            delay(1500); s = look(app, 0)
        }
        // Patience for a slowly loading screen is measured in time, not in readings: readings got faster with
        // event-aware settling, and 8 of them were then only ~6 s (2026-11-09: ABB home still loading after the PIN).
        var step = 0; var backs = 0; var logins = 0; var waitingSince = -1L; var ownerAsked = false
        while (true) {
            coroutineContext.ensureActive()
            if (s.sensitive) {
                // App PIN / login: the owner types it; Köməkçi never reads or enters credentials.
                if (++logins > 2 || !waitForOwnerLogin(app)) throw BotError("${app.label}: the login was not finished.")
                waitingSince = -1L
                delay(1500); s = look(app, step); continue
            }
            if (s.packageName != app.packageName) throw BotError("${app.label}: another app opened, so I stopped.")
            if (ready(s, app)) return s
            // Resume from wherever the app already is: jump to the furthest entry element visible now.
            val visible = app.entry.indices.lastOrNull { BotLocator.find(s, app.entry[it]) != null }
            if (visible != null && visible >= step) step = visible
            val node = if (step < app.entry.size) BotLocator.find(s, app.entry[step]) else null
            if (node != null) {
                val target = BotLocator.clickTarget(s, node) ?: throw BotError("${app.label}: the button cannot be tapped.")
                val tappedAt = now()
                if (!execute(proposal(s, "click", target))) throw BotError("${app.label}: the phone did not accept the tap.")
                step++; waitingSince = -1L
                // Was a fixed 1.2 s: now as soon as the app has redrawn; a next element that is still loading is
                // handled by the waiting below.
                s = look(app, step, since = tappedAt); continue
            }
            if (waitingSince < 0) waitingSince = now()
            val waitedMs = now() - waitingSince
            // App exposes an empty tree on purpose (bank anti-fraud). Never work around it: the owner opens
            // the chat; if that screen is readable, continue from there (max 2 min). Only after 5 s of an empty
            // screen, so a slowly loading screen is not mistaken for a hidden one.
            if (s.nodes.isEmpty() && waitedMs >= 5_000 && !ownerAsked) {
                ownerAsked = true
                askOwnerToOpen(app)
                for (i in 0 until 120) {
                    delay(1000)
                    s = capture(app.deepChat)
                    if (s.sensitive) break
                    if (ready(s, app)) return s
                }
                if (!s.sensitive) throw BotError("${app.label}: the app hides its screen from assistants and I could not read the chat. Please do this step yourself.")
                continue
            }
            if (waitedMs < 12_000) { delay(700); s = look(app, step); continue }
            if (app.allowBack && backs < 3) { backs++; waitingSince = -1L; back(); delay(900); step = 0; s = look(app, 0); continue }
            throw BotError("${app.label}: I could not find the chat window. Open it yourself and try again.")
        }
    }

    /** Types and sends one message; returns the bot's reply text ("" if none came in time). */
    suspend fun exchange(app: BotApp, message: String, onProgress: (String) -> Unit = {}): String {
        var s = read(app)
        if (!ready(s, app)) s = open(app)
        val input = BotLocator.input(s) ?: throw BotError("${app.label}: there is no text field.")
        if (input.text.isNotBlank()) throw BotError("${app.label}: the text field already has text. I will not delete it; please clear it yourself.")
        val baseline = ReplyDiff.baseline(s)
        val typedAt = now()
        if (!execute(proposal(s, "set_text", input, message))) throw BotError("${app.label}: the text could not be typed.")
        awaitReaction(app.packageName, typedAt, 500)
        s = read(app)
        val typed = BotLocator.input(s)
        if (typed == null || stripInvisible(typed.text).trim() != stripInvisible(message).trim()) throw BotError("${app.label}: I could not check the typed text.")
        val send = BotLocator.sendButton(s, app, typed) ?: throw BotError("${app.label}: I did not find the send button.")
        if (!execute(proposal(s, "click", send))) throw BotError("${app.label}: the send was not accepted.")
        onProgress("Sent, waiting for the reply…")
        // Reply = new text that has stopped changing (bots stream their answers). Max ~45 s. Finished when the app has
        // sent no screen change for 1.5 s and two readings agree, else (no change events known) after 2.8 s unchanged
        // as before; the "thinking…" indicator always means not finished.
        val deadline = now() + 45_000
        var last = ""; var sameSince = now(); var sawMine = false
        while (now() < deadline) {
            coroutineContext.ensureActive()
            delay(400)
            val screen = read(app)
            if (screen.sensitive || screen.packageName != app.packageName) throw BotError("${app.label}: the screen changed, so I stopped.")
            sawMine = sawMine || ReplyDiff.sentVisible(screen, message)
            val reply = ReplyDiff.reply(screen, baseline, message)
            val busy = ReplyDiff.texts(screen).any { ReplyDiff.isPending(it.second) }
            val t = now()
            if (reply.isNotEmpty() && reply == last && !busy) {
                val quietFor = lastChangeAt(app.packageName)?.let { t - it }
                if (t - sameSince >= 2800 || (quietFor != null && quietFor >= 1500 && t - sameSince >= 400)) return reply
            } else { last = reply; sameSince = t; if (reply.isNotEmpty()) onProgress(reply.lineSequence().last().take(120)) }
        }
        if (!sawMine) throw BotError("${app.label}: I did not see the message go out. I will not send it again.")
        return last
    }
}
