package dev.personal.phoneagent.agent

import java.util.Locale

/**
 * "Metinin WhatsApp mesajından kartı götür, ABB-dən 20 manat göndər" (design: docs/card-transfer.md).
 * Pure logic, no Android types. The card number never leaves the phone: the cloud model only ever sees it masked.
 */
/** [contactWord] = the name as written ("Metinin"); [contactGuess] = a best-effort stem, only a search hint. */
data class TransferRequest(val contactWord: String, val contactGuess: String, val amount: String, val currency: String = "AZN") {
    companion object {
        private val az = Locale("az", "AZ")
        private val trigger = Regex("kart|\\bcard", RegexOption.IGNORE_CASE)
        private val source = Regex("whatsapp|vatsap|mesaj|message", RegexOption.IGNORE_CASE)
        private val bank = Regex("\\babb\\b", RegexOption.IGNORE_CASE)
        private val money = Regex("(\\d{1,6}(?:[.,]\\d{1,2})?)\\s*(manat|man\\b|azn|₼)", RegexOption.IGNORE_CASE)
        private val notNames = Regex("^(abb|whatsapp|vatsap|kart|mesaj|manat|azn|sabah|bu|onun|ona|mənə|mene)", RegexOption.IGNORE_CASE)
        private const val vowels = "aeıioöuüəAEIİOÖUÜƏ"
        /** Sentence openers and request words: capitalised in English, but never the person. */
        private val englishWords = setOf("take","get","grab","find","copy","use","send","pay","transfer","please","the","card","number","message","from","my","open","go","today","i")
        private val possessive = Regex("(\\p{L}+(?:-\\p{L}+)?)['’]s\\b")
        private val fromPerson = Regex("\\bfrom\\s+(\\p{Lu}\\p{L}+)")
        private fun isPerson(word: String) = !notNames.containsMatchIn(word) && word.lowercase() !in englishWords
        /** "Metin's message", "from Metin": English names the person with no case ending to strip. Null for Azerbaijani wording. */
        private fun englishContact(text: String): String? =
            (possessive.findAll(text) + fromPerson.findAll(text)).map { it.groupValues[1] }.firstOrNull { isPerson(it) }?.replaceFirstChar { it.titlecase(az) }

        /** Spellings to search in WhatsApp for a typed name: see [NameSearch]. */
        fun searchNames(guess: String): List<String> = NameSearch.spellings(guess)
        /** The open chat's header names the person (first word, Azerbaijani letters folded), not a group. */
        fun isPersonChat(header: String, word: String, guess: String): Boolean {
            val first = stripInvisible(header).trim().split(Regex("\\s+")).firstOrNull().orEmpty()
            return listOf(guess, word).any { it.isNotBlank() && (sameName(first, it) || sameName(stripInvisible(header).trim(), it)) }
        }
        fun matches(text: String) = trigger.containsMatchIn(text) && source.containsMatchIn(text) && bank.containsMatchIn(text)

        fun parse(text: String): TransferRequest? {
            if (!matches(text)) return null
            val m = money.find(text)
            val amount = m?.groupValues?.get(1)?.replace(',', '.').orEmpty()
            val word = contactWord(text)
            return TransferRequest(word, englishContact(text) ?: guessStem(word), amount)
        }
        /** An English "Metin's"/"from Metin", else the first capitalised word that is not an app/money word ("Metinin"). "" if none. */
        fun contactWord(text: String): String = englishContact(text) ?:
            Regex("[\\p{Lu}][\\p{L}]+(?:-[\\p{L}]+)?").findAll(text).map { it.value }.firstOrNull { isPerson(it) }.orEmpty()
        /**
         * Azerbaijani case endings are ambiguous on names ("Metinin" = Metin+in, but "Metin" ≠ Met+in), so this is
         * only a hint: the WhatsApp search is done by the model, and the open chat's header is what the owner approves.
         */
        fun guessStem(word: String): String {
            val w = word.removeSuffix("-")
            fun cut(n: Int) = w.dropLast(n).trimEnd('-')
            val before = { n: Int -> w.getOrNull(w.length - n - 1) }
            val stem = when {
                Regex("(dan|dən|den|tan|tən)$", RegexOption.IGNORE_CASE).containsMatchIn(w) -> cut(3)
                // Metin+in, Emin+in, Elçin+in: a repeated syllable means the name itself ends in -in.
                Regex("(inin|ının|unun|ünün)$", RegexOption.IGNORE_CASE).containsMatchIn(w) -> cut(2)
                Regex("(nın|nin|nun|nün)$", RegexOption.IGNORE_CASE).containsMatchIn(w) && before(3)?.let { it in vowels } == true -> cut(3)
                Regex("(ın|in|un|ün)$", RegexOption.IGNORE_CASE).containsMatchIn(w) && before(2)?.let { it !in vowels } == true && w.length >= 6 -> cut(2)
                Regex("(ya|yə|ye)$", RegexOption.IGNORE_CASE).containsMatchIn(w) && before(2)?.let { it in vowels } == true -> cut(2)
                Regex("(ə|e)$", RegexOption.IGNORE_CASE).containsMatchIn(w) && before(1)?.let { it !in vowels } == true && w.length >= 5 -> cut(1)
                else -> w
            }
            return stem.ifBlank { w }.replaceFirstChar { it.titlecase(az) }
        }
    }
}

/**
 * WhatsApp search matches from the start of a word and does not fold Azerbaijani letters: "Rahman" does not find
 * "Rəhman" and "hman" finds nothing (owner's phone, 2026-11-08); "Metin" did not find "Mətin". The typed name is
 * therefore searched under its likely saved spellings, most likely first. Rows are still matched locally with
 * [sameName], so a wrong spelling can only find nothing, never the wrong person.
 */
object NameSearch {
    /** Letters people type without the Azerbaijani keyboard, and what the saved name may have instead. */
    private val vowel = mapOf('a' to "ə", 'e' to "ə", 'ə' to "ae")
    private val other = mapOf('i' to "ı", 'ı' to "i", 'o' to "ö", 'ö' to "o", 'u' to "ü", 'ü' to "u", 's' to "ş", 'ş' to "s",
        'c' to "ç", 'ç' to "c", 'g' to "ğ", 'ğ' to "g")
    private fun swap(name: String, i: Int, to: Char): String {
        val c = name[i]; val t = if (c.isUpperCase()) to.uppercaseChar() else to
        return name.substring(0, i) + t + name.substring(i + 1)
    }
    private fun positions(name: String, table: Map<Char, String>) = name.indices.filter { name[it].lowercaseChar() in table }
    /**
     * Order: a name typed WITH Azerbaijani letters (Rəhman, Elçin) is searched exactly as typed first: the owner wrote it
     * as it is saved (2026-11-08: "Rəhman" found "Rəhman Ağazadə" only after three ASCII spellings). Otherwise: one ə-swap
     * at a time (Rahman → Rəhman, Rahmən), every a/e→ə at once (Rəhmən), the name as typed, then one other-letter swap at
     * a time (Elcin → Elçin). At most [max]; each search costs ~2 s on the phone.
     */
    fun spellings(typed: String, max: Int = 8): List<String> {
        val name = stripInvisible(typed).trim()
        if (name.isEmpty()) return emptyList()
        val out = linkedSetOf<String>()
        if (name.any { it.lowercaseChar() in "əıöüşçğ" }) out += name
        val vs = positions(name, vowel)
        for (i in vs) for (to in vowel.getValue(name[i].lowercaseChar())) out += swap(name, i, to)
        if (vs.size > 1 && vs.all { name[it].lowercaseChar() != 'ə' }) out += vs.fold(name) { acc, i -> swap(acc, i, 'ə') }
        out += name
        for (i in positions(name, other)) for (to in other.getValue(name[i].lowercaseChar())) out += swap(name, i, to)
        return out.take(max)
    }
}

object CardFinder {
    /** 16-digit card numbers, written together or in groups ("4169 7388 1234 5678", "4169-7388-…"). */
    private val candidate = Regex("(?<![0-9])(?:[0-9][ -]?){15}[0-9](?![0-9])")
    fun digits(v: String) = v.filter { it.isDigit() }
    fun luhn(number: String): Boolean {
        val d = digits(number)
        if (d.length != 16) return false
        var sum = 0
        d.reversed().forEachIndexed { i, c -> var x = c - '0'; if (i % 2 == 1) { x *= 2; if (x > 9) x -= 9 }; sum += x }
        return sum % 10 == 0
    }
    data class Found(val number: String, val message: String, val top: Int, val others: Int)
    /**
     * Card numbers visible in the open chat, newest (lowest on screen) first. [texts] = (top, text) read locally.
     * [others] tells the owner when several different cards are on screen.
     */
    fun find(texts: List<Pair<Int, String>>): Found? {
        val hits = texts.flatMap { (top, t) -> candidate.findAll(t).map { it.value }.filter { luhn(it) }.map { Triple(top, digits(it), t) } }
        if (hits.isEmpty()) return null
        val newest = hits.maxBy { it.first }
        return Found(newest.second, newest.third.take(300), newest.first, hits.map { it.second }.distinct().size - 1)
    }
    fun grouped(number: String) = digits(number).chunked(4).joinToString(" ")
    fun masked(number: String) = digits(number).let { "${it.take(4)} **** **** ${it.takeLast(4)}" }
}

/** ABB transfer screens, as seen on the owner's phone 2026-11-08 ("Köçürmələr" → "Digər karta"). */
object AbbTransfer {
    const val CARD_FIELD = "cell_edit_text"
    /** The card-number field of the "Digər karta" form, empty and ready. */
    fun cardField(s: UiSnapshot): UiNode? =
        if (s.packageName != ABB || s.sensitive) null
        else s.nodes.firstOrNull { it.editable && it.enabled && it.resourceId.endsWith("/$CARD_FIELD") }
    /**
     * The owner asked Köməkçi to press the final button itself (2026-11-08). Allowed only when the phone itself sees,
     * in ABB's raw screen text (local), the exact approved amount AND the approved card's last 4 digits, and exactly
     * one final control is on screen. Anything else → the owner presses it.
     */
    fun confirmReady(rawTexts: List<String>, amount: String, card: String): Boolean {
        val want = dev.personal.phoneagent.adapters.AbbAdapter.numbers(amount)
        val last4 = CardFinder.digits(card).takeLast(4)
        // ABB shows the typed amount as its own "1" next to a separate "₼" (2026-11-08).
        val bare = Regex("^\\s*\\d+(?:[.,]\\d+)?\\s*$")
        val amountShown = (rawTexts.any { bare.matches(it) && dev.personal.phoneagent.adapters.AbbAdapter.numbers(it).any { n -> n in want } } &&
            rawTexts.any { Regex("₼|azn|manat", RegexOption.IGNORE_CASE).containsMatchIn(it) }) || rawTexts.any { t -> dev.personal.phoneagent.adapters.AbbAdapter.numbers(t).any { it in want } && Regex("azn|₼|manat|məbləğ", RegexOption.IGNORE_CASE).containsMatchIn(t) } ||
            rawTexts.zipWithNext().any { (a, b) -> Regex("məbləğ|mebleg|amount", RegexOption.IGNORE_CASE).containsMatchIn(a) && dev.personal.phoneagent.adapters.AbbAdapter.numbers(b).any { it in want } }
        val cardShown = last4.length == 4 && rawTexts.any { Regex("(?<![0-9])$last4(?![0-9])").containsMatchIn(it.replace(" ", " ")) }
        return amountShown && cardShown
    }
    /** The single final control (Köçür / Təsdiqlə / Göndər …) on screen, by its own name; null if none or several. */
    fun finalButton(s: UiSnapshot): UiNode? {
        if (s.packageName != ABB || s.sensitive) return null
        val hits = s.nodes.filter { n -> n.clickable && n.enabled && run {
            val own = listOf(n.text, n.description).firstOrNull { it.isNotBlank() }
                ?: s.under(n).drop(1).firstNotNullOfOrNull { c -> listOf(c.text, c.description).firstOrNull { it.isNotBlank() } }.orEmpty()
            dev.personal.phoneagent.adapters.AbbAdapter.isFinal(own)
        } }
        return hits.singleOrNull()
    }
    /** The owner's own cards shown masked on the amount screen ("**** 1234", "•• 1234"), minus the recipient's. */
    fun ownCards(rawTexts: List<String>, recipient: String): List<String> {
        val skip = CardFinder.digits(recipient).takeLast(4)
        return rawTexts.flatMap { t -> Regex("(?:\\*{2,}|•{2,}|x{2,}|X{2,})\\s?(\\d{4})(?!\\d)").findAll(t).map { it.groupValues[1] } }
            .filter { it != skip }.distinct()
    }
    /** One of the owner's cards in ABB's card list: "Mastercard", "•••• 4179", "4.97₼" on one row (2026-11-08). */
    data class OwnCard(val last4: String, val label: String, val balance: String) {
        val choice get() = "$label •••• $last4" + (if (balance.isNotBlank()) " ($balance)" else "")
    }
    /** Cards in a list read locally as (top, text): each masked number with the name above it and the balance on its row. */
    fun cardOptions(raw: List<Pair<Int, String>>, recipient: String): List<OwnCard> {
        val skip = CardFinder.digits(recipient).takeLast(4)
        // "•••• 4179" (Məhsullarım) or "Kart • Debit • 7503" (Göndərən sheet), 2026-11-08.
        val masked = Regex("(?:\\*{2,}|•{2,}|x{2,}|X{2,})\\s?(\\d{4})(?!\\d)|[•·]\\s?(\\d{4})\\s*$")
        val money = Regex("\\d+(?:[.,]\\d+)?\\s*(₼|AZN|USD|EUR|\\$|€)")
        val sorted = raw.sortedBy { it.first }
        return sorted.mapIndexedNotNull { i, (top, t) ->
            val m = masked.find(t) ?: return@mapIndexedNotNull null
            val last4 = m.groupValues[1].ifBlank { m.groupValues[2] }
            val inline = t.substring(0, m.range.first).trim().trimEnd('•', '·', ' ').trim()
            if (last4 == skip) return@mapIndexedNotNull null
            // Nearest text above (same row allowed) that is neither a balance nor another card number.
            val name = sorted.subList(0, i).lastOrNull { top - it.first in 0..140 && !masked.containsMatchIn(it.second) && !money.containsMatchIn(it.second) }?.second.orEmpty()
            val balance = sorted.filter { kotlin.math.abs(it.first - top) <= 140 && money.containsMatchIn(it.second) }.minByOrNull { kotlin.math.abs(it.first - top) }?.second.orEmpty()
            OwnCard(last4, inline.ifBlank { name }.ifBlank { "Kart" }.trim(), balance.trim())
        }.distinctBy { it.last4 }
    }
    /** Amount screen: the source-card tile (balance, no recipient digits), e.g. "Kart · 4.97 ₼" left of the arrow. */
    fun sourceTile(s: UiSnapshot, recipient: String): UiNode? {
        if (s.packageName != ABB || s.sensitive) return null
        val last4 = CardFinder.digits(recipient).takeLast(4)
        s.nodes.firstOrNull { it.clickable && it.enabled && it.resourceId.endsWith("/leftCardContainer") }?.let { return it }
        return s.nodes.filter { n -> n.clickable && n.enabled && !n.editable && run {
            val texts = (listOf(n.text, n.description) + s.under(n).drop(1).flatMap { listOf(it.text, it.description) }).filter { it.isNotBlank() }
            texts.any { Regex("^kart$|kartdan", RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) || Regex("₼|\\[məbləğ gizli]").containsMatchIn(it) } &&
                texts.none { it.contains(last4) || Regex("köçür|davam|təyinat", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        } }.minByOrNull { Box.parse(it.bounds)?.let { b -> b.top * 10 + b.left / 100 } ?: Int.MAX_VALUE }
    }
    val success = Regex("uğurla|uğurlu|icra olundu|icra edildi|tamamlandı|göndərildi|köçürüldü|successful|success|completed", RegexOption.IGNORE_CASE)
    private fun label(s: UiSnapshot, n: UiNode) = (listOf(n.text, n.description) + s.under(n).drop(1).flatMap { listOf(it.text, it.description) })
        .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    /** Home screen (MainPageActivity, readable — verified 2026-11-08): the "Köçürmələr" shortcut, resource id `transfer`. */
    fun transferButton(s: UiSnapshot): UiNode? = if (s.packageName != ABB || s.sensitive) null
        else s.nodes.firstOrNull { it.clickable && it.enabled && it.resourceId.endsWith("/transfer") }
    /** Transfers screen with another tab active: the "Digər karta" tab. */
    fun otherCardTab(s: UiSnapshot): UiNode? = if (s.packageName != ABB || s.sensitive) null
        else s.nodes.firstOrNull { it.clickable && it.enabled && label(s, it).equals("Digər karta", true) }
    fun onTransferScreen(s: UiSnapshot) = cardField(s) != null &&
        s.nodes.any { Regex("^Köçürmələr$|^Digər karta$", RegexOption.IGNORE_CASE).containsMatchIn(it.text.trim()) }
}
