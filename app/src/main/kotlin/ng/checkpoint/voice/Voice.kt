package ng.checkpoint.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Speech to a String, using the platform recogniser. No third-party dependency.
 *
 * The result goes down exactly the same path as typing. Voice gets no special treatment
 * downstream, and the transcript never leaves the device.
 *
 * Staying on the device is not a nicety here. A network recogniser uploads what someone
 * says at a roadside, and this app promises the opposite. When the offline pack for their
 * language is missing, the honest move is to say so and let them type, not to quietly send
 * the audio away.
 *
 * `EXTRA_PREFER_OFFLINE` is only a request, and the service is free to ignore it. From
 * API 33 there is a recogniser with no network path at all, so the guarantee comes from
 * which object is created rather than from a flag the service may disregard. Below that,
 * the flag is the best available and the error messages carry the rest.
 *
 * Known limit: recogniser coverage for Nigerian Pidgin is uneven and depends on the
 * device and the installed voice packs. Measure before promising it in the UI.
 */
class Voice(private val context: Context) {

    private var recognizer: SpeechRecognizer? = null
    private var onHeard: ((String) -> Unit)? = null
    private var onFailed: ((String) -> Unit)? = null
    private var languageTag: String? = null
    private var retried = false
    private var cancelling = false

    fun available(): Boolean = onDeviceAvailable() || SpeechRecognizer.isRecognitionAvailable(context)

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /**
     * @param languageTag a BCP 47 tag, or null to use whatever the device is set to.
     *   Null is the default because a forced tag is the common way this fails: asking for
     *   one the device has no offline pack for gets a flat refusal, while the device's own
     *   language is the one most likely to be installed.
     */
    fun listen(languageTag: String? = null, onHeard: (String) -> Unit, onFailed: (String) -> Unit) {
        if (!available()) { onFailed("Speech recognition is not available on this device."); return }
        this.onHeard = onHeard
        this.onFailed = onFailed
        this.languageTag = languageTag
        retried = false
        start()
    }

    /**
     * One recogniser, reused.
     *
     * Destroying and recreating it on every tap unbinds and rebinds the recognition
     * service, and that race showed up as an intermittent `ERROR_SERVER_DISCONNECTED`.
     * There is no cancel here either: cancelling reports `ERROR_CLIENT`, so a tidy-up call
     * before every session manufactured a failure to display.
     */
    private fun start() {
        val session = recognizer ?: create().also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        cancelling = false
        session.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            languageTag?.let { putExtra(RecognizerIntent.EXTRA_LANGUAGE, it) }
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    /** The on-device recogniser where the platform has one, so audio cannot leave. */
    private fun create(): SpeechRecognizer =
        if (onDeviceAvailable()) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle) {
            retried = false
            val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isBlank()) onFailed?.invoke("I did not catch that.") else onHeard?.invoke(text)
        }

        override fun onError(code: Int) {
            // cancel() reports ERROR_CLIENT. We asked for it, so it is not a failure and
            // saying so would be inventing a problem.
            if (cancelling && code == SpeechRecognizer.ERROR_CLIENT) {
                cancelling = false
                return
            }
            Log.w(TAG, "recogniser error $code, onDevice=${onDeviceAvailable()}")
            // A dropped binding leaves this instance dead, and that is our problem, not
            // something to hand to someone standing next to an officer. Rebuild and try
            // once more before saying anything.
            if (code == SpeechRecognizer.ERROR_SERVER_DISCONNECTED && !retried) {
                retried = true
                release()
                start()
                return
            }
            retried = false
            onFailed?.invoke(describe(code))
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rms: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partial: Bundle?) {}
        override fun onEvent(type: Int, params: Bundle?) {}
    }

    /** Stop listening but keep the binding, because the next tap will want it. */
    fun stop() {
        cancelling = true
        recognizer?.cancel()
    }

    /** Give the service back. Only on teardown. */
    fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    /**
     * Every branch either tells the person what to do or carries the code so the next
     * person can find out. A bare "it failed" wastes the one clue there was.
     */
    private fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Could not record audio."
        SpeechRecognizer.ERROR_NO_MATCH -> "I did not catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I heard nothing."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Try again."
        // Not a network problem, whatever the name suggests: the recognition service
        // dropped the connection. Reported only after a rebuild and retry have failed.
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "The recogniser dropped out. Try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "This phone has no offline voice pack for your language. Install one under " +
                "Settings, then General management, then Voice input. Typing works either way."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER ->
            "The recogniser asked for the network. Voice stays on this device, so it needs " +
                "the offline pack for your language installed."
        SpeechRecognizer.ERROR_CLIENT -> "The recogniser rejected the request (client error)."
        else -> "Speech recognition failed (code $code)."
    }

    private companion object { const val TAG = "CheckpointVoice" }
}
