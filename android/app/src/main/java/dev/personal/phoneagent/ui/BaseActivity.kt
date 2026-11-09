package dev.personal.phoneagent.ui

import android.app.Activity
import android.os.Bundle

/** Every screen: loads the day/night palette before any view is built. Night-mode changes recreate the activity. */
abstract class BaseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        Ui.init(this)
        super.onCreate(savedInstanceState)
    }
    protected fun d(v: Int) = Ui.dp(this,v)
}
