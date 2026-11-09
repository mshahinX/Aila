package dev.personal.phoneagent.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

const val OWN_PACKAGE = "dev.personal.phoneagent"
const val WHATSAPP = "com.whatsapp"
const val MYGOV = "az.gov.my"
const val BANK = "az.kapitalbank.mbanking"
val SUPPORTED_PACKAGES = setOf(OWN_PACKAGE, WHATSAPP, MYGOV, BANK, ABB)
/**
 * The system share sheet (myGov "Yüklə" opens it with the PDF, 2026-11-08). Köməkçi reads and taps it ONLY inside its
 * scripted passport flow: it is not in [SUPPORTED_PACKAGES], so ActionPolicy refuses every cloud-model proposal there.
 */
val SCRIPTED_PACKAGES = setOf("android", "com.android.intentresolver")
val READABLE_PACKAGES = SUPPORTED_PACKAGES + SCRIPTED_PACKAGES
/**
 * The only elements Köməkçi may TAP (a finger gesture at the element's centre), for rows that offer no click action.
 * WhatsApp's "Send to…" contact rows (2026-11-08: actions select/focus/scroll only). Owner approved this 2026-11-08,
 * for the document-share flow only, on a row whose name was verified first. Never available to the cloud model.
 */
val TAP_TARGETS = setOf("$WHATSAPP:id/contactpicker_row_container")
/** Removes zero-width characters some apps insert into visible text (e.g. WhatsApp search: "\u200BMatin"). */
fun stripInvisible(value: String): String = value.replace(Regex("[\u200B-\u200D\u2060\uFEFF]"), "")
/**
 * Same person's name typed with or without Azerbaijani letters ("Matin" / "Mətin", "Ali" / "Əli").
 * ə may be typed as a or e, but a and e are never treated as equal to each other ("Matin" != "Metin").
 */
fun sameName(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    fun fold(v: String, schwa: Char) = stripInvisible(v).trim().lowercase().map { when (it) {
        'ə' -> schwa; 'ı' -> 'i'; 'ö' -> 'o'; 'ü' -> 'u'; 'ş' -> 's'; 'ç' -> 'c'; 'ğ' -> 'g'; else -> it } }.joinToString("")
    return fold(a,'a') == fold(b,'a') || fold(a,'e') == fold(b,'e')
}
fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

data class UiNode(
    val id: String, val resourceId: String = "", val text: String = "",
    val description: String = "", val className: String = "", val clickable: Boolean = false,
    val editable: Boolean = false, val scrollable: Boolean = false, val enabled: Boolean = true,
    val bounds: String = "",
    val localContentHash: String = "" // Raw-content hash for local stale checks; never serialized.
) {
    fun json() = JSONObject().put("id", id).put("resourceId", resourceId).put("text", text)
        .put("description", description).put("className", className).put("clickable", clickable)
        .put("editable", editable).put("scrollable", scrollable).put("enabled", enabled)
}

data class UiSnapshot(
    val snapshotId: String = UUID.randomUUID().toString(), val packageName: String,
    val windowId: Int = -1, val capturedAt: Long = System.currentTimeMillis(),
    val screen: String = "unavailable", val nodes: List<UiNode> = emptyList(),
    val truncated: Boolean = false, val sensitive: Boolean = false
) {
    // Deliberately exclude timestamps/UUID; changes in text, identity, layout or actions invalidate a plan.
    val fingerprint: String get() {
        fun encode(fields: List<Any>) = fields.joinToString("") { val v=it.toString(); "${v.length}:$v" }
        return digest(encode(listOf(packageName,windowId,screen,truncated,sensitive)) + nodes.joinToString("") {
            encode(listOf(it.id,it.resourceId,it.text,it.description,it.className,it.bounds,it.enabled,it.clickable,it.editable,it.scrollable,it.localContentHash))
        })
    }
    fun json() = JSONObject().put("snapshotId", snapshotId).put("fingerprint", fingerprint)
        .put("packageName", packageName).put("windowId", windowId).put("capturedAt", capturedAt)
        .put("screen", screen).put("nodes", JSONArray(nodes.map { it.json() }))
        .put("truncated", truncated).put("sensitive", sensitive)
    fun node(id: String) = nodes.singleOrNull { it.id == id }
    fun withIdSuffix(suffix: String) = nodes.filter { it.resourceId.endsWith("/$suffix") }
    fun only(suffix: String): UiNode? = withIdSuffix(suffix).singleOrNull()
    fun under(node: UiNode): List<UiNode> = nodes.filter { it.id == node.id || it.id.startsWith(node.id + ".") }
}

data class Proposal(
    val actionId: String, val snapshotId: String, val type: String,
    val targetId: String = "", val text: String = "", val packageName: String = "",
    val question: String = "", val message: String = "", val direction: String = "forward", val durationMs: Int = 0
) {
    companion object {
        fun parse(j: JSONObject): Proposal {
            val expected = setOf("actionId","snapshotId","type","targetId","text","packageName","question","message","direction","durationMs")
            require(j.keys().asSequence().toSet() == expected) { "Unexpected proposal fields" }
            require(listOf("actionId","snapshotId","type","targetId","text","packageName","question","message","direction").all { j.get(it) is String })
            require(j.get("durationMs") is Int || j.get("durationMs") is Long) { "Duration must be an integer" }
            require(j.getLong("durationMs") in 0L..2000L) { "Duration out of range" }
            val p = Proposal(j.getString("actionId"),j.getString("snapshotId"),j.getString("type"),
                j.getString("targetId"),j.getString("text"),j.getString("packageName"),j.getString("question"),
                j.getString("message"),j.getString("direction"),j.getInt("durationMs"))
            require(p.type in setOf("launch","click","set_text","scroll","back","wait","ask_user","finish","manual"))
            require(p.actionId.isNotBlank() && p.snapshotId.isNotBlank() && p.durationMs in 0..2000)
            require(p.direction in setOf("forward","backward") && p.text.length <= 4000 && p.question.length <= 4000 && p.message.length <= 4000)
            return p
        }
    }
}

data class TaskContext(
    val command: String, val clarifications: MutableList<String> = mutableListOf(),
    var confirmedRecipient: String? = null, val mode: String = "mock",
    /** myGov only: READ = navigate and read; ACTION = may also fill form fields with the owner's own words. */
    val govIntent: GovIntent = GovIntent.READ
)
sealed interface Decision {
    data object Allow : Decision
    data class Approval(val recipient: String, val exactText: String, val reason: String = "Send this message?") : Decision
    data class Clarify(val question: String, val choices: List<String> = emptyList()) : Decision
    data class Manual(val reason: String) : Decision
}

object Privacy {
    private val longDigits = Regex("(?<![0-9])(?:[0-9][ -]?){6,}(?![0-9])")
    private val iban = Regex("\\b[A-Z]{2}[0-9]{2}[A-Z0-9 ]{10,32}\\b")
    fun mask(value: String): String = iban.replace(longDigits.replace(value) { "[identifier hidden]" }) { "[account hidden]" }
    private val money = Regex("(?<![0-9])[0-9]([0-9 .,]*[0-9])?\\s?(AZN|₼|USD|EUR|\\$|€|manat)|(AZN|₼|USD|EUR|\\$|€)\\s?[0-9]([0-9 .,]*[0-9])?", RegexOption.IGNORE_CASE)
    /** Balances on bank screens never leave the phone; the amount the owner asked for lives in the command. */
    fun maskMoney(value: String): String = money.replace(value) { "[məbləğ gizli]" }
    // FİN (7 chars, letters+digits), ID card / passport serials, Azerbaijani phone numbers.
    private val fin = Regex("\\b(?=[0-9A-Z]{7}\\b)(?=[0-9A-Z]*[0-9])(?=[0-9A-Z]*[A-Z])[0-9A-Z]{7}\\b")
    private val serial = Regex("\\b(AA|AZE|MYI|C)\\s?[0-9]{7,8}\\b")
    private val phone = Regex("(\\+?994|\\b0)[\\s-]?(10|12|50|51|55|60|70|77|99)[\\s-]?[0-9]{3}[\\s-]?[0-9]{2}[\\s-]?[0-9]{2}\\b")
    /** myGov screens: identifiers never leave the phone (amounts and dates stay readable). */
    fun maskIdentifiers(value: String): String =
        mask(phone.replace(serial.replace(fin.replace(value) { "[FİN gizli]" }) { "[sənəd nömrəsi gizli]" }) { "[telefon gizli]" })
    fun containsSensitiveIdentifier(value: String) = longDigits.containsMatchIn(value) || iban.containsMatchIn(value)
    private val credentialRequest = Regex("\\b(password|passwd|passcode|pin|otp|cvv|cvc|verification code|security code|recovery phrase|seed phrase)\\b|birdəfəlik|təhlükəsizlik kodu|şifrə", RegexOption.IGNORE_CASE)
    fun requestsCredentials(value: String) = credentialRequest.containsMatchIn(value)
    private val credentialIdWords = Regex("(^|[/_])(password|passwd|passcode|otp|cvv|cvc|verification_code|security_code)([_]|$)", RegexOption.IGNORE_CASE)
    // "pin" only as a PIN field (pin, pin_entry, pin_code…), never e.g. WhatsApp's pinned-chat "pin_indicator".
    private val pinFieldId = Regex("(^|[/_])pin(_(code|entry|input|field|pad|digit|digits|view|edit|text|box))*$", RegexOption.IGNORE_CASE)
    fun credentialResourceId(resourceId: String) = credentialIdWords.containsMatchIn(resourceId) || pinFieldId.containsMatchIn(resourceId)
    // No raw screen content in state logs. Only fixed executor results go to the backend.
    fun safeResult(value: String): String = mask(value).take(1000)
}
