package dev.personal.phoneagent.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.personal.phoneagent.AgentApplication
import dev.personal.phoneagent.R
import dev.personal.phoneagent.agent.*

/** Local-only financial checklist. No model call, bank submission, or stored form values. */
class BankPreparationActivity : BaseActivity() {
    private var flow = BankFlow.CARD
    private var currency = "AZN"
    private lateinit var flowGroup: LinearLayout
    private lateinit var currencyBox: LinearLayout
    private lateinit var providerBox: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val command=intent.getStringExtra("command").orEmpty().lowercase()
        flow=intent.getStringExtra("flow")?.let { f -> BankFlow.entries.firstOrNull { it.name==f } } ?: when {
            "bill" in command || "kommunal" in command -> BankFlow.BILL; "own" in command || "between" in command -> BankFlow.OWN; else -> BankFlow.CARD }
        currency=intent.getStringExtra("currency")?.takeIf { it in setOf("AZN","USD","EUR") } ?: "AZN"

        // The one action sits in the dock, always reachable above the keyboard.
        val dock = Ui.vertical(this)
        val check = Ui.primary(this,getString(R.string.b_check)) { review() }
        dock.addView(check)
        val (root, b) = Ui.scaffold(this,Ui.topBar(this,getString(R.string.b_title),{ setResult(RESULT_CANCELED); finish() }),dock)
        root.importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        root.isSaveEnabled=false

        b.addView(Ui.banner(this,Ui.Tone.YOU,null,getString(R.string.b_banner)),Ui.lp(top=d(8)))

        b.addView(Ui.section(this,getString(R.string.b_what)))
        flowGroup = Ui.group(this)
        b.addView(flowGroup)

        val form = Ui.card(this)
        recipient = Ui.labeledField(this,getString(R.string.b_to),getString(R.string.b_to_hint)).apply { edit.setText(intent.getStringExtra("recipient").orEmpty()) }
        source = Ui.labeledField(this,getString(R.string.b_from),getString(R.string.b_from_hint))
        provider = Ui.labeledField(this,getString(R.string.b_provider),getString(R.string.b_provider_hint))
        providerBox = provider.view
        amount = Ui.labeledField(this,getString(R.string.b_amount),"0.00").apply {
            edit.inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            edit.setText(intent.getStringExtra("amount").orEmpty()); edit.textSize=26f*Ui.scale
            edit.setTypeface(null,android.graphics.Typeface.BOLD)
        }
        form.addView(recipient.view); form.addView(source.view,Ui.lp(top=d(16))); form.addView(providerBox,Ui.lp(top=d(16)))
        form.addView(amount.view,Ui.lp(top=d(16)))
        form.addView(Ui.text(this,getString(R.string.b_currency),Ui.Type.LABEL+1,Ui.ink,bold=true).apply { setPadding(d(4),d(16),0,d(6)) })
        currencyBox = Ui.vertical(this)
        form.addView(currencyBox)
        b.addView(form,Ui.lp(top=d(16)))
        b.addView(Ui.text(this,getString(R.string.b_never),Ui.Type.LABEL,Ui.muted).apply { setPadding(d(4),d(12),d(4),0) })
        renderChoices()
    }
    private lateinit var recipient: Ui.Field
    private lateinit var source: Ui.Field
    private lateinit var provider: Ui.Field
    private lateinit var amount: Ui.Field

    private fun review() {
        val preparation=BankPreparation(flow,recipient.edit.text.toString().trim(),source.edit.text.toString().trim(),
            amount.edit.text.toString().trim().replace(',','.'),currency,provider.edit.text.toString().trim())
        val error=preparation.validate()
        if(error!=null) { Toast.makeText(this,error,Toast.LENGTH_LONG).show(); return }
        Ui.confirm(this,getString(R.string.b_review_title),preparation.review(),getString(R.string.b_open),getString(R.string.b_edit)) {
            val app=application as AgentApplication
            if(app.agent.state.value.active) app.agent.stop("The bank step is now yours. No payment was sent or checked.")
            val launch=packageManager.getLaunchIntentForPackage(BANK)
            if(launch==null) Toast.makeText(this,R.string.b_not_installed,Toast.LENGTH_LONG).show()
            else { setResult(RESULT_OK); startActivity(launch); finish() }
        }
    }

    private fun renderChoices() {
        flowGroup.removeAllViews()
        BankFlow.entries.forEach { f -> flowGroup.addView(Ui.radioRow(this,f.label,f==flow) { flow=f; renderChoices() }) }
        providerBox.visibility = if(flow==BankFlow.BILL) View.VISIBLE else View.GONE
        currencyBox.removeAllViews()
        val options = listOf("AZN","USD","EUR")
        currencyBox.addView(Ui.segmented(this,listOf("₼ AZN","$ USD","€ EUR"),options.indexOf(currency),options) { i -> currency=options[i]; renderChoices() })
    }
}
