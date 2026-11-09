package dev.personal.phoneagent.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.personal.phoneagent.AgentApplication
import dev.personal.phoneagent.BuildConfig
import dev.personal.phoneagent.R
import dev.personal.phoneagent.accessibility.AgentAccessibilityService

/** Grouped settings: everyday choices first, safety facts, then technical settings collapsed under "Advanced". */
class SettingsActivity : BaseActivity() {
    private val app get() = application as AgentApplication
    private var advancedOpen = false
    private var scrollY = 0
    private var body: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        advancedOpen = savedInstanceState?.getBoolean("advanced") ?: false
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putBoolean("advanced",advancedOpen) }
    override fun onResume() { super.onResume(); build() }  // Reflects Accessibility changes made in Android settings.

    private fun build() {
        (body?.parent as? ScrollView)?.let { scrollY = it.scrollY }
        val (_, b) = Ui.scaffold(this,Ui.topBar(this,getString(R.string.settings),{ finish() }))
        body = b

        // Uncertain send comes first: it blocks new tasks until the user checks.
        if(app.agent.hasUncertainAction())
            b.addView(Ui.banner(this,Ui.Tone.ERROR,getString(R.string.s_uncertain_title),getString(R.string.s_uncertain_body),getString(R.string.s_uncertain_action)) {
                Ui.confirm(this,getString(R.string.s_uncertain_confirm_title),getString(R.string.s_uncertain_confirm_body),
                    getString(R.string.s_uncertain_action),getString(R.string.not_yet)) { app.agent.acknowledgeUncertain(); build() }
            },Ui.lp(top=d(8)))

        // Appearance: text size with a live preview line.
        b.addView(Ui.section(this,getString(R.string.s_appearance)))
        val size = Ui.card(this)
        size.addView(Ui.text(this,getString(R.string.s_text_size),Ui.Type.BODY,bold=true).apply { setPadding(0,0,0,d(8)) })
        val scales = listOf(1.0f,1.15f,1.3f,1.5f)
        val selected = scales.indexOfFirst { kotlin.math.abs(app.settings.textScale-it)<0.01f }.coerceAtLeast(0)
        size.addView(Ui.segmented(this,List(4) { "A" },selected,
            listOf(R.string.s_size_small,R.string.s_size_medium,R.string.s_size_large,R.string.s_size_xl).map { getString(it) },
            sizes=listOf(16f,20f,24f,28f)) { i -> app.settings.textScale=scales[i]; Ui.scale=scales[i]; build() })
        size.addView(Ui.text(this,getString(R.string.s_text_preview),Ui.Type.BODY,Ui.muted),Ui.lp(top=d(10)))
        b.addView(size)

        // Voice.
        b.addView(Ui.section(this,getString(R.string.s_voice)))
        b.addView(Ui.group(this).apply {
            addView(Ui.switchRow(this@SettingsActivity,getString(R.string.s_speak_replies),getString(R.string.s_speak_replies_sub),app.settings.speakReplies) { _, on -> app.settings.speakReplies=on })
        })

        // Phone controls (Accessibility) with a clear status.
        b.addView(Ui.section(this,getString(R.string.s_controls)))
        val on = AgentAccessibilityService.instance!=null
        b.addView(Ui.group(this).apply {
            addView(Ui.listRow(this@SettingsActivity,getString(R.string.s_controls_row),getString(R.string.s_controls_sub),
                leading=R.drawable.ic_shield,leadingTint=if(on) Ui.ok else Ui.amber,
                trailing=Ui.badge(this@SettingsActivity,getString(if(on) R.string.s_controls_on else R.string.s_controls_off),if(on) Ui.Tone.OK else Ui.Tone.YOU)))
            addView(Ui.listRow(this@SettingsActivity,getString(R.string.s_controls_open),null,chevron=true) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        })

        // Safety facts: what Köməkçi never does.
        b.addView(Ui.section(this,getString(R.string.s_safety)))
        b.addView(Ui.group(this).apply {
            addView(Ui.listRow(this@SettingsActivity,getString(R.string.s_safety_pin),leading=R.drawable.ic_lock).apply { isFocusable=true })
            addView(Ui.listRow(this@SettingsActivity,getString(R.string.s_safety_ask),leading=R.drawable.ic_hand,leadingTint=Ui.amber).apply { isFocusable=true })
            addView(Ui.listRow(this@SettingsActivity,getString(R.string.s_safety_verify),leading=R.drawable.ic_check,leadingTint=Ui.ok).apply { isFocusable=true })
        })

        // Advanced, collapsed by default.
        b.addView(Ui.section(this,getString(R.string.s_advanced)))
        val advanced = Ui.vertical(this).apply { visibility=if(advancedOpen) View.VISIBLE else View.GONE }
        b.addView(Ui.group(this).apply {
            addView(Ui.listRow(this@SettingsActivity,getString(R.string.s_advanced_row),getString(R.string.s_advanced_sub),leading=R.drawable.ic_settings,chevron=true) {
                advancedOpen=!advancedOpen; advanced.visibility=if(advancedOpen) View.VISIBLE else View.GONE
            }.apply { stateDescription=if(advancedOpen) "Expanded" else "Collapsed" })
        })
        b.addView(advanced)
        buildAdvanced(advanced)

        b.addView(Ui.text(this,getString(R.string.s_version,BuildConfig.VERSION_NAME+if(BuildConfig.DEBUG) " (debug)" else ""),Ui.Type.CAPTION,Ui.muted).apply {
            gravity=android.view.Gravity.CENTER }, Ui.lp(top=d(24)))
        (b.parent as ScrollView).post { (b.parent as ScrollView).scrollTo(0,scrollY) }
    }

    private fun buildAdvanced(box: LinearLayout) {
        box.addView(Ui.section(this,getString(R.string.s_demo)))
        val demo = Ui.card(this)
        demo.addView(Ui.text(this,getString(R.string.s_demo_sub),Ui.Type.LABEL+1,Ui.muted).apply { setPadding(0,0,0,0) })
        demo.addView(Ui.secondary(this,getString(R.string.s_demo_start)) {
            if(!app.agent.state.value.active && app.agent.start("Run the safe fixture test: draft hello to Alex Example and request approval.",false)) {
                startActivity(Intent(this,FixtureActivity::class.java)); finish()
            } else { Toast.makeText(this,R.string.s_demo_failed,Toast.LENGTH_LONG).show(); finish() }
        },Ui.lp(top=d(12)))
        box.addView(demo)
        box.addView(Ui.group(this).apply {
            addView(Ui.switchRow(this@SettingsActivity,getString(R.string.s_live),getString(R.string.s_live_sub),app.liveEnabled) { sw, enabled ->
                if(!enabled) { app.liveEnabled=false; return@switchRow }
                Ui.confirm(this@SettingsActivity,getString(R.string.s_live_title),getString(R.string.s_live_body),getString(R.string.s_live_confirm),getString(R.string.cancel),
                    onCancel={ sw.isChecked=false }) { app.liveEnabled=true }
            })
        },Ui.lp(top=d(12)))

        box.addView(Ui.section(this,getString(R.string.s_connection)))
        val conn = Ui.card(this)
        conn.addView(Ui.banner(this,Ui.Tone.YOU,null,getString(R.string.s_connection_note)))
        val url = Ui.labeledField(this,getString(R.string.s_server),"https://…",getString(R.string.s_server_helper)).apply {
            edit.setText(app.settings.baseUrl); edit.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; edit.setSingleLine(true) }
        val token = Ui.labeledField(this,getString(R.string.s_token),"",getString(R.string.s_token_helper)).apply {
            edit.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; edit.setSingleLine(true) }
        conn.addView(url.view,Ui.lp(top=d(14))); conn.addView(token.view,Ui.lp(top=d(14)))
        val consent = CheckBox(this).apply {
            text=getString(R.string.s_consent); textSize=16f*Ui.scale; setTextColor(Ui.ink); isChecked=app.settings.consent; minHeight=d(56)
            buttonTintList=android.content.res.ColorStateList.valueOf(Ui.accent); setPadding(d(8),d(8),0,d(8))
        }
        conn.addView(consent,Ui.lp(top=d(10)))
        conn.addView(Ui.primary(this,getString(R.string.s_save)) {
            if(app.agent.state.value.active) { Toast.makeText(this,R.string.s_stop_first,Toast.LENGTH_LONG).show(); return@primary }
            try {
                app.settings.baseUrl=url.edit.text.toString().trim()
                if(token.edit.text.isNotBlank()) { app.settings.saveToken(token.edit.text.toString().trim()); token.edit.text.clear() }
                app.settings.consent=consent.isChecked
                Toast.makeText(this,R.string.s_saved,Toast.LENGTH_LONG).show()
            } catch (_: Exception) { Toast.makeText(this,R.string.s_save_error,Toast.LENGTH_LONG).show() }
        },Ui.lp(top=d(12)))
        box.addView(conn)

        box.addView(Ui.section(this,getString(R.string.s_supported)))
        box.addView(Ui.group(this).apply {
            listOf("WhatsApp" to "Finds the contact and prepares the message. Every send needs your approval.",
                   "ABB" to "Talks to the app's AI assistant. Fills in the payment form; you do the final confirmation.",
                   "myGov" to "Talks to the app's AI assistant. You confirm any application.",
                   "Kapital Bank" to "A checklist before paying. You do everything in the bank.")
                .forEach { (name, what) -> addView(Ui.listRow(this@SettingsActivity,name,what).apply { isFocusable=true }) }
        })
    }
}
