package dev.personal.phoneagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import dev.personal.phoneagent.agent.*

class SnapshotCollector(private val service: AccessibilityService) {
    private val credentialPrompt = Regex("enter (your )?(pin|password|otp)|one.time (password|code)|verification code|birdəfəlik|təhlükəsizlik kodu|giriş kodu", RegexOption.IGNORE_CASE)
    private fun applicationRoot(): AccessibilityNodeInfo? {
        // The top-most app window: a bottom sheet / dialog (ABB "Göndərən" card list, 2026-11-08) is its own window above
        // the screen and is what the user sees and touches.
        val apps = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val win = apps.maxByOrNull { it.layer }?.takeIf { top -> apps.any { (it.isActive || it.isFocused) && it.root?.packageName == top.root?.packageName } }
            ?: apps.firstOrNull { it.isActive || it.isFocused }
        return win?.root ?: service.rootInActiveWindow
    }
    /**
     * [maxNodes]/[maxDepth] default to the upload limits. The chatbot runner reads larger/deeper trees locally
     * (WebView chats, busy bank home screens): those trees are never uploaded, only the bot's reply text is.
     */
    fun capture(maxNodes: Int = 150, maxDepth: Int = 28): UiSnapshot {
        if ((service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)
            return UiSnapshot(packageName = "locked", sensitive = true, screen = "manual")
        val root = applicationRoot() ?: return UiSnapshot(packageName = OWN_PACKAGE, screen = "unavailable")
        try {
            val pkg = root.packageName?.toString().orEmpty()
            val windowId = root.windowId
            if (pkg !in READABLE_PACKAGES) return UiSnapshot(packageName = pkg,windowId = windowId,screen = "manual")
            // Real-bank capture stays off until its screens are independently calibrated.
            if (pkg == BANK) return UiSnapshot(packageName = pkg,windowId = windowId,screen = "manual",sensitive = true)
            val nodes = mutableListOf<UiNode>()
            var sensitive = false
            var truncated = false
            fun visit(n: AccessibilityNodeInfo, path: String, depth: Int) {
                if (nodes.size >= maxNodes || depth > maxDepth) { truncated = true; return }
                if (!n.isVisibleToUser) return
                val resource = n.viewIdResourceName.orEmpty()
                val shown = stripInvisible(n.text?.toString().orEmpty())
                // Some apps (WhatsApp's message box) show placeholder text without flagging it as a hint.
                val placeholder = n.isEditable && (shown == n.hintText?.toString() ||
                    (resource.endsWith("/entry") && shown.trim().lowercase() in setOf("message","mesaj","сообщение","type a message")))
                val rawText = if (n.isShowingHintText || placeholder) "" else shown
                val description = stripInvisible(n.contentDescription?.toString().orEmpty())
                // Draft and open-chat header bind approvals, so identifiers there stop capture. List rows are only masked.
                val identityOrDraft = resource.substringAfterLast('/') in setOf("entry","conversation_contact_name")
                // Never approve a masked message/recipient as though it were the full exact value.
                // ABB transfer form: the recipient card Köməkçi typed itself stays in the form; it is masked (below), not a stop.
                val abbCard = pkg == ABB && n.isEditable && !n.isPassword && Privacy.containsSensitiveIdentifier(rawText)
                if (n.isPassword || Privacy.credentialResourceId(resource) || credentialPrompt.containsMatchIn(rawText) ||
                    credentialPrompt.containsMatchIn(description) ||
                    (!abbCard && (n.isEditable || identityOrDraft) && Privacy.containsSensitiveIdentifier(rawText))) {
                    sensitive = true; return
                }
                if (rawText.length > 1000 || description.length > 500) truncated = true
                // Empty layout containers carry no meaning; skip them (children are still visited) so busy
                // screens like WhatsApp's chat list stay within the 150-node limit.
                val informative = resource.isNotEmpty() || rawText.isNotEmpty() || description.isNotEmpty() || n.isClickable || n.isEditable || n.isScrollable
                val rect = Rect(); n.getBoundsInScreen(rect)
                if (informative) nodes += UiNode(path,resource,Privacy.mask(rawText.take(1000)),Privacy.mask(description.take(500)),
                    n.className?.toString().orEmpty(),n.isClickable,n.isEditable,n.isScrollable,n.isEnabled,rect.toShortString(),
                    digest("${rawText.length}:$rawText${description.length}:$description"))
                for (i in 0 until n.childCount) {
                    val child = n.getChild(i) ?: continue
                    try { visit(child,"$path.$i",depth+1) } finally { child.recycle() }
                }
            }
            visit(root,"0",0)
            if (nodes.isEmpty() && pkg != OWN_PACKAGE && (service.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                // Debug builds only, no content: why did a supported app expose nothing?
                val wins = service.windows.joinToString(" | ") { w -> val r = w.root
                    "type=${w.type} active=${w.isActive} focused=${w.isFocused} pkg=${r?.packageName} kids=${r?.childCount} vis=${r?.isVisibleToUser}".also { r?.recycle() } }
                android.util.Log.d("KemekciChat","empty-capture root: kids=${root.childCount} vis=${root.isVisibleToUser} cls=${root.className} || $wins")
            }
            if (sensitive) return UiSnapshot(packageName=pkg,windowId=windowId,screen="manual",sensitive=true)
            // ABB: balances/amounts are masked on bank screens. Not inside its AI help chat (the fee answer is
            // the point there) and not in editable fields (the owner's own form input must stay verifiable).
            if (pkg == ABB && nodes.none { it.resourceId == "$ABB:id/gleap_webview" }) nodes.replaceAll { n ->
                n.copy(text = if (n.editable) n.text else Privacy.maskMoney(n.text), description = Privacy.maskMoney(n.description)) }
            // myGov's own PIN pad is not a password field: detect it here so it counts as a login screen.
            if (pkg == MYGOV && GovLogin.looksLikeLogin(nodes)) return UiSnapshot(packageName=pkg,windowId=windowId,screen="manual",sensitive=true)
            // myGov: FİN, document serials and phone numbers never leave the phone (amounts/dates stay readable).
            if (pkg == MYGOV) nodes.replaceAll { n -> n.copy(text = Privacy.maskIdentifiers(n.text), description = Privacy.maskIdentifiers(n.description)) }
            val isFixture = nodes.any { it.resourceId.endsWith("/fixture_header") }
            // The agent's own chat/settings/approval controls are never sent as model tools.
            if (pkg == OWN_PACKAGE && !isFixture) return UiSnapshot(packageName=pkg,windowId=windowId,screen="unavailable")
            val screen = when {
                isFixture -> "fixture"
                nodes.any { it.resourceId.endsWith("/conversation_contact_name") } -> "chat"
                nodes.any { it.resourceId.endsWith("/search_input") } -> "search"
                pkg == WHATSAPP -> "chats"
                // myGov: read-only browsing; MyGovAdapter blocks every consequential control.
                pkg == MYGOV -> "browse"
                // ABB screens it exposes (transfer forms): AbbAdapter never taps the final confirm.
                pkg == ABB -> "browse"
                else -> "manual"
            }
            return UiSnapshot(packageName=pkg,windowId=windowId,screen=screen,nodes=nodes,truncated=truncated)
        } finally { root.recycle() }
    }
    /**
     * LOCAL ONLY, never uploaded or logged: raw visible texts of the open WhatsApp chat (top, text), so a card number
     * the owner asked for can be read on the phone. Snapshots sent to the cloud keep masking it.
     */
    fun rawChatTexts(maxNodes: Int = 400): List<Pair<Int, String>> = rawTexts(WHATSAPP, maxNodes)
    /** LOCAL ONLY: raw visible (top, text) of [pkg]'s window, for on-phone checks (card read, ABB confirm screen). */
    fun rawTexts(pkg: String, maxNodes: Int = 400): List<Pair<Int, String>> {
        val root = applicationRoot() ?: return emptyList()
        try {
            if (root.packageName?.toString() != pkg) return emptyList()
            val out = mutableListOf<Pair<Int, String>>()
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (out.size >= maxNodes || depth > 60 || !n.isVisibleToUser || n.isPassword) return
                val t = stripInvisible(n.text?.toString().orEmpty())
                if (!n.isEditable && t.isNotBlank()) { val r = Rect(); n.getBoundsInScreen(r); out += r.top to t.take(2000) }
                for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; try { visit(c, depth + 1) } finally { c.recycle() } }
            }
            visit(root, 0)
            return out
        } finally { root.recycle() }
    }
    /** LOCAL ONLY: raw text of the first editable field whose resource id ends with [idSuffix] in [pkg] (verifies typing). */
    fun rawFieldText(pkg: String, idSuffix: String): String? {
        val root = applicationRoot() ?: return null
        try {
            if (root.packageName?.toString() != pkg) return null
            var found: String? = null
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (found != null || depth > 60) return
                if (n.isEditable && !n.isPassword && n.viewIdResourceName.orEmpty().endsWith("/$idSuffix")) { found = n.text?.toString().orEmpty(); return }
                for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; try { visit(c, depth + 1) } finally { c.recycle() } }
            }
            visit(root, 0)
            return found
        } finally { root.recycle() }
    }
    /** Re-resolve by the snapshot-local path; callers must compare a fresh fingerprint first. */
    fun withTarget(id: String, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val parts = id.split('.')
        if (parts.firstOrNull() != "0") return false
        var node = applicationRoot() ?: return false
        try {
            for (part in parts.drop(1)) {
                val index = part.toIntOrNull() ?: return false
                val next = node.getChild(index) ?: return false
                node.recycle(); node = next
            }
            if (node.packageName?.toString() !in READABLE_PACKAGES || node.isPassword) return false
            return action(node)
        } finally { node.recycle() }
    }
}
