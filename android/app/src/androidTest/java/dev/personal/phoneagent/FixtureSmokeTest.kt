package dev.personal.phoneagent

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.personal.phoneagent.agent.ActionJournal
import dev.personal.phoneagent.ui.FixtureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic UI/storage checks only. Does not authorize or test real-app sends. */
@RunWith(AndroidJUnit4::class)
class FixtureSmokeTest {
    @Test fun syntheticUiPreservesUnicodeAndSimulatesDelivery() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        // Enter through the normal exported launcher, then open the internal fixture from
        // a foreground Activity. Direct internal-screen launch timed out on this MIUI device.
        val mainReady=java.util.concurrent.CountDownLatch(1)
        val fixtureReady=java.util.concurrent.CountDownLatch(1)
        var main: android.app.Activity?=null
        var fixture: android.app.Activity?=null
        val monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
        val callback=androidx.test.runner.lifecycle.ActivityLifecycleCallback { activity, stage ->
            if(stage==androidx.test.runner.lifecycle.Stage.RESUMED) {
                if(activity is dev.personal.phoneagent.ui.MainActivity) { main=activity; mainReady.countDown() }
                if(activity is FixtureActivity) { fixture=activity; fixtureReady.countDown() }
            }
        }
        instrumentation.runOnMainSync { monitor.addLifecycleCallback(callback) }
        val activity=try {
            val descriptor=instrumentation.uiAutomation.executeShellCommand("am start -W -n dev.personal.phoneagent/.ui.MainActivity")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            check(mainReady.await(10,java.util.concurrent.TimeUnit.SECONDS)) { "Launcher did not resume" }
            instrumentation.runOnMainSync { main!!.startActivity(Intent(main,FixtureActivity::class.java)) }
            check(fixtureReady.await(10,java.util.concurrent.TimeUnit.SECONDS)) { "Fixture did not resume from launcher" }
            fixture!!
        } finally { instrumentation.runOnMainSync { monitor.removeLifecycleCallback(callback) } }
        try {
            instrumentation.runOnMainSync {
                assertNotNull(activity.findViewById<TextView>(R.id.fixture_header))
                activity.findViewById<android.view.View>(R.id.fixture_contact_alex).performClick()
                val entry=activity.findViewById<EditText>(R.id.fixture_entry)
                entry.setText("Əli, görüşərik!")
                assertEquals("Əli, görüşərik!",entry.text.toString())
                activity.findViewById<android.view.View>(R.id.fixture_send).performClick()
                assertEquals("",entry.text.toString())
                assertEquals("Delivered",activity.findViewById<TextView>(R.id.fixture_status).text.toString())
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
    @Test fun privatePairingIsEncryptedOneShotAndRejectsMalformedConsent() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fixture-pairing-${UUID.randomUUID()}"
        val directory=java.io.File(base.filesDir,name).apply { mkdirs() }
        val isolated=object: ContextWrapper(base) {
            override fun getFilesDir()=directory
            override fun getSharedPreferences(ignored: String, mode: Int)=base.getSharedPreferences(name,Context.MODE_PRIVATE)
        }
        val token="SyntheticDevicePairingTokenForTests12345"
        val pending=java.io.File(directory,dev.personal.phoneagent.network.DebugPairing.FILE_NAME)
        try {
            pending.writeText(org.json.JSONObject().put("token",token).put("cloudConsent",true).toString())
            val store=dev.personal.phoneagent.network.SettingsStore(isolated)
            assertEquals(dev.personal.phoneagent.network.DebugPairing.Status.PAIRED,dev.personal.phoneagent.network.DebugPairing.consume(isolated,store))
            assertEquals(token,store.token())
            assertTrue(store.consent)
            assertFalse(pending.exists())
            assertFalse(isolated.getSharedPreferences("ignored",Context.MODE_PRIVATE).all.values.joinToString().contains(token))
            assertEquals(dev.personal.phoneagent.network.DebugPairing.Status.NONE,dev.personal.phoneagent.network.DebugPairing.consume(isolated,store))
            pending.writeText(org.json.JSONObject().put("token",token).put("cloudConsent","true").toString())
            assertEquals(dev.personal.phoneagent.network.DebugPairing.Status.FAILED,dev.personal.phoneagent.network.DebugPairing.consume(isolated,store))
            assertFalse(pending.exists())
        } finally { base.deleteSharedPreferences(name); directory.deleteRecursively() }
    }
    @Test fun uncertainJournalSurvivesNewInstanceWithoutStoringRawPayload() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="fixture-journal-${UUID.randomUUID()}"
        val isolated=object: ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name,Context.MODE_PRIVATE)
        }
        try {
            assertTrue(ActionJournal(isolated).recordAttempt("task","action","private synthetic payload"))
            val restarted=ActionJournal(isolated)
            assertTrue(restarted.attempted())
            assertFalse(restarted.recordAttempt("new-task","new-action","second payload"))
            val persisted=isolated.getSharedPreferences("ignored",Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(persisted.contains("private synthetic payload"))
            restarted.resolved()
            assertFalse(ActionJournal(isolated).attempted())
        } finally { base.deleteSharedPreferences(name) }
    }
}
