package ng.checkpoint.engine.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * Text to a 384-d normalised vector.
 *
 * The tokenizer and the mean pool are compiled into the ONNX graph, so this takes a raw
 * String and gets back a sentence embedding. That removes tokenizer parity as a category
 * of bug rather than managing it: there is exactly one tokenizer, it lives inside the
 * model file, and `tools/build_model.py` checks the whole graph against the encoder the
 * heads were fitted on before the file is allowed out.
 *
 * L2 normalisation is the one step left in Kotlin, matching sentence-transformers'
 * Normalize module.
 */
class Embedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val prefix: String,
) : AutoCloseable {

    suspend fun embed(text: String): FloatArray = withContext(Dispatchers.Default) {
        val input = arrayOf(prefix + text.trim())
        OnnxTensor.createTensor(env, input).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                val raw = (result[0].value as Array<*>)[0] as FloatArray
                normalise(raw)
            }
        }
    }

    private fun normalise(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += (x * x).toDouble()
        val norm = sqrt(sum).toFloat().coerceAtLeast(1e-9f)
        return FloatArray(v.size) { v[it] / norm }
    }

    override fun close() { session.close() }

    companion object {
        /** Must match PREFIX in tools/build_model.py and in training. e5 is trained with it. */
        const val QUERY_PREFIX = "query: "

        fun open(model: File): Embedder {
            val env = OrtEnvironment.getEnvironment()
            // The sentencepiece operator lives in onnxruntime-extensions, not in core ORT.
            val opts = OrtSession.SessionOptions().apply {
                registerCustomOpLibrary(OrtxPackage.getLibraryPath())
            }
            return Embedder(env, env.createSession(model.absolutePath, opts), QUERY_PREFIX)
        }
    }
}
