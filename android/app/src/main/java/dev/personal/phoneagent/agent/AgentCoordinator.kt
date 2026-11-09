package dev.personal.phoneagent.agent

import android.content.Context
import dev.personal.phoneagent.accessibility.AgentAccessibilityService
import dev.personal.phoneagent.network.BackendClient
import dev.personal.phoneagent.network.SettingsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

 data class ChatLine(val role: String, val text: String)
 /** One step of a v2 channel plan, for the timeline UI. [bot] = talks to the app's own assistant. */
 data class ChannelStepView(val app: String, val bot: Boolean, val goal: String, val state: StepState = StepState.PENDING, val result: String = "")
 data class AgentState(val status: String = "Ready", val active: Boolean = false,
    val lines: List<ChatLine> = emptyList(),
    /** Incremented each time a task's cleanup finishes; lets the plan runner detect completion. */
    val finishedTasks: Int = 0,
    /** True only when the last finished task ended with on-screen send evidence. */
    val lastVerified: Boolean = false,
    /** Latest v2 channel plan; drawn where the "plan" chat line sits. */
    val channels: List<ChannelStepView> = emptyList())

class AgentCoordinator(context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val backend = BackendClient(settings)
    private val journal = ActionJournal(context)
    private val gate = ApprovalGate()
    private val policy = ActionPolicy()
    private val mutable = MutableStateFlow(AgentState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var answer: CompletableDeferred<String>? = null
    private var session: String? = null
    private var verified = false
    /** Last finish report of a UI-loop task (what the agent read on screen); null if it stopped otherwise. */
    private var finishReport: String? = null
    /** Why the last UI-loop task stopped for manual handling (e.g. "Form hazırdır…"). */
    private var manualReport: String? = null
    /** True when the task ended by asking the owner to act inside another app: then Köməkçi does not pull them away. */
    private var handover = false
    private fun handOver(text: String) { note(text); handover = true }
    /** Adds the user's own words to the conversation (used by the local planner). */
    fun userSaid(text: String) { trace("you",text); mutable.value = mutable.value.copy(lines=(mutable.value.lines+ChatLine("you",text)).takeLast(100)) }
    /** Debug builds only: mirror chat/status to logcat (USB development; uiautomator dumps disconnect our service). */
    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private fun trace(kind: String, text: String) { if(debuggable) android.util.Log.d("KemekciChat","$kind: ${text.take(600)}") }
    /** Debug builds only: what the speech recogniser wrote, to see how it mishears names. */
    fun heard(guesses: List<String>) = trace("heard",guesses.joinToString(" | "))
    fun note(text: String) { trace("agent",text); mutable.value = mutable.value.copy(lines=(mutable.value.lines+ChatLine("agent",text)).takeLast(100)) }
    private fun status(text: String) { trace("status",text); mutable.value=mutable.value.copy(status=text) }
    fun reply(text: String): Boolean {
        val pending=answer ?: return false
        if (Privacy.containsSensitiveIdentifier(text)) { note("Please do not type card or account numbers or security codes. Do that directly in the bank."); return true }
        mutable.value=mutable.value.copy(lines=(mutable.value.lines+ChatLine("you",text)).takeLast(100))
        pending.complete(text); return true
    }
    /** Debug builds only: replaces the visible conversation for the design preview screen. Never starts or runs anything. */
    fun debugPreview(lines: List<ChatLine>, channels: List<ChannelStepView>) {
        if(!debuggable || mutable.value.active) return
        mutable.value=mutable.value.copy(lines=lines,channels=channels)
    }
    /** True while a task is waiting for the user to type an answer in the chat. */
    fun awaitingReply() = answer != null
    fun acknowledgeUncertain() {
        if (mutable.value.active) return
        journal.resolved(); note("You confirmed that you checked the earlier uncertain action. It will not be repeated. Start a new task only after checking the app.")
    }
    fun hasUncertainAction() = journal.attempted()
    fun stop(reason: String = "Stopped. No further steps will be taken.") {
        gate.invalidate(); backend.cancel(); answer?.cancel(); answer=null
        job?.cancel()
        AgentAccessibilityService.instance?.overlay?.dismiss()
        mutable.value=mutable.value.copy(active=false,status="Stopped")
        note(reason)
    }
    /** Shared start checks for v1 tasks and v2 channel plans. */
    private fun precheck(command: String): Boolean {
        if (job != null || mutable.value.active) { note("A task is still running. Stop it or answer its question, then try again."); return false }
        if (command.isBlank() || command.length>4000) { note("Keep the request under 4000 characters."); return false }
        if (Privacy.containsSensitiveIdentifier(command)) { note("Do not type full card or account numbers into the chat. Use the bank checklist for that."); return false }
        if (!settings.consent) { note("First allow screen text to be sent, in Settings."); return false }
        if (AgentAccessibilityService.instance==null) { note("First turn on Aila phone control in Settings → Phone control."); return false }
        if (journal.attempted()) { note("The result of the last send or transfer is unknown. Check WhatsApp or “Son köçürmələrim” in ABB yourself and confirm in Settings. I will not repeat it."); return false }
        if (runCatching { settings.token() }.isFailure) { note("First pair the phone with the server in Settings."); return false }
        return true
    }
    /** Returns true only if a task was actually started. [echo] = false when the user's words are already shown. */
    fun start(command: String, live: Boolean, echo: Boolean = true, recipient: String? = null): Boolean {
        if (!precheck(command)) return false
        // A recipient the user named in their own request counts as confirmed only when it exactly matches one contact.
        val task=TaskContext(command,mode=if(live) "live" else "mock",confirmedRecipient=recipient?.takeIf { it.isNotBlank() })
        launchTask(command,echo,600_000) { run(task) }
        return true
    }
    /** v2: chatbot-first plan. Each step talks to the app's own AI assistant, or falls back to the v1 UI loop. */
    fun startChannels(command: String, live: Boolean): Boolean {
        if (!precheck(command)) return false
        launchTask(command,true,1_800_000) { runChannels(command,if(live) "live" else "mock") }
        return true
    }
    /** Card transfer: card from a WhatsApp message (read on the phone) → ABB form; the owner confirms twice. */
    fun startCardTransfer(command: String, live: Boolean): Boolean {
        if (!precheck(command)) return false
        if (!live) { note("Card transfers work only in live mode (Settings → Live mode)."); return false }
        launchTask(command,true,900_000) { runCardTransfer(command) }
        return true
    }
    /** myGov ID card / passport PDF → WhatsApp personal chat (agent/PassportShare.kt). The owner taps WhatsApp's Send. */
    fun startPassportShare(command: String, live: Boolean): Boolean {
        if (!precheck(command)) return false
        if (!live) { note("Sending documents works only in live mode (Settings → Live mode)."); return false }
        launchTask(command,true,600_000) { runPassportShare(command) }
        return true
    }
    private fun launchTask(command: String, echo: Boolean, timeoutMs: Long, block: suspend CoroutineScope.() -> Unit) {
        verified=false; handover=false
        mutable.value=mutable.value.copy(active=true,status="Starting",lines=(if(echo) mutable.value.lines+ChatLine("you",command) else mutable.value.lines).takeLast(100))
        job=scope.launch {
            try {
                withTimeout(timeoutMs) { block() }
            } catch (_: TimeoutCancellationException) { note("The task reached its time limit. No further steps will be taken.") }
            catch (_: CancellationException) { /* Stop cannot dispatch more actions. */ }
            catch (e: Exception) {
                // Content-free technical code (exception type + our own fixed message) to make failures diagnosable.
                val code="${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}"
                android.util.Log.w("Kemekci","task stopped: $code")
                note("The task stopped because the connection, reply or phone state could not be checked. Nothing is retried automatically.\nTechnical code: $code")
            }
            finally {
                gate.invalidate(); answer=null
                AgentAccessibilityService.instance?.overlay?.dismiss()
                closeSession()
                val open=setOf(StepState.PENDING,StepState.RUNNING)
                mutable.value=mutable.value.copy(active=false,status=if(journal.attempted()) "Check the uncertain send" else "Ready",
                    channels=mutable.value.channels.map { if(it.state in open) it.copy(state=StepState.SKIPPED,result="Stopped") else it },
                    finishedTasks=mutable.value.finishedTasks+1,lastVerified=verified)
                job=null
                // Done, stopped ("Dayandır" / "Xeyr") or failed: back to Köməkçi's chat (owner's request, 2026-11-08),
                // unless the owner was just asked to finish something in the other app.
                if(!handover) backToApp()
            }
        }
    }
    private suspend fun closeSession() {
        val id=session; session=null
        if(id!=null) withContext(NonCancellable) { withTimeoutOrNull(5000) { runCatching { backend.close(id) } } }
    }

    // ---------- v2 channels ----------
    private val appContext = context.applicationContext
    private val bots = BotRunner(
        capture = { deep -> withContext(Dispatchers.Default) {
            val started=System.currentTimeMillis()
            val snap=if(deep) service().collector.capture(maxNodes=500,maxDepth=90) else service().collector.capture()
            trace("capture","${if(deep) "deep" else "shallow"} ${snap.packageName} nodes=${snap.nodes.size} trunc=${snap.truncated} sens=${snap.sensitive} ${System.currentTimeMillis()-started}ms")
            snap } },
        execute = { service().executor.execute(it) },
        launch = { pkg -> appContext.packageManager.getLaunchIntentForPackage(pkg)?.let {
            appContext.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false },
        back = { service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) },
        waitForOwnerLogin = { app -> awaitOwnerLogin(app) },
        askOwnerToOpen = { app ->
            val name=app.label.substringBefore(" ")
            val how=if(app==BotApp.ABB_BOT) "tap 💬, then “AI-dan soruş”" else "open the assistant chat"
            status("Waiting for you to open the $name chat")
            note("$name hides its screen from assistants like me (a bank security rule). Please $how in $name. I will continue as soon as the chat is open.")
            progress(name,"Please $how. Then I continue by myself.",you=true)
        })
    /** Owner types the app's PIN; Köməkçi never reads it and continues by itself once the login screen is gone (max 2 min). */
    private suspend fun awaitOwnerLogin(app: BotApp): Boolean {
        val name=app.label.substringBefore(" ")
        status("Waiting for the $name login"); note("Log in to $name. I cannot see your code; I continue after you log in.")
        progress(name,"Log in to $name.",you=true)
        repeat(120) {
            delay(1000)
            val s=service().collector.capture()
            if(s.packageName==app.packageName && !s.sensitive) {
                // Replace the "enter your code" panel at once; it must never stay up after the owner has logged in.
                status("Logged in, continuing"); progress(name,"Logged in. Continuing; please do not touch the phone.")
                delay(1500); return true
            }
        }
        note("The $name login was not finished within 2 minutes."); return false
    }
    /** [userDone] = the owner confirmed it themselves; Köməkçi did not see the result. */
    data class StepOutcome(val ok: Boolean, val result: String, val userDone: Boolean = false)
    private fun setChannel(i: Int, state: StepState, result: String = "") {
        val list=mutable.value.channels.toMutableList()
        if(i in list.indices) { list[i]=list[i].copy(state=state,result=result.ifBlank { list[i].result }); mutable.value=mutable.value.copy(channels=list) }
    }
    private fun botLine(text: String) { trace("bot",text); mutable.value = mutable.value.copy(lines=(mutable.value.lines+ChatLine("bot",text)).takeLast(100)) }
    private fun appLabel(key: String) = when(key) { "abb" -> "ABB"; "mygov" -> "myGov"; "whatsapp" -> "WhatsApp"; "azercell" -> "Azercell"; else -> key }
    private fun progress(title: String, line: String, you: Boolean = false) { service().overlay.progress(title,line,you) { stop() } }
    private suspend fun askOwner(question: String, choices: List<String>): String {
        status("Waiting for your answer"); note(question)
        val pending=CompletableDeferred<String>(); answer=pending
        service().overlay.question(question,choices,{ pending.complete(it) },{ stop() })
        val result=try { pending.await() } finally { answer=null }
        service().overlay.dismiss()
        if(Privacy.containsSensitiveIdentifier(result) || Privacy.requestsCredentials(result)) throw CancellationException("Sensitive answer")
        return result.take(1000)
    }
    private suspend fun approveBotMessage(label: String, text: String): Boolean {
        status("Waiting for your approval")
        val ok=CompletableDeferred<Boolean>()
        service().overlay.approval(label,text,"This message involves money, a confirmation or personal data.",{ ok.complete(true) },{ ok.complete(false) },via="The app\'s AI assistant")
        val accepted=withTimeoutOrNull(60_000) { ok.await() } ?: false
        service().overlay.dismiss()
        return accepted
    }
    private suspend fun runChannels(command: String, mode: String) {
        status("Making a plan")
        val steps=backend.plan(command,mode)
        mutable.value=mutable.value.copy(
            channels=steps.map { ChannelStepView(appLabel(it.app),it.channel=="bot",it.goal) },
            lines=(mutable.value.lines+ChatLine("plan","Plan, ${steps.size} steps: "+steps.mapIndexed { i, s -> "${i+1}. ${appLabel(s.app)}: ${s.goal}" }.joinToString(". "))).takeLast(100))
        trace("plan","${steps.size} steps")
        val results=linkedMapOf<String,String>()
        val summary=mutableListOf<String>()
        for((i,step) in steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            status("Step ${i+1} of ${steps.size} · ${appLabel(step.app)}")
            setChannel(i,StepState.RUNNING)
            val outcome=try {
                if(step.usesResultOf.isNotBlank() && results[step.usesResultOf]==null) StepOutcome(false,"The earlier answer this needs is missing; skipped.")
                else if(step.app=="mygov") runGovStep(step,results,mode)
                else if(step.channel=="bot") runBotStep(step,results,mode) else runUiStep(step,results,mode)
            } catch(e: BotRunner.BotError) { StepOutcome(false,e.message.orEmpty()) }
            if(outcome.ok) results[step.id]=outcome.result
            // The owner sees results in English; results[] (used by later steps) keeps myGov's own words.
            val shown=GovEnglish.show(outcome.result)
            setChannel(i,when { !outcome.ok -> StepState.FAILED; outcome.userDone -> StepState.USER_DONE; else -> StepState.VERIFIED },shown.take(400))
            summary += "${if(outcome.ok) "✓" else "✕"} ${i+1}. ${appLabel(step.app)}: ${shown.take(400)}"
        }
        service().overlay.dismiss()
        val ok=summary.count { it.startsWith("✓") }
        note((if(ok==steps.size) "Done." else "$ok of ${steps.size} steps worked.")+"\n"+summary.joinToString("\n"))
    }
    private suspend fun runBotStep(step: BackendClient.ChannelStep, results: Map<String,String>, mode: String): StepOutcome {
        val app=BotApp.of(step.app) ?: return runUiStep(step,results,mode)
        progress(app.label,"Opening the chat…")
        bots.open(app)
        val transcript=mutableListOf<Pair<String,String>>()
        val clarifications=mutableListOf<String>()
        val prior=results.map { it.key to it.value }
        repeat(10) {
            status("Talking to the ${app.label}")
            val t=backend.botTurn(mode,app.key,step.goal,transcript,prior,clarifications)
            when(t.type) {
                "say" -> {
                    when(BotRisk.decide(t.text)) {
                        BotRisk.Level.BLOCK -> { note("This message contains sensitive data such as a code or number. I did not send it."); return StepOutcome(false,"Sensitive data, not sent.") }
                        BotRisk.Level.CONFIRM -> if(!approveBotMessage(app.label,t.text)) return StepOutcome(false,"You declined; not sent.")
                        BotRisk.Level.INFO -> Unit
                    }
                    note("Writing to the ${app.label}: ${t.text}")
                    progress(app.label,t.text)
                    val reply=Privacy.mask(bots.exchange(app,t.text) { line -> progress(app.label,line) })
                    botLine("${app.label}: ${reply.ifBlank { "(no reply)" }}")
                    transcript += "agent" to t.text.take(2000); transcript += "bot" to reply.take(2000)
                    while(transcript.size>24) transcript.removeAt(0)
                }
                "done" -> {
                    if(GoalCheck.answers(step.goal,t.summary)) return StepOutcome(true,t.summary)
                    // The bot explained where to look but did not give the value: go and read it on screen.
                    val lastBot=transcript.lastOrNull { it.first=="bot" }?.second.orEmpty()
                    note("The ${app.label} did not answer, it only showed the way. I am looking on the screen myself.")
                    return runUiStep(step,results,mode,hint=lastBot)
                }
                "ask_user" -> clarifications += askOwner(t.text,emptyList()).also { if(clarifications.size>=8) clarifications.removeAt(0) }
                else -> { note("The ${app.label} cannot do this itself: ${t.summary}\nI will follow this path on the screen myself."); return runUiStep(step,results,mode,hint=t.summary) }
            }
        }
        return StepOutcome(false,"The chat did not reach a result.")
    }
    private suspend fun runUiStep(step: BackendClient.ChannelStep, results: Map<String,String>, mode: String, hint: String = ""): StepOutcome {
        val label=appLabel(step.app)
        if(step.app=="mygov") return runGovScreen(step,mode,GovIntent.READ,hint)
        if(step.app=="whatsapp") {
            val recipient=RequestPlanner.recipient(step.goal).ifBlank { null }
            val draft=results[step.usesResultOf]
            verified=false
            if(mode=="mock") {
                note("Demo mode: a synthetic chat instead of real WhatsApp. No real message is sent.")
                appContext.startActivity(android.content.Intent(appContext,dev.personal.phoneagent.ui.FixtureActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(1200)
                runTask(TaskContext("Run the safe fixture test: draft: \"${(draft ?: "Salam!").replace("\"","'").take(500)}\" to Alex Example and request approval.",mode="mock"))
            } else {
                val command=if(draft!=null) "WhatsApp-da ${recipient ?: "adı çəkilən kontakt"} üçün bu mesajı yaz və göndər: \"${draft.replace("\"","'")}\"" else step.goal
                runTask(TaskContext(command,mode="live",confirmedRecipient=recipient))
            }
            return StepOutcome(verified,if(verified) "Message sent (I saw it on screen)." else "The message was not confirmed.")
        }
        if(mode=="live") runTask(TaskContext("${step.goal}. Bu $label tətbiqində edilməlidir. Formu doldur, amma son təsdiq/ödə düyməsini BASMA — onu sahibi özü basacaq.",mode="live"))
        else {
            val pkg=when(step.app) { "abb" -> ABB; "azercell" -> AZERCELL; "mygov" -> MYGOV; else -> null }
            pkg?.let { appContext.packageManager.getLaunchIntentForPackage(it) }?.let { appContext.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            note("Demo mode: $label is open. Do the step “${step.goal}” yourself.")
        }
        val answer=askOwner("$label: did you check it and do the final confirmation yourself?",listOf("Yes, I did","No"))
        return if(answer.startsWith("Yes")) StepOutcome(true,"You confirmed it (I did not check the bank\'s result).",userDone=true) else StepOutcome(false,"Not done.")
    }
    // ---------- myGov: classify → bot / read on screen / prepare form (docs/mygov-agent.md) ----------
    private suspend fun runGovStep(step: BackendClient.ChannelStep, results: Map<String,String>, mode: String): StepOutcome {
        val rule=GovIntentRules.classify(step.goal)
        var intent=GovIntentRules.decide(rule,GovIntent.of(step.intent))
        trace("intent","rule=$rule ai=${step.intent} -> $intent")
        if(intent==GovIntent.UNCLEAR) intent=GovIntentRules.fromChoice(askOwner(GovIntentRules.question(step.goal),GovIntentRules.choices))
        return when(intent) {
            GovIntent.READ, GovIntent.ACTION -> runGovScreen(step,mode,intent)
            // General question: the chatbot first; GoalCheck sends a directions-only reply on to the screen.
            else -> runBotStep(step.copy(channel="bot"),results,mode)
        }
    }
    private suspend fun runGovScreen(step: BackendClient.ChannelStep, mode: String, intent: GovIntent, botHint: String = ""): StepOutcome {
        if(mode=="mock") return StepOutcome(false,"In demo mode I cannot move through myGov myself. Turn on live mode and ask again.")
        val route=GovRoutes.find(step.goal,intent)
        val what=if(intent==GovIntent.ACTION) "preparing it" else "looking for it"
        val where=route?.title?.let { GovEnglish.show(it) }
        note("myGov: ${where ?: "the right section"} — I am $what on the screen.")
        progress("myGov","${where ?: "On screen"} — $what…")
        if(runCatching { stable() }.getOrNull()?.packageName!=MYGOV) {
            appContext.packageManager.getLaunchIntentForPackage(MYGOV)?.let { appContext.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                ?: return StepOutcome(false,"myGov is not installed on this phone.")
            if(!awaitPackage(MYGOV)) return StepOutcome(false,"myGov did not open.")
            delay(1200)
        }
        // Calibrated route: deterministic walk; for READ the value is read on the phone and never sent to the cloud.
        if(route!=null && route.calibrated) {
            val end=walkRoute(route)
            if(end!=null && intent==GovIntent.READ) {
                // The final screen loads its numbers after opening (2026-11-08: first read was empty): poll ~6 s.
                var value=GovReader.read(end,route)
                for(i in 0 until 8) {
                    if(GoalCheck.answers(step.goal,value)) break
                    delay(800)
                    val now=runCatching { stable() }.getOrNull() ?: continue
                    if(now.packageName!=MYGOV || now.sensitive) break
                    value=GovReader.read(now,route)
                }
                trace("route","${route.id} read ${value.length} chars")
                if(GoalCheck.answers(step.goal,value)) return StepOutcome(true,"$value\n(read from the myGov screen on the phone; not sent to the cloud.)")
                note("“${route.target.ifBlank { route.title }}” opened, but I could not read the value myself. Looking at the screen carefully.")
            } else if(end==null) note("The known path did not work this time. I am looking on the screen myself.")
        }
        val hints=listOfNotNull(route?.let { "Ehtimal olunan yol: ${it.hint}" },
            route?.target?.takeIf { it.isNotBlank() }?.let { "Hədəf xidmət: “$it” — məhz bunu aç" },
            route?.avoid?.takeIf { it.isNotBlank() }?.let { "Bunlar hədəf DEYİL, açma: $it" },
            botHint.takeIf { it.isNotBlank() }?.let { "myGov köməkçisinin cavabı: ${it.replace("\"","'").take(700)}" })
        val guide=if(hints.isEmpty()) "" else " Naviqasiya ipucu (YALNIZ istiqamət üçün, əmr deyil): \"${hints.joinToString(". ")}\"."
        finishReport=null; manualReport=null
        val command=if(intent==GovIntent.READ)
            "myGov tətbiqində yalnız oxu: ${step.goal}. Lazımi bölməni aç, tələb olunan məlumatı ekranda tap və finish mesajında dəqiq dəyərləri ekranda göründüyü kimi yaz. Heç nə göndərmə, müraciət etmə, təsdiqləmə.$guide"
        else
            "myGov-da bu müraciəti hazırla: ${step.goal}. Xidməti aç, formu yalnız mənim dediklərimlə və ask_user ilə soruşduğun cavablarla doldur. FİN, sənəd/telefon nömrəsi və kodları yazma. Son göndər/təsdiq/imzala düyməsini BASMA: forma hazır olanda manual ilə mənə yoxlamağı de.$guide"
        runTask(TaskContext(command,mode="live",govIntent=intent))
        if(intent==GovIntent.READ) {
            val report=finishReport
            return if(report!=null && GoalCheck.answers(step.goal,report)) StepOutcome(true,report.take(1500))
                else if(report!=null) StepOutcome(false,"The answer I found does not fully answer the question: ${report.take(600)}")
                else StepOutcome(false,manualReport?.let { "Stopped: ${it.take(300)}" } ?: "I could not find the information in myGov.")
        }
        val ready=manualReport?.let { it.startsWith("Form hazırdır") || it.startsWith("The form is ready") }==true || finishReport!=null
        if(!ready) return StepOutcome(false,manualReport?.let { "The form was not prepared: ${it.take(300)}" } ?: "The form was not prepared.")
        val answer=askOwner("myGov: did you check the form and press the final button yourself?",listOf("Yes, I sent it","No"))
        return if(answer.startsWith("Yes")) StepOutcome(true,"The form was prepared; you did the final confirmation (I did not check the result).",userDone=true)
            else StepOutcome(false,"The form is ready but was not sent.")
    }
    /** Deterministic walk along a calibrated route. Returns the final screen, or null (then the AI navigator takes over). */
    private suspend fun walkRoute(route: GovRoute): UiSnapshot? {
        var s=runCatching { stable() }.getOrNull() ?: return null
        var step=0; var logins=0; var scrolls=0; var backs=0; var tries=0
        while(step<route.path.size && tries++<24) {
            currentCoroutineContext().ensureActive()
            if(s.sensitive && s.packageName==MYGOV) {
                if(logins++>=2 || !awaitOwnerLogin(BotApp.MYGOV_BOT)) return null
                s=runCatching { stable() }.getOrNull() ?: return null; continue
            }
            if(s.packageName!=MYGOV) return null
            val at=route.path.indices.lastOrNull { it>=step && BotLocator.find(s,route.path[it])!=null }
            if(at==null) {
                val list=s.nodes.filter { it.scrollable && it.enabled }.maxByOrNull { n -> Box.parse(n.bounds)?.let { (it.right-it.left)*(it.bottom-it.top) } ?: 0 }
                if(list==null || scrolls++>=3) {
                    // Opened deep inside another service: go back towards a screen on the route (myGov back is safe).
                    if(step==0 && backs++<4) { trace("route","${route.id}: back ${backs}"); service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); scrolls=0; delay(900); s=runCatching { stable() }.getOrNull() ?: return null; continue }
                    trace("route","${route.id} step ${step+1} not found"); return null
                }
                service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"scroll",targetId=list.id))
                delay(700); s=runCatching { stable() }.getOrNull() ?: return null; continue
            }
            val target=BotLocator.clickTarget(s,BotLocator.find(s,route.path[at])!!) ?: return null
            trace("route","${route.id} ${at+1}/${route.path.size} click")
            if(!service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"click",targetId=target.id))) return null
            step=at+1; scrolls=0
            delay(1200); s=runCatching { stable() }.getOrNull() ?: return null
        }
        return s.takeIf { step>=route.path.size && it.packageName==MYGOV && !it.sensitive }
    }

    // ---------- card transfer: WhatsApp card → ABB (docs/card-transfer.md) ----------
    private suspend fun runCardTransfer(command: String) {
        val req=TransferRequest.parse(command) ?: return note("I did not understand: from whose message, and how many manat?")
        val who=req.contactWord.ifBlank { askOwner("From whose WhatsApp message should I take the card number? (type the name)",emptyList()).trim() }
        val amount=req.amount.ifBlank {
            Regex("\\d{1,6}(?:[.,]\\d{1,2})?").find(askOwner("How many manat should we send?",emptyList()))?.value?.replace(',','.') ?: ""
        }
        if(who.isBlank() || amount.isBlank()) return note("The recipient or amount is not clear. Nothing was done.")
        val guess=if(req.contactWord.isNotBlank()) req.contactGuess else who

        // 1. WhatsApp: open the named person's chat (model navigates; WhatsAppAdapter asks when several contacts match).
        note("1/3 WhatsApp: opening the chat with $guess.")
        progress("WhatsApp","Opening the $guess chat…")
        // Deterministic (no model): 2026-11-08 the model opened "Ana" from the chat list instead of searching.
        val names=TransferRequest.searchNames(guess)
        val header=openPersonChat(names,who,guess)
            ?: return note("I could not find a personal chat named $guess in WhatsApp (searched for ${names.joinToString("/")}). No money was sent.")

        // 2. Read the card on the phone only (never uploaded, never logged), then the owner confirms it.
        val found=CardFinder.find(service().collector.rawChatTexts())
            ?: return note("I did not see a card number in the $header chat. Scroll up so the message with the card is visible and try again. No money was sent.")
        trace("transfer","card found in chat (masked ${CardFinder.masked(found.number).takeLast(4)})")
        val warn=if(found.others>0) " There are ${found.others} other cards on screen; I took the newest one." else ""
        status("Waiting for you to confirm the card")
        val okCard=CompletableDeferred<Boolean>()
        service().overlay.approval("$header → ${CardFinder.grouped(found.number)}","$amount AZN\nMesaj: «${Privacy.maskIdentifiers(found.message).take(200)}»",
            "This card was taken from $header\'s WhatsApp message. Check the digits: money sent to a wrong card is hard to get back. " +
                "If you confirm, Aila will type the card and amount in ABB and ask which of your cards to use. At the end you tap “Köçürmə et”.$warn",
            { okCard.complete(true) },{ okCard.complete(false) },via="ABB transfer",seconds=120)
        val accepted=withTimeoutOrNull(120_000) { okCard.await() } ?: false
        service().overlay.dismiss()
        if(!accepted) return note("You did not confirm the card. No money was sent.")
        note("2/3 Card confirmed: ${CardFinder.masked(found.number)} ($header).")

        // 3. ABB: the owner enters the PIN; Köməkçi then opens Köçürmələr → Digər karta itself. The home screen is
        //    readable once loaded (2026-11-08: MainPageActivity, 81 nodes, `transfer` shortcut); the PIN screen is not.
        appContext.packageManager.getLaunchIntentForPackage(ABB)?.let { appContext.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            ?: return note("ABB is not installed on this phone.")
        status("Waiting for the ABB login")
        note("Opening ABB. Enter your PIN; then I open the transfer.")
        progress("ABB","Enter your ABB PIN.",you=true)
        var form: UiSnapshot?=null
        var lastTap=-10
        var backs=0; var idle=0
        for(i in 0 until 150) {
            delay(1000)
            val s=service().collector.capture()
            if(AbbTransfer.onTransferScreen(s)) { form=s; break }
            if(s.packageName!=ABB || s.sensitive || s.nodes.isEmpty() || i-lastTap<3) continue   // PIN / loading / just tapped
            val tap=AbbTransfer.transferButton(s)?.also { trace("act","click Köçürmələr") } ?: AbbTransfer.otherCardTab(s)?.also { trace("act","click Digər karta") }
            if(tap==null && backs<3 && ++idle>=4) {   // another ABB screen (e.g. Məhsullarım): back towards home
                trace("act","back"); service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); backs++; idle=0; lastTap=i; continue
            }
            if(tap!=null) {
                progress("ABB","Opening transfers…")
                service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"click",targetId=tap.id)); lastTap=i
            }
        }
        val field=form?.let { AbbTransfer.cardField(it) } ?: return note("I could not reach the ABB transfer screen within 2.5 minutes (was the PIN entered?). No money was sent.")
        if(field.text.isNotBlank()) return note("Something is already typed in the ABB card field. Delete it and try again. No money was sent.")
        delay(600)
        val fresh=runCatching { stable() }.getOrNull()
        val target=fresh?.let { AbbTransfer.cardField(it) } ?: return note("The ABB screen changed. No money was sent.")
        if(!service().executor.execute(Proposal(UUID.randomUUID().toString(),fresh.snapshotId,"set_text",targetId=target.id,text=CardFinder.digits(found.number))))
            return note("I could not type into the ABB card field. No money was sent.")
        delay(800)
        val typed=service().collector.rawFieldText(ABB,AbbTransfer.CARD_FIELD)
        // The field may have moved on (ABB auto-advances after 16 digits); only a visible mismatch stops here.
        if(typed!=null && typed.isNotBlank() && CardFinder.digits(typed)!=CardFinder.digits(found.number))
            return note("The card number in ABB does not match. Check the field; no money was sent.")
        note("3/3 ABB: card typed (${CardFinder.masked(found.number)}). Typing the amount; you will do the final confirmation.")
        progress("ABB","Card typed. Opening the amount…")

        // 4. "Davam et" → amount screen. It loads for ~1 s (2026-11-08: first reads empty, then readable — not hidden).
        val next=runCatching { stable() }.getOrNull()?.let { s -> s.nodes.firstOrNull { n -> n.clickable && n.enabled &&
            (listOf(n.text,n.description)+s.under(n).drop(1).map { it.text }).any { Regex("^(Davam et|Növbəti|Davam)$",RegexOption.IGNORE_CASE).matches(it.trim()) } }?.let { s to it } }
        if(next!=null) {
            trace("act","click Davam et")
            service().executor.execute(Proposal(UUID.randomUUID().toString(),next.first.snapshotId,"click",targetId=next.second.id))
        }
        var amountScreen: UiSnapshot?=null
        for(i in 0 until 16) {
            delay(500)
            val s=service().collector.capture()
            // A new, loaded ABB screen (not the card screen we just left).
            if(s.packageName==ABB && !s.sensitive && s.nodes.isNotEmpty() && (next==null || s.fingerprint!=next.first.fingerprint)) { amountScreen=runCatching { stable() }.getOrNull() ?: s; break }
        }
        if(amountScreen==null) {
            service().overlay.dismiss()
            handOver("I could not read ABB\'s next screen. The card is ready (${CardFinder.masked(found.number)}); type $amount as the amount and tap “Köçür” yourself.")
            return
        }
        // 5. Source card FIRST: ABB clears the amount when the card changes (2026-11-08). Amount screen ids seen that day:
        //    leftCardContainer (source tile "Kart · 4.97 ₼"), etAmountInput, btnMain ("Köçürmə et"). The tile opens a
        //    "Göndərən" sheet in its own window: rows "Kart • Debit • 7503" + "180.63 ₼"; the owner has 3 cards.
        progress("ABB","Choosing the card you pay from…")
        var payFrom=""; var payBalance=""
        val amountView=runCatching { stable() }.getOrNull() ?: amountScreen
        val tile=AbbTransfer.sourceTile(amountView,found.number)
        if(tile!=null) {
            trace("act","click source card tile")
            service().executor.execute(Proposal(UUID.randomUUID().toString(),amountView.snapshotId,"click",targetId=tile.id))
            var options=emptyList<AbbTransfer.OwnCard>()
            for(i in 0 until 6) { delay(500); options=AbbTransfer.cardOptions(service().collector.rawTexts(ABB),found.number); if(options.isNotEmpty()) break }
            trace("transfer","card list: ${options.size} cards")
            if(options.isEmpty()) return handOver("I could not read ABB\'s card list. Choose your card there yourself, type $amount as the amount and tap “Köçürmə et”.")
            val pick=askOwner("Which of your cards should we send from?",options.map { it.choice }+listOf("Stop"))
            val chosen=options.firstOrNull { it.choice==pick } ?: return note("Stopped. No money was sent.")
            val list=runCatching { stable() }.getOrNull()
            val row=list?.nodes?.firstOrNull { Regex("(?<![0-9])${chosen.last4}(?![0-9])").containsMatchIn(it.text+" "+it.description) }?.let { BotLocator.clickTarget(list,it) }
            if(list==null || row==null) return handOver("I could not tap card ${chosen.last4} in the list. Choose the card yourself, type $amount as the amount and tap “Köçürmə et”.")
            trace("act","pick card ${chosen.last4}")
            progress("ABB","Using your card …${chosen.last4}. Typing $amount AZN…")
            service().executor.execute(Proposal(UUID.randomUUID().toString(),list.snapshotId,"click",targetId=row.id))
            delay(1200)
            payFrom=chosen.last4; payBalance=chosen.balance
            val bal=dev.personal.phoneagent.adapters.AbbAdapter.numbers(chosen.balance).firstOrNull()?.toDoubleOrNull()
            if(bal!=null && amount.toDoubleOrNull()?.let { it>bal }==true) note("Note: card ${chosen.last4} has ${chosen.balance}, less than $amount AZN.")
        } else {
            val ok=askOwner("I could not find the card choice on screen. Send from the card ABB shows?",listOf("Yes","No"))
            if(!ok.startsWith("Yes")) return handOver("Choose the card in ABB yourself, type $amount as the amount and tap “Köçürmə et”.")
        }

        // 6. Amount, after the card: etAmountInput (or the single empty field) gets the owner's amount; verified locally.
        val amountForm=runCatching { stable() }.getOrNull() ?: return note("The ABB screen changed. No money was sent.")
        val amountField=amountForm.nodes.firstOrNull { it.editable && it.enabled && it.resourceId.endsWith("/etAmountInput") }
            ?: amountForm.nodes.filter { it.editable && it.enabled && !it.resourceId.endsWith("/${AbbTransfer.CARD_FIELD}") }.singleOrNull()
        if(amountField!=null) {
            trace("act","type amount")
            service().executor.execute(Proposal(UUID.randomUUID().toString(),amountForm.snapshotId,"set_text",targetId=amountField.id,text=amount))
            delay(800)
        } else {
            manualReport=null; finishReport=null
            runTask(TaskContext("ABB-də kartdan karta köçürmə: alıcının kartı və göndərən kart artıq seçilib, onlara toxunma. " +
                "Məbləğ sahəsinə dəqiq $amount yaz. Başqa sahəni dəyişmə. Son Köçür/Köçürmə et/Təsdiqlə düyməsini BASMA: forma hazır olanda manual ilə mənə yoxlamağı de.",mode="live"))
        }
        val typedAmount=service().collector.rawFieldText(ABB,"etAmountInput")
        if(typedAmount!=null && dev.personal.phoneagent.adapters.AbbAdapter.numbers(typedAmount)!=dev.personal.phoneagent.adapters.AbbAdapter.numbers(amount))
            return handOver("The amount field shows “$typedAmount”, not $amount. Fix the amount yourself and check before “Köçürmə et”.")
        val raw=service().collector.rawTexts(ABB).map { it.second }
        // The tile shows the chosen card by its balance (no digits): that balance must now be on the amount screen.
        val wantBal=dev.personal.phoneagent.adapters.AbbAdapter.numbers(payBalance)
        val chosenShown=payFrom.isEmpty() || wantBal.isEmpty() || raw.any { t -> dev.personal.phoneagent.adapters.AbbAdapter.numbers(t).any { it in wantBal } && Regex("₼|azn",RegexOption.IGNORE_CASE).containsMatchIn(t) }

        // 7. "Köçürmə et" is the owner's tap (owner's decision, 2026-11-08). Köməkçi checks the screen first and says so.
        // A focused amount field keeps blinking: use the latest reading if the screen never settles.
        val screen=runCatching { stable() }.getOrNull() ?: runCatching { service().collector.capture() }.getOrNull()
        val button=screen?.let { AbbTransfer.finalButton(it) }
        // The amount sits inside the editable etAmountInput (raw texts skip editables): its typed value was verified above.
        val amountOk=typedAmount!=null && dev.personal.phoneagent.adapters.AbbAdapter.numbers(typedAmount)==dev.personal.phoneagent.adapters.AbbAdapter.numbers(amount)
        val ready=AbbTransfer.confirmReady(raw+(if(amountOk) listOf(amount,"₼") else emptyList()),amount,found.number)
        val checked=button!=null && chosenShown && ready
        // Which condition failed, without values: no balances or card numbers in the log.
        trace("transfer","final check: screen=${screen?.packageName} button=${button?.text ?: button?.description ?: "none"} chosenShown=$chosenShown amountOk=$amountOk amountAndCard=$ready")
        val from=if(payFrom.isNotEmpty()) " from your card …$payFrom" else ""
        note(if(checked) "Ready: $amount AZN$from → ${CardFinder.masked(found.number)} ($header). I checked it on screen. Now tap “Köçürmə et”; if a code is asked, enter it yourself."
            else "I filled in the form but could not check everything on screen. Before “Köçürmə et”, check yourself that it is $amount AZN$from → …${CardFinder.digits(found.number).takeLast(4)}.")
        // Small top card only: a question sheet would cover ABB's "Köçürmə et" at the bottom (2026-11-08).
        status("Waiting for you to tap “Köçürmə et”")
        progress("ABB","Check, then tap “Köçürmə et”.",you=true)
        var outcome="timeout"
        for(i in 0 until 180) {
            delay(1000)
            val s=service().collector.capture()
            if(s.sensitive || (s.packageName==ABB && s.nodes.isEmpty())) continue        // code entry / loading: keep waiting
            if(s.packageName==ABB && service().collector.rawTexts(ABB).any { AbbTransfer.success.containsMatchIn(it.second) }) { outcome="ok"; break }
            if(s.packageName!=ABB && s.packageName!=OWN_PACKAGE) { outcome="left"; break }
        }
        service().overlay.dismiss()
        when(outcome) {
            "ok" -> { note("Done: $amount AZN was transferred to $header$from. I saw ABB\'s success screen."); backToApp() }
            else -> {
                backToApp()
                val done=askOwner("Did the $amount AZN transfer finish in ABB?",listOf("Yes, it did","No"))
                note(if(done.startsWith("Yes")) "Done: you confirmed the $amount AZN transfer to $header." else "The transfer was not finished. The form may still be open in ABB.")
            }
        }
    }
    // ---------- myGov passport → WhatsApp (agent/PassportShare.kt) ----------
    private fun clickNode(s: UiSnapshot, n: UiNode): Boolean =
        BotLocator.clickTarget(s,n)?.let { service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"click",targetId=it.id)) } ?: false
    /** Labels of a node: its own text/description, then its children's. */
    private fun labels(s: UiSnapshot, n: UiNode) = s.under(n).flatMap { listOf(it.text,it.description) }.map { it.trim() }.filter { it.isNotEmpty() }
    private fun labelled(s: UiSnapshot, re: Regex) = s.nodes.firstOrNull { n -> listOf(n.text,n.description).any { re.matches(it.trim()) } }

    private suspend fun runPassportShare(command: String) {
        val doc=PassportShare.document(command)
        val who=PassportShare.recipient(command).ifBlank { askOwner("Who should I send the ${doc.title} to on WhatsApp? (type the name)",emptyList()).trim() }
        if(who.isBlank()) return note("The recipient is not clear. Nothing was done.")

        // 1. myGov: home → carousel card → document row → menu → Yüklə. Explicitly asked by the owner, so this scripted
        //    route may tap "Yüklə" (MyGovAdapter keeps refusing it for the cloud model).
        note("1/3 myGov: opening and downloading the ${doc.title}.")
        progress("myGov","Opening ${doc.title}…")
        val gov=appContext.packageManager.getLaunchIntentForPackage(MYGOV) ?: return note("The myGov app was not found.")
        // Fresh task: myGov otherwise resumes whatever screen was open last.
        appContext.startActivity(gov.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK))
        if(!awaitPackage(MYGOV)) return note("myGov did not open. Nothing was sent.")
        var rowOpened=false; var menuOpened=false; var swipes=0; var backs=0; var logins=0; var downloaded=false
        for(i in 0 until 30) {
            ensureActive()
            val s=runCatching { stable() }.getOrNull() ?: run { delay(800); null } ?: continue
            if(s.packageName in SCRIPTED_PACKAGES) { downloaded=true; break }              // share sheet already open
            if(s.packageName!=MYGOV) return note("myGov closed. Nothing was sent.")
            if(s.sensitive) { if(logins++>=2 || !awaitOwnerLogin(BotApp.MYGOV_BOT)) return note("There was no myGov login. Nothing was sent."); progress("myGov","Opening ${doc.title}…"); continue }
            labelled(s,PassportShare.download)?.let { n ->
                trace("passport","Yüklə"); if(!clickNode(s,n)) return note("I could not tap “Yüklə”.")
                downloaded=true; break
            }
            if(rowOpened && !menuOpened) {
                // Document screen: its menu is the unlabelled icon at the top right (back is top left).
                val menu=s.nodes.filter { it.clickable && it.enabled }.mapNotNull { n -> Box.parse(n.bounds)?.let { n to it } }
                    .filter { (_,b) -> b.top<350 && b.cx>700 }.maxByOrNull { (_,b) -> b.right }?.first
                if(menu!=null && clickNode(s,menu)) { trace("passport","menu"); menuOpened=true; delay(1200); continue }
            }
            // Passport: card → "ÜMUMVƏTƏNDAŞ" row → document. ID card: the card opens the document itself.
            if(!rowOpened && doc.row!=null) labelled(s,doc.row)?.let { n ->
                if(clickNode(s,n)) { trace("passport","row"); rowOpened=true; delay(2000) }
            }?.let { continue }
            if(!rowOpened) labelled(s,doc.card)?.let { n ->
                if(clickNode(s,n)) { trace("passport","card ${doc.name}"); if(doc.row==null) rowOpened=true; delay(2000) }
            }?.let { continue }
            // Home carousel: page through the document cards.
            val home=s.nodes.any { it.text.trim()=="Xidmətlər" || it.description.trim()=="Xidmətlər" }
            if(home && !rowOpened && swipes<16) {
                val pager=s.nodes.filter { it.scrollable && it.enabled }.mapNotNull { n -> Box.parse(n.bounds)?.let { n to it } }
                    .filter { (_,b) -> b.top<900 && b.bottom>500 }.minByOrNull { (_,b) -> (b.right-b.left)*(b.bottom-b.top) }?.first
                if(pager!=null) {
                    trace("passport","carousel ${swipes+1}")
                    service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"scroll",targetId=pager.id,direction=if(swipes<6) "forward" else "backward"))   // to the end, then back to the first card
                    swipes++; delay(1000); continue
                }
            }
            if(menuOpened) { menuOpened=false; delay(800); continue }                       // menu still opening
            if(backs++<4) { trace("passport","back"); service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); rowOpened=false; delay(900); continue }
            break
        }
        if(!downloaded) return note("I could not find the “${doc.title}” in myGov. Nothing was sent.")

        // 2. myGov opens Android's share sheet with the PDF (~3 s; it can also show an error instead).
        progress("myGov","Downloading ${doc.title}…")
        var picked=false
        for(i in 0 until 40) {
            delay(1000)
            val s=runCatching { stable() }.getOrNull() ?: service().collector.capture()   // the sheet animates while targets load
            if(s.packageName==WHATSAPP) { picked=true; break }
            trace("passport","wait ${i+1}: ${s.packageName} nodes=${s.nodes.size}")
            if(s.packageName !in SCRIPTED_PACKAGES) continue
            trace("passport","sheet targets: "+s.nodes.filter { it.clickable && it.enabled }.joinToString(" | ") { labels(s,it).joinToString("+").take(40) })
            val wa=s.nodes.filter { it.clickable && it.enabled }.firstOrNull { PassportShare.isWhatsAppTarget(labels(s,it)) }
            if(wa!=null) { trace("passport","share → WhatsApp"); if(clickNode(s,wa)) { picked=true; delay(1500); break } }
            else s.nodes.firstOrNull { it.scrollable && it.enabled }?.let { service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"scroll",targetId=it.id)) }
        }
        if(!picked) return note("myGov did not download the document or the share window did not open (myGov sometimes says “xəta baş verdi”). Try again a little later. Nothing was sent.")
        note("2/3 ${doc.title} downloaded, handing it to WhatsApp.")

        // 3. WhatsApp "Send to…": search the person under each likely spelling, select only their own contact.
        progress("WhatsApp","Looking for $who…")
        val name=sharePickPerson(who) ?: run {
            service().overlay.dismiss(); handOver("I could not find a personal contact named $who in WhatsApp (searched for ${TransferRequest.searchNames(who).joinToString("/")}). Choose the contact yourself or go back. Nothing was sent.")
            return
        }
        service().overlay.dismiss()

        // 4. Owner approves in Köməkçi's own card, then Köməkçi presses WhatsApp's send (owner's choice, 2026-11-08).
        //    Only when exactly the verified person is selected; re-checked after the approval.
        fun selection(t: UiSnapshot?): UiNode? {
            if(t==null || t.packageName!=WHATSAPP) return null
            val footer=t.only("footer_container") ?: return null
            if(labels(t,footer).joinToString(", ")!=name) return null                   // one person, the verified one
            return t.withIdSuffix("send").firstOrNull { it.clickable && it.enabled }
        }
        // The picker animates after a selection (2026-11-08): look for the footer + send for ~4 s, latest reading if it never settles.
        var sendReady: UiNode?=null
        for(i in 0 until 6) {
            val t=runCatching { stable(deep=true) }.getOrNull() ?: runCatching { service().collector.capture(maxNodes=400,maxDepth=90) }.getOrNull()
            sendReady=selection(t)
            trace("passport","selection check ${i+1}: footer=${t?.only("footer_container")?.let { labels(t,it) }} send=${t?.withIdSuffix("send")?.size}")
            if(sendReady!=null) break
            delay(700)
        }
        if(sendReady==null) {
            handOver("3/3 $name is selected in WhatsApp, but I could not check the send button. Check the file and send it yourself.")
            return
        }
        status("Waiting for your approval")
        note("3/3 $name is selected in WhatsApp and the ${doc.title} PDF is attached. Please approve the send.")
        val ok=CompletableDeferred<Boolean>()
        service().overlay.approval(name,"${doc.title} (PDF, myGov)",
            "This document has your personal data. It will go only to $name. If you approve, Aila will tap WhatsApp\'s send button.",
            { ok.complete(true) },{ ok.complete(false) },via="WhatsApp",seconds=120)
        val approved=withTimeoutOrNull(120_000) { ok.await() } ?: false
        service().overlay.dismiss()
        if(!approved) { handOver("I did not send it. $name is still selected in WhatsApp: send it yourself if you want, or go back."); return }
        val now=runCatching { stable(deep=true) }.getOrNull()
        val send=selection(now)
        if(now==null || send==null) { handOver("The WhatsApp screen changed, so I did not send. Check it yourself."); return }
        status("Sending")
        trace("passport","send")
        if(!clickNode(now,send)) { handOver("I could not tap Send. Please tap it yourself."); return }
        // WhatsApp may ask once more (document preview / "Send to …?"): press only ITS send, never on the picker again.
        var chat: String?=null; var extra=0
        for(i in 0 until 12) {
            delay(800)
            val t=runCatching { stable(deep=true) }.getOrNull() ?: continue
            if(t.packageName!=WHATSAPP) break
            t.only("conversation_contact_name")?.text?.let { h -> if(TransferRequest.isPersonChat(h,who,who)) chat=h }
            if(chat!=null) break
            if(t.only("footer_container")!=null) continue                                  // still the picker: wait
            val again=t.nodes.firstOrNull { it.clickable && it.enabled && (it.resourceId.endsWith("/send") ||
                (it.resourceId=="android:id/button1" && Regex("(?iu)^(send|göndər)$").matches(it.text.trim()))) }
            trace("passport","after send: ${t.nodes.filter { it.clickable }.map { it.resourceId.substringAfter(":id/","") }.distinct().take(12)}")
            if(again!=null && extra<2 && clickNode(t,again)) { extra++; trace("passport","confirm send $extra") }
        }
        backToApp()
        note(if(chat!=null) "Done: with your approval I tapped Send in WhatsApp for the ${doc.title} and the $chat chat opened. Check delivery (✓✓) there."
            else "I tapped Send but did not see the $name chat. Check in WhatsApp that it was sent. Nothing is retried automatically.")
    }

    /**
     * WhatsApp share picker ("Send to…"): search box → each spelling from [NameSearch] → only rows that are the person
     * (Rəhman = Rahman, never a group); several → the owner picks. Taps the row once (selects it). Returns its name.
     */
    /** A picker row's name: its text, else the description WhatsApp puts on search-result rows (first line only). */
    private fun pickerName(n: UiNode) = n.text.ifBlank { n.description }.trim().lineSequence().firstOrNull()?.trim().orEmpty()
    private suspend fun sharePickPerson(who: String): String? {
        if(!awaitPackage(WHATSAPP)) return null
        // The picker may rotate as it opens (phone held sideways, 2026-11-08): its window is empty/invisible meanwhile.
        var ready=false
        for(i in 0 until 24) {
            val s=runCatching { stable(deep=true) }.getOrNull()
            if(s!=null && s.packageName==WHATSAPP && (s.withIdSuffix("menuitem_search").isNotEmpty() || s.withIdSuffix("search_src_text").isNotEmpty())) { ready=true; break }
            if(s!=null && s.packageName!=WHATSAPP && s.packageName!=OWN_PACKAGE) return null
            delay(500)
        }
        if(!ready) { trace("act","picker not ready"); return null }
        for(name in TransferRequest.searchNames(who)) {
            var s=runCatching { stable(deep=true) }.getOrNull() ?: return null
            if(s.packageName!=WHATSAPP) return null
            var field=s.nodes.firstOrNull { it.editable && it.enabled && it.resourceId.endsWith("/search_src_text") }
            if(field==null) {
                val search=s.withIdSuffix("menuitem_search").firstOrNull { it.enabled } ?: return null
                if(!clickNode(s,search)) return null
                delay(900); s=runCatching { stable(deep=true) }.getOrNull() ?: return null
                field=s.nodes.firstOrNull { it.editable && it.enabled && it.resourceId.endsWith("/search_src_text") } ?: return null
            }
            trace("act","picker search “$name”")
            if(!service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"set_text",targetId=field.id,text=name))) return null
            delay(1500)
            // Results can take a moment and can use other row views than the plain contact list (2026-11-08: "Rəhman"
            // was on screen but no contactpicker_row_name matched), so any non-editable name text counts, twice checked.
            var rows=emptyList<UiNode>()
            for(attempt in 0 until 3) {
                s=runCatching { stable(deep=true) }.getOrNull() ?: return null
                // A search-result row exposes its name as the row's description, not as a child text (2026-11-08).
                rows=s.nodes.filter { !it.editable && !it.resourceId.endsWith("/search_src_text") && pickerName(it).let { l -> l.isNotBlank() && TransferRequest.isPersonChat(l,who,who) } }
                trace("act","picker “$name”: ${s.nodes.size} nodes, matches=${rows.joinToString { it.resourceId.substringAfter(":id/","")+"="+pickerName(it).take(30) }}" +
                    if(rows.isEmpty()) " rows=${s.withIdSuffix("contactpicker_row_container").joinToString { "t=${it.text.take(30)}/d=${it.description.take(40)}" }}" else "")
                if(rows.isNotEmpty()) break
                delay(800)
            }
            if(rows.isEmpty()) continue
            val distinct=rows.map { pickerName(it) }.distinct()
            val row=if(distinct.size==1) rows.first() else {
                val pick=askOwner("Which $who in WhatsApp?",distinct+listOf("None of them"))
                progress("WhatsApp","Selecting $pick…")
                s=runCatching { stable(deep=true) }.getOrNull() ?: return null
                s.nodes.firstOrNull { !it.editable && pickerName(it)==pick } ?: return null
            }
            // Rows offer no click action: tap the verified row itself (TAP_TARGETS), else nothing.
            val tapped=clickNode(s,row) || service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"tap",targetId=row.id))
            if(!tapped) { trace("act","picker tap refused"); return null }
            trace("act","picker select ${pickerName(row)}")
            delay(1200)
            return pickerName(row)
        }
        return null
    }

    /** Brings Köməkçi's own chat to the front when a task has its answer (owner's request, 2026-11-08). */
    private fun backToApp() {
        runCatching { appContext.startActivity(android.content.Intent(appContext,dev.personal.phoneagent.ui.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }

    /**
     * Opens the named person's own WhatsApp chat: search box → each spelling (Azerbaijani first) → only rows whose
     * name is that person (Mətin = Metin); several such rows → the owner picks. Returns the open chat's header or null.
     */
    /**
     * A screen reading for flows that must not give up on lively screens (WhatsApp's chat list keeps animating:
     * typing indicators, status rings, counters). Two tries at a settled screen, then the latest reading; every tap is
     * still checked against the reading it was planned on.
     */
    private suspend fun settled(): UiSnapshot =
        runCatching { stable() }.getOrNull() ?: runCatching { stable() }.getOrNull() ?: service().collector.capture()

    private suspend fun openPersonChat(names: List<String>, who: String, guess: String): String? {
        val wa=appContext.packageManager.getLaunchIntentForPackage(WHATSAPP) ?: return null
        appContext.startActivity(wa.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        if(!awaitPackage(WHATSAPP)) { trace("chat","WhatsApp did not come to the front"); return null }
        val searchIds=setOf("menuitem_search","search_bar","search_bar_inner_layout","search_container","search_text","my_search_bar")
        fun click(s: UiSnapshot, n: UiNode) = BotLocator.clickTarget(s,n)?.let { service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"click",targetId=it.id)) } ?: false
        for(name in names) {
            var s=settled()
            var backs=0
            while(s.withIdSuffix("search_input").isEmpty()) {
                if(s.packageName!=WHATSAPP) { trace("chat","left WhatsApp: ${s.packageName}"); return null }
                s.only("conversation_contact_name")?.text?.let { h -> if(TransferRequest.isPersonChat(h,who,guess)) return h }
                val search=s.nodes.firstOrNull { it.enabled && it.resourceId.substringAfter(":id/","") in searchIds }
                if(search!=null && click(s,search)) { trace("act","click WhatsApp search"); delay(900) }
                else { if(backs++>=3) { trace("chat","no search box"); return null }; trace("act","back"); service().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK); delay(800) }
                s=settled()
                if(backs>3) { trace("chat","no search box"); return null }
            }
            val input=s.withIdSuffix("search_input").first()
            trace("act","search “$name”")
            if(!service().executor.execute(Proposal(UUID.randomUUID().toString(),s.snapshotId,"set_text",targetId=input.id,text=name))) { trace("chat","could not type “$name”"); return null }
            delay(1500)
            s=settled()
            trace("chat","“$name”: ${s.withIdSuffix("conversations_row_contact_name").map { it.text }}")
            val rows=s.withIdSuffix("conversations_row_contact_name").filter { TransferRequest.isPersonChat(it.text,who,guess) }
            if(rows.isEmpty()) continue
            val row=if(rows.map { it.text }.distinct().size==1 && rows.size==1) rows.single() else {
                val pick=askOwner("Which $guess in WhatsApp?",rows.map { it.text }.distinct()+listOf("None of them"))
                rows.firstOrNull { it.text==pick } ?: return null
            }
            if(!click(s,row)) { trace("chat","could not tap ${row.text}"); return null }
            trace("act","open chat ${row.text}")
            delay(1500)
            val h=settled().only("conversation_contact_name")?.text
            trace("chat","opened header: $h")
            if(h!=null && TransferRequest.isPersonChat(h,who,guess)) return h
        }
        return null
    }

    /** Runs one v1 UI-loop task inside the current job and closes its backend session afterwards. */
    private suspend fun runTask(task: TaskContext) { try { run(task) } finally { closeSession() } }

    private fun service() = AgentAccessibilityService.instance ?: error("Controls disconnected")
    /**
     * [deep] = read the whole tree (local-only flows; never uploaded): WhatsApp's share-picker names sit below the
     * default 28 levels (2026-11-08: "Rəhman Aghazade" was on screen, only its row container was captured).
     */
    private suspend fun stable(deep: Boolean = false): UiSnapshot {
        val cap={ if(deep) service().collector.capture(maxNodes=400,maxDepth=90) else service().collector.capture() }
        var previous=cap()
        // Up to ~2.4 s: app launches and screen transitions often animate for over a second.
        repeat(12) {
            delay(200)
            val next=cap()
            if(previous.fingerprint==next.fingerprint) return next
            previous=next
        }
        error("Screen did not settle")
    }
    private suspend fun ask(question: String, choices: List<String>, task: TaskContext, snapshot: UiSnapshot): String {
        status("Waiting for your answer"); note(question)
        val pending=CompletableDeferred<String>(); answer=pending
        service().overlay.question(question,choices,{ pending.complete(it) },{ stop() })
        val result=try { pending.await() } finally { answer=null }
        require(!Privacy.containsSensitiveIdentifier(result)) { "Sensitive answer not accepted" }
        if(result.equals("Cancel",true)) throw CancellationException("User canceled")
        task.clarifications.add(result.take(1000))
        task.clarifications.apply { while(size>8) removeAt(0) }
        val contacts=snapshot.withIdSuffix("conversations_row_contact_name").map { it.text }
        val header=snapshot.only("conversation_contact_name")?.text
        if(result in contacts) task.confirmedRecipient=result
        if(header!=null && result=="Use $header") task.confirmedRecipient=header
        service().overlay.stop { stop() }
        return result
    }
    private suspend fun run(task: TaskContext) {
        service().overlay.stop { stop() }
        delay(450)
        session=backend.create(task.command,task.mode)
        var previous: String?=null
        var logins=0
        for(sequence in 0 until 15) {
            ensureActive()
            status("Looking at the screen · ${sequence+1}/15")
            var s=stable()
            // myGov login/PIN: the owner types it (never read or sent), then the task continues by itself.
            if(s.sensitive && s.packageName==MYGOV && logins++<2) {
                service().overlay.stop { stop() }
                if(!awaitOwnerLogin(BotApp.MYGOV_BOT)) return
                service().overlay.stop { stop() }; status("Login finished, continuing")
                previous="Owner completed login; observe again."
                s=stable()
            }
            if(s.sensitive || s.packageName==BANK) {
                note("I stopped before a security screen. Nothing was sent from it. Do the login or bank step yourself, then start a new task.")
                return
            }
            if(s.packageName !in SUPPORTED_PACKAGES) { note("The open app is not on the allowed list. The task was stopped."); return }
            if(s.truncated || s.json().toString().toByteArray(Charsets.UTF_8).size > 110_000) {
                note("The screen is bigger than the safe reading limit. Nothing was sent; do this step yourself."); return
            }
            status("Thinking · ${sequence+1}/15")
            val p=backend.next(session!!,sequence,UUID.randomUUID().toString(),s,previous,task)
            ensureActive()
            require(p.snapshotId==s.snapshotId)
            when(val decision=policy.assess(s,p,task)) {
                is Decision.Manual -> { manualReport=decision.reason; note(decision.reason); return }
                is Decision.Clarify -> { ask(decision.question,decision.choices,task,s); previous="User answered the question (see clarifications); observe again."; continue }
                is Decision.Approval -> {
                    val fresh=stable()
                    if(fresh.fingerprint!=s.fingerprint) { previous="Screen changed; approval was not issued. Observe again."; continue }
                    val ticket=gate.issue(session!!,fresh,p,decision)
                    val approved=CompletableDeferred<Boolean>()
                    status("Waiting for your approval")
                    note("The message to ${decision.recipient} needs your approval. Read the full text on screen.")
                    service().overlay.approval(decision.recipient,decision.exactText,decision.reason,
                        { approved.complete(true) },{ approved.complete(false) })
                    val accepted=withTimeoutOrNull(60_000) { approved.await() } ?: false
                    if(!accepted) { gate.invalidate(); note("Declined or timed out. The message was not sent."); return }
                    // Keep overlay until revalidation; then remove it only after consuming the capability.
                    val before=stable()
                    if(!gate.consume(ticket,session!!,before,p)) { note("The recipient, message or screen changed. The approval was cancelled; nothing was sent."); return }
                    ensureActive()
                    if(!journal.recordAttempt(session!!,p.actionId,"${decision.recipient}|${decision.exactText}")) error("Uncertain earlier commit")
                    service().overlay.stop { stop() }
                    status("Sending once")
                    val dispatched=service().executor.execute(p)
                    // No network/model round trip or automatic retry for this commit.
                    val result=if(dispatched) verifyCommit(before,decision) else "Unknown: a send was attempted but could not be checked. Check the app yourself; nothing is retried automatically."
                    note(result)
                    if(result.startsWith(VERIFIED)) { journal.resolved(); verified=true }
                    return
                }
                Decision.Allow -> Unit
            }
            when(p.type) {
                "ask_user" -> { ask(p.question.ifBlank { p.message.ifBlank { "Please make your request more specific." } },emptyList(),task,s); previous="User supplied a clarification." }
                "manual" -> { manualReport=p.message.ifBlank { null }; note(p.message.ifBlank { "You need to do this step yourself. Then start a new request." }); return }
                "finish" -> { finishReport=p.message.takeIf { it.isNotBlank() }; note("Aila\'s report (not independent proof of payment or delivery):\n"+p.message.ifBlank { "The task is finished." }); return }
                "wait" -> { delay(p.durationMs.toLong().coerceAtLeast(200)); previous="Waited; no action executed." }
                else -> {
                    val fresh=stable()
                    if(fresh.fingerprint!=s.fingerprint) { previous="Screen changed before execution; no action executed."; continue }
                    if(Privacy.containsSensitiveIdentifier(p.text)) { note("The suggested text contains a sensitive number. Enter it yourself directly in the app."); return }
                    ensureActive()
                    status("Working")
                    trace("act","${p.type} ${s.node(p.targetId)?.let { n -> (listOf(n.text,n.description)+s.under(n).drop(1).flatMap { listOf(it.text,it.description) }).firstOrNull { it.isNotBlank() } }.orEmpty().take(60)}${if(p.type=="launch") p.packageName else ""}")
                    val accepted=service().executor.execute(p)
                    if(!accepted) { note("The phone did not accept this step. The task stopped; nothing is retried blindly."); return }
                    delay(350)
                    if(p.type=="launch") {
                        // Apps can take a few seconds to come to the foreground; wait for the exact package.
                        if(!awaitPackage(p.packageName)) { note("Could not check that the app opened."); return }
                        previous="Requested app opened."; continue
                    }
                    val after=stable()
                    previous=when(p.type) {
                        "set_text" -> if(after.nodes.any { it.resourceId==s.node(p.targetId)?.resourceId && stripInvisible(it.text).trim()==stripInvisible(p.text).trim() }) "Text entry verified in the selected field." else { note("Could not check that the text was typed. The task stopped."); return }
                        "launch" -> if(after.packageName==p.packageName) "Requested app opened." else { note("Could not check that the app opened."); return }
                        else -> if(after.fingerprint!=fresh.fingerprint) "Screen changed after the action; inspect the new observation." else "Action accepted, but screen did not change. Do not repeat blindly."
                    }
                }
            }
        }
        note("I stopped at the 15-step safety limit. Start a new task if needed.")
    }
    private suspend fun verifyCommit(before: UiSnapshot, d: Decision.Approval): String {
        repeat(5) {
            delay(600)
            val after=stable()
            if(after.packageName!=before.packageName || after.sensitive) return "Unknown: the app changed after sending. Check it yourself; nothing is retried."
            if(after.packageName==OWN_PACKAGE && after.only("fixture_status")?.text=="Delivered" && after.only("fixture_entry")?.text.isNullOrEmpty())
                return "$VERIFIED demo finished. Simulated: delivered; no real message was sent."
            if(after.only("conversation_contact_name")?.text!=d.recipient) return "Unknown: the recipient screen changed after sending. Check it yourself."
            when(SendEvidence.whatsappStatus(before,after,d.recipient,d.exactText)) {
                "delivered" -> return "$VERIFIED WhatsApp shows it delivered."
                "read" -> return "$VERIFIED WhatsApp shows it read."
                "sent" -> return "$VERIFIED WhatsApp shows it sent; delivery is not confirmed yet."
            }
        }
        return "Unknown: one send was attempted and the result was not checked. Check WhatsApp yourself. Nothing is retried automatically."
    }
    private suspend fun awaitPackage(pkg: String): Boolean {
        repeat(20) {
            ensureActive()
            // A lively screen (WhatsApp chat list) may never settle: the current reading's package is enough here.
            val now=runCatching { stable() }.getOrNull() ?: runCatching { service().collector.capture() }.getOrNull()
            if(now?.packageName==pkg) return true
            trace("wait","want $pkg, see ${now?.packageName ?: "nothing"} (${now?.screen})")
            delay(300)
        }
        return false
    }
    private suspend fun ensureActive() { currentCoroutineContext().ensureActive() }
    companion object { const val VERIFIED = "Verified:" }
}
