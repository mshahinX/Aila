package dev.personal.phoneagent.adapters

import dev.personal.phoneagent.agent.*

class WhatsAppAdapter : AppAdapter {
    override val packageName = WHATSAPP
    override fun assess(s: UiSnapshot, p: Proposal, context: TaskContext): Decision {
        val n = s.node(p.targetId) ?: return Decision.Manual("The selected control is unavailable.")
        if (!n.enabled) return Decision.Manual("Control disabled.")
        val resource = n.resourceId.removePrefix("$WHATSAPP:id/")
        val header = s.only("conversation_contact_name")?.text.orEmpty()
        val contacts = s.withIdSuffix("conversations_row_contact_name")
        if (p.type == "set_text") {
            if (resource == "search_input" && n.editable) return Decision.Allow
            if (resource == "entry" && n.editable && header.isNotBlank()) {
                if (!sameName(context.confirmedRecipient, header))
                    return Decision.Clarify("Confirm the recipient before I write anything.", listOf("Use $header", "Cancel"))
                if (n.text.isNotEmpty() && n.text != p.text)
                    return Decision.Manual("An existing draft is present. Save or clear it manually, then resume. I will not overwrite it.")
                return Decision.Allow
            }
        }
        if (p.type == "scroll" && n.scrollable && contacts.isNotEmpty()) return Decision.Allow
        if (p.type == "click") {
            if (resource == "send" && header.isNotBlank()) {
                val draft = s.only("entry")?.text.orEmpty()
                if (!sameName(context.confirmedRecipient, header))
                    return Decision.Clarify("Confirm the recipient first.", listOf("Use $header", "Cancel"))
                if (draft.isBlank()) return Decision.Manual("No draft to send.")
                return Decision.Approval(header, draft)
            }
            // Pin navigation to the real app resource namespace, not a message containing 'Search'.
            if (n.resourceId.startsWith("$WHATSAPP:id/") && resource in setOf(
                    "menuitem_search", "search_bar", "search_bar_inner_layout", "search_text", "search_input", "search_container"))
                return Decision.Allow
            if (n.description.startsWith("Chats") && s.screen == "chats" && header.isBlank() &&
                s.under(n).any { it.resourceId.startsWith("$WHATSAPP:id/navigation_bar_item_") && it.text == "Chats" }) return Decision.Allow
            val associated = contacts.filter { it.id == n.id || it.id.startsWith(n.id + ".") }
            if (associated.size == 1) {
                val chosen = associated.single().text
                // Two rows with the same name are ambiguous even if the user named it: always ask.
                if (contacts.count { sameName(it.text, chosen) } > 1)
                    return Decision.Clarify("Which contact did you mean?", contacts.map { it.text }.distinct())
                if (contacts.size > 1 && !sameName(context.confirmedRecipient, chosen))
                    return Decision.Clarify("Which contact did you mean?", contacts.map { it.text }.distinct())
                return Decision.Allow
            }
            // Model may select the contact's text node; only its verified clickable ancestor is used.
            if (n in contacts) {
                if (contacts.count { sameName(it.text, n.text) } > 1 || (contacts.size > 1 && !sameName(context.confirmedRecipient, n.text)))
                    return Decision.Clarify("Which contact did you mean?", contacts.map { it.text }.distinct())
                return Decision.Allow
            }
        }
        return Decision.Manual("This WhatsApp control has not been verified as safe navigation. Please handle it manually, then resume.")
    }
}
