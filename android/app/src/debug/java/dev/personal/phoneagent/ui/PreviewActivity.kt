package dev.personal.phoneagent.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import dev.personal.phoneagent.AgentApplication
import dev.personal.phoneagent.agent.*

/**
 * Debug-only design preview: `adb shell am start -n dev.personal.phoneagent/.ui.PreviewActivity --es screen <name>`.
 * Screens: home, plan, running, done, channels, approval, question, settings, bank.
 * It only seeds in-memory UI state (cleared by restarting the app). It never starts a task, sends anything or opens another app.
 */
class PreviewActivity : BaseActivity() {
    private val app get() = application as AgentApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(app.agent.state.value.active) { finish(); return }  // Never disturb a real task.
        val ask = "Anama 50 manat göndərmək istəyirəm, ona WhatsApp-da xəbər ver və sabah həkim qəbulumu xatırlat."
        when(intent.getStringExtra("screen") ?: "home") {
            "home" -> { app.plan=null; app.agent.debugPreview(emptyList(),emptyList()); main(false) }
            "plan" -> { app.agent.debugPreview(listOf(ChatLine("you",ask)),emptyList()); app.plan=PlanRun(RequestPlanner.plan(ask)); main(true) }
            "running" -> {
                app.agent.debugPreview(listOf(ChatLine("you",ask)),emptyList())
                app.plan=PlanRun(RequestPlanner.plan(ask)).apply {
                    start(); advance(); set(StepState.USER_DONE,"You confirmed it in the bank. I cannot see the bank's result.")
                    advance(); set(StepState.ACTIVE)
                }
                main(true)
            }
            "done" -> {
                app.agent.debugPreview(listOf(ChatLine("you",ask)),emptyList())
                app.plan=PlanRun(RequestPlanner.plan(ask)).apply {
                    start(); advance(); set(StepState.USER_DONE,"You confirmed it in the bank. I cannot see the bank's result.")
                    advance(); set(StepState.VERIFIED,"I saw the “sent” mark in WhatsApp.")
                    advance(); set(StepState.USER_DONE,"You saved it in the calendar.")
                    advance()
                }
                main(true)
            }
            "channels" -> {
                app.plan=null
                val q = "ABB-dən soruş, balans artırmanın komissiyası var? Sonra nömrəmə 1 manat yüklə və cavabı Anama WhatsApp-da yaz."
                app.agent.debugPreview(listOf(
                    ChatLine("agent","Phone control connected. Tasks run only when you start them."),
                    ChatLine("you",q), ChatLine("plan","Plan, 3 steps"),
                    ChatLine("bot","ABB: Mobil nömrəyə balans artırma komissiyasızdır.")),
                    listOf(
                        ChannelStepView("ABB",true,"Ask about the top-up fee",StepState.VERIFIED,"Komissiya yoxdur."),
                        ChannelStepView("ABB",false,"Top up your number with 1 ₼",StepState.USER_DONE,"You confirmed it (I did not check the bank's result)."),
                        ChannelStepView("WhatsApp",false,"Send the answer to Mom",StepState.PENDING)))
                main(true)
            }
            "approval" -> overApp { OverlayViews.approval(this,"Ana","Salam! Sənə 1 manat yüklədim. ABB dedi ki, komissiya yoxdur.","","WhatsApp",{ finish() },{ finish() }) }
            "question" -> overApp { OverlayViews.question(this,"ABB: did you check it and do the final confirmation yourself?",listOf("Yes, I did","No"),{ finish() },{ finish() }) }
            "progress" -> overApp(top=true) { OverlayViews.progress(this,"ABB","Enter your ABB PIN.",you=true) { finish() } }
            "settings" -> { startActivity(Intent(this,SettingsActivity::class.java)); finish() }
            "bank" -> { startActivity(Intent(this,BankPreparationActivity::class.java).putExtra("recipient","Anam").putExtra("amount","50").putExtra("flow",BankFlow.CARD.name)); finish() }
            else -> finish()
        }
    }

    private fun main(conversation: Boolean) {
        startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SHOW_CONVERSATION,conversation))
        finish()
    }

    /** Draws an overlay sheet over a neutral stand-in for another app, dimmed like the real window. */
    private fun overApp(top: Boolean = false, sheet: () -> android.view.View) {
        val frame = FrameLayout(this).apply { setBackgroundColor(0xFFE9E5DF.toInt()) }
        frame.addView(TextView(this).apply { text="Another app (preview)"; textSize=18f; setTextColor(0xFF555555.toInt()); gravity=Gravity.CENTER },
            FrameLayout.LayoutParams(-1,-1))
        if(!top) frame.addView(android.view.View(this).apply { setBackgroundColor(0x73000000) },FrameLayout.LayoutParams(-1,-1))
        val view = sheet()
        val width = -1
        frame.addView(view,FrameLayout.LayoutParams(width,-2,if(top) Gravity.TOP or Gravity.END else Gravity.BOTTOM).apply { setMargins(d(12),d(56),d(12),d(16)) })
        setContentView(frame)
        Ui.edgeToEdge(this,frame,0,0,0,0)
    }
}
