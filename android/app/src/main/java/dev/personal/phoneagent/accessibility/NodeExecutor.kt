package dev.personal.phoneagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.personal.phoneagent.agent.*
import dev.personal.phoneagent.ui.FixtureActivity

class NodeExecutor(private val service: AccessibilityService, private val collector: SnapshotCollector) {
    fun execute(p: Proposal): Boolean = when (p.type) {
        "launch" -> {
            if (p.packageName !in SUPPORTED_PACKAGES) false else {
                val intent = if (p.packageName == OWN_PACKAGE) Intent(service,FixtureActivity::class.java)
                    else service.packageManager.getLaunchIntentForPackage(p.packageName)
                if (intent == null) false else { service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
            }
        }
        "back" -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        "set_text" -> collector.withTarget(p.targetId) { node ->
            node.isEditable && node.isEnabled && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,p.text) })
        }
        "click" -> collector.withTarget(p.targetId) { node ->
            // The selected node itself must be actionable. Never widen a click to an unvalidated ancestor.
            node.isEnabled && node.isClickable && node.packageName?.toString() in READABLE_PACKAGES &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        "scroll" -> collector.withTarget(p.targetId) { n -> n.isScrollable && n.performAction(
            if (p.direction == "backward") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) }
        // Scripted flows only (ActionPolicy refuses it; the backend protocol cannot send it): a short finger tap at the
        // centre of an allow-listed element that offers no click action (WhatsApp "Send to…" rows).
        "tap" -> collector.withTarget(p.targetId) { node ->
            val r = android.graphics.Rect().also { node.getBoundsInScreen(it) }
            // Tap a third of the way in from the left (the name/photo, not a trailing button); a 1 px stroke, since a
            // single-point path was ignored on the owner's MIUI phone (2026-11-08).
            val x = r.left + r.width() / 3f; val y = r.exactCenterY()
            if (!(node.isEnabled && node.isVisibleToUser && node.viewIdResourceName.orEmpty() in TAP_TARGETS && !r.isEmpty)) false
            else {
                // Köməkçi's own panel lets this touch through; the stroke starts 150 ms later so the window flag is applied.
                val overlay = (service as? AgentAccessibilityService)?.overlay
                overlay?.passThrough(true)
                val restore = { overlay?.passThrough(false); Unit }
                val sent = service.dispatchGesture(android.accessibilityservice.GestureDescription.Builder().addStroke(
                    android.accessibilityservice.GestureDescription.StrokeDescription(
                        android.graphics.Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }, 150, 80)).build(),
                    object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(g: android.accessibilityservice.GestureDescription?) { restore(); android.util.Log.d("KemekciChat", "act: tap completed at ${x.toInt()},${y.toInt()}") }
                        override fun onCancelled(g: android.accessibilityservice.GestureDescription?) { restore(); android.util.Log.w("KemekciChat", "act: tap cancelled at ${x.toInt()},${y.toInt()}") }
                    }, null)
                if (!sent) restore()
                sent
            }
        }
        else -> false
    }
}
