package dev.personal.phoneagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import dev.personal.phoneagent.AgentApplication

class AgentAccessibilityService : AccessibilityService() {
    lateinit var collector: SnapshotCollector
        private set
    lateinit var executor: NodeExecutor
        private set
    lateinit var overlay: AgentOverlay
        private set
    override fun onServiceConnected() {
        super.onServiceConnected()
        collector = SnapshotCollector(this)
        executor = NodeExecutor(this,collector)
        overlay = AgentOverlay(this)
        instance = this
        registerProbe()
        // Automatic reconnects stay silent; otherwise this would be read aloud every time the app opens.
        if (!PhoneControlKeeper.working) (application as AgentApplication).agent.note("Phone control connected. Tasks run only when you start them.")
    }
    /**
     * Debug builds only: `adb shell am broadcast -a dev.personal.phoneagent.PROBE` logs what Köməkçi can see on the
     * current screen — counts and resource ids only, never text — to check whether an app (ABB) hides a screen.
     */
    private var probe: android.content.BroadcastReceiver? = null
    private var request: android.content.BroadcastReceiver? = null
    private fun registerProbe() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0 || probe != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val raw = rootInActiveWindow
                val rawKids = raw?.childCount; val rawPkg = raw?.packageName; raw?.recycle()
                val s = collector.capture(maxNodes = 500, maxDepth = 90)
                val ids = { f: (dev.personal.phoneagent.agent.UiNode) -> Boolean -> s.nodes.filter(f).map { it.resourceId.substringAfter(":id/", "").ifBlank { it.className.substringAfterLast('.') } }.distinct().take(15) }
                android.util.Log.d("KemekciProbe", "pkg=${s.packageName} rawPkg=$rawPkg rawKids=$rawKids nodes=${s.nodes.size} texts=${s.nodes.count { it.text.isNotBlank() || it.description.isNotBlank() }} " +
                    "editable=${s.nodes.count { it.editable }} clickable=${s.nodes.count { it.clickable }} scrollable=${s.nodes.count { it.scrollable }} sensitive=${s.sensitive} truncated=${s.truncated} screen=${s.screen}")
                android.util.Log.d("KemekciProbe", "editableIds=${ids { it.editable }} clickableIds=${ids { it.clickable }}")
                // Labels of tappable elements only (menus/buttons), no digits, ≤40 chars: maps a screen without its personal data.
                val labels = s.nodes.filter { it.clickable && it.enabled }.mapNotNull { n ->
                    val own = (listOf(n.text, n.description) + s.under(n).drop(1).flatMap { listOf(it.text, it.description) }).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                    val b = dev.personal.phoneagent.agent.Box.parse(n.bounds) ?: return@mapNotNull null
                    val id = n.resourceId.substringAfter(":id/", "")
                    val label = own.takeIf { it.length in 1..40 && it.none(Char::isDigit) }.orEmpty()
                    "${label.ifEmpty { "-" }}@${b.cx},${b.cy}[${b.right-b.left}x${b.bottom-b.top}]${if (id.isNotEmpty()) "#$id" else ""}"
                }
                labels.chunked(12).forEachIndexed { i, c -> android.util.Log.d("KemekciProbe", "labels[$i]=$c") }
            }
        }
        val f = android.content.IntentFilter("dev.personal.phoneagent.PROBE")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, android.content.Context.RECEIVER_EXPORTED) else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(r, f)
        probe = r
        // `adb shell am broadcast -a dev.personal.phoneagent.REQUEST -p dev.personal.phoneagent --es text "…" [--ez live true]`:
        // types a request into Köməkçi for on-device testing. Senders must hold DUMP (adb shell does; apps cannot).
        val q = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val text = i?.getStringExtra("text")?.trim().orEmpty().take(500)
                if (text.isEmpty()) return
                val app = application as AgentApplication
                if (i?.getBooleanExtra("live", false) == true) app.liveEnabled = true
                // `--ez type true`: type it visibly into the message box at a human pace (for screen recordings).
                if (i?.getBooleanExtra("type", false) == true) app.debugType = text else app.debugRequest = text
                startActivity(android.content.Intent(this@AgentAccessibilityService, dev.personal.phoneagent.ui.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        val qf = android.content.IntentFilter("dev.personal.phoneagent.REQUEST")
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(q, qf, android.Manifest.permission.DUMP, null, android.content.Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(q, qf, android.Manifest.permission.DUMP, null)
        request = q
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* No passive capture or event logging. */ }
    override fun onInterrupt() { (application as AgentApplication).agent.stop("Android stopped phone control.") }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        probe?.let { runCatching { unregisterReceiver(it) } }; probe = null
        request?.let { runCatching { unregisterReceiver(it) } }; request = null
        (application as AgentApplication).agent.stop("Phone control disconnected.")
        if (::overlay.isInitialized) overlay.dismiss()
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object { @Volatile var instance: AgentAccessibilityService? = null }
}
