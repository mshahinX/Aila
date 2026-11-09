package dev.personal.phoneagent.agent

/**
 * myGov request handling (design: docs/mygov-agent.md). Pure logic, no Android types, so JVM tests cover it.
 *
 *  INFO    general question ("necə yeniləyim?")          → myGov chatbot; screen if the bot cannot answer
 *  READ    the owner's own data ("pensiya kapitalım")     → navigate + read on screen; never the bot
 *  ACTION  do something ("arayış sifariş et")            → navigate + fill the form; owner taps the final button
 *  UNCLEAR                                               → ask the owner
 */
enum class GovIntent { INFO, READ, ACTION, UNCLEAR;
    companion object { fun of(v: String) = entries.firstOrNull { it.name.equals(v.trim(), true) } ?: UNCLEAR }
}

object GovIntentRules {
    /** Domain nouns that, with a 1st-person possessive suffix, mean "my own data". */
    private val stems = "pensiya|kapital|cərimə|cerime|sənəd|sened|vəsiqə|vesiqe|pasport|arayış|arayis|borc|maaş|maas|vergi|müraciət|muraciet|staj|sığorta|sigorta|ödəniş|odenis|hesab|balans|yığıl|yigil|toplan"
    private val possessive = Regex("\\b(mənim|menim|mənə aid|mene aid)\\b|\\b($stems)\\p{L}*(m|ım|im|um|üm)(ı|i|u|ü|ın|in|un|ün|a|ə|da|də|dan|dən)?\\b|" +
        "\\bmy\\s+(?:\\p{L}+\\s+)?(pension|capital|fines?|penalt\\p{L}*|documents?|passport|id card|certificates?|debts?|salary|tax(?:es)?|applications?|insurance|balance|licen[cs]e)\\b", RegexOption.IGNORE_CASE)
    /** Ask-to-do verbs (imperative / "I want to"). */
    private val action = Regex("(müraciət|muraciet|sifariş|sifaris|qeydiyyat|abunə|abune)\\s*(et|edin|elə|ele|etmək|etmek|edim|ver)|" +
        "\\b(arayış|arayis)\\S*\\s+(al|götür|gotur|çıxar|cixar|sifariş|sifaris)|\\b(yenilə|yenile|dəyiş|deyis|doldur|ödə|ode|uzat)(\\b|y?in\\b|mək|mek)|" +
        "\\b(apply|order|renew|submit|book)\\b", RegexOption.IGNORE_CASE)
    /** How/where/what questions: general information. */
    private val info = Regex("\\b(necə|nece|harada|harda|haradan|nə vaxt|ne vaxt|hansı sənəd|hansi sened|nə lazımdır|ne lazimdir|nədir|nedir|qaydası|qaydasi|şərtləri|sertleri|kim(lər)? ala bilər|how|where|what documents|what is)\\b", RegexOption.IGNORE_CASE)

    fun classify(text: String): GovIntent {
        val asksHow = info.containsMatchIn(text)
        val wantsDone = action.containsMatchIn(text)
        val own = possessive.containsMatchIn(text)
        return when {
            // "necə müraciət edim?" could be a question or a request: the owner decides.
            asksHow && wantsDone -> GovIntent.UNCLEAR
            wantsDone -> GovIntent.ACTION
            asksHow && !own -> GovIntent.INFO
            // "vəsiqəmin müddəti nə vaxt bitir?" — own data asked as a question: let the model / owner decide.
            own && !asksHow -> GovIntent.READ
            else -> GovIntent.UNCLEAR
        }
    }
    /**
     * Phone rules override the model on personal-data / action signals (so own data never goes to the bot by
     * mistake); otherwise the model decides, and a real disagreement goes to the owner.
     */
    fun decide(rule: GovIntent, ai: GovIntent): GovIntent = when {
        rule == ai -> rule
        rule == GovIntent.READ || rule == GovIntent.ACTION -> rule
        rule == GovIntent.UNCLEAR -> ai
        ai == GovIntent.UNCLEAR -> rule
        else -> GovIntent.UNCLEAR   // rule says INFO, model says READ/ACTION
    }
    fun question(goal: String) = "“${goal.take(120)}”: what would you like?"
    val choices = listOf("General information", "Look at my own data", "Apply or order something")
    fun fromChoice(choice: String) = when {
        choice.startsWith("General") -> GovIntent.INFO
        choice.startsWith("Look") -> GovIntent.READ
        choice.startsWith("Apply") -> GovIntent.ACTION
        else -> GovIntent.UNCLEAR
    }
}

/**
 * Prepared myGov routes. [calibrated] = verified on the owner's phone; an uncalibrated route is only a hint for
 * the AI navigator. Values on a calibrated route's final screen are read locally and never sent to the cloud.
 */
data class GovRoute(
    val id: String, val title: String, val keywords: Regex, val intent: GovIntent,
    val path: List<Matcher>,
    /** Labels whose neighbouring values are the answer (e.g. "Pensiya kapitalı"). */
    val readLabels: Regex,
    val calibrated: Boolean = false,
    /** The service to end on, named for the AI navigator; [avoid] = look-alike services that are NOT it. */
    val target: String = "", val avoid: String = "",
) {
    /** Human-readable path, also the navigation hint for the AI. */
    val hint: String get() = path.joinToString(" → ") { m -> (m.label ?: m.text ?: m.desc)?.pattern?.trim('^','$')?.replace("|"," / ") ?: m.id }
}

object GovRoutes {
    private fun l(re: String) = Matcher(label = Regex(re, RegexOption.IGNORE_CASE))
    /** Paths come from myGov's own assistant (2026-11-08) and the public app layout; all await calibration. */
    val all = listOf(
        // Labels seen on the owner's phone 2026-11-08 (Xidmətlər grid → Sosial müdafiə və təminat → "Pensiya və
        // ödənişlər" list, whose "Xidmətlər" section holds the calculator). The final screen's read is not yet verified.
        GovRoute("pension", "Pensiya kapitalı", Regex("pensiya|kapital|pension|\\bcapital\\b", RegexOption.IGNORE_CASE), GovIntent.READ,
            listOf(l("^Xidmətlər$"), l("^Sosial müdafiə"), l("^Pensiya və ödənişlər$"), l("^Pensiya kalkulyatoru$")),
            Regex("pensiya kapital|yığılmış|yigilmis|məbləğ|cəmi|yaşa görə|əlilliyə görə|ailə başçısını", RegexOption.IGNORE_CASE),
            calibrated = true, target = "Pensiya kalkulyatoru",
            avoid = "“Əmək pensiyası üzrə məlumatlarım” (yalnız artıq pensiya alanların aylıq pensiyası), “Pensiya ödənişləri”, “Pensiya təyinatı”"),
        GovRoute("fines", "Cərimələr", Regex("cərimə|cerime|protokol|\\bfines?\\b|penalt", RegexOption.IGNORE_CASE), GovIntent.READ,
            listOf(l("^Xidmətlər$"), l("^(Daxili işlər|Nəqliyyat|Yol polisi)"), l("cərimə")),
            Regex("cərimə|məbləğ|borc|tarix|protokol|status", RegexOption.IGNORE_CASE)),
        GovRoute("documents", "Sənədlərim", Regex("sənəd|sened|vəsiqə|vesiqe|pasport|sürücülük|surucu|\\bdocuments?\\b|passport|licen[cs]e|\\bid card\\b", RegexOption.IGNORE_CASE), GovIntent.READ,
            listOf(l("^Sənədlər(im)?$")),
            Regex("etibarlı|bitmə|son tarix|verilmə|tarixi|vəsiqə|pasport|sürücülük|status", RegexOption.IGNORE_CASE)),
        GovRoute("certificate", "Arayışlar", Regex("arayış|arayis|certificate", RegexOption.IGNORE_CASE), GovIntent.ACTION,
            listOf(l("^Xidmətlər$"), l("arayış")),
            Regex("arayış|status|tarix", RegexOption.IGNORE_CASE)),
    )
    fun find(goal: String, intent: GovIntent): GovRoute? =
        all.filter { it.keywords.containsMatchIn(goal) }.let { hits -> hits.firstOrNull { it.intent == intent } ?: hits.firstOrNull() }
}

/**
 * myGov draws its PIN/login screen with its own keypad (no password field), so Android does not flag it.
 * Detected here and treated as sensitive: never sent to the cloud; Köməkçi waits for the owner to log in.
 * (2026-11-08: the AI saw the PIN screen and stopped the task instead of waiting.)
 */
object GovLogin {
    private val authWords = Regex("pin|şifrə|sifre|parol|giriş kodu|kodu daxil|biometri|barmaq izi|face ?id|asan imza|sima imza|mygov id", RegexOption.IGNORE_CASE)
    private val explicit = Regex("pin[ -]?kod(u|unu)?\\s*(daxil|yaz|təyin|tekrar|təkrar)|şifrəni (daxil|yaz)|giriş kodunu", RegexOption.IGNORE_CASE)
    private val digit = Regex("^\\d$")
    fun looksLikeLogin(nodes: List<UiNode>): Boolean {
        val texts = nodes.flatMap { listOf(it.text, it.description) }.map { it.trim() }.filter { it.isNotBlank() }
        val keypad = texts.count { digit.matches(it) } >= 6
        return (keypad && texts.any { authWords.containsMatchIn(it) }) || texts.any { explicit.containsMatchIn(it) }
    }
}

/** Reads the answer from a route's final screen, on the phone. Identifiers are masked even here. */
object GovReader {
    private data class Cell(val text: String, val box: Box)
    /**
     * Each short value ("10425.73₼", "65") gets the nearest label ABOVE IT IN THE SAME COLUMN: myGov shows values
     * in two-column cards (Pensiya kapitalı | Yaşa görə pensiya məbləği), so "the row above" would mix them up.
     * Keeps rows related to the route's read labels when any exist. "" when no value is visible yet.
     */
    fun read(s: UiSnapshot, route: GovRoute): String {
        val cells = s.nodes.filter { !it.editable }.flatMap { n ->
            val b = Box.parse(n.bounds) ?: return@flatMap emptyList()
            listOf(n.text, n.description).filter { it.isNotBlank() }.map { Cell(it.trim(), b) }
        }.distinctBy { it.text to it.box }
        val digit = Regex("\\d")
        val labels = cells.filter { !digit.containsMatchIn(it.text) && it.text.length <= 80 }
        val out = cells.filter { it.text.length <= 40 && GoalCheck.hasValue(it.text) }.sortedWith(compareBy({ it.box.top }, { it.box.left })).map { v ->
            val label = labels.filter { l -> l.box.bottom <= v.box.top + 4 && l.box.left < v.box.right && l.box.right > v.box.left }
                .maxByOrNull { it.box.bottom }?.text?.trimEnd(':', ' ')
            if (label != null) "$label: ${v.text}" else v.text
        }
        val related = out.filter { route.readLabels.containsMatchIn(it) }
        return Privacy.maskIdentifiers((related.ifEmpty { out }).distinct().take(12).joinToString("\n")).take(1500)
    }
}

/**
 * Shows myGov results to the owner in English (the UI language since v9): known myGov labels are translated and
 * amounts get thousands separators ("Pensiya kapitalı: 10425.73₼" → "Pension capital: 10,425.73 ₼"). Display only:
 * the value a later step uses (e.g. a WhatsApp message to Mom) stays exactly as myGov wrote it.
 */
object GovEnglish {
    /** Longest first, so "Yaşa görə pensiya məbləği" wins over "Pensiya məbləği". */
    private val labels = listOf(
        "Ailə başçısını itirməyə görə pensiya məbləği" to "Survivor's pension amount",
        "Əlilliyə görə pensiya məbləği" to "Disability pension amount",
        "Yaşa görə pensiya məbləği" to "Age-based pension amount",
        "Yığılmış pensiya kapitalı" to "Accumulated pension capital",
        "Hüququn yaranma tarixi" to "Entitlement date",
        "Pensiya kalkulyatoru" to "Pension calculator",
        "Pensiya məbləği" to "Pension amount",
        "Pensiya kapitalı" to "Pension capital",
        "Pensiya yaşı" to "Pension age",
        "Sığorta stajı" to "Insurance record",
        "Cərimələr" to "Fines",
        "Cərimə" to "Fine",
        "Borc" to "Debt",
        "Məbləğ" to "Amount",
        "Cəmi" to "Total",
    ).sortedByDescending { it.first.length }
    private val amount = Regex("(?<![\\d.,])(\\d{1,3}(?:\\d{3})*|\\d+)([.,]\\d{1,2})?\\s*(₼|AZN)")
    fun show(text: String): String {
        var out = text
        for ((az, en) in labels) out = out.replace(Regex("(?iu)(?<!\\p{L})" + Regex.escape(az) + "(?!\\p{L})"), en)
        return out.replace(amount) { m ->
            val whole = m.groupValues[1].reversed().chunked(3).joinToString(",").reversed()
            whole + m.groupValues[2].replace(',', '.') + " " + m.groupValues[3]
        }
    }
}
