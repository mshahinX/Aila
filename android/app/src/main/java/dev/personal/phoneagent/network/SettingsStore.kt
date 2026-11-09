package dev.personal.phoneagent.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.personal.phoneagent.BuildConfig
import java.net.URI
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection",Context.MODE_PRIVATE)
    var baseUrl: String
        get() = prefs.getString("baseUrl","http://127.0.0.1:8787")!!
        set(value) { validateUrl(value); prefs.edit().putString("baseUrl",value.trimEnd('/')).apply() }
    var consent: Boolean
        get() = prefs.getBoolean("consent",false)
        set(value) { prefs.edit().putBoolean("consent",value).apply() }
    /** In-app text size multiplier (accessibility). */
    var textScale: Float
        get() = prefs.getFloat("textScale",1.15f).coerceIn(1f,1.6f)
        set(value) { prefs.edit().putFloat("textScale",value.coerceIn(1f,1.6f)).apply() }
    /** Read assistant replies aloud with the phone's text-to-speech. */
    var speakReplies: Boolean
        get() = prefs.getBoolean("speakReplies",false)   // off by default: replies are read aloud only after the owner turns it on
        set(value) { prefs.edit().putBoolean("speakReplies",value).apply() }
    val deviceId: String get() = prefs.getString("deviceId",null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("deviceId",it).commit() }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("phone-agent-pairing",null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("phone-agent-pairing",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
    fun saveToken(token: String) {
        require(token.matches(Regex("[A-Za-z0-9_-]{32,128}"))) { "Invalid pairing token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val data = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("iv",Base64.encodeToString(cipher.iv,Base64.NO_WRAP))
            .putString("token",Base64.encodeToString(data,Base64.NO_WRAP)).commit())
    }
    fun token(): String {
        val data = prefs.getString("token",null) ?: error("Pair this phone in Settings first.")
        val iv = Base64.decode(prefs.getString("iv",null) ?: error("Pairing needs reset."),Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv)) }
        return String(cipher.doFinal(Base64.decode(data,Base64.NO_WRAP)),Charsets.UTF_8)
    }
    companion object {
        fun validateUrl(value: String) {
            val u = URI(value)
            require(u.userInfo == null && u.rawQuery == null && u.rawFragment == null && !u.host.isNullOrBlank()) { "Use a server URL without credentials/query." }
            require(u.scheme == "https" || (BuildConfig.DEBUG && u.scheme == "http" && u.host in setOf("127.0.0.1","localhost"))) { "HTTPS required except debug loopback." }
        }
    }
}
