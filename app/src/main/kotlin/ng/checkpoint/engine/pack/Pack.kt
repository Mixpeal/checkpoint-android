package ng.checkpoint.engine.pack

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive

/**
 * A fact condition is written the way it reads best: a yes/no fact as the JSON boolean
 * `true`, a choice fact as its option name `"cash_or_pos"`. Both reach the engine as text,
 * which is the one form it compares against.
 */
private object FactValue : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("FactValue", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): String =
        (decoder as JsonDecoder).decodeJsonElement().jsonPrimitive.content
    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

private typealias Conditions = Map<String, @Serializable(FactValue::class) String>

/**
 * Language tag to text. `en` is always present and is the fallback, so a pack that is only
 * half translated degrades to English instead of showing a blank card.
 */
typealias Text = Map<String, String>
typealias TextList = Map<String, List<String>>

fun Text.pick(lang: String): String = this[lang] ?: this["en"].orEmpty()
fun TextList.pick(lang: String): List<String> = this[lang] ?: this["en"].orEmpty()

/**
 * Shapes of the packs directory. These are CONTENT, not code: every string here was written and
 * verified by a human against a primary source. Nothing in the app composes them.
 *
 * `quote` is verbatim statutory text. It is never edited, reflowed or tidied. See AGENTS.md.
 */

@Serializable
data class Citation(
    val instrument: String,
    val section: String,
    val url: String = "",
    val captured: String,
)

@Serializable
data class Claim(
    val id: String,
    val canonical: Text,
    val bucket: String,                 // settled | contested | folk
    val answer: Text,
    /**
     * Verbatim statutory text, never edited and never translated. Nigerian law is enacted
     * in English, so a translated quotation is a paraphrase wearing quotation marks. The
     * card shows a translated explanation beside the original words.
     */
    val quote: String? = null,
    val citation: Citation,
    @SerialName("next_step") val nextStep: Text,
    /** Editorial policy, read at runtime. Never baked into a trained head. */
    val threshold: Double = 0.60,
    /** Facts that make this claim apply whatever words the person first typed. */
    @SerialName("relevant_when") val relevantWhen: Conditions? = null,
)

@Serializable data class Bucket(val label: Text, val tone: String)
@Serializable data class Channel(val name: String, val detail: Text, val url: String = "")
@Serializable data class Abstain(val headline: Text, val body: Text, val channels: List<Channel>)

@Serializable
data class ClaimPack(
    @SerialName("pack_id") val packId: String,
    @SerialName("pack_name") val packName: String,
    @SerialName("pack_version") val packVersion: String,
    val captured: String,
    val buckets: Map<String, Bucket>,
    val claims: List<Claim>,
    val abstain: Abstain,
)

@Serializable
data class Question(
    val id: String,
    val type: String,                   // choice | noul
    val prompt: Text,
    val ask: Text,
    /** Stored values, not user text. These are never translated. */
    val options: List<String> = listOf("no", "yes"),
    val labels: TextList = emptyMap(),
    /** Without this, nothing can be said responsibly. Gates rendering entirely. */
    val essential: Boolean = false,
    val threshold: Double = 0.60,
)

@Serializable
data class Rule(
    val id: String,
    val `when`: Conditions,
    val severity: String,
    val text: Text,
    val citation: Citation,
)

@Serializable data class SituationPack(val questions: List<Question>, val rules: List<Rule>)

@Serializable data class Head(
    val options: List<String>,
    val temperature: Double,
    @SerialName("W") val w: List<List<Double>>,
    @SerialName("b") val b: List<Double>,
)

@Serializable data class SituationHeads(val prefix: String, val dim: Int, val heads: Map<String, Head>)

@Serializable
data class ClaimHead(
    val prefix: String,
    val dim: Int,
    @SerialName("claim_ids") val claimIds: List<String>,
    /** One-vs-rest, so "none of these apply" is expressible. A softmax cannot say that. */
    @SerialName("Wo") val wo: List<List<Double>>,
    @SerialName("bo") val bo: List<Double>,
)
