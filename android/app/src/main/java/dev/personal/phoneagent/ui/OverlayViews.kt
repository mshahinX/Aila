package dev.personal.phoneagent.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import dev.personal.phoneagent.R

/**
 * Views shown over other apps (by the Accessibility overlay) and in the debug design preview.
 * Same cards as the chat: white, 1dp border, orange edge when the owner must decide.
 * Pure builders: no window handling, no decisions; callers own approval logic.
 */
object OverlayViews {
    private fun dp(c: Context, v: Int) = Ui.dp(c,v)

    /** Bottom-sheet surface used by approval and question overlays. */
    fun sheet(c: Context) = Ui.vertical(c).apply {
        background = Ui.rounded(Ui.surface,dp(c,16).toFloat(),Ui.amberLine,(1.5f*c.resources.displayMetrics.density).toInt())
        setPadding(dp(c,20),dp(c,18),dp(c,20),dp(c,16))
        elevation = dp(c,8).toFloat()
    }

    private fun kicker(c: Context, value: String) = Ui.horizontal(c).apply {
        addView(Ui.icon(c,R.drawable.ic_info,Ui.amber,18))
        addView(Ui.text(c,value,Ui.Type.CAPTION+1,Ui.amber,bold=true).apply { setPadding(0,0,0,0) },Ui.lp(-2,start=dp(c,6)))
    }

    fun stopChip(c: Context, onStop: () -> Unit) = Ui.stopButton(c) { onStop() }.apply { elevation = dp(c,6).toFloat() }

    /**
     * Wide panel at the top of the target app while Köməkçi works there. The instruction is the big text; [you] turns it
     * orange ("Your turn": PIN, login, final button). Never focusable, so the target app keeps its keyboard.
     */
    fun progress(c: Context, title: String, line: String, you: Boolean = false, onStop: () -> Unit) = Ui.horizontal(c).apply {
        val head = if(you) c.getString(R.string.your_turn)+" · "+title else "Aila · "+title
        contentDescription = "$head. $line"
        background = if(you) Ui.rounded(Ui.amberSoft,dp(c,16).toFloat(),Ui.amberLine,(1.5f*c.resources.displayMetrics.density).toInt())
                     else Ui.rounded(Ui.strong,dp(c,16).toFloat())
        setPadding(dp(c,16),dp(c,12),dp(c,12),dp(c,12)); elevation = dp(c,8).toFloat()
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val col = Ui.vertical(c)
        col.addView(Ui.horizontal(c).apply {
            addView(Ui.icon(c,if(you) R.drawable.ic_person else R.drawable.ic_chat,if(you) Ui.amber else Ui.inversePrimary,16))
            addView(Ui.text(c,head,Ui.Type.CAPTION,if(you) Ui.amber else Ui.onStrong,bold=true).apply { setPadding(0,0,0,0); alpha=if(you) 1f else 0.8f; maxLines=1 },Ui.lp(-2,start=dp(c,6)))
        })
        col.addView(Ui.text(c,line.take(160),Ui.Type.BODY,if(you) Ui.ink else Ui.onStrong,bold=true).apply { maxLines=4; ellipsize=TextUtils.TruncateAt.END },Ui.lp(top=dp(c,2)))
        addView(col,Ui.lp(0,weight=1f))
        addView(Ui.stopButton(c) { onStop() },Ui.lp(-2,start=dp(c,10)))
    }

    /** Exact recipient + message, one-use approval. The two buttons are the only way forward. */
    fun approval(c: Context, recipient: String, message: String, reason: String, via: String, onApprove: () -> Unit, onReject: () -> Unit, seconds: Int = 60) = sheet(c).apply {
        contentDescription = c.getString(R.string.o_send_spoken,recipient,message)
        addView(kicker(c,c.getString(R.string.permission_needed)))
        addView(Ui.title(c,"Send this to $recipient?",Ui.Type.HEADLINE+2),Ui.lp(top=dp(c,6)))
        addView(Ui.text(c,via,Ui.Type.LABEL,Ui.muted).apply { setPadding(0,0,0,0) })
        val body = Ui.text(c,message,Ui.Type.BODY).apply {
            background = Ui.rounded(Ui.ground,dp(c,10).toFloat()); setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12)); setTextIsSelectable(false)
        }
        // Short messages show in full; long ones scroll inside a bounded box so the buttons stay on screen.
        addView(ScrollView(c).apply { addView(body) },
            Ui.lp(height=if(message.length<=140) LinearLayout.LayoutParams.WRAP_CONTENT else dp(c,200),top=dp(c,12)))
        if(reason.isNotBlank() && reason!="Send this message?") addView(Ui.youLine(c,reason),Ui.lp(top=dp(c,8)))
        val row = LinearLayout(c)
        row.addView(Ui.secondary(c,c.getString(R.string.o_send_no),60) { onReject() },Ui.lp(0,weight=1f))
        row.addView(Ui.primary(c,c.getString(R.string.o_send_yes),60) { onApprove() },Ui.lp(0,weight=1.5f,start=dp(c,10)))
        addView(row,Ui.lp(top=dp(c,14)))
        addView(Ui.text(c,c.getString(R.string.o_once,seconds),Ui.Type.CAPTION,Ui.muted),Ui.lp(top=dp(c,8)))
    }

    /** A question from Köməkçi: fixed choices, or a free-text answer. Stop is always present. */
    fun question(c: Context, question: String, choices: List<String>, onAnswer: (String) -> Unit, onStop: () -> Unit) = sheet(c).apply {
        contentDescription = c.getString(R.string.o_question_spoken,question)
        addView(kicker(c,c.getString(R.string.o_asks)))
        addView(Ui.title(c,question,Ui.Type.HEADLINE+2),Ui.lp(top=dp(c,6)))
        if (choices.isEmpty()) {
            val field = Ui.field(c,c.getString(R.string.o_answer))
            addView(field,Ui.lp(top=dp(c,4)))
            addView(Ui.primary(c,c.getString(R.string.o_continue)) { val value=field.text.toString().trim(); if(value.isNotEmpty()) onAnswer(value) },Ui.lp(top=dp(c,12)))
        } else choices.take(8).forEachIndexed { i, choice ->
            val label = if(choice.equals("Cancel",true)) c.getString(R.string.cancel) else choice
            // First choice is the expected answer; the rest are equal-weight alternatives.
            addView((if(i==0) Ui.primary(c,label) { onAnswer(choice) } else Ui.secondary(c,label) { onAnswer(choice) }),Ui.lp(top=dp(c,if(i==0) 10 else 8)))
        }
        addView(Ui.tertiary(c,c.getString(R.string.stop),Ui.stop) { onStop() }.apply { contentDescription=c.getString(R.string.stop_spoken) },
            Ui.lp(-2,top=dp(c,6)).apply { gravity=Gravity.CENTER_HORIZONTAL })
    }
}
