package ng.checkpoint.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ng.checkpoint.ui.Lang
import java.util.Locale

/**
 * Reads a card aloud, using the platform engine. No third-party dependency, and it works
 * with the network off.
 *
 * This is not a convenience. Voice input without voice output is the wrong way round for
 * someone who reads with difficulty: it lets them ask and then hands them a wall of text.
 * The question gets read aloud as well as the answer, because a question they cannot read
 * is a question they cannot answer.
 *
 * It never speaks anything the model wrote, because the model writes nothing. Every string
 * that reaches here came from `packs/` or from `Copy`.
 *
 * The voice is chosen explicitly rather than left to the engine, because engines will
 * happily synthesise over the network. Nothing this app reads aloud should leave the
 * device any more than what it is asked.
 */
class Speaker(context: Context, private val onFinished: () -> Unit) {

    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "text to speech unavailable, status $status")
                return@TextToSpeech
            }
            // Every way a reading can end reports back, so a button that says STOP is
            // never left saying it over silence.
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = onFinished()
                override fun onStop(utteranceId: String?, interrupted: Boolean) = onFinished()
                @Deprecated("required by the platform", ReplaceWith(""))
                override fun onError(utteranceId: String?) = onFinished()
            })
        }
    }

    fun available(): Boolean = ready

    /**
     * @return null on success, or a sentence explaining why nothing was spoken. The caller
     *   shows it. Silence with no explanation looks like a broken button.
     */
    fun speak(text: String, lang: Lang): String? {
        val tts = engine ?: return "Reading aloud is not available on this device."
        if (!ready) return "Reading aloud is still starting up. Try again in a moment."

        val locale = when (lang) {
            Lang.EN -> Locale.ENGLISH
            Lang.FR -> Locale.FRENCH
            Lang.PT -> Locale("pt")
        }
        if (tts.setLanguage(locale) in setOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)) {
            return missing(locale)
        }

        // The engine will happily pick a voice that synthesises over the network. English
        // resolved to an embedded voice here and French to one named "server". Choosing the
        // voice explicitly keeps the promise the rest of the app makes: pick one that says
        // it needs no connection, or say nothing and explain why.
        val candidates = tts.voices.orEmpty().filter {
            it.locale.language == locale.language && !it.isNetworkConnectionRequired
        }
        // Any English voice is intelligible, but the first one offered was Australian.
        // Prefer the region this phone is already set to.
        val here = Locale.getDefault().country
        val offline = candidates.firstOrNull { it.locale.country == here }
            ?: candidates.firstOrNull()
            ?: return missing(locale)

        tts.voice = offline
        tts.stop()
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
        return null
    }

    private fun missing(locale: Locale) =
        "This phone has no offline voice for ${locale.displayLanguage}. Install one under " +
            "Settings, then General management, then Text-to-speech. Reading stays on the " +
            "device, so nothing is spoken over the network."

    fun stop() { engine?.stop() }

    fun shutdown() {
        engine?.stop()
        engine?.shutdown()
        engine = null
        ready = false
    }

    private companion object {
        const val TAG = "CheckpointSpeaker"
        const val UTTERANCE = "checkpoint"
    }
}
