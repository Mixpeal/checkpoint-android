package ng.checkpoint

import kotlinx.serialization.json.*
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writing rules from AGENTS.md, enforced.
 *
 * `quote` is exempt and must stay exempt: it is verbatim statutory text, including the OCR
 * character errors in the scanned gazette. Tidying a quote breaks the trust claim.
 */
class PackTextRulesTest {

    private val banned = listOf(
        "underscore", "crucial", "vital", "pivotal", "key role", "plays a role",
        "serves as", "stands as", "represents a", "boasts", "showcase", "testament",
        "landscape", "robust", "delve", "enhance", "foster", "garner", "meticulous",
        "intricate", "align with", "commitment to", "groundbreaking", "renowned",
        "vibrant", "worth noting", "important to note",
    )

    private fun read(name: String) =
        Json.parseToJsonElement(javaClass.classLoader!!.getResource(name)!!.readText()).jsonObject

    /**
     * Every user-visible string in every language, excluding quote.
     *
     * Pack text is a language map now, so a rule that only read the English would pass
     * while French shipped an em dash. Each entry carries its language because the banned
     * vocabulary is a list about English register and does not transfer.
     */
    private fun userText(): List<Triple<String, String, String>> {
        val out = mutableListOf<Triple<String, String, String>>()

        // The field name carries no language, so grouping by it collects every
        // translation of the same string. The language rides in the second slot.
        fun add(where: String, node: JsonElement?) {
            node?.jsonObject?.forEach { (lang, value) ->
                out += Triple(where, lang, value.jsonPrimitive.content)
            }
        }

        fun addList(where: String, node: JsonElement?) {
            node?.jsonObject?.forEach { (lang, value) ->
                value.jsonArray.forEachIndexed { i, item ->
                    out += Triple("$where[$i]", lang, item.jsonPrimitive.content)
                }
            }
        }

        val claims = read("claims.json")
        claims["claims"]!!.jsonArray.forEach { el ->
            val c = el.jsonObject
            val id = c["id"]!!.jsonPrimitive.content
            listOf("canonical", "answer", "next_step").forEach { add("$id.$it", c[it]) }
        }
        claims["buckets"]!!.jsonObject.forEach { (name, bucket) ->
            add("buckets.$name", bucket.jsonObject["label"])
        }
        claims["abstain"]!!.jsonObject.let { abstain ->
            listOf("headline", "body").forEach { add("abstain.$it", abstain[it]) }
            abstain["channels"]!!.jsonArray.forEachIndexed { i, channel ->
                add("abstain.channels[$i].detail", channel.jsonObject["detail"])
            }
        }

        val situation = read("situation.json")
        situation["rules"]!!.jsonArray.forEach { el ->
            val r = el.jsonObject
            add("${r["id"]!!.jsonPrimitive.content}.text", r["text"])
        }
        situation["questions"]!!.jsonArray.forEach { el ->
            val q = el.jsonObject
            val id = q["id"]!!.jsonPrimitive.content
            listOf("prompt", "ask").forEach { add("$id.$it", q[it]) }
            addList("$id.labels", q["labels"])
        }
        return out
    }

    /** Typography, so it applies whatever the language. */
    @Test fun `no dashes used as a pause`() {
        val bad = userText().filter { it.third.contains('—') || it.third.contains('–') }
        assertTrue("dashes in: ${bad.map { "${it.first}(${it.second})" }}", bad.isEmpty())
    }

    /** The banned list is about English register, so it is checked against English. */
    @Test fun `no banned vocabulary`() {
        val bad = userText().filter { it.second == "en" }.flatMap { (where, _, text) ->
            banned.filter { text.contains(it, ignoreCase = true) }.map { "$where: '$it'" }
        }
        assertTrue("banned words: $bad", bad.isEmpty())
    }

    /** English is the fallback every other language falls back to, so it must be there. */
    @Test fun `every translatable string has english`() {
        val bad = userText().groupBy({ it.first }, { it.second }).filterValues { "en" !in it }.keys
        assertTrue("missing english: $bad", bad.isEmpty())
    }

    @Test fun `every claim carries a citation with a capture date`() {
        val bad = read("claims.json")["claims"]!!.jsonArray.filter { el ->
            val c = el.jsonObject["citation"]?.jsonObject
            c == null || c["section"]?.jsonPrimitive?.content.isNullOrBlank() ||
                c["captured"]?.jsonPrimitive?.content.isNullOrBlank()
        }.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue("missing citation: $bad", bad.isEmpty())
    }

    /**
     * A TODO must never reach a user.
     *
     * Matched as a whole word and case-sensitively. A substring match flagged the
     * Portuguese "todos", which is the ordinary word for "all".
     */
    @Test fun `no placeholder text ships`() {
        val marker = Regex("\\bTODO\\b")
        val bad = userText().filter { marker.containsMatchIn(it.third) }
        assertTrue("placeholder in: ${bad.map { "${it.first}(${it.second})" }}", bad.isEmpty())
    }

    /** Thresholds are editorial policy and must stay out of the trained head. */
    @Test fun `trained head carries no thresholds`() {
        assertTrue("claim_head.json must not contain thresholds",
            read("claim_head.json")["threshold"] == null)
    }
}
