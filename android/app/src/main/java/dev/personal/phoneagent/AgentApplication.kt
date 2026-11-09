package dev.personal.phoneagent

import android.app.Application
import dev.personal.phoneagent.agent.AgentCoordinator
import dev.personal.phoneagent.network.SettingsStore

class AgentApplication : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var agent: AgentCoordinator
        private set
    /** Current multi-step plan; kept here so it survives activity recreation. */
    var plan: dev.personal.phoneagent.agent.PlanRun? = null
    /** On at every start (owner's decision, 2026-11-09). Turning it off in Settings lasts until the next app restart. */
    var liveEnabled = true
    /**
     * Debug builds only: a request sent from `adb shell` (DUMP-protected broadcast, see AgentAccessibilityService),
     * submitted by MainActivity exactly as if typed. In memory only, so other apps cannot inject one via an intent.
     */
    @Volatile var debugRequest: String? = null
    /** Debug builds only: like [debugRequest], but MainActivity types it into the message box key by key, then sends it. */
    @Volatile var debugType: String? = null
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        dev.personal.phoneagent.ui.Ui.scale = settings.textScale
        dev.personal.phoneagent.ui.Ui.init(this)
        val pairing=dev.personal.phoneagent.network.DebugPairing.consume(this,settings)
        agent = AgentCoordinator(this,settings)
        when(pairing) {
            dev.personal.phoneagent.network.DebugPairing.Status.PAIRED -> agent.note("USB pairing is complete. Your consent was saved. Live mode is on; check that phone control is on in Settings.")
            dev.personal.phoneagent.network.DebugPairing.Status.FAILED -> agent.note("USB pairing could not be verified. No key was written. Check the connection settings.")
            dev.personal.phoneagent.network.DebugPairing.Status.NONE -> Unit
        }
        dev.personal.phoneagent.accessibility.PhoneControlKeeper.ensureOn(this)
    }
}
