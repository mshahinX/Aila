package dev.personal.phoneagent.agent

/**
 * Plans a clear single-app request on the phone, so it can start without the 3–8 s cloud plan call (owner's
 * decision, 2026-11-09). Deliberately narrow: exactly one of ABB or myGov named, one clause, no message to a person,
 * and plainly either a question (→ that app's chatbot) or an ABB top-up/payment (→ its form, owner confirms).
 * Everything else returns null and goes to the cloud plan as before. Pure logic, no Android types.
 */
data class LocalStep(val app: String, val channel: String, val goal: String, val intent: String, val firstMessage: String)

object LocalPlan {
    private val abb = Regex("\\babb\\b", RegexOption.IGNORE_CASE)
    private val mygov = Regex("\\bmy\\s?gov", RegexOption.IGNORE_CASE)
    /** "pensiya kapitalım" (pension capital) is not Kapital Bank. */
    private val otherApps = Regex("whatsapp|vatsap|meta ai|azercell|kapital\\s*bank|birbank", RegexOption.IGNORE_CASE)
    /** A second step, or a message for someone: the cloud plan splits and links those. */
    private val multi = Regex("\\s(sonra|və|ve|həm də|hem de|and|then|also)\\s|[.!?]\\s+\\S|\\b(de ki|xəbər ver|xeber ver|göndər|gonder|yaz|tell|send|message)\\b|\\p{L}+(ə|a|e|yə|ya|ye)\\s+de\\b", RegexOption.IGNORE_CASE)
    private val question = Regex("soruş|sorus|öyrən|oyren|\\?|\\bnə qədər|\\bne qeder|\\bnecə\\b|\\bnece\\b|\\bharada\\b|\\bhansı\\b|\\bvarmı\\b|\\bvar mı\\b|\\bhow\\b|\\bwhat\\b|\\bwhich\\b|\\bwhere\\b|\\bask\\b", RegexOption.IGNORE_CASE)
    private val pay = Regex("yüklə|yukle|balans art|köçür|kocur|\\bödə|\\bode\\b|\\btop.?up\\b|\\bpay\\b|\\btransfer\\b", RegexOption.IGNORE_CASE)
    private val lead = Regex("^(abb|my\\s?gov)[-\\s]*(dən|dan|den)?\\s*(soruş|sorus|öyrən|oyren)[:,]?\\s*|^ask\\s+(?:abb|my\\s?gov)[:,]?\\s*", RegexOption.IGNORE_CASE)

    fun single(command: String): LocalStep? {
        val text = command.trim()
        if (text.isEmpty() || text.length > 600 || otherApps.containsMatchIn(text) || multi.containsMatchIn(text.trimEnd('.', '!', '?'))) return null
        val app = when {
            abb.containsMatchIn(text) && !mygov.containsMatchIn(text) -> "abb"
            mygov.containsMatchIn(text) && !abb.containsMatchIn(text) -> "mygov"
            else -> return null
        }
        // A question wins over a payment word, as in the cloud plan ("is there a fee for a top-up?" is a question).
        val asks = question.containsMatchIn(text)
        val pays = !asks && pay.containsMatchIn(text)
        if (!asks && !pays) return null
        val firstMessage = { lead.replace(text, "").trim().ifEmpty { text }.take(1000) }
        return if (app == "abb") {
            if (asks) LocalStep("abb", "bot", text, "info", firstMessage()) else LocalStep("abb", "ui", text, "action", "")
        } else {
            if (pays) return null   // myGov payments are never automated; the cloud plan words the handoff
            // The phone's own myGov rules decide own data / form / general question exactly as for a cloud plan step.
            when (GovIntentRules.classify(text)) {
                GovIntent.INFO -> LocalStep("mygov", "bot", text, "info", firstMessage())
                GovIntent.READ -> LocalStep("mygov", "ui", text, "read", "")
                GovIntent.ACTION -> LocalStep("mygov", "ui", text, "action", "")
                GovIntent.UNCLEAR -> LocalStep("mygov", "ui", text, "unclear", "")
            }
        }
    }
}
