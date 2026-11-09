package dev.personal.phoneagent.adapters

import dev.personal.phoneagent.agent.*

/**
 * myGov policy (design: docs/mygov-agent.md). The model proposes; this decides.
 *  READ:   tabs/menus/list items, scroll, back, search box. Anything consequential is manual.
 *  ACTION: additionally fills form fields, but only with words the owner said (command or chat answers),
 *          never identifiers/credentials, and never taps the final submit/sign/pay control: the owner does.
 * Login always stays with the owner.
 */
class MyGovAdapter : AppAdapter {
    override val packageName = MYGOV
    companion object {
        /**
         * READ mode: controls that change state. VERB forms only: menu categories are nouns ("Pensiya və ödənişlər",
         * "Müraciətlərim", "Arayış sifarişi") and are navigation. Checked on the control's own name, not on the
         * descriptions inside a tile (a 2026-11-08 test run was wrongly stopped by "…sığorta ödənişləri" in a subtitle).
         */
        val consequential = Regex(
            "müraciət et|muraciet et|müraciəti göndər|göndər|gonder|təsdiqlə|tesdiqle|təsdiq et|imzala|\\bödə\\b|\\bode\\b|ödəniş et|odenis et|ödənişə keç|" +
            "sifariş et|sifaris et|\\bsil\\b|silin|ləğv et|legv et|paylaş|paylas|yüklə|yukle|endir|çıxış|cixis|hesabdan çıx|qeydiyyatdan keç|razıyam|qəbul edirəm|" +
            "\\b(apply|submit|send|sign|pay|delete|remove|cancel|share|download|upload|confirm|agree|accept)\\b|log ?out|sign ?out",
            RegexOption.IGNORE_CASE)
        /** ACTION mode: the final control of a form (own label, short). Opening a service called "Arayış sifarişi" is fine. */
        val finalAction = Regex("^(müraciət(i)? (et|göndər|təsdiqlə)|müraciət et(mək)?|göndər|təsdiq(lə|lə və göndər|ləyin)?|təsdiq et|imzala|imzala və göndər|" +
            "sifariş et|sifarişi (təsdiqlə|göndər|tamamla)|ödə|ödəniş et|ödənişə keç|tamamla|razıyam|qəbul edirəm|submit|send|confirm|sign|pay|apply|finish|agree)[.!]?$", RegexOption.IGNORE_CASE)
        val danger = Regex("\\bsil\\b|silin|ləğv et|legv et|paylaş|paylas|çıxış|cixis|hesabdan çıx|log ?out|sign ?out|\\b(delete|share|pay)\\b|\\bödə\\b|ödəniş et|ödənişə keç", RegexOption.IGNORE_CASE)
        val search = Regex("axtar|search|tap\\b|find", RegexOption.IGNORE_CASE)
        /** Fields the agent never types into, whatever the value. */
        val identifierField = Regex("fin|seriya|nömrə|nomre|telefon|mobil|şifrə|sifre|parol|password|kod|code|pin|otp|kart|card|hesab|iban|e-?poçt|email|mail|imza", RegexOption.IGNORE_CASE)
        private fun words(v: String) = Regex("[\\p{L}\\p{N}]{2,}").findAll(v.lowercase()).map { it.value }.toSet()
        /** Every word typed must come from the owner (command or chat answers): no invented data. */
        fun ownerSaid(text: String, context: TaskContext): Boolean {
            val said = words(context.command + " " + context.clarifications.joinToString(" "))
            val typed = words(text)
            return typed.isNotEmpty() && typed.all { it in said }
        }
    }
    override fun assess(s: UiSnapshot, p: Proposal, context: TaskContext): Decision {
        val n = s.node(p.targetId) ?: return Decision.Manual("The selected element does not exist.")
        if (!n.enabled) return Decision.Manual("Element aktiv deyil.")
        val own = listOf(n.text, n.description).filter { it.isNotBlank() }.joinToString(" ").trim()
        // Compose often puts the label on a child of the clickable row.
        val label = listOf(n.text, n.description, n.resourceId.substringAfter(":id/", "")).plus(s.under(n).flatMap { listOf(it.text, it.description, it.resourceId.substringAfter(":id/", "")) })
            .filter { it.isNotBlank() }.joinToString(" ").take(400)
        val form = context.govIntent == GovIntent.ACTION
        // The control's own name: its text/description, else its first labelled child, else its resource id.
        val head = own.ifBlank { s.under(n).drop(1).firstNotNullOfOrNull { c -> listOf(c.text, c.description).firstOrNull { it.isNotBlank() } }.orEmpty() }
            .ifBlank { n.resourceId.substringAfter(":id/", "") }.trim().take(120)
        return when (p.type) {
            "click" -> when {
                !n.clickable -> Decision.Manual("The element cannot be tapped.")
                !form && consequential.containsMatchIn(head) -> Decision.Manual("In myGov I only read information. Do the step “${head.take(60)}” yourself.")
                form && finalAction.matches(head) ->
                    Decision.Manual("The form is ready. Check the details and tap “${head.take(40)}” yourself.")
                form && danger.containsMatchIn(head) -> Decision.Manual("I do not automate this myGov action: “${head.take(60)}”. Please do it yourself.")
                else -> Decision.Allow
            }
            "scroll" -> if (n.scrollable) Decision.Allow else Decision.Manual("Scrolling is not possible.")
            "set_text" -> when {
                !n.editable -> Decision.Manual("This field cannot be typed into.")
                Privacy.containsSensitiveIdentifier(p.text) || Privacy.requestsCredentials(p.text) -> Decision.Manual("Please enter this information yourself.")
                search.containsMatchIn(label) && p.text.length <= 80 -> Decision.Allow
                form && identifierField.containsMatchIn(label) -> Decision.Manual("Fill in the “${label.take(40)}” field yourself (I do not type into number or code fields).")
                form && n.text.isNotBlank() && n.text != p.text -> Decision.Manual("The field already has “${n.text.take(40)}”. I will not change it; change it yourself if needed.")
                form && ownerSaid(p.text, context) -> Decision.Allow
                form -> Decision.Clarify("What should I type in the “${label.take(40)}” field?")
                else -> Decision.Manual("In myGov I only type into the search field. Please fill in this field yourself.")
            }
            else -> Decision.Manual("Unsupported myGov step.")
        }
    }
}
