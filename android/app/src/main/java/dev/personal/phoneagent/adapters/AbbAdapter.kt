package dev.personal.phoneagent.adapters

import dev.personal.phoneagent.agent.*

/**
 * ABB UI fallback (payments the bank's AI chat cannot do). Navigation and form filling are allowed;
 * the final confirm/pay/send control never is: the owner checks the form and taps it, then handles PIN/OTP.
 */
class AbbAdapter : AppAdapter {
    override val packageName = ABB
    companion object {
        // "Köçürmə et" is ABB's final transfer button (owner, 2026-11-08); "Köçürmələr" (the tab) stays navigable.
        val finalAction = Regex("^(təsdiq(lə|ləyin|lə və ödə)?|təsdiq et|ödə|ödəniş et|ödənişi təsdiqlə|göndər|köçür|köçürmə et|köçürməni (et|təsdiqlə|tamamla|göndər)|köçürməni təsdiqlə|confirm|pay( now)?|send|transfer)$", RegexOption.IGNORE_CASE)
        /** Amounts, currency and separators a button may carry next to its verb ("Köçürmə et • 1.00 ₼", "1,00 AZN Ödə"). */
        private val amountBits = Regex("\\d[\\d\\s.,]*\\s*(₼|azn|manat)?|₼|\\bazn\\b|\\bmanat\\b|[•·|]", RegexOption.IGNORE_CASE)
        /** True for ABB's final control, by exact label or by label once any amount is removed. */
        fun isFinal(label: String): Boolean {
            val t = label.trim()
            if (finalAction.matches(t)) return true
            val bare = amountBits.replace(t, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.', '!', ':', '-', '–', '—').trim()
            return bare.isNotEmpty() && finalAction.matches(bare)
        }
        private val number = Regex("\\d+(?:[.,]\\d+)?")
        /** "20", "20.0", "20,00" → "20"; "12.50" → "12.5". Integers keep their zeros ("10", "100"). */
        fun numbers(v: String) = number.findAll(v).map { it.value.replace(',', '.').let { n -> if ('.' in n) n.trimEnd('0').trimEnd('.') else n } }.toSet()
        val danger = Regex("sil|delete|blokla|block|bağla|close account|limit|pin dəyiş|change pin|çıxış|log ?out", RegexOption.IGNORE_CASE)
    }
    override fun assess(s: UiSnapshot, p: Proposal, context: TaskContext): Decision {
        val n = s.node(p.targetId) ?: return Decision.Manual("The selected element does not exist.")
        if (!n.enabled) return Decision.Manual("Element aktiv deyil.")
        val label = (n.text.ifBlank { n.description }).trim() +
            s.under(n).joinToString(" ") { it.text.ifBlank { it.description } }.let { if (n.text.isBlank() && n.description.isBlank()) it.trim() else "" }
        return when (p.type) {
            "click" -> when {
                isFinal(label) -> Decision.Manual("The form is ready. Check the recipient and amount and do the final confirmation in ABB yourself.")
                danger.containsMatchIn(label) -> Decision.Manual("I do not automate this ABB action. Please do it yourself.")
                n.clickable -> Decision.Allow
                else -> Decision.Manual("The element cannot be tapped.")
            }
            "set_text" -> when {
                !n.editable || Privacy.containsSensitiveIdentifier(p.text) || Privacy.requestsCredentials(p.text) -> Decision.Manual("Please fill in this field yourself.")
                // Never overwrite what is already in a field (e.g. the recipient card the phone typed).
                n.text.isNotBlank() && n.text != p.text -> Decision.Manual("The field already has data; I will not change it.")
                // Amounts and other numbers only as the owner said them: the model cannot invent or change a sum.
                numbers(p.text).any { it !in numbers(context.command + " " + context.clarifications.joinToString(" ")) } ->
                    Decision.Manual("This is not the amount you said; I will not type it. Check the amount yourself.")
                else -> Decision.Allow
            }
            "scroll" -> if (n.scrollable) Decision.Allow else Decision.Manual("Scrolling is not possible.")
            else -> Decision.Manual("Unsupported ABB step.")
        }
    }
}
