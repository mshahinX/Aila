package dev.personal.phoneagent.agent

/**
 * "myGov-dan şəxsiyyət vəsiqəmi yüklə və WhatsApp-da Rəhmana göndər." (or the ümumvətəndaş pasportu). Pure logic.
 *
 * Routes calibrated on the owner's phone 2026-11-08 (labels only, never the document's contents):
 *   ID card:  myGov home → carousel card "Şəxsiyyət vəsiqəsi" → document screen → top-right menu → "Yüklə"
 *   Passport: myGov home → carousel card "Ümumvətəndaş pasportu" → "ÜMUMVƏTƏNDAŞ" row → document screen → menu → "Yüklə"
 *   then: Android share sheet (myGov opens it with the PDF) → "WhatsApp" app → WhatsApp "Send to…" picker → search the
 *   person → select the personal contact. The owner taps WhatsApp's own Send: Köməkçi never does.
 * The PDF stays in myGov's private folder; only the share sheet hands it to WhatsApp.
 */
enum class GovDoc(val title: String, val accusative: String, val card: Regex, val row: Regex?) {
    ID_CARD("ID card", "şəxsiyyət vəsiqənizi", Regex("(?iu)^şəxsiyyət vəsiqəsi$"), null),
    PASSPORT("passport", "ümumvətəndaş pasportunuzu", Regex("(?iu)^ümumvətəndaş pasportu$"), Regex("(?iu)^ümumvətəndaş$")),
}

object PassportShare {
    private val idCard = Regex("(?iu)şəxsiyyət|sexsiyyet|şexsiyyet|səxsiyyət|vəsiqə|vesiqe|\\bid ?kart|\\bid card|\\bidentity card")
    private val english = Regex("(?iu)\\b(send|share|download|get|to|with|the|please)\\b")
    private val toPerson = Regex("(?iu)\\b(?:to|with)\\s+(?:my\\s+)?(\\p{L}+(?:-\\p{L}+)?)")
    private val notPeople = setOf("whatsapp","mygov","me","him","her","them","the","a","my")
    private val passport = Regex("(?iu)pasport|passport|ümumvətəndaş|umumvetendas|umumvətəndaş")
    private val send = Regex("(?iu)göndər|gonder|yolla|paylaş|paylas|\\bat\\b|whatsapp|vatsap|send|share")
    /** Never a person: app names and words of the request itself. */
    private val notNames = Regex("(?iu)^(whatsapp|vatsap|mygov|my|gov|abb|pasport|passport|xarici|ümumvətəndaş|umumvetendas|şəxsiyyət|sexsiyyet|vəsiqə|vesiqe|sənəd|sened|pdf)")
    private const val vowels = "aeıioöuüəAEIİOÖUÜƏ"

    val download = Regex("(?iu)^yüklə$")

    fun matches(text: String) = (idCard.containsMatchIn(text) || passport.containsMatchIn(text)) && send.containsMatchIn(text)
    /** "vəsiqə" → the ID card; otherwise the passport. */
    fun document(text: String) = if (idCard.containsMatchIn(text)) GovDoc.ID_CARD else GovDoc.PASSPORT

    /**
     * The person, from a dative name: "Rəhmana" → "Rəhman", "Rahman-a" → "Rahman", "Leylaya" → "Leyla".
     * Capitalised words only (a name), so "yüklə" or "vəsiqəmi" never become a recipient. "" → ask the owner.
     * English names the person after "to"/"with"; the dative rules would turn "Share" into "Shar" and "Sara" into "Sar".
     */
    fun recipient(text: String): String {
        if (english.containsMatchIn(text))
            return toPerson.findAll(text).map { it.groupValues[1] }.firstOrNull { it.lowercase() !in notPeople }?.replaceFirstChar { it.titlecase() }.orEmpty()
        for (word in Regex("[\\p{L}]+(?:-[\\p{L}]+)?").findAll(text).map { it.value }) {
            if (!word.first().isUpperCase() || notNames.containsMatchIn(word)) continue
            if ('-' in word) {
                val (name, suffix) = word.split('-', limit = 2)
                if (suffix.lowercase() in setOf("a", "ə", "e", "ya", "yə", "ye") && name.length >= 2) return name
                continue
            }
            val lower = word.lowercase()
            if (word.length < 4) continue
            val before = { n: Int -> word.getOrNull(word.length - n - 1) }
            return when {
                Regex("(ya|yə|ye)$").containsMatchIn(lower) && before(2)?.let { it in vowels } == true -> word.dropLast(2)
                Regex("(a|ə|e)$").containsMatchIn(lower) && before(1)?.let { it !in vowels } == true -> word.dropLast(1)
                else -> continue
            }
        }
        return ""
    }

    /** The share sheet's WhatsApp APP target: its only label is "WhatsApp" (direct-share rows also name a chat). */
    fun isWhatsAppTarget(labels: List<String>) = labels.map { it.trim() }.filter { it.isNotEmpty() }.distinct() == listOf("WhatsApp")
}
