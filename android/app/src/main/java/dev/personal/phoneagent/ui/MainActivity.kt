package dev.personal.phoneagent.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.CalendarContract
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import dev.personal.phoneagent.AgentApplication
import dev.personal.phoneagent.R
import dev.personal.phoneagent.accessibility.AgentAccessibilityService
import dev.personal.phoneagent.agent.*
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * The one main screen: a chat. One spoken request becomes a local plan (see [RequestPlanner]) or a v2 channel plan;
 * the plan, "your turn" steps and the result appear as cards in the conversation. Every step runs through an
 * existing safe path and the user stays in control of money, messages and saving.
 */
class MainActivity : BaseActivity() {
    private val app get() = application as AgentApplication
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main)
    private var observer: Job? = null
    private lateinit var empty: LinearLayout
    private lateinit var readiness: LinearLayout
    private lateinit var modeLine: TextView
    private lateinit var timeline: LinearLayout
    private lateinit var planBox: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var statusBox: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var newChat: View
    private lateinit var settingsButton: View
    private lateinit var runBar: LinearLayout
    private lateinit var runStatus: TextView
    private lateinit var composer: LinearLayout
    private lateinit var voiceButton: ImageButton
    private lateinit var input: EditText
    private lateinit var speaker: Speaker
    private var lastLines: List<ChatLine>? = null
    private var lastChannels: List<ChannelStepView>? = null
    private var spokenCount = 0
    private var builtScale = 0f
    private var showHome = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Splash exit (~0.75 s): the logo settles back a touch, then the blue splash lifts away like a curtain (the logo
        // rising a little faster and fading) while the chat glides up into place. Sliding instead of cross-fading keeps
        // the blue and the light chat from mixing into a washed-out middle frame. Only runs after a real splash.
        splashScreen.setOnExitAnimationListener { v ->
            val inOut = android.view.animation.PathInterpolator(0.65f, 0f, 0.35f, 1f)
            val out = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
            val content = findViewById<View>(android.R.id.content)
            val h = v.height.toFloat().takeIf { it > 0f } ?: resources.displayMetrics.heightPixels.toFloat()
            content.translationY = h * 0.12f
            val anims = mutableListOf<android.animation.Animator>(
                android.animation.ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -h).apply { startDelay = 160L; duration = 560L; interpolator = inOut },
                android.animation.ObjectAnimator.ofFloat(content, View.TRANSLATION_Y, h * 0.12f, 0f).apply { startDelay = 200L; duration = 600L; interpolator = out },
            )
            v.iconView?.let { icon ->
                anims += android.animation.AnimatorSet().apply {
                    playSequentially(
                        android.animation.AnimatorSet().apply { duration = 160L; interpolator = out
                            playTogether(android.animation.ObjectAnimator.ofFloat(icon, View.SCALE_X, 1f, 0.94f),
                                         android.animation.ObjectAnimator.ofFloat(icon, View.SCALE_Y, 1f, 0.94f)) },
                        android.animation.AnimatorSet().apply { duration = 420L; interpolator = inOut
                            playTogether(android.animation.ObjectAnimator.ofFloat(icon, View.TRANSLATION_Y, 0f, -h * 0.18f),
                                         android.animation.ObjectAnimator.ofFloat(icon, View.ALPHA, 1f, 0f)) })
                }
            }
            android.animation.AnimatorSet().apply {
                playTogether(anims)
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: android.animation.Animator) { content.translationY = 0f; v.remove() }
                })
                start()
            }
        }
        speaker =Speaker(this) { if(!ttsWarned) { ttsWarned=true; Toast.makeText(this,R.string.no_tts,Toast.LENGTH_LONG).show() } }
        showHome = savedInstanceState?.getBoolean("home") ?: (app.plan==null && app.agent.state.value.lines.none { it.role=="you" })
        spokenCount = app.agent.state.value.lines.size
        build()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putBoolean("home",showHome) }

    // ---------- Layout ----------

    private fun build() {
        builtScale = Ui.scale
        val root = Ui.vertical(this).apply { setBackgroundColor(Ui.surface) }

        // Header: brand, status, "New chat" (in words), read aloud, settings.
        val header = Ui.horizontal(this).apply { minimumHeight=d(64); setPadding(d(16),0,d(4),0) }
        header.addView(Ui.logo(this),LinearLayout.LayoutParams(d(32),d(32)))
        header.addView(Ui.text(this,getString(R.string.app_name),Ui.Type.BODY_LARGE,bold=true).apply { isAccessibilityHeading=true; maxLines=1; setPadding(0,0,0,0) },Ui.lp(0,weight=1f,start=d(10)))
        val status = Ui.horizontal(this).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        statusDot = View(this).apply { background=Ui.rounded(Ui.ok,d(4).toFloat()); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        statusText = Ui.text(this,getString(R.string.status_ready),Ui.Type.LABEL,Ui.muted).apply { setPadding(0,0,0,0); maxLines=1 }
        status.addView(statusDot,LinearLayout.LayoutParams(d(8),d(8)))
        status.addView(statusText,Ui.lp(-2,start=d(6)))
        header.addView(status,Ui.lp(-2,end=d(2)))
        statusBox = status
        newChat = Ui.tertiary(this,getString(R.string.new_chat)) { goHome() }
        header.addView(newChat,Ui.lp(-2))
        header.addView(Ui.iconButton(this,R.drawable.ic_speaker,getString(R.string.read_aloud)) { readAloud() })
        settingsButton = Ui.iconButton(this,R.drawable.ic_settings,getString(R.string.settings)) { startActivity(Intent(this,SettingsActivity::class.java)) }
        header.addView(settingsButton)
        root.addView(header)
        root.addView(Ui.divider(this))

        // Conversation.
        val content = Ui.vertical(this).apply { setPadding(d(16),d(12),d(16),d(16)) }
        readiness = Ui.vertical(this)
        empty = buildEmpty()
        timeline = Ui.vertical(this)
        planBox = Ui.vertical(this)
        content.addView(readiness)
        content.addView(empty,Ui.lp(height=0,weight=1f))
        content.addView(timeline); content.addView(planBox)
        scroller = ScrollView(this).apply { addView(content); isFillViewport=true; isVerticalScrollBarEnabled=false; setBackgroundColor(Ui.ground) }
        root.addView(scroller,Ui.lp(height=0,weight=1f))
        root.addView(Ui.divider(this))

        // Bottom bar: while working, status + Stop. Otherwise the text field + microphone (send while typing).
        runBar = Ui.horizontal(this).apply { setPadding(d(16),d(12),d(16),d(4)) }
        runStatus = Ui.text(this,"",Ui.Type.BODY,Ui.muted).apply { setPadding(0,0,0,0); maxLines=2; accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        runBar.addView(runStatus,Ui.lp(0,weight=1f))
        runBar.addView(Ui.stopButton(this) { stopEverything() },Ui.lp(-2,start=d(12)))
        root.addView(runBar)

        composer = Ui.horizontal(this).apply { setPadding(d(16),d(12),d(16),d(12)) }
        input = Ui.field(this,getString(R.string.type_hint)).apply {
            maxLines=4; imeOptions=EditorInfo.IME_ACTION_SEND; contentDescription=getString(R.string.type_hint)
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setOnEditorActionListener { _, id, _ -> if(id==EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        composer.addView(input,Ui.lp(0,weight=1f))
        voiceButton = Ui.voice(this) { if(input.text.isNotBlank()) sendTyped() else listen() }
        composer.addView(voiceButton,LinearLayout.LayoutParams(d(64),d(64)).apply { marginStart=d(10) })
        input.addTextChangedListener(object: TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val typing = !s.isNullOrBlank()
                voiceButton.setImageResource(if(typing) R.drawable.ic_send else R.drawable.ic_mic)
                voiceButton.contentDescription = getString(if(typing) R.string.send else R.string.voice_spoken)
            }
        })
        root.addView(composer)

        setContentView(root)
        Ui.edgeToEdge(this,root)
        lastLines = null; lastChannels = null
    }

    /** Empty chat: one question, one sentence, the mode. The microphone is the only thing to press. */
    private fun buildEmpty() = Ui.vertical(this).apply {
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        setPadding(d(12),d(24),d(12),d(24))
        addView(FrameLayout(this@MainActivity).apply {
            background=Ui.rounded(Ui.accentSoft,d(16).toFloat()); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(Ui.icon(this@MainActivity,R.drawable.ic_chat,Ui.accent,32),FrameLayout.LayoutParams(d(32),d(32),Gravity.CENTER))
        },LinearLayout.LayoutParams(d(64),d(64)))
        addView(Ui.title(this@MainActivity,getString(R.string.home_title),Ui.Type.DISPLAY).apply { gravity=Gravity.CENTER },Ui.lp(top=d(20)))
        addView(Ui.text(this@MainActivity,getString(R.string.home_lead),Ui.Type.BODY_LARGE,Ui.muted).apply { gravity=Gravity.CENTER },Ui.lp(top=d(4)))
        modeLine = Ui.text(this@MainActivity,"",Ui.Type.CAPTION,Ui.muted).apply { gravity=Gravity.CENTER }
        addView(modeLine,Ui.lp(top=d(14)))
    }

    /** What stops tasks from running, shown before the user tries: controls off, setup incomplete, uncertain send. */
    private fun renderReadiness() {
        readiness.removeAllViews()
        modeLine.text = getString(if(app.liveEnabled) R.string.mode_live else R.string.mode_demo)
        if(!showHome) return
        val open = { startActivity(Intent(this,SettingsActivity::class.java)) }
        val banners = mutableListOf<View>()
        if(app.agent.hasUncertainAction()) banners += Ui.banner(this,Ui.Tone.ERROR,getString(R.string.ready_uncertain_title),getString(R.string.ready_uncertain_body),getString(R.string.open_settings),open)
        if(AgentAccessibilityService.instance==null && !dev.personal.phoneagent.accessibility.PhoneControlKeeper.working) banners += Ui.banner(this,Ui.Tone.YOU,getString(R.string.ready_controls_title),getString(R.string.ready_controls_body),getString(R.string.open_settings),open)
        else if(!app.settings.consent || runCatching { app.settings.token() }.isFailure)
            banners += Ui.banner(this,Ui.Tone.YOU,getString(R.string.ready_consent_title),getString(R.string.ready_consent_body),getString(R.string.open_settings),open)
        banners.forEachIndexed { i, b -> readiness.addView(b,Ui.lp(top=d(if(i==0) 4 else 10))) }
    }

    // ---------- Input ----------

    /** Debug hook (see AgentApplication.debugType): types [text] into the message box with uneven, human-like pauses, then taps send. */
    private fun typeLikeAPerson(text: String) {
        scope.launch {
            input.requestFocus(); input.text.clear()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input, 0)
            delay(900)
            val rnd = java.util.Random()
            for (c in text) {
                input.append(c.toString())
                delay(95L + rnd.nextInt(110) + (if (c == ' ') 120L else 0L) + (if (c == ',' || c == '-') 180L else 0L))
            }
            delay(1100)
            sendTyped()
        }
    }

    private fun sendTyped() {
        val text = input.text.toString().trim()
        if(text.isEmpty()) return
        input.text.clear()
        submit(text)
    }

    private fun listen(prompt: String = getString(R.string.listen_prompt)) {
        // Requests are spoken in English; the on-phone matchers also still read requests typed in Azerbaijani.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE,"en-US")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,"en-US")
            .putExtra(RecognizerIntent.EXTRA_PROMPT,prompt)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS,3)
        speaker.stop()
        try { startActivityForResult(intent,REQ_VOICE) }
        catch (_: ActivityNotFoundException) { Toast.makeText(this,R.string.no_stt,Toast.LENGTH_LONG).show(); input.requestFocus() }
    }

    private fun submit(raw: String) {
        val text = dev.personal.phoneagent.agent.SpokenEnglish.normalize(raw.trim())
        if(text.isEmpty()) return
        if(app.agent.reply(text)) { showHome=false; refresh(); return }  // Answer to the agent's pending question.
        if(Privacy.containsSensitiveIdentifier(text)) {
            app.agent.note("Please do not type card or account numbers into the chat. Enter them only in the bank app.")
            showHome=false; refresh(); return
        }
        if(busy()) { app.agent.note("A task is still running. Finish it or tap Stop first."); showHome=false; refresh(); return }
        showHome=false
        // myGov ID card / passport PDF → WhatsApp personal chat; the owner taps WhatsApp's Send (agent/PassportShare.kt).
        if(PassportShare.matches(text)) {
            app.plan = null
            val go = { if(app.agent.startPassportShare(text,app.liveEnabled)) speak("Got it. Downloading the document from myGov."); refresh() }
            if(!app.liveEnabled) Ui.confirm(this,getString(R.string.s_live_title),getString(R.string.live_prompt_body),
                getString(R.string.s_live_confirm),getString(R.string.cancel),onCancel={ app.agent.note("Sending documents works only in live mode."); refresh() }) { app.liveEnabled=true; go() }
            else go()
            refresh(); return
        }
        // Card transfer: card number from a WhatsApp message → ABB form (read on the phone, owner confirms twice).
        if(TransferRequest.matches(text)) {
            app.plan = null
            val go = { if(app.agent.startCardTransfer(text,app.liveEnabled)) speak("Got it. First I will find the card in WhatsApp."); refresh() }
            if(!app.liveEnabled) Ui.confirm(this,getString(R.string.s_live_title),getString(R.string.live_prompt_body),
                getString(R.string.s_live_confirm),getString(R.string.cancel),onCancel={ app.agent.note("Card transfers work only in live mode."); refresh() }) { app.liveEnabled=true; go() }
            else go()
            refresh(); return
        }
        // v2: requests naming an app with its own AI assistant (or asking a question) go chatbot-first.
        if(Regex("\\babb\\b|my\\s?gov|meta ai|azercell|soruş|sorus|öyrən|oyren",RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            app.plan = null
            val go = { if(app.agent.startChannels(text,app.liveEnabled)) speak("Got it. Making a plan."); refresh() }
            // Live can be turned off in Settings until the next restart; ask here instead of silently running scripted demo replies.
            if(!app.liveEnabled) Ui.confirm(this,getString(R.string.s_live_title),getString(R.string.live_prompt_body),
                getString(R.string.s_live_confirm),getString(R.string.live_prompt_demo),onCancel=go) { app.liveEnabled=true; go() }
            else go()
            refresh(); return
        }
        val steps = RequestPlanner.plan(text)
        if(RequestPlanner.isServicePlan(steps)) {
            app.agent.userSaid(text)
            app.plan = PlanRun(steps)
            refresh()
            if(steps.size==1) {
                // One step: no extra "Yes, start" tap. The only confirmation is the exact on-screen approval before sending.
                speak("Got it. ${steps[0].title}.")
                startPlan()
            } else speak("Got it. ${steps.size} steps. "+steps.mapIndexed { i, s -> "${i+1}. ${s.title}" }.joinToString(". ")+". Tap Yes, start to begin.")
        } else if(Regex("kapital|birbank|card.to.card|between my accounts|pay (a |the )?bill",RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            openBank(null)
        } else {
            app.plan = null
            app.agent.start(text,app.liveEnabled)
            refresh()
        }
    }

    // ---------- Plan execution ----------

    private fun busy() = app.agent.state.value.active || app.plan?.let { it.started && !it.finished } == true

    private fun startPlan() {
        val plan = app.plan ?: return
        if(app.agent.state.value.active) { app.agent.note("Stop the running task first."); refresh(); return }
        plan.start()
        next()
    }

    /** Moves to the next step. The first step runs immediately (the user just tapped Yes, start); later ones wait for one tap. */
    private fun next(runNow: Boolean = false) {
        val plan = app.plan ?: return
        val first = plan.index<0
        val step = plan.advance()
        if(step==null) { finishPlan(); return }
        refresh()
        if(first || runNow) go() else speak("Next step: ${step.title}.")
    }

    private fun go() {
        val plan = app.plan ?: return
        val step = plan.current ?: return
        when(step.kind) {
            StepKind.MONEY -> openBank(step)
            StepKind.REMINDER -> if(step.time.isBlank()) { plan.set(StepState.ASK_TIME); refresh(); speak("What time should I remind you?") } else openCalendar(step)
            StepKind.GOV -> {
                val launch = packageManager.getLaunchIntentForPackage(MYGOV)
                if(launch==null) { plan.set(StepState.FAILED,"myGov is not installed on this phone."); next() }
                else { plan.set(StepState.ASK_DONE,"myGov is open. You log in and confirm."); refresh(); startActivity(launch) }
            }
            StepKind.MESSAGE, StepKind.AGENT -> runAgentStep(step)
        }
    }

    private fun runAgentStep(step: PlanStep) {
        val plan = app.plan ?: return
        val mark = app.agent.state.value.finishedTasks
        val demo = step.kind==StepKind.MESSAGE && !app.liveEnabled
        val started = if(demo) {
            app.agent.note("Demo mode: a synthetic chat opens instead of real WhatsApp. No real message is sent. You can turn on live mode in Settings.")
            val draft = step.draft.ifBlank { "Salam!" }.replace("\"","'")
            val ok = app.agent.start("Run the safe fixture test: draft: \"$draft\" to Alex Example and request approval.",false,echo=false)
            if(ok) startActivity(Intent(this,FixtureActivity::class.java))
            ok
        } else app.agent.start(step.agentCommand(),app.liveEnabled,echo=false,recipient=if(step.kind==StepKind.MESSAGE) step.recipient else null)
        if(started) { plan.agentMark=mark; plan.set(StepState.RUNNING,if(demo) "Demo mode · synthetic chat" else "") }
        else { plan.set(StepState.FAILED,"Did not start. The reason is in the message above."); next() }
        refresh()
    }

    /** Called from render: resolves a running agent step once its task has fully finished. */
    private fun checkAgentStep(s: AgentState) {
        val plan = app.plan ?: return
        if(plan.current==null || plan.states[plan.index]!=StepState.RUNNING || s.active || s.finishedTasks<=plan.agentMark) return
        if(s.lastVerified) plan.set(StepState.VERIFIED,if(plan.current?.kind==StepKind.MESSAGE && !app.liveEnabled) "Demo: simulated, no real message was sent." else "I saw the result on screen.")
        else plan.set(StepState.FAILED,s.lines.lastOrNull { it.role=="agent" }?.text?.take(180)
            ?: "I could not check the result. Check the app yourself; nothing will be sent again.")
        next()
    }

    private fun openBank(step: PlanStep?) {
        if(app.agent.state.value.active) app.agent.stop("Opening the bank checklist. Automation stopped.")
        val intent = Intent(this,BankPreparationActivity::class.java)
        if(step!=null) intent.putExtra("recipient",step.recipient).putExtra("amount",step.amount).putExtra("currency",step.currency).putExtra("flow",BankFlow.CARD.name)
        startActivityForResult(intent,if(step!=null) REQ_BANK_STEP else REQ_BANK)
    }

    private fun openCalendar(step: PlanStep) {
        val plan = app.plan ?: return
        val (h,m) = step.time.split(":").map { it.toInt() }
        val at = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR,step.dayOffset); set(Calendar.HOUR_OF_DAY,h); set(Calendar.MINUTE,m); set(Calendar.SECOND,0); set(Calendar.MILLISECOND,0) }
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE,step.reminderTitle)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME,at.timeInMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME,at.timeInMillis+30*60_000)
            .putExtra(CalendarContract.Events.DESCRIPTION,"Created with Aila")
        try {
            plan.set(StepState.ASK_DONE,"Calendar is open: ${RequestPlanner.dayName(step.dayOffset)}, ${step.time}. Tap Save.")
            refresh(); startActivity(intent)
        } catch (_: ActivityNotFoundException) { plan.set(StepState.FAILED,"No calendar app found."); next() }
    }

    private fun answerDone(done: Boolean) {
        val plan = app.plan ?: return
        val step = plan.current ?: return
        plan.set(if(done) StepState.USER_DONE else StepState.NOT_DONE,
            if(done) when(step.kind) { StepKind.MONEY -> "You confirmed it in the bank. I cannot see the bank's result."; StepKind.REMINDER -> "You saved it in the calendar."; else -> "You finished it yourself." }
            else "Not done.")
        next()
    }

    private fun skipStep() { app.plan?.set(StepState.SKIPPED,"You skipped it."); next() }

    private fun finishPlan() {
        refresh()
        app.plan?.let { speak("Done. "+it.summary()) }
    }

    private fun stopEverything() {
        app.agent.stop()
        app.plan?.takeIf { it.started && !it.finished }?.cancelRemaining()
        speaker.stop()
        refresh()
    }

    /** "New chat": clears the plan and returns to the empty screen (the agent's log stays for the session). */
    private fun goHome() {
        if(busy()) { Toast.makeText(this,R.string.finish_first,Toast.LENGTH_SHORT).show(); return }
        app.plan = null; showHome = true; refresh()
        scroller.post { scroller.scrollTo(0,0) }
    }

    @Deprecated("Activity result API is not used in this framework-only app")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        when(requestCode) {
            REQ_VOICE -> if(resultCode==RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.let { guesses ->
                app.agent.heard(guesses)
                dev.personal.phoneagent.agent.SpokenEnglish.best(guesses)?.let { submit(it) }
            }
            REQ_BANK_STEP -> {
                val plan = app.plan ?: return
                if(plan.current?.kind!=StepKind.MONEY) return
                if(resultCode==RESULT_OK) plan.set(StepState.ASK_DONE,"Kapital Bank is open. Check the recipient and amount and confirm it yourself.")
                refresh()
            }
        }
    }

    // ---------- Rendering ----------

    override fun onStart() {
        super.onStart()
        observer = scope.launch { app.agent.state.collect { render(it) } }
    }
    override fun onResume() {
        super.onResume()
        if(builtScale!=Ui.scale) build()
        if(intent.getBooleanExtra(EXTRA_SHOW_CONVERSATION,false)) { showHome=false; intent.removeExtra(EXTRA_SHOW_CONVERSATION) }
        dev.personal.phoneagent.accessibility.PhoneControlKeeper.ensureOn(this)
        refresh()
        app.debugRequest?.let { app.debugRequest=null; submit(it) }
        app.debugType?.let { app.debugType=null; typeLikeAPerson(it) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent) }

    private fun refresh() = render(app.agent.state.value)

    private fun render(s: AgentState) {
        checkAgentStep(s)
        val plan = app.plan
        val planRunning = plan!=null && plan.started && !plan.finished
        val running = s.active || planRunning
        val waiting = app.agent.awaitingReply() || plan?.let { p -> p.started && !p.finished && p.states.getOrNull(p.index) in setOf(StepState.ASK_DONE,StepState.ASK_TIME,StepState.ACTIVE) } == true
        empty.visibility = if(showHome) View.VISIBLE else View.GONE
        timeline.visibility = if(showHome) View.GONE else View.VISIBLE
        planBox.visibility = if(showHome) View.GONE else View.VISIBLE
        newChat.visibility = if(showHome || running) View.GONE else View.VISIBLE
        settingsButton.visibility = if(running) View.GONE else View.VISIBLE
        runBar.visibility = if(running) View.VISIBLE else View.GONE
        composer.visibility = if(!running || app.agent.awaitingReply()) View.VISIBLE else View.GONE
        runStatus.text = when { app.agent.awaitingReply() || waiting -> getString(R.string.status_waiting); s.active -> s.status; else -> getString(R.string.status_working) }
        statusText.text = when {
            planRunning -> getString(R.string.step_of,plan!!.index+1,plan.steps.size)
            running -> getString(R.string.status_working)
            else -> getString(R.string.status_ready)
        }
        statusText.setTextColor(if(running) Ui.accentDark else Ui.muted)
        statusDot.visibility = if(running) View.GONE else View.VISIBLE
        statusBox.visibility = if(running || showHome) View.VISIBLE else View.GONE
        renderReadiness()
        if(s.lines!=lastLines || s.channels!=lastChannels) {
            timeline.removeAllViews()
            val lastPlan = s.lines.indexOfLast { it.role=="plan" }
            var previous: ChatLine? = null
            s.lines.forEachIndexed { i, line ->
                if(line==previous) return@forEachIndexed
                timeline.addView(if(line.role=="plan" && i==lastPlan && s.channels.isNotEmpty()) channelCard(s.channels) else bubble(line))
                previous = line
            }
            val fresh = s.lines.drop(spokenCount).lastOrNull { it.role=="agent" }
            if(fresh!=null && lastLines!=null) speak(fresh.text)
            spokenCount = s.lines.size
            val grew = (lastLines?.size ?: 0)!=s.lines.size
            lastLines = s.lines; lastChannels = s.channels
            if(!showHome && grew) scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        renderPlan()
    }

    /** Your words: navy bubble on the right. Köməkçi: white card on the left. Another app's bot: card with its label. Events: one small centred line. */
    private fun bubble(line: ChatLine): View {
        val maxW = (resources.displayMetrics.widthPixels*0.82).toInt()
        if(line.role=="you") return Ui.text(this,line.text,Ui.Type.BODY,Ui.onPrimaryContainer).apply {
            setPadding(d(16),d(12),d(16),d(12)); background=Ui.corners(this@MainActivity,Ui.primaryContainer,16,16,4,16)
            maxWidth=maxW; setTextIsSelectable(true)
            contentDescription=getString(R.string.you_said,line.text)
            layoutParams=Ui.lp(-2,top=d(12)).apply { gravity=Gravity.END }
        }
        if(line.role=="bot") return Ui.vertical(this).apply {
            background=Ui.corners(this@MainActivity,Ui.surface,16,16,16,4,Ui.line); setPadding(d(16),d(12),d(16),d(12))
            addView(Ui.text(this@MainActivity,getString(R.string.bot_said_label),Ui.Type.CAPTION,Ui.muted,bold=true).apply { setPadding(0,0,0,0) })
            addView(Ui.text(this@MainActivity,line.text,Ui.Type.BODY).apply { setTextIsSelectable(true) })
            contentDescription=getString(R.string.bot_said,line.text)
            layoutParams=Ui.lp(top=d(12)).apply { marginEnd=d(32) }
        }
        if(line.role=="agent" && line.text.startsWith("Phone control")) return Ui.horizontal(this).apply {
            gravity=Gravity.CENTER
            val on = line.text.contains("connected")
            addView(Ui.icon(this@MainActivity,if(on) R.drawable.ic_check else R.drawable.ic_info,if(on) Ui.ok else Ui.muted,16))
            addView(Ui.text(this@MainActivity,line.text.substringBefore(". ").trimEnd('.'),Ui.Type.CAPTION,Ui.muted).apply { setPadding(0,0,0,0) },Ui.lp(-2,start=d(6)))
            contentDescription=line.text
            layoutParams=Ui.lp(top=d(12))
        }
        return Ui.text(this,line.text,Ui.Type.BODY).apply {
            setPadding(d(16),d(12),d(16),d(12)); background=Ui.corners(this@MainActivity,Ui.surface,16,16,16,4,Ui.line)
            maxWidth=maxW; setTextIsSelectable(true); contentDescription=getString(R.string.agent_said,line.text)
            layoutParams=Ui.lp(-2,top=d(12)).apply { gravity=Gravity.START }
        }
    }

    /** A white Köməkçi card in the conversation (plan, result). */
    private fun agentCard() = Ui.vertical(this).apply {
        background=Ui.corners(this@MainActivity,Ui.surface,16,16,16,4,Ui.line); setPadding(d(16),d(14),d(16),d(14))
    }
    /** An orange-bordered card: the user has to act or decide. */
    private fun actionCard(kicker: String) = Ui.vertical(this).apply {
        background=Ui.rounded(Ui.surface,d(16).toFloat(),Ui.amberLine,(1.5f*resources.displayMetrics.density).toInt()); setPadding(d(16),d(14),d(16),d(16))
        addView(Ui.horizontal(this@MainActivity).apply {
            addView(Ui.icon(this@MainActivity,R.drawable.ic_info,Ui.amber,18))
            addView(Ui.text(this@MainActivity,kicker,Ui.Type.CAPTION+1,Ui.amber,bold=true).apply { setPadding(0,0,0,0) },Ui.lp(-2,start=d(6)))
        })
        accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** v2 plan: one row per app step; bot answers quoted under their step. */
    private fun channelCard(steps: List<ChannelStepView>) = agentCard().apply {
        val finished = steps.none { it.state in setOf(StepState.PENDING,StepState.RUNNING) }
        addView(Ui.text(this@MainActivity,if(finished) getString(R.string.done_title) else getString(R.string.plan_intro,steps.size),Ui.Type.BODY,bold=finished).apply { isAccessibilityHeading=true; setPadding(0,0,0,d(8)) })
        steps.forEachIndexed { i, step ->
            val col = Ui.vertical(this@MainActivity)
            col.addView(Ui.text(this@MainActivity,step.goal,Ui.Type.BODY,bold=step.state==StepState.RUNNING).apply { setPadding(0,0,0,0) })
            col.addView(Ui.horizontal(this@MainActivity).apply {
                addView(Ui.text(this@MainActivity,getString(if(step.bot) R.string.channel_bot else R.string.channel_ui,step.app),Ui.Type.CAPTION,Ui.muted).apply { setPadding(0,0,0,0) },Ui.lp(0,weight=1f))
                if(step.state!=StepState.PENDING) addView(statusPill(step.state),Ui.lp(-2,start=d(6)))
            },Ui.lp(top=d(2)))
            if(!step.bot && step.app!="WhatsApp" && step.state in setOf(StepState.PENDING,StepState.RUNNING))
                col.addView(Ui.youLine(this@MainActivity,getString(R.string.channel_you)),Ui.lp(top=d(4)))
            if(step.result.isNotBlank()) col.addView(Ui.text(this@MainActivity,step.result,Ui.Type.LABEL).apply {
                background=Ui.rounded(Ui.ground,d(10).toFloat()); setPadding(d(12),d(8),d(12),d(8)); setTextIsSelectable(true) },Ui.lp(top=d(6)))
            addView(Ui.timelineStep(this@MainActivity,i+1,markFor(step.state,step.state==StepState.RUNNING),i==steps.lastIndex,col).apply {
                contentDescription = getString(R.string.step_spoken,i+1,step.goal,step.app)+" "+getString(R.string.step_state_spoken,PlanRun.label(step.state))+" "+step.result
            })
        }
        layoutParams=Ui.lp(top=d(12))
    }

    private fun markFor(state: StepState, current: Boolean) = when {
        state in setOf(StepState.VERIFIED,StepState.USER_DONE) -> Ui.Mark.DONE
        state==StepState.FAILED -> Ui.Mark.FAILED
        state in setOf(StepState.SKIPPED,StepState.NOT_DONE) -> Ui.Mark.OFF
        current -> Ui.Mark.CURRENT
        else -> Ui.Mark.PENDING
    }

    private fun renderPlan() {
        planBox.removeAllViews()
        val plan = app.plan ?: return
        if(plan.finished) { renderSummary(plan); return }
        val card = agentCard()
        card.addView(Ui.text(this,getString(R.string.plan_intro,plan.steps.size),Ui.Type.BODY).apply { isAccessibilityHeading=true; setPadding(0,0,0,d(10)) })
        plan.steps.forEachIndexed { i, step -> card.addView(stepRow(plan,i,step,i==plan.steps.lastIndex)) }
        planBox.addView(card,Ui.lp(top=d(12)))
        if(!plan.started) {
            planBox.addView(Ui.text(this,getString(R.string.plan_ask),Ui.Type.BODY_LARGE,bold=true).apply { gravity=Gravity.CENTER },Ui.lp(top=d(16)))
            planBox.addView(Ui.primary(this,getString(R.string.plan_start)) { startPlan() },Ui.lp(top=d(8)))
            planBox.addView(Ui.secondary(this,getString(R.string.plan_change)) { input.setText(app.agent.state.value.lines.lastOrNull { it.role=="you" }?.text.orEmpty()); input.setSelection(input.text.length); app.plan=null; refresh(); input.requestFocus() },Ui.lp(top=d(10)))
        } else plan.current?.let { addStepActions(plan,it,plan.states[plan.index]) }
    }

    /** Peak-end: one clear finish with who did what. "Verified" only with on-screen evidence. */
    private fun renderSummary(plan: PlanRun) {
        val card = agentCard()
        val head = Ui.horizontal(this)
        head.addView(Ui.icon(this,R.drawable.ic_check_circle,Ui.ok,26))
        head.addView(Ui.text(this,getString(R.string.done_title),Ui.Type.HEADLINE,bold=true).apply { setPadding(0,0,0,0); isAccessibilityHeading=true },Ui.lp(0,weight=1f,start=d(8)))
        card.addView(head)
        val done = plan.states.count { it in setOf(StepState.VERIFIED,StepState.USER_DONE) }
        card.addView(Ui.text(this,if(done==plan.steps.size) getString(R.string.done_lead,plan.steps.size) else getString(R.string.done_partial,done,plan.steps.size),Ui.Type.LABEL+1,Ui.muted),Ui.lp(top=d(2),bottom=d(8)))
        plan.steps.forEachIndexed { i, step ->
            val state = plan.states[i]
            val row = Ui.horizontal(this,center=false).apply { setPadding(0,d(6),0,d(6)); isFocusable=true }
            val (res,tint) = when(state) {
                StepState.VERIFIED -> R.drawable.ic_check to Ui.ok
                StepState.USER_DONE -> R.drawable.ic_person to Ui.amber
                StepState.FAILED, StepState.NOT_DONE -> R.drawable.ic_warning to Ui.stop
                else -> R.drawable.ic_info to Ui.muted
            }
            row.addView(Ui.icon(this,res,tint,20),Ui.lp(d(20),d(20),top=d(2)))
            val col = Ui.vertical(this)
            col.addView(Ui.text(this,step.title,Ui.Type.BODY).apply { setPadding(0,0,0,0) })
            col.addView(Ui.text(this,PlanRun.label(state)+(plan.notes[i].takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),Ui.Type.CAPTION,Ui.fg(toneFor(state))).apply { setPadding(0,d(2),0,0) })
            row.addView(col,Ui.lp(0,weight=1f,start=d(10)))
            row.contentDescription="${step.title}: ${PlanRun.label(state)}. ${plan.notes[i]}"
            card.addView(row)
        }
        card.addView(Ui.text(this,getString(R.string.done_legend),Ui.Type.CAPTION,Ui.muted),Ui.lp(top=d(8)))
        card.addView(Ui.tertiary(this,getString(R.string.read_aloud)) { speaker.speak("Done. "+plan.summary()) },Ui.lp(-2,top=d(4)).apply { marginStart=-d(12) })
        planBox.addView(card,Ui.lp(top=d(12)))
        planBox.addView(bubble(ChatLine("agent",getString(R.string.what_else))))
    }

    /** One step inside the plan card: mark, title, detail, who acts, notes. */
    private fun stepRow(plan: PlanRun, i: Int, step: PlanStep, last: Boolean): View {
        val state = plan.states[i]
        val current = plan.started && i==plan.index
        val done = state in setOf(StepState.VERIFIED,StepState.USER_DONE)
        val off = state in setOf(StepState.SKIPPED,StepState.NOT_DONE,StepState.FAILED)
        val col = Ui.vertical(this)
        col.addView(Ui.text(this,step.title,Ui.Type.BODY,bold=current).apply { setPadding(0,0,0,0) })
        col.addView(Ui.horizontal(this).apply {
            addView(Ui.text(this@MainActivity,step.detail,Ui.Type.CAPTION,Ui.muted).apply { setPadding(0,0,0,0) },Ui.lp(0,weight=1f))
            if(plan.started && state!=StepState.PENDING) addView(statusPill(state),Ui.lp(-2,start=d(6)))
        },Ui.lp(top=d(2)))
        if(step.kind==StepKind.MESSAGE && step.draft.isNotBlank())
            col.addView(Ui.text(this,"“${step.draft}”",Ui.Type.LABEL).apply { background=Ui.rounded(Ui.ground,d(10).toFloat()); setPadding(d(12),d(8),d(12),d(8)) },Ui.lp(top=d(6)))
        if(!done && !off) col.addView(Ui.youLine(this,step.userPart),Ui.lp(top=d(4)))
        if(step.kind==StepKind.MESSAGE && !app.liveEnabled && !plan.started)
            col.addView(Ui.text(this,"Demo mode: the message is shown in the synthetic “Alex Example” chat, not sent to ${step.recipient.ifBlank { "the contact" }}. Real WhatsApp needs live mode in Settings.",Ui.Type.CAPTION,Ui.muted),Ui.lp(top=d(4)))
        plan.notes[i].takeIf { it.isNotBlank() && !done }?.let { col.addView(Ui.text(this,it,Ui.Type.CAPTION,Ui.muted)) }
        return Ui.timelineStep(this,i+1,markFor(state,current),last,col).apply {
            contentDescription = getString(R.string.step_spoken,i+1,step.title,step.detail)+(if(plan.started) " "+getString(R.string.step_state_spoken,PlanRun.label(state)) else "")
        }
    }

    /** The current step's decision, as its own card under the plan. */
    private fun addStepActions(plan: PlanRun, step: PlanStep, state: StepState) {
        when(state) {
            StepState.ACTIVE -> {
                val card = agentCard()
                card.addView(Ui.text(this,getString(R.string.step_of,plan.index+1,plan.steps.size),Ui.Type.CAPTION,Ui.muted,bold=true).apply { setPadding(0,0,0,0) })
                card.addView(Ui.text(this,step.title,Ui.Type.HEADLINE,bold=true),Ui.lp(top=d(2)))
                card.addView(Ui.youLine(this,step.userPart),Ui.lp(top=d(2)))
                val label = when(step.kind) {
                    StepKind.MONEY -> "Open the bank checklist"; StepKind.REMINDER -> "Set the reminder"; StepKind.GOV -> "Open myGov"
                    StepKind.MESSAGE -> "Prepare the message"; StepKind.AGENT -> "Start"
                }
                card.addView(Ui.primary(this,label) { go() },Ui.lp(top=d(12)))
                card.addView(Ui.tertiary(this,"Skip this step") { skipStep() },Ui.lp(-2,top=d(4)).apply { gravity=Gravity.CENTER_HORIZONTAL })
                planBox.addView(card,Ui.lp(top=d(12)))
            }
            StepState.ASK_TIME -> {
                val card = actionCard(getString(R.string.your_turn))
                card.addView(Ui.text(this,"What time should I remind you?",Ui.Type.HEADLINE,bold=true),Ui.lp(top=d(6)))
                listOf(listOf("08:00","09:00","11:00"),listOf("14:00","17:00","20:00")).forEach { rowTimes ->
                    val row = LinearLayout(this)
                    rowTimes.forEachIndexed { j, t ->
                        row.addView(Ui.choice(this,t,false) {
                            val idx = plan.index
                            plan.steps[idx] = step.copy(time=t)
                            plan.set(StepState.ACTIVE)
                            openCalendar(plan.steps[idx])
                        },Ui.lp(0,weight=1f,start=if(j>0) d(8) else 0))
                    }
                    card.addView(row,Ui.lp(top=d(8)))
                }
                planBox.addView(card,Ui.lp(top=d(12)))
            }
            StepState.ASK_DONE -> {
                val card = actionCard(getString(R.string.your_turn))
                val q = when(step.kind) { StepKind.MONEY -> "Did you send the money in the bank?"; StepKind.REMINDER -> "Did you save it in the calendar?"; StepKind.GOV -> "Did you finish in myGov?"; else -> "Did you finish?" }
                card.addView(Ui.text(this,q,Ui.Type.HEADLINE,bold=true),Ui.lp(top=d(6)))
                plan.notes[plan.index].takeIf { it.isNotBlank() }?.let { card.addView(Ui.text(this,it,Ui.Type.LABEL,Ui.muted)) }
                if(step.kind==StepKind.MONEY) card.addView(Ui.text(this,"Never tell anyone your PIN or SMS code. Enter them only in the bank app.",Ui.Type.CAPTION,Ui.muted),Ui.lp(top=d(4)))
                val row = LinearLayout(this)
                row.addView(Ui.secondary(this,"Not yet") { answerDone(false) },Ui.lp(0,weight=1f))
                row.addView(Ui.primary(this,"Yes, I did") { answerDone(true) },Ui.lp(0,weight=1.3f,start=d(10)))
                card.addView(row,Ui.lp(top=d(12)))
                if(step.kind==StepKind.MONEY) card.addView(Ui.tertiary(this,"Open the bank again") { go() },Ui.lp(-2,top=d(4)).apply { gravity=Gravity.CENTER_HORIZONTAL })
                planBox.addView(card,Ui.lp(top=d(12)))
            }
            else -> Unit
        }
    }

    private fun toneFor(state: StepState) = when(state) {
        StepState.VERIFIED -> Ui.Tone.OK
        StepState.USER_DONE, StepState.ASK_DONE, StepState.ASK_TIME -> Ui.Tone.YOU
        StepState.ACTIVE, StepState.RUNNING -> Ui.Tone.INFO
        StepState.FAILED, StepState.NOT_DONE -> Ui.Tone.ERROR
        else -> Ui.Tone.NEUTRAL
    }
    private fun statusPill(state: StepState): TextView = Ui.badge(this,PlanRun.label(state),toneFor(state))

    // ---------- Voice output ----------

    private fun speak(text: String) { if(app.settings.speakReplies) speaker.speak(text) }
    private fun readAloud() {
        val plan = app.plan
        val text = when {
            plan!=null && plan.finished -> "Done. "+plan.summary()
            plan!=null -> plan.steps.mapIndexed { i, s -> "${i+1}. ${s.title}. ${s.detail}" }.joinToString(". ")
            else -> app.agent.state.value.lines.lastOrNull { it.role=="agent" || it.role=="plan" }?.text ?: "Hello! What would you like to do? Tap the microphone and speak."
        }
        speaker.speak(text)
    }

    override fun onStop() { observer?.cancel(); super.onStop() }
    override fun onDestroy() { speaker.shutdown(); scope.cancel(); super.onDestroy() }

    companion object {
        private const val REQ_VOICE = 41
        private const val REQ_BANK = 42
        private const val REQ_BANK_STEP = 43
        /** Opens the conversation instead of the empty screen (used by the debug design preview). */
        const val EXTRA_SHOW_CONVERSATION = "show_conversation"
        /** The missing-voice notice is shown once per app run, not on every screen. */
        private var ttsWarned = false
    }
}
