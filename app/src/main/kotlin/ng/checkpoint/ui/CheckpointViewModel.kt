package ng.checkpoint.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ng.checkpoint.engine.assets.AssetState
import ng.checkpoint.engine.assets.AssetStore
import ng.checkpoint.engine.decide.Engine
import ng.checkpoint.engine.decide.Outcome
import ng.checkpoint.engine.embed.Embedder
import ng.checkpoint.engine.pack.ClaimHead
import ng.checkpoint.engine.pack.ClaimPack
import ng.checkpoint.engine.pack.Question
import ng.checkpoint.engine.pack.SituationHeads
import ng.checkpoint.engine.pack.SituationPack
import ng.checkpoint.voice.Speaker
import ng.checkpoint.voice.Voice
import java.io.File

/** First run has a model to fetch. Every run after this is instant and offline. */
sealed interface Boot {
    data object Starting : Boot
    data class Checking(val file: String) : Boot
    data class Fetching(val file: String, val percent: Int) : Boot
    data class Broken(val reason: String) : Boot
    data object Ready : Boot
}

data class Query(
    val text: String = "",
    val listening: Boolean = false,
    val thinking: Boolean = false,
    /** Answers the person tapped. Certain, and they override anything the model read. */
    val answers: Map<String, String> = emptyMap(),
    /** They asked to see what we have rather than answer more. Never skips an essential. */
    val bailed: Boolean = false,
    val outcome: Outcome? = null,
    val notice: String? = null,
    val millis: Long = 0,
)

class CheckpointViewModel(app: Application) : AndroidViewModel(app) {

    private val _boot = MutableStateFlow<Boot>(Boot.Starting)
    val boot: StateFlow<Boot> = _boot.asStateFlow()

    private val _query = MutableStateFlow(Query())
    val query: StateFlow<Query> = _query.asStateFlow()

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val _lang = MutableStateFlow(Lang.EN)
    val lang: StateFlow<Lang> = _lang.asStateFlow()

    private val voice = Voice(app)
    private val _speaking = MutableStateFlow<String?>(null)
    /** Which card is being read, or null. Drives the LISTEN and STOP label. */
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    private val speaker = Speaker(app) { _speaking.value = null }

    private var engine: Engine? = null
    private var embedder: Embedder? = null

    /** The vector for the text last checked. Answering a question re-decides without re-embedding. */
    private var vector: FloatArray? = null

    val packVersion: String get() = engine?.pack?.packVersion.orEmpty()
    val claims: ClaimPack? get() = engine?.pack
    val questions: List<Question> get() = engine?.questions.orEmpty()

    init {
        watchNetwork()
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        val context = getApplication<Application>()
        try {
            val json = Json { ignoreUnknownKeys = true }
            fun text(name: String) = context.assets.open(name).bufferedReader().use { it.readText() }

            // Packs ship inside the APK. The trained head and the claim list must version
            // together: a head fetched separately could drift from the claims it indexes.
            val pack = json.decodeFromString<ClaimPack>(text("claims.json"))
            val situation = json.decodeFromString<SituationPack>(text("situation.json"))
            val claimHead = json.decodeFromString<ClaimHead>(text("claim_head.json"))
            val heads = json.decodeFromString<SituationHeads>(text("situation_head.json"))

            // The model does not. It is large, it changes rarely, and it is verified by hash.
            val manifest = AssetStore.parseManifest(text("manifest.json"))
            val store = AssetStore(File(context.filesDir, "models"))
            val report: (AssetState) -> Unit = { state ->
                _boot.value = when (state) {
                    is AssetState.Checking -> Boot.Checking(state.file)
                    is AssetState.Fetching -> Boot.Fetching(state.file, state.percent)
                    is AssetState.Failed -> Boot.Broken("${state.file}: ${state.reason}")
                    AssetState.Ready -> Boot.Starting
                }
            }
            // A model copied into the app's external files directory is put in place here
            // and then hashed by ensure, on the same terms as a downloaded one.
            store.adopt(manifest, context.getExternalFilesDir("sideload"))
            if (!store.ensure(manifest, report)) return

            // The engine needs only the packs, so build it before touching the encoder.
            // If the encoder then fails, the header can still name the pack that loaded.
            engine = Engine(pack, situation, claimHead, heads)

            val model = store.fileFor(manifest.files.first { it.path.endsWith(".onnx") })
            embedder = Embedder.open(model)
            _boot.value = Boot.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (err: Throwable) {
            // Throwable, not Exception. A missing native library arrives as an
            // UnsatisfiedLinkError, and this screen exists so that startup failures are
            // read rather than crashed through.
            _boot.value = Boot.Broken(err.message ?: err::class.java.simpleName)
        }
    }

    fun onText(value: String) = _query.update { it.copy(text = value) }

    /**
     * Switching language re-renders. It never re-decides.
     *
     * The encoder is multilingual and the heads were fitted across all three languages, so
     * which claim applies does not depend on what the reader wants to read it in. Only the
     * words change.
     */
    fun setLang(value: Lang) {
        voice.stop()
        stopReading()
        _lang.value = value
        _query.update { it.copy(listening = false, notice = null) }
    }

    /**
     * Back to a blank page.
     *
     * Everything the last question established goes with it. A stop is one situation, and
     * carrying facts from the previous one into the next is how a wrong answer gets built
     * out of right parts.
     */
    fun reset() {
        voice.stop()
        stopReading()
        vector = null
        _query.value = Query()
    }

    /** A fresh question. Everything the previous one established is gone. */
    fun ask() {
        val text = _query.value.text.trim()
        val embed = embedder ?: return
        if (text.isEmpty() || _query.value.thinking) return

        _query.update { it.copy(thinking = true, answers = emptyMap(), bailed = false, outcome = null, notice = null) }
        viewModelScope.launch {
            try {
                val started = System.nanoTime()
                val vec = embed.embed(text)
                val eng = engine ?: error("no engine")
                val outcome = eng.decide(vec, eng.readSituation(vec, emptyMap()))
                val elapsed = (System.nanoTime() - started) / 1_000_000

                vector = vec
                _query.update { it.copy(thinking = false, millis = elapsed, outcome = outcome) }
            } catch (err: Exception) {
                _query.update { it.copy(thinking = false, notice = err.message ?: "Could not read that.") }
            }
        }
    }

    fun answer(questionId: String, value: String) {
        _query.update { it.copy(answers = it.answers + (questionId to value), notice = null) }
        decide()
    }

    fun skip(questionId: String) = answer(questionId, Engine.SKIPPED)

    fun bail() {
        _query.update { it.copy(bailed = true) }
        decide()
    }

    private fun decide() {
        val vec = vector ?: return
        val eng = engine ?: return
        val state = _query.value
        val facts = eng.readSituation(vec, state.answers)
        _query.update { it.copy(outcome = eng.decide(vec, facts, state.bailed)) }
    }

    /**
     * Read a card aloud.
     *
     * The verbatim quote is left out on purpose. It is OCR of a scanned gazette and carries
     * character errors, and in French or Portuguese a voice would be reading English legal
     * text. Spoken nonsense is worse than a block of text the reader can point someone at.
     */
    fun speak(id: String, text: String) {
        // Tapping the card that is already reading stops it. Anything else replaces it,
        // because two voices at once is worse than neither.
        if (_speaking.value == id) {
            speaker.stop()
            _speaking.value = null
            return
        }
        val problem = speaker.speak(text, _lang.value)
        _speaking.value = if (problem == null) id else null
        _query.update { it.copy(notice = problem) }
    }

    private fun stopReading() {
        speaker.stop()
        _speaking.value = null
    }

    fun listen() {
        if (_query.value.listening) { voice.stop(); _query.update { it.copy(listening = false) }; return }
        _query.update { it.copy(listening = true, notice = null) }
        voice.listen(
            languageTag = _lang.value.speechTag,
            onHeard = { heard ->
                // Voice reaches the engine down the same path as typing, with no special case.
                _query.update { it.copy(text = heard, listening = false) }
                ask()
            },
            onFailed = { reason -> _query.update { it.copy(listening = false, notice = reason) } },
        )
    }

    private fun watchNetwork() {
        val manager = getApplication<Application>()
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        _online.value = manager.activeNetwork
            ?.let { manager.getNetworkCapabilities(it) }
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = true }
            override fun onLost(network: Network) { _online.value = false }
        })
    }

    override fun onCleared() {
        voice.release()
        speaker.shutdown()
        embedder?.close()
    }
}
