package dev.personal.phoneagent.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Reads replies aloud with the phone's own text-to-speech engine in English (the UI language since v9).
 * If no English voice is available it says so once instead of reading with another language's voice.
 */
class Speaker(context: Context, private val unavailable: () -> Unit) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext,this)
    private var initialized = false
    private var ready = false
    private var warned = false
    private var pending: String? = null
    override fun onInit(status: Int) {
        initialized = true
        if(status==TextToSpeech.SUCCESS) {
            val result = runCatching { tts.setLanguage(Locale.US) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            ready = result!=TextToSpeech.LANG_MISSING_DATA && result!=TextToSpeech.LANG_NOT_SUPPORTED
            tts.setSpeechRate(0.9f)
        }
        pending?.let { pending=null; speak(it) }
    }
    fun speak(text: String) {
        if(text.isBlank()) return
        if(!initialized) { pending=text; return }
        if(!ready) { if(!warned) { warned=true; unavailable() }; return }
        tts.speak(sayable(text).take(TextToSpeech.getMaxSpeechInputLength()-1),TextToSpeech.QUEUE_FLUSH,null,"kemekci")
    }
    /** Written the way it is said, so the English voice does not have to guess at "myGov". */
    private fun sayable(text: String) = text.replace(Regex("\\bmy ?gov\\b",RegexOption.IGNORE_CASE),"my guv")
    fun stop() { runCatching { tts.stop() } }
    fun shutdown() { runCatching { tts.stop(); tts.shutdown() } }
}
