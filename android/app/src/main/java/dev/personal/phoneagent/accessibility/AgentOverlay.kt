package dev.personal.phoneagent.accessibility

import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.personal.phoneagent.ui.OverlayViews
import dev.personal.phoneagent.ui.Ui

/** Trusted local UI. Not part of any model snapshot and never executable as an agent target. */
class AgentOverlay(private val service: AgentAccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private fun dp(v: Int) = Ui.dp(service,v)
    fun dismiss() { view?.let { runCatching { wm.removeView(it) } }; view = null }
    /**
     * While Köməkçi taps with a gesture, its own panel must not catch the touch (2026-11-08: the wide top panel sat over
     * WhatsApp's "Rəhman Ağazadə" row and swallowed the tap). The panel stays visible; only touches pass through.
     */
    fun passThrough(on: Boolean) {
        val v = view ?: return
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        p.flags = if(on) p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        runCatching { wm.updateViewLayout(v,p) }
    }
    private fun show(content: View, focusable: Boolean = false, wide: Boolean = false, top: Boolean = false) {
        dismiss()
        val p = WindowManager.LayoutParams(if(wide || top) service.resources.displayMetrics.widthPixels-dp(24) else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or (if(focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                (if(wide) WindowManager.LayoutParams.FLAG_DIM_BEHIND else 0),PixelFormat.TRANSLUCENT)
        if(wide) p.dimAmount = 0.45f
        p.gravity = when { wide -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; top -> Gravity.TOP or Gravity.CENTER_HORIZONTAL; else -> Gravity.TOP or Gravity.END }
        p.x = if(wide || top) 0 else dp(12); p.y = if(wide) dp(16) else dp(56)
        // A stale service connection has no valid window token; the overlay is UI only, never a reason to fail a task.
        if (runCatching { wm.addView(content,p) }.isFailure) { android.util.Log.w("Kemekci","overlay unavailable"); return }
        view = content
        content.announceForAccessibility(content.contentDescription ?: "Aila")
    }
    /** Palette follows the phone's current day/night setting each time something is shown. */
    private fun ctx() = service.also { Ui.init(it) }
    fun stop(onStop: () -> Unit) = show(OverlayViews.stopChip(ctx(),onStop))
    fun approval(recipient: String, message: String, reason: String, onApprove: () -> Unit, onReject: () -> Unit, via: String = "WhatsApp", seconds: Int = 60) =
        show(OverlayViews.approval(ctx(),recipient,message,reason,via,onApprove,onReject,seconds),wide=true)
    fun progress(title: String, line: String, you: Boolean = false, onStop: () -> Unit) {
        val card = OverlayViews.progress(ctx(),title,line,you,onStop)
        // Wide strip at the top: readable, and clear of bottom sheets and the final button at the bottom.
        // Never focusable, so the target app keeps the keyboard.
        card.layoutParams = android.view.ViewGroup.LayoutParams(service.resources.displayMetrics.widthPixels-dp(24),android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        show(card,top=true)
    }
    fun question(question: String, choices: List<String>, onAnswer: (String) -> Unit, onStop: () -> Unit) =
        show(OverlayViews.question(ctx(),question,choices,onAnswer,onStop),focusable=choices.isEmpty(),wide=true)
}
