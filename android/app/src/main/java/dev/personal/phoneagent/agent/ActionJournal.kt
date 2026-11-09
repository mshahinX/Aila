package dev.personal.phoneagent.agent

import android.content.Context

/** Only hashes/status persist. An attempted commit is never automatically replayed after a crash. */
class ActionJournal(context: Context) {
    private val prefs = context.getSharedPreferences("commit-journal",Context.MODE_PRIVATE)
    @Synchronized fun attempted(): Boolean = prefs.getBoolean("uncertain",false)
    @Synchronized fun recordAttempt(taskId: String, actionId: String, payload: String): Boolean {
        if (attempted()) return false
        return prefs.edit().putBoolean("uncertain",true).putString("action",digest("$taskId|$actionId|$payload")).commit()
    }
    @Synchronized fun resolved() { check(prefs.edit().clear().commit()) { "Could not update action journal" } }
}
