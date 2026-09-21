package ng.checkpoint.engine.decide

import ng.checkpoint.engine.pack.*
import kotlin.math.exp

/**
 * The decision engine. Pure functions over a 384-d vector, with no Android imports, so it
 * runs under `./gradlew test` without a device.
 *
 * It decides WHICH checked answer applies. It never phrases one.
 */

/** A claim below this is noise. Rendering it beside a question makes it look like an answer. */
private const val NEAR_FLOOR = 0.50

/** A fact this uncertain is worth asking about. Outside the band, asking is fishing. */
private const val AMBIGUOUS_LO = 0.40
private const val AMBIGUOUS_HI = 0.62

data class Fact(
    val value: String,
    val p: Double,
    val question: Question,
    val fromUser: Boolean = false,
    val skipped: Boolean = false,
) {
    /** A tapped answer is certain. A predicted one must clear the question's own bar. */
    val settled: Boolean get() = fromUser || p >= question.threshold
}

data class Match(
    val claim: Claim,
    val p: Double,
    val viaFact: Boolean,
    /** Below its own threshold. Shown as background, never as the answer. */
    val near: Boolean = false,
)

sealed interface Outcome {
    /**
     * A question is worth more than the answer we currently have. Render this and nothing
     * else: a partial answer beside a question anchors the reader on the weaker result.
     *
     * @param why the rule this question would complete, or null when the fact is essential.
     */
    data class Ask(
        val question: Question,
        val essential: Boolean,
        val why: Rule?,
        val known: Int,
        val total: Int,
    ) : Outcome

    data class Answer(val matches: List<Match>, val facts: Map<String, Fact>, val fired: List<Rule>) : Outcome
    data class Nothing(val best: Double, val facts: Map<String, Fact>, val fired: List<Rule>) : Outcome
}

class Engine(
    private val claimPack: ClaimPack,
    private val situation: SituationPack,
    private val claimHead: ClaimHead,
    private val heads: SituationHeads,
) {
    val pack: ClaimPack get() = claimPack
    val questions: List<Question> get() = situation.questions

    fun readSituation(vec: FloatArray, answers: Map<String, String>): Map<String, Fact> =
        situation.questions.mapNotNull { q ->
            val head = heads.heads[q.id] ?: return@mapNotNull null
            val given = answers[q.id]
            if (given != null && given != SKIPPED) return@mapNotNull q.id to Fact(given, 1.0, q, fromUser = true)
            val p = softmax(head.w.mapIndexed { i, row -> (head.b[i] + dot(row, vec)) / head.temperature })
            val k = p.indices.maxBy { p[it] }
            q.id to Fact(head.options[k], p[k], q, skipped = given == SKIPPED)
        }.toMap()

    /**
     * @param bailed the person asked to see what we have rather than answer more questions.
     *   Suppresses follow-ups. It never suppresses the essential gate.
     */
    fun decide(vec: FloatArray, facts: Map<String, Fact>, bailed: Boolean = false): Outcome {
        // Essentials outrank any answer we think we have, and outrank bailing out. Without
        // who stopped you and what they say you did, no claim or rule can be stated.
        situation.questions.firstOrNull { it.essential && !known(facts[it.id]) }?.let {
            return ask(it, essential = true, why = null, facts = facts)
        }

        val fired = situation.rules.filter { r -> r.`when`.all { (f, want) -> holds(facts[f], want) } }

        val scored = claimHead.wo.mapIndexed { i, row -> i to sigmoid(claimHead.bo[i] + dot(row, vec)) }
        val byText = scored.filter { (i, p) -> p >= claimPack.claims[i].threshold }
            .map { (i, p) -> Match(claimPack.claims[i], p, viaFact = false) }
        // Text similarity searches the words typed BEFORE the questions were answered.
        // A claim may instead declare the facts that make it apply.
        val byFact = claimPack.claims.withIndex()
            .filter { (_, c) ->
                c.relevantWhen != null && byText.none { it.claim.id == c.id } &&
                    c.relevantWhen.all { (f, want) -> holds(facts[f], want) }
            }
            .map { (i, c) -> Match(c, scored[i].second, viaFact = true) }

        val matches = (byText + byFact).sortedByDescending { it.p }

        // Only ask once nothing useful is on offer. If a claim matched or a rule fired, the
        // person already has something worth reading, and asking anyway is nagging for
        // completeness they did not request.
        if (!bailed && matches.isEmpty() && fired.isEmpty()) {
            nextQuestion(facts)?.let { (q, rule) -> return ask(q, essential = false, why = rule, facts = facts) }
        }

        if (matches.isNotEmpty()) return Outcome.Answer(matches, facts, fired)

        val best = scored.maxOf { it.second }
        if (best >= NEAR_FLOOR) {
            val near = scored.sortedByDescending { it.second }.take(2)
                .map { (i, p) -> Match(claimPack.claims[i], p, viaFact = false, near = true) }
            return Outcome.Answer(near, facts, fired)
        }
        return Outcome.Nothing(best, facts, fired)
    }

    /**
     * The one question most worth asking, or null.
     *
     * Only asks to COMPLETE a pattern already partly seen. A single-condition rule is one
     * condition away by definition, so allowing those made the app fish for facts unrelated
     * to what the person typed. A confirmed sibling condition keeps the question anchored to
     * their own words; a genuinely 50/50 fact earns the exception.
     */
    private fun nextQuestion(facts: Map<String, Fact>): Pair<Question, Rule>? {
        data class Candidate(val question: Question, val rule: Rule, val anchored: Int)

        val candidates = situation.rules.mapNotNull { rule ->
            val conditions = rule.`when`.entries.toList()
            val unmet = conditions.filter { !holds(facts[it.key], it.value) }
            if (unmet.size != 1) return@mapNotNull null

            val id = unmet.first().key
            val question = situation.questions.firstOrNull { it.id == id } ?: return@mapNotNull null
            if (known(facts[id])) return@mapNotNull null

            val p = facts[id]?.p
            val ambiguous = p != null && p >= AMBIGUOUS_LO && p <= AMBIGUOUS_HI
            if (conditions.size < 2 && !ambiguous) return@mapNotNull null

            Candidate(question, rule, anchored = conditions.size - 1)
        }

        return candidates
            .sortedWith(compareByDescending<Candidate> { it.anchored }.thenByDescending { it.rule.severity == "high" })
            .firstOrNull()
            ?.let { it.question to it.rule }
    }

    private fun ask(q: Question, essential: Boolean, why: Rule?, facts: Map<String, Fact>) =
        Outcome.Ask(
            question = q,
            essential = essential,
            why = why,
            known = situation.questions.count { known(facts[it.id]) },
            total = situation.questions.size,
        )

    private fun known(f: Fact?): Boolean =
        f != null && (f.fromUser || f.skipped ||
            (f.value != "unclear" && f.value != "none_stated" && f.p >= f.question.threshold))

    private fun holds(f: Fact?, want: String): Boolean = when {
        f == null || !f.settled -> false
        want == "true"  -> f.value == "yes"
        want == "false" -> f.value == "no"
        else            -> f.value == want
    }

    companion object { const val SKIPPED = "__skipped" }
}

private fun dot(row: List<Double>, v: FloatArray): Double {
    var s = 0.0
    for (j in row.indices) s += row[j] * v[j]
    return s
}

private fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z))

private fun softmax(z: List<Double>): List<Double> {
    val m = z.max()
    val e = z.map { exp(it - m) }
    val sum = e.sum()
    return e.map { it / sum }
}
