package dev.personal.phoneagent.agent

/**
 * v2 "chatbot-first" channels. Pure logic (no Android types) so the JVM policy harness can test it.
 * Entry paths were calibrated on the demo phone on 2026-11-08 (see docs/chatbot-channels.md).
 */
const val ABB = "iba.mobilbank"
const val AZERCELL = "com.azercell.ss.app"

/** One deterministic UI element on a bot's entry path. Never chosen by a model. */
data class Matcher(val id: String = "", val text: Regex? = null, val desc: Regex? = null, val pickLast: Boolean = false,
                   /** Matches text OR description: WebView/Compose expose labels inconsistently. */
                   val label: Regex? = null) {
    fun matches(n: UiNode) = (id.isEmpty() || n.resourceId == id) &&
        (text == null || text.containsMatchIn(n.text)) && (desc == null || desc.containsMatchIn(n.description)) &&
        (label == null || label.containsMatchIn(n.text.trim()) || label.containsMatchIn(n.description.trim())) &&
        (id.isNotEmpty() || text != null || desc != null || label != null)
}

enum class BotApp(val key: String, val packageName: String, val label: String, val entry: List<Matcher>,
                  val send: Matcher? = null, val allowBack: Boolean = false,
                  /** Chat lives in a WebView deeper than the upload read limit (ABB's Gleap). */
                  val deepChat: Boolean = false) {
    ABB_BOT("abb", ABB, "ABB assistant", listOf(
        Matcher(id = "$ABB:id/imageViewHelpCenter"),
        Matcher(label = Regex("^AI-dan soruş", RegexOption.IGNORE_CASE))), deepChat = true),
    MYGOV_BOT("mygov", MYGOV, "myGov assistant", listOf(
        Matcher(label = Regex("^Daha çox$"), pickLast = true),
        Matcher(label = Regex("^Dəstək$")),
        Matcher(label = Regex("^Süni intellektdən soruş"))), allowBack = true),
    META_AI("whatsapp", WHATSAPP, "Meta AI", listOf(
        Matcher(id = "$WHATSAPP:id/extended_mini_fab")),
        send = Matcher(id = "$WHATSAPP:id/send"), allowBack = true);
    companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
}

data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx get() = (left + right) / 2
    val cy get() = (top + bottom) / 2
    companion object {
        private val re = Regex("^\\[?(-?\\d+),\\s*(-?\\d+)\\]?\\s*[-\\[]\\s*(-?\\d+),\\s*(-?\\d+)\\]?$")
        /** Accepts Rect.toShortString() "[l,t][r,b]". */
        fun parse(v: String): Box? = re.find(v.trim())?.destructured?.let { (l, t, r, b) -> Box(l.toInt(), t.toInt(), r.toInt(), b.toInt()) }
    }
}

object BotLocator {
    fun find(s: UiSnapshot, m: Matcher): UiNode? {
        val all = s.nodes.filter { m.matches(it) }
        return if (m.pickLast) all.maxByOrNull { Box.parse(it.bounds)?.top ?: -1 } else all.firstOrNull()
    }
    /** The node itself if clickable, else its nearest clickable ancestor present in the snapshot. */
    fun clickTarget(s: UiSnapshot, n: UiNode): UiNode? {
        if (n.clickable && n.enabled) return n
        var id = n.id
        while (id.contains('.')) {
            id = id.substringBeforeLast('.')
            s.node(id)?.let { if (it.clickable && it.enabled) return it }
        }
        return null
    }
    fun input(s: UiSnapshot): UiNode? = s.nodes.filter { it.editable && it.enabled }.maxByOrNull { Box.parse(it.bounds)?.bottom ?: 0 }
    private val notSend = Regex("attach|camera|emoji|mic|voice|səs|şəkil|kamera|sticker|gif|photo|fayl|file", RegexOption.IGNORE_CASE)
    /** Explicit matcher when calibrated; otherwise the clickable control right of the input on the same row. */
    fun sendButton(s: UiSnapshot, app: BotApp, input: UiNode): UiNode? {
        app.send?.let { m -> return find(s, m)?.let { clickTarget(s, it) } }
        val box = Box.parse(input.bounds) ?: return null
        return s.nodes.filter { n ->
            val b = Box.parse(n.bounds) ?: return@filter false
            n.clickable && n.enabled && !n.editable && n.id != input.id && !input.id.startsWith(n.id + ".") &&
                b.cy in (box.top - 40)..(box.bottom + 40) && b.cx > box.cx && b.right - b.left < 400 &&
                !notSend.containsMatchIn(n.description + " " + n.text + " " + n.resourceId)
        }.minByOrNull { (Box.parse(it.bounds)!!.left - box.right).let { d -> if (d < -200) 9999 else kotlin.math.abs(d) } }
    }
}

/** Phone-side risk decision for a message Köməkçi wants to send to a bot. The model has no say. */
object BotRisk {
    enum class Level { INFO, CONFIRM, BLOCK }
    private val money = Regex("köçür|kocur|göndər|gonder|ödə|ode[ y]|ödəniş|pul\\b|manat|\\bazn\\b|₼|transfer|top.?up|yüklə|yukle|balans artır|kart(dan|a)\\b|hesab(dan|a)\\b|pay\\b|send money", RegexOption.IGNORE_CASE)
    private val personal = Regex("\\bfin\\b|fin kod|ünvan|unvan|doğum|dogum|telefon nömrə|nomre|şəxsiyyət vəsiqəsinin (nömrə|seriya)|passport number|address", RegexOption.IGNORE_CASE)
    private val submit = Regex("müraciət et|muraciet et|təsdiq|tesdiq|imzala|confirm|submit|ləğv et|blokla|bağla", RegexOption.IGNORE_CASE)
    fun assess(text: String): Level = when {
        Privacy.requestsCredentials(text) || Privacy.containsSensitiveIdentifier(text) -> Level.BLOCK
        money.containsMatchIn(text) || personal.containsMatchIn(text) || submit.containsMatchIn(text) -> Level.CONFIRM
        else -> Level.INFO
    }
    /** Pure information questions ("…komissiya var?") are fine even when they mention money words. */
    fun isQuestionOnly(text: String) = text.trim().endsWith("?") && !Regex("\\b(et|edin|göndər|köçür|ödə|yüklə|təsdiqlə)\\b[^?]*\\?$", RegexOption.IGNORE_CASE).containsMatchIn(text)
    fun decide(text: String): Level = assess(text).let { if (it == Level.CONFIRM && isQuestionOnly(text) && !personal.containsMatchIn(text)) Level.INFO else it }
}

/**
 * Did a step's result actually answer its goal? Phone-side and deterministic, so neither a model nor a bot can
 * mark "how to find it yourself" as an answer. Quantity questions need a number; directions-only replies fail.
 */
object GoalCheck {
    private val quantity = Regex("n[eə]\\s*q[eə]d[eə]r|neçə\\b|m[eə]bl[eə]ğ|mebleg|balans|qalıq|qaliq|borc|kapital|cərimə|cerime|how much|how many|amount|balance", RegexOption.IGNORE_CASE)
    private val directions = Regex("daxil ol|keçid et|kecid et|seçməlisiniz|secmelisiniz|bölməsin(d|)ə|bolmesin|bölümündə|xidmətini seç|log ?in|navigate|go to|select the|open the .* section", RegexOption.IGNORE_CASE)
    private val listMarker = Regex("(?m)^\\s*\\d{1,2}[.)]\\s*")
    /** A real value: 2+ digits, or a digit next to a currency. List numbering ("1.", "2)") does not count. */
    private val value = Regex("\\d{2,}|\\d\\s*(₼|azn|manat)|(₼|azn|manat)\\s*\\d", RegexOption.IGNORE_CASE)
    fun hasValue(text: String) = value.containsMatchIn(text.replace(listMarker, ""))
    fun answers(goal: String, result: String): Boolean {
        val r = result.trim()
        if (r.length < 2) return false
        if (quantity.containsMatchIn(goal) && !hasValue(r)) return false
        if (directions.containsMatchIn(r) && !hasValue(r)) return false
        return true
    }
}

/** Finds the bot's reply: visible text that was not on screen before Köməkçi sent its message. */
object ReplyDiff {
    private val chrome = Regex("^(çat id:.*|chat id:.*|\\d{1,2}:\\d{2}( ?[AP]M)?|bu gün|today|dünən|yesterday|…|\\.\\.\\.|typing…?|yazır…?|online|onlayn|göndərildi|sent|delivered|read|oxundu)$", RegexOption.IGNORE_CASE)
    /** Bots show these while generating; a reply that is only an indicator is not an answer yet. */
    private val pending = Regex("^((düşünür|düşünürəm|thinking|typing|yazır|cavab hazırlanır|hazırlanır|loading|yüklənir|gözləyin|please wait|generating)[.…\\s]*|sorğu(nuz)? üzərində işləyirəm.*|.*çox çəkməyəcək[.!…]*)$", RegexOption.IGNORE_CASE)
    fun isPending(t: String) = pending.matches(t.trim())
    fun texts(s: UiSnapshot): List<Pair<Int, String>> = s.nodes.filter { !it.editable }
        .flatMap { n -> listOf(n.text, n.description).filter { it.isNotBlank() }.map { (Box.parse(n.bounds)?.top ?: 0) to it.trim() } }
    fun baseline(s: UiSnapshot): Set<String> = texts(s).map { it.second }.toSet()
    fun sentVisible(s: UiSnapshot, sent: String): Boolean = texts(s).any { it.second.contains(sent.trim().take(60)) }
    fun reply(s: UiSnapshot, baseline: Set<String>, sent: String): String {
        val mine = sent.trim()
        return texts(s).filter { (_, t) -> t !in baseline && t.length >= 2 && !chrome.matches(t) && !isPending(t) && !t.contains(mine.take(60)) && !(t.length >= 12 && mine.contains(t)) }
            .sortedBy { it.first }.map { it.second }.distinct().joinToString("\n").take(2000)
    }
}
