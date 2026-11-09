package dev.personal.phoneagent.adapters

import dev.personal.phoneagent.agent.*

class FixtureAdapter : AppAdapter {
    override val packageName = OWN_PACKAGE
    override fun assess(s: UiSnapshot, p: Proposal, context: TaskContext): Decision {
        if (s.screen != "fixture") return Decision.Manual("Agent settings and approval screens cannot be controlled by the agent.")
        val n = s.node(p.targetId) ?: return Decision.Manual("Fixture target unavailable.")
        val name = n.resourceId.substringAfterLast("/")
        return when {
            p.type == "set_text" && name == "fixture_search" -> Decision.Allow
            p.type == "set_text" && name == "fixture_entry" && (n.text.isEmpty() || n.text == p.text) -> Decision.Allow
            p.type == "click" && name == "fixture_contact_alex" -> Decision.Allow
            p.type == "click" && name == "fixture_send" -> {
                val text = s.only("fixture_entry")?.text.orEmpty()
                if (text.isBlank()) Decision.Manual("Fixture draft empty.") else Decision.Approval("Alex Example (synthetic)", text, "Simulate send? No real message will be sent.")
            }
            else -> Decision.Manual("Unrecognized fixture control.")
        }
    }
}
