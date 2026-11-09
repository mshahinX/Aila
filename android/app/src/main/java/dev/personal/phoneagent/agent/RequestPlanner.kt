package dev.personal.phoneagent.agent

import java.util.Locale

/**
 * Local, deterministic splitting of one spoken request into service steps.
 * Runs entirely on the phone; nothing here calls the network or executes anything.
 * Each step is later run through an existing safe path (bank checklist handoff, agent task with
 * per-message approval, calendar screen the user saves). Unrecognised text stays one agent step.
 */
enum class StepKind { MONEY, MESSAGE, REMINDER, GOV, AGENT }

data class PlanStep(
    val kind: StepKind, val source: String,
    val recipient: String = "", val amount: String = "", val currency: String = "AZN",
    val draft: String = "", val reminderTitle: String = "", val dayOffset: Int = 0, val time: String = "",
    val dependsOnMoney: Boolean = false
) {
    val title: String get() = when(kind) {
        StepKind.MONEY -> if(amount.isBlank()) "Send money" else "Send $amount $currency"
        StepKind.MESSAGE -> "WhatsApp message"
        StepKind.REMINDER -> "Reminder: $reminderTitle"
        StepKind.GOV -> "myGov service"
        StepKind.AGENT -> "Aila task"
    }
    val detail: String get() = when(kind) {
        StepKind.MONEY -> listOfNotNull(recipient.takeIf { it.isNotBlank() }?.let { "To: $it" },"Kapital Bank").joinToString(" · ")
        StepKind.MESSAGE -> if(recipient.isBlank()) "To: I will ask you" else "To: $recipient"
        StepKind.REMINDER -> listOf(RequestPlanner.dayName(dayOffset),time.ifBlank { "choose a time" }).joinToString(" · ")
        StepKind.GOV -> "myGov opens, you continue there"
        StepKind.AGENT -> source
    }
    /** What the user does themselves for this step; shown as an amber note. */
    val userPart: String get() = when(kind) {
        StepKind.MONEY -> "You do the final confirmation in the bank"
        StepKind.MESSAGE -> "I will ask you before sending"
        StepKind.REMINDER -> "You tap Save in the calendar"
        StepKind.GOV -> "You log in and confirm"
        StepKind.AGENT -> "I will ask you at important steps"
    }
    /** Command handed to the agent for MESSAGE/AGENT steps. */
    fun agentCommand(): String = when(kind) {
        StepKind.MESSAGE -> buildString {
            append("WhatsApp-da ")
            append(if(recipient.isBlank()) "kontaktı məndən soruş və ona" else "\"$recipient\" kontaktına")
            if(draft.isBlank()) append(" bu istəyə uyğun qısa mesaj hazırla: \"$source\".")
            else append(" bu mesajı hazırla: \"$draft\".")
            append(" Göndərməzdən əvvəl mənim təsdiqimi al.")
        }
        else -> source
    }
}

object RequestPlanner {
    // English "and" only splits before a new action: "buy bread and milk" stays one thing.
    private val split = Regex("\\s*(?:,(?!\\d)|;|\\.(?:\\s+|$)|\\s+və\\s+|\\s+sonra\\s+|\\s+həm də\\s+|\\s+and\\s+then\\s+|\\s+then\\s+|\\s+and\\s+(?=(?:also\\s+)?(?:send|pay|transfer|message|text|tell|whatsapp|remind)\\b))\\s*",RegexOption.IGNORE_CASE)
    private val money = Regex("(\\d{1,6}(?:[.,]\\d{1,2})?)\\s*(manat|man\\b|azn|₼|dollar|usd|\\$|avro|euro|eur|€)",RegexOption.IGNORE_CASE)
    private val moneyVerb = Regex("pul\\s+(göndər|köçür|at)|köçür|ödə|ödəniş|send\\s+(?:some\\s+)?money|transfer\\s+money|transfer\\s+to\\b",RegexOption.IGNORE_CASE)
    private val reminder = Regex("xatırlat|yadıma sal|remind",RegexOption.IGNORE_CASE)
    /** English puts the verb first, so "Remind me to pay 20 manat" is a reminder and not a payment. */
    private val remindFirst = Regex("^(?:please\\s+)?remind\\b",RegexOption.IGNORE_CASE)
    private val message = Regex("whatsapp|vatsap|mesaj|xəbər ver|\\byaz\\b|de ki|message|\\btext\\b|\\btell\\b",RegexOption.IGNORE_CASE)
    private val gov = Regex("mygov|my gov|asan|dövlət|arayış",RegexOption.IGNORE_CASE)
    private val time = Regex("(?:saat\\s*)?(\\d{1,2})[:.](\\d{2})|saat\\s*(\\d{1,2})\\b",RegexOption.IGNORE_CASE)
    private val englishTime = Regex("(?:\\bat\\s+)?\\b(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)\\b|\\bat\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\b(?:\\s*o['’]?clock)?",RegexOption.IGNORE_CASE)
    private val quoted = Regex("[\"“”«»](.{1,500}?)[\"“”«»]|(?:de ki|yaz ki|desin ki|\\bsaying\\b|\\bsay\\b|\\bthat\\b)[:\\s]+(.{1,500})",RegexOption.IGNORE_CASE)
    private val notNames = setOf("ona","mənə","sənə","bizə","onlara","sabah","mene","sene","bize","birigün","bura","ora","whatsapp","manat","azn","pula","indi")
    private val english = Regex("\\b(send|tell|text|message|pay|transfer|to|the|please|him|her|them)\\b",RegexOption.IGNORE_CASE)
    /** The word after the verb or "to" is the person. A lookahead, so "message to Sara" can still match at "to". */
    private val englishPerson = Regex("(?:\\b(?:to|tell|text|message|send|pay)\\s+|^(?:please\\s+)?whatsapp\\s+)(?=(?:my\\s+)?(\\p{L}+(?:-\\p{L}+)?))",RegexOption.IGNORE_CASE)
    private val notPeople = setOf("a","an","the","me","him","her","them","us","it","this","that","my","some","to","on","in","via","money","message","whatsapp","mesaj")
    private val az = Locale("az","AZ")

    fun dayName(offset: Int) = when(offset) { 0 -> "Today"; 1 -> "Tomorrow"; 2 -> "In 2 days"; else -> "In $offset days" }

    fun plan(request: String): List<PlanStep> {
        val clauses=request.trim().split(split).map { it.trim() }.filter { it.length>1 }
        val steps=mutableListOf<PlanStep>()
        var lastRecipient=""
        for(clause in clauses) {
            val step=classify(clause,lastRecipient,steps.lastOrNull { it.kind==StepKind.MONEY })
            if(step.recipient.isNotBlank()) lastRecipient=step.recipient
            // A fragment with no service of its own (e.g. "istəyirəm") joins the previous step's text.
            if(step.kind==StepKind.AGENT && steps.isNotEmpty() && clause.split(Regex("\\s+")).size<=2) continue
            steps+=step
        }
        return steps.ifEmpty { listOf(PlanStep(StepKind.AGENT,request.trim())) }.take(5)
    }

    /** True when the plan is worth showing as a multi-service plan instead of a plain agent task. */
    fun isServicePlan(steps: List<PlanStep>) = steps.any { it.kind!=StepKind.AGENT }

    private fun classify(clause: String, lastRecipient: String, priorMoney: PlanStep?): PlanStep {
        val m=money.find(clause)
        if(!remindFirst.containsMatchIn(clause) && (m!=null || moneyVerb.containsMatchIn(clause))) {
            val amount=m?.groupValues?.get(1)?.replace(',','.').orEmpty()
            val currency=when(m?.groupValues?.get(2)?.lowercase()) { "dollar","usd","$" -> "USD"; "avro","euro","eur","€" -> "EUR"; else -> "AZN" }
            return PlanStep(StepKind.MONEY,clause,recipient=recipient(clause).ifBlank { if(refersBack(clause)) lastRecipient else "" },amount=amount,currency=currency)
        }
        if(reminder.containsMatchIn(clause)) return reminderStep(clause)
        if(message.containsMatchIn(clause)) {
            val who=recipient(clause).ifBlank { if(refersBack(clause)) lastRecipient else "" }
            val explicit=quoted.find(clause)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }?.trim().orEmpty()
            val draft=explicit.ifBlank {
                if(priorMoney!=null && priorMoney.amount.isNotBlank() && (who.isBlank() || who==priorMoney.recipient))
                    "Salam, sənə ${priorMoney.amount} ${if(priorMoney.currency=="AZN") "manat" else priorMoney.currency} göndərdim." else ""
            }
            return PlanStep(StepKind.MESSAGE,clause,recipient=who,draft=draft,
                dependsOnMoney=priorMoney!=null && explicit.isBlank() && draft.isNotBlank())
        }
        if(gov.containsMatchIn(clause)) return PlanStep(StepKind.GOV,clause)
        return PlanStep(StepKind.AGENT,clause)
    }

    private fun refersBack(clause: String) = Regex("\\b(ona|onu|onunla|him|her|them)\\b",RegexOption.IGNORE_CASE).containsMatchIn(clause)

    /** English names the person outright ("to Sara", "tell my mom"); Azerbaijani dative ("Anama", "Əliyə", "Leylaya") → nickname ("Anam", "Əli", "Leyla"). */
    fun recipient(clause: String): String {
        // The dative rules below would read English words as names ("Sara" → "Sar", "please" → "Pleas").
        if(english.containsMatchIn(clause))
            return englishPerson.findAll(clause).map { it.groupValues[1] }.firstOrNull { it.lowercase() !in notPeople }?.replaceFirstChar { it.titlecase(Locale.ROOT) }.orEmpty()
        val words=Regex("[\\p{L}]+(?:-[\\p{L}]+)?").findAll(clause).map { it.value }.toList()
        for(word in words) {
            val lower=word.lowercase(az)
            if(lower in notNames) continue
            // "Matin-ə" / "Matin-e": name, hyphen, dative suffix.
            if('-' in word) {
                val (name,suffix)=word.split('-',limit=2)
                if(suffix.lowercase(az) in setOf("a","ə","e","ya","yə","ye") && name.length>=2 && name.lowercase(az) !in notNames) return name.replaceFirstChar { it.titlecase(az) }
                continue
            }
            if(word.length<4) continue
            // "e"/"ye" cover typing without the ə key ("Matine", "Əliye").
            val stem=when {
                lower.endsWith("ya") || lower.endsWith("yə") || lower.endsWith("ye") -> word.dropLast(2)
                (lower.endsWith("a") || lower.endsWith("ə") || lower.endsWith("e")) && lower[lower.length-2] !in "aeıioöuüə" -> word.dropLast(1)
                else -> continue
            }
            if(stem.length<2 || Regex("(mək|maq)$").containsMatchIn(lower)) continue
            if(Regex("^(göndər|köçür|yaz|xəbər|sabah|həkim|qəbul|bank|kart|hesab|ödə|pul|mesaj|salam|whatsapp|zəng|gəl|ged)",RegexOption.IGNORE_CASE).containsMatchIn(lower)) continue
            return stem.replaceFirstChar { it.titlecase(az) }
        }
        return ""
    }

    private fun reminderStep(clause: String): PlanStep {
        val lower=clause.lowercase(az)
        val day=when { "birigün" in lower || "day after tomorrow" in lower -> 2; "sabah" in lower || "tomorrow" in lower -> 1; else -> 0 }
        val en=englishTime.find(clause)
        val t=time.find(clause)
        val at=if(en!=null) englishClock(en) else t?.let {
            val h=(it.groupValues[1].ifBlank { it.groupValues[3] }).toIntOrNull() ?: return@let ""
            val min=it.groupValues[2].ifBlank { "00" }.toIntOrNull() ?: 0
            if(h in 0..23 && min in 0..59) "%02d:%02d".format(h,min) else ""
        }.orEmpty()
        var title=clause.replace(englishTime,"").replace(time,"")
            .replace(Regex("\\b(sabah|birigün|bu gün|bugün|mənə|please|(?:the )?day after tomorrow|tomorrow|today|remind me( to| about| of| that)?)\\b",RegexOption.IGNORE_CASE),"")
            .replace(Regex("(xatırlat|yadıma sal)\\p{L}*",RegexOption.IGNORE_CASE),"")
            .replace(Regex("\\s+")," ").trim()
        // "həkim qəbulumu" → "həkim qəbulu" (possessive + accusative → possessive).
        title=title.replace(Regex("(\\p{L}+)(umu|ümü|ımı|imi)$")) { it.groupValues[1]+it.groupValues[2].first() }
        return PlanStep(StepKind.REMINDER,clause,reminderTitle=title.ifBlank { "Reminder" }.replaceFirstChar { it.titlecase(az) },dayOffset=day,time=at)
    }

    /** "5 pm" → 17:00, "12 am" → 00:00, "at 9:30" → 09:30. A bare hour is taken as written, like "saat 5". "" if impossible. */
    private fun englishClock(m: MatchResult): String {
        val (hour1,minute1,meridiem,hour2,minute2)=m.destructured
        val hour=hour1.ifBlank { hour2 }.toIntOrNull() ?: return ""
        val minute=minute1.ifBlank { minute2 }.ifBlank { "00" }.toIntOrNull() ?: return ""
        val h=when(meridiem.lowercase()) { "pm" -> if(hour==12) 12 else hour+12; "am" -> if(hour==12) 0 else hour; else -> hour }
        return if(h in 0..23 && minute in 0..59 && (meridiem.isBlank() || hour in 1..12)) "%02d:%02d".format(h,minute) else ""
    }
}

enum class StepState { PENDING, ACTIVE, RUNNING, ASK_TIME, ASK_DONE, VERIFIED, USER_DONE, NOT_DONE, SKIPPED, FAILED }

/** Progress of one plan. Pure state; the activity performs the side effects. */
class PlanRun(steps: List<PlanStep>) {
    val steps = steps.toMutableList()
    val states = MutableList(steps.size) { StepState.PENDING }
    val notes = MutableList(steps.size) { "" }
    var index = -1
        private set
    var started = false
        private set
    /** Coordinator finishedTasks count when this plan's agent step was launched. */
    var agentMark = -1
    val current: PlanStep? get() = steps.getOrNull(index)
    val finished: Boolean get() = started && index>=steps.size
    fun start() { started=true }
    /** Moves to the next runnable step, auto-skipping a money-dependent message when money was not sent. */
    fun advance(): PlanStep? {
        while(true) {
            index++
            val step=current ?: return null
            if(step.kind==StepKind.MESSAGE && step.dependsOnMoney) {
                val money=steps.indices.lastOrNull { it<index && steps[it].kind==StepKind.MONEY }
                if(money!=null && states[money] !in setOf(StepState.USER_DONE,StepState.VERIFIED)) {
                    states[index]=StepState.SKIPPED; notes[index]="Skipped this message because the money was not sent."; continue
                }
            }
            states[index]=StepState.ACTIVE
            return step
        }
    }
    fun set(state: StepState, note: String = "") { if(index in steps.indices) { states[index]=state; if(note.isNotBlank()) notes[index]=note } }
    fun cancelRemaining() {
        for(i in steps.indices) if(states[i] in setOf(StepState.PENDING,StepState.ACTIVE,StepState.RUNNING,StepState.ASK_TIME,StepState.ASK_DONE)) {
            states[i]=StepState.SKIPPED; notes[i]="Stopped"
        }
        started=true; index=steps.size
    }
    fun summary(): String = steps.indices.joinToString(". ") { i -> "${steps[i].title}: ${label(states[i])}" }
    companion object {
        fun label(s: StepState) = when(s) {
            StepState.PENDING -> "Waiting"; StepState.ACTIVE -> "Next"; StepState.RUNNING -> "Working"; StepState.ASK_TIME -> "Choose a time"
            StepState.ASK_DONE -> "Waiting for you"; StepState.VERIFIED -> "Verified"; StepState.USER_DONE -> "You did it"
            StepState.NOT_DONE -> "Not done"; StepState.SKIPPED -> "Skipped"; StepState.FAILED -> "Failed"
        }
    }
}
