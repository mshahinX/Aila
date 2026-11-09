package dev.personal.phoneagent.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Keeps Aila phone control switched on whenever the app starts. Android allows this only with WRITE_SECURE_SETTINGS,
 * which debug builds declare and the owner grants once over USB:
 *   adb shell pm grant dev.personal.phoneagent android.permission.WRITE_SECURE_SETTINGS
 * Without the grant (and always in release builds) this does nothing and the Android settings path applies.
 */
object PhoneControlKeeper {
    /** A service that is merely starting up connects by itself within this time. */
    private const val GRACE_MS = 1500L
    /** MIUI keeps a listed service unbound after a reinstall unless it is really off for a moment first. */
    private const val OFF_MS = 2000L
    private const val SETTLE_MS = 3000L
    private val main = Handler(Looper.getMainLooper())

    /** True while a reconnect is in progress: the app shows no "phone control is off" banner and no "connected" note. */
    @Volatile var working = false
        private set

    fun ensureOn(context: Context) {
        val app = context.applicationContext
        if (working || AgentAccessibilityService.instance != null) return
        if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        working = true
        main.postDelayed({
            if (AgentAccessibilityService.instance != null) { working = false; return@postDelayed }
            set(app, false)
            main.postDelayed({
                set(app, true)
                main.postDelayed({ working = false }, SETTLE_MS)
            }, OFF_MS)
        }, GRACE_MS)
    }

    /** Changes only Aila's own entry; every other accessibility service stays exactly as the owner set it. */
    private fun set(app: Context, on: Boolean) {
        runCatching {
            val me = ComponentName(app, AgentAccessibilityService::class.java)
            val others = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && it != "null" && ComponentName.unflattenFromString(it) != me }
            val list = if (on) others + me.flattenToString() else others
            Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        }
    }
}
