package ng.checkpoint

import kotlinx.serialization.json.Json
import ng.checkpoint.engine.decide.Engine
import ng.checkpoint.engine.decide.Outcome
import ng.checkpoint.engine.pack.ClaimHead
import ng.checkpoint.engine.pack.ClaimPack
import ng.checkpoint.engine.pack.SituationHeads
import ng.checkpoint.engine.pack.SituationPack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The engine against the real packs, on the JVM, with no device.
 *
 * The probe vector is all zeros, which makes every score fall out of the biases alone.
 * That is not a realistic embedding and it is not meant to be: these tests are about the
 * gates and the wiring, which must hold whatever the encoder returns.
 */
class EngineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun <T> load(name: String, read: (String) -> T): T =
        read(javaClass.classLoader!!.getResource(name)!!.readText())

    private val claims = load("claims.json") { json.decodeFromString<ClaimPack>(it) }
    private val situation = load("situation.json") { json.decodeFromString<SituationPack>(it) }
    private val claimHead = load("claim_head.json") { json.decodeFromString<ClaimHead>(it) }
    private val heads = load("situation_head.json") { json.decodeFromString<SituationHeads>(it) }

    private val engine = Engine(claims, situation, claimHead, heads)
    private val zero = FloatArray(claimHead.dim)

    private fun decide(answers: Map<String, String> = emptyMap(), bailed: Boolean = false) =
        engine.decide(zero, engine.readSituation(zero, answers), bailed)

    private val essentials = mapOf("agency" to "police", "allegation" to "licence")

    @Test fun `an unanswered essential gates everything else`() {
        val out = decide()
        assertTrue("expected an Ask, got $out", out is Outcome.Ask)
        assertTrue("first question must be essential", (out as Outcome.Ask).essential)
    }

    /** The whole safety argument rests on this. Bailing out must not reach past it. */
    @Test fun `bailing cannot skip an essential`() {
        val out = decide(bailed = true)
        assertTrue("bailing skipped the essential gate: $out", out is Outcome.Ask && out.essential)
    }

    @Test fun `answering the essentials clears the gate`() {
        val out = decide(essentials)
        assertFalse("still asking for an essential: $out", out is Outcome.Ask && out.essential)
    }

    /**
     * Text similarity searches the words typed before any question was answered. A claim
     * that declares the facts making it apply must still reach the reader.
     */
    @Test fun `a claim can match on facts alone`() {
        val out = decide(essentials)
        assertTrue("expected an answer, got $out", out is Outcome.Answer)
        assertTrue(
            "no claim matched via facts",
            (out as Outcome.Answer).matches.any { it.viaFact },
        )
    }

    @Test fun `a follow-up question names the rule it would complete`() {
        val out = decide(mapOf("agency" to "police", "allegation" to Engine.SKIPPED))
        if (out is Outcome.Ask && !out.essential) {
            assertTrue("a non-essential question must say which rule it serves", out.why != null)
        }
    }

    @Test fun `heads and claims agree on dimension`() {
        assertEquals(claimHead.dim, heads.dim)
        assertEquals(claimHead.claimIds.size, claims.claims.size)
        assertEquals(claims.claims.map { it.id }, claimHead.claimIds)
    }

    /** A typo here fails silently: the claim simply never fires. */
    @Test fun `every relevant_when names a real question and a real option`() {
        val byId = situation.questions.associateBy { it.id }
        val bad = claims.claims.flatMap { claim ->
            claim.relevantWhen.orEmpty().mapNotNull { (field, want) ->
                val q = byId[field]
                when {
                    q == null -> "${claim.id}: no question '$field'"
                    want in listOf("true", "false") && q.type != "noul" ->
                        "${claim.id}: '$field' is ${q.type}, not a yes/no"
                    want !in listOf("true", "false") && want !in q.options ->
                        "${claim.id}: '$field' has no option '$want'"
                    else -> null
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun `every rule condition names a real question and a real option`() {
        val byId = situation.questions.associateBy { it.id }
        val bad = situation.rules.flatMap { rule ->
            rule.`when`.mapNotNull { (field, want) ->
                val q = byId[field]
                when {
                    q == null -> "${rule.id}: no question '$field'"
                    want !in listOf("true", "false") && want !in q.options ->
                        "${rule.id}: '$field' has no option '$want'"
                    else -> null
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** Every rule must carry a citation. A rule without one is an opinion. */
    @Test fun `every rule carries a citation`() {
        val bad = situation.rules.filter {
            it.citation.instrument.isBlank() || it.citation.section.isBlank()
        }.map { it.id }
        assertTrue("uncited rules: $bad", bad.isEmpty())
    }
}

/**
 * `engine/decide` must stay runnable on the JVM. The moment an Android type reaches it,
 * the decision logic can only be tested on an emulator, and it stops being tested.
 */
class EngineIsolationTest {

    @Test fun `the decision engine has no android imports`() {
        val root = File("src/main/kotlin/ng/checkpoint/engine/decide")
        assertTrue("expected sources at ${root.absolutePath}", root.isDirectory)

        val bad = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) ->
                    line.startsWith("import android.") || line.startsWith("import androidx.")
                }
                .map { (i, line) -> "${file.name}:${i + 1} $line" }
        }.toList()

        assertTrue("android types in the decision engine:\n${bad.joinToString("\n")}", bad.isEmpty())
    }
}
