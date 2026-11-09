package dev.personal.phoneagent.agent

/**
 * Rewrites how the en-US speech recogniser writes things ("five manat", "$20", "1,000", "5 p.m.") into the forms the
 * request matchers read. Pure logic. Only English forms change, so requests typed in Azerbaijani pass through untouched.
 */
object SpokenEnglish {
    private val numbers = listOf("one","two","three","four","five","six","seven","eight","nine","ten","eleven","twelve",
        "thirteen","fourteen","fifteen","sixteen","seventeen","eighteen","nineteen").withIndex().associate { (i,w) -> w to i+1 } +
        listOf("twenty","thirty","forty","fifty","sixty","seventy","eighty","ninety").withIndex().associate { (i,w) -> w to (i+2)*10 }
    private val word = (numbers.keys+listOf("hundred","thousand")).joinToString("|")
    private val phrase = "(?:a\\s+(?=hundred|thousand))?(?:$word)(?:[\\s-]+(?:and\\s+)?(?:$word))*"

    private val meridiem = Regex("\\b([ap])\\.\\s?m\\.?(?=\\s|$|[,;!?])",RegexOption.IGNORE_CASE)
    private val symbolFirst = Regex("([$€₼])\\s?(\\d[\\d,]*(?:\\.\\d{1,2})?)")
    private val thousands = Regex("(?<![\\d.,])\\d{1,3}(?:,\\d{3})+(?![\\d,])")
    /** A number word stays a word unless it is clearly an amount or a time: "no one is home" must not become "no 1 is home". */
    private val beforeUnit = Regex("\\b($phrase)\\b(?=\\s*(?:manat|azn|dollars?|euros?|am\\b|pm\\b|o['’]?clock\\b))",RegexOption.IGNORE_CASE)
    private val afterAt = Regex("(?<=\\bat )($phrase)\\b(?=\\s*(?:$|[.,;]|tomorrow\\b|today\\b|to\\b))",RegexOption.IGNORE_CASE)
    private val alone = Regex("^\\s*($phrase)\\s*[.!?]?\\s*$",RegexOption.IGNORE_CASE)

    private val namesMyGov = Regex("\\bmy\\s?g(?:ov|uv)\\b",RegexOption.IGNORE_CASE)
    private val joinedMyGov = Regex("\\b(?:my|mi|mu|ma|mc)g(?:ov|uv)\\b",RegexOption.IGNORE_CASE)
    /** Look-alikes the recogniser writes for "my gov". Real words count only right after a word that introduces an app: "ask my golf", never "clean my golf clubs". */
    private val soundsLikeMyGov = Regex("(^\\s*|\\b(?:ask|open|from|on|in|at|to|via|check|use|with|inside)\\s+)(?:the\\s+)?my\\s+(?:gove|gob|gof|golf|gulf|gop)\\b(?!\\s+(?:club|clubs|bag|course|cart|ball|balls|game|lesson))",RegexOption.IGNORE_CASE)

    /** The recogniser's first guess, unless a later one says myGov outright. */
    fun best(guesses: List<String>): String? = guesses.firstOrNull { namesMyGov.containsMatchIn(it) } ?: guesses.firstOrNull()

    fun normalize(text: String): String {
        var out = soundsLikeMyGov.replace(text) { it.groupValues[1]+"myGov" }
        out = joinedMyGov.replace(namesMyGov.replace(out,"myGov"),"myGov")
        out = meridiem.replace(out) { it.groupValues[1].lowercase()+"m" }
        out = symbolFirst.replace(out) { m ->
            val unit = when(m.groupValues[1]) { "$" -> "dollars"; "€" -> "euro"; else -> "manat" }
            "${m.groupValues[2].replace(",","")} $unit"
        }
        out = thousands.replace(out) { it.value.replace(",","") }
        for(pattern in listOf(alone,beforeUnit,afterAt)) out = pattern.replace(out) { m -> value(m.groupValues[1])?.toString() ?: m.value }
        return out
    }

    /** Only well-formed numbers: "twelve fifty" is null (12.50? 1250?), so it is never guessed into an amount. */
    private fun value(spoken: String): Int? {
        var total = 0; var part = 0; var below = Int.MAX_VALUE   // Each word must be smaller than the one before it.
        for(w in spoken.lowercase().trim().split(Regex("[\\s-]+"))) when(w) {
            "a","and" -> Unit
            "thousand" -> { if(total>0 || part>999) return null; total = maxOf(part,1)*1000; part = 0; below = 1000 }
            "hundred" -> { if(part>9) return null; part = maxOf(part,1)*100; below = 100 }
            else -> { val n = numbers[w] ?: return null; if(n>=below) return null; part += n; below = if(n>=20) 10 else 1 }
        }
        return total+part
    }
}
