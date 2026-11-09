package dev.personal.phoneagent.network

import android.content.Context
import dev.personal.phoneagent.BuildConfig
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Debug-only, app-private USB bootstrap. No exported component or model-accessible tool. */
object DebugPairing {
    const val FILE_NAME = ".usb-pairing.pending"
    enum class Status { NONE, PAIRED, FAILED }
    fun consume(context: Context, settings: SettingsStore): Status {
        if(!BuildConfig.DEBUG) return Status.NONE
        val pending=File(context.filesDir,FILE_NAME)
        if(!Files.exists(pending.toPath(),LinkOption.NOFOLLOW_LINKS)) return Status.NONE
        return try {
            require(Files.isRegularFile(pending.toPath(),LinkOption.NOFOLLOW_LINKS) && pending.length() in 1L..512L)
            val text=pending.readText(Charsets.UTF_8)
            // Remove the temporary plaintext before applying anything. Fail closed if removal fails.
            check(pending.delete())
            val payload=JSONObject(text)
            require(payload.keys().asSequence().toSet()==setOf("token","cloudConsent"))
            require(payload.get("token") is String && payload.get("cloudConsent") is Boolean)
            val token=payload.getString("token")
            require(token.matches(Regex("[A-Za-z0-9_-]{32,128}")))
            settings.saveToken(token)
            check(settings.token()==token) // Verify the Keystore round trip without logging a value.
            settings.baseUrl="http://127.0.0.1:8787"
            settings.consent=payload.getBoolean("cloudConsent")
            Status.PAIRED
        } catch (_: Exception) {
            pending.delete()
            Status.FAILED
        }
    }
}
