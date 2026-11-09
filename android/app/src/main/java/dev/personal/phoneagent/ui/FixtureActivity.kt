package dev.personal.phoneagent.ui

import android.os.Bundle
import android.widget.*
import dev.personal.phoneagent.R

/**
 * Native, clearly synthetic integration fixture. No messaging/network/payment side effects.
 * View IDs and the "Not sent"/"Delivered" status values are part of the verification contract; keep them.
 */
class FixtureActivity : BaseActivity() {
    private var selected=false
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); contacts() }
    private fun base(): LinearLayout {
        val bar = Ui.topBar(this,null,{ finish() })
        val (_, b) = Ui.scaffold(this,bar)
        b.addView(Ui.title(this,getString(R.string.f_title)).apply { id=R.id.fixture_header })
        b.addView(Ui.banner(this,Ui.Tone.YOU,null,getString(R.string.f_banner)))
        return b
    }
    private fun contacts() {
        val b=base()
        b.addView(Ui.field(this,getString(R.string.f_search)).apply { id=R.id.fixture_search },Ui.lp(top=d(16)))
        b.addView(Ui.group(this).apply {
            addView(Ui.listRow(this@FixtureActivity,"Alex Example","Sintetik kontakt",leading=R.drawable.ic_chat,chevron=true) { selected=true; chat() }.apply { id=R.id.fixture_contact_alex })
        },Ui.lp(top=d(12)))
        b.addView(Ui.tertiary(this,getString(R.string.f_back)) { finish() },Ui.lp(-2,top=d(8)))
    }
    private fun chat() {
        val b=base()
        b.addView(Ui.text(this,"Alex Example",24f,bold=true),Ui.lp(top=d(12)))
        val status=Ui.text(this,"Not sent",Ui.Type.LABEL+1,Ui.muted).apply { id=R.id.fixture_status }
        val entry=Ui.field(this,getString(R.string.f_message)).apply { id=R.id.fixture_entry }
        b.addView(status); b.addView(entry,Ui.lp(top=d(8)))
        b.addView(Ui.primary(this,getString(R.string.f_send)) {
            if(entry.text.isNotBlank()) { entry.text.clear(); status.text="Delivered" }
        }.apply { id=R.id.fixture_send },Ui.lp(top=d(12)))
        b.addView(Ui.tertiary(this,getString(R.string.f_back)) { finish() },Ui.lp(-2,top=d(8)))
    }
}
