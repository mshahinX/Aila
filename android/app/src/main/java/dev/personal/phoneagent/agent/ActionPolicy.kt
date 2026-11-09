package dev.personal.phoneagent.agent

import dev.personal.phoneagent.adapters.AdapterRegistry

class ActionPolicy(private val registry: AdapterRegistry = AdapterRegistry()) {
    fun assess(s: UiSnapshot, p: Proposal, context: TaskContext): Decision {
        if (p.snapshotId != s.snapshotId) return Decision.Manual("Stale proposal snapshot.")
        if (p.type == "tap") return Decision.Manual("Screen taps are for Aila's own scripted flows only.")
        if (s.sensitive) return Decision.Manual("Security-sensitive screen. Automation and cloud capture are paused.")
        if (s.truncated) return Decision.Manual("Screen is too large to inspect safely. Navigate manually to a smaller screen.")
        if (s.packageName !in SUPPORTED_PACKAGES) return Decision.Manual("App is outside your allowlist.")
        if (p.type == "launch") return if (p.packageName in SUPPORTED_PACKAGES &&
            (p.packageName != OWN_PACKAGE || context.mode == "mock")) Decision.Allow else Decision.Manual("Unsupported app launch.")
        // Bank screen content is never used by the generic model executor in this version.
        if (s.packageName == BANK) return Decision.Manual("Banking is manual handoff only until screen verification. Never automate final confirmation.")
        if (p.type == "ask_user" && Privacy.requestsCredentials(p.question))
            return Decision.Manual("Security credentials must never be requested in agent chat. Handle authentication directly in the destination app.")
        if (p.type in setOf("ask_user","manual","wait")) return Decision.Allow
        if (p.type == "finish") return Decision.Allow // Coordinator labels unverified model claims honestly.
        if (p.type == "back") return if (s.screen in setOf("search","chats","chat","fixture") || s.packageName == ABB || s.packageName == MYGOV) Decision.Allow else Decision.Manual("Back from an unverified form requires manual handling.")
        return registry.adapter(s.packageName)?.assess(s,p,context) ?: Decision.Manual("No supported adapter.")
    }
}
