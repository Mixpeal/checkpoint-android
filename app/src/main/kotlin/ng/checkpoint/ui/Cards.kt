package ng.checkpoint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ng.checkpoint.engine.decide.Fact
import ng.checkpoint.engine.decide.Match
import ng.checkpoint.engine.decide.Outcome
import ng.checkpoint.engine.pack.Abstain
import ng.checkpoint.engine.pack.Citation
import ng.checkpoint.engine.pack.ClaimPack
import ng.checkpoint.engine.pack.Question
import ng.checkpoint.engine.pack.Rule
import ng.checkpoint.engine.pack.pick
import kotlin.math.roundToInt

/** There is only ever one question on screen, so it needs only one id. */
private const val ASK_ID = "__ask"

private val cardShape = RoundedCornerShape(12.dp)
private val innerShape = RoundedCornerShape(9.dp)

/** @param accent the 2dp bar across the top, or null for a card that carries no verdict. */
@Composable
private fun Panel(accent: Color?, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(cardShape).border(1.dp, Ink.line, cardShape).background(Ink.card)
    ) {
        if (accent != null) Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
        Column(Modifier.padding(16.dp), content = content)
    }
}

/**
 * Reads the card out, and stops it. Sized like a Tag so it sits beside one without
 * shouting. The label says what the next tap will do, not what the button is called.
 */
@Composable
private fun Listen(copy: Copy, speaking: Boolean, onClick: () -> Unit) {
    Text(
        if (speaking) copy.stopReading else copy.listen,
        fontSize = 9.5.sp,
        letterSpacing = 1.sp,
        color = Ink.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .border(1.dp, Ink.line, RoundedCornerShape(5.dp))
            .clickable(role = Role.Button) { onClick() }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Tag(text: String) {
    Text(
        text.uppercase(),
        fontSize = 9.5.sp,
        letterSpacing = 1.sp,
        color = Ink.t3,
        modifier = Modifier
            .border(1.dp, Ink.line, RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** Named instrument and section, or nothing. An answer without one does not ship. */
@Composable
private fun Cite(citation: Citation, copy: Copy) {
    Column(Modifier.fillMaxWidth().padding(top = 11.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        Row(Modifier.padding(top = 11.dp)) {
            Text(citation.instrument, fontSize = 11.sp, color = Ink.t2, fontWeight = FontWeight.Medium)
            Text("  ${citation.section}", fontSize = 11.sp, color = Ink.t4)
        }
        Text(copy.captured(citation.captured), fontSize = 11.sp, color = Ink.t4)
    }
}

@Composable
private fun Meter(fraction: Double, label: String) {
    Row(Modifier.fillMaxWidth().padding(top = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 10.5.sp, color = Ink.t4)
        Box(
            Modifier.padding(start = 9.dp).weight(1f).height(2.dp)
                .clip(RoundedCornerShape(2.dp)).background(Ink.line)
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0.0, 1.0).toFloat())
                    .height(2.dp).background(Ink.accent)
            )
        }
    }
}

/** Every word here was written by a human and checked against the instrument it cites. */
@Composable
fun ClaimCard(
    match: Match,
    pack: ClaimPack,
    lang: Lang,
    copy: Copy,
    speakingId: String?,
    onSpeak: (String, String) -> Unit,
) {
    val claim = match.claim
    Panel(bucketColour(claim.bucket)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(pack.buckets[claim.bucket]?.label?.pick(lang.tag) ?: claim.bucket)
            Spacer(Modifier.width(8.dp))
            Listen(copy, speaking = speakingId == claim.id) {
                onSpeak(
                    claim.id,
                    listOf(
                        claim.canonical.pick(lang.tag),
                        claim.answer.pick(lang.tag),
                        claim.nextStep.pick(lang.tag),
                    ).joinToString(". "),
                )
            }
        }
        Text(
            claim.canonical.pick(lang.tag),
            fontSize = 14.5.sp, lineHeight = 21.sp, color = Ink.fg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 11.dp),
        )
        Text(
            claim.answer.pick(lang.tag),
            fontSize = 13.5.sp, lineHeight = 20.sp, color = Ink.t1,
            modifier = Modifier.padding(top = 9.dp),
        )
        claim.quote?.let { Quote(it, copy) }
        Box(
            Modifier.padding(top = 13.dp).fillMaxWidth()
                .clip(innerShape).border(1.dp, Ink.line, innerShape)
                .background(Ink.raise).padding(11.dp)
        ) {
            Text(claim.nextStep.pick(lang.tag), fontSize = 13.sp, lineHeight = 20.sp, color = Ink.t1)
        }
        Cite(claim.citation, copy)
        Meter(
            match.p,
            when {
                match.viaFact -> copy.viaFact
                match.near -> "${(match.p * 100).roundToInt()}%  ${copy.related}"
                else -> "${(match.p * 100).roundToInt()}%"
            },
        )
    }
}

/**
 * Verbatim statutory text.
 *
 * The Police Act pack is OCR of a scanned gazette and carries character errors. They stay.
 * Tidying them would quietly turn a quotation into a paraphrase, which is the one thing
 * this panel promises it is not.
 */
@Composable
private fun Quote(text: String, copy: Copy) {
    Row(Modifier.padding(top = 13.dp).fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(Ink.lineHi))
        Column(
            Modifier.fillMaxWidth().background(Ink.raise)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(copy.asWritten, fontSize = 9.sp, letterSpacing = 1.sp, color = Ink.t5)
            Text(
                text,
                fontSize = 12.5.sp, lineHeight = 21.sp, color = Ink.t2,
                modifier = Modifier.padding(top = 7.dp),
            )
        }
    }
}

@Composable
fun AskCard(
    ask: Outcome.Ask,
    lang: Lang,
    copy: Copy,
    onAnswer: (String, String) -> Unit,
    onSkip: (String) -> Unit,
    onBail: () -> Unit,
    speakingId: String?,
    onSpeak: (String, String) -> Unit,
) {
    val q = ask.question
    val labels = q.labels.pick(lang.tag)
    val choices = if (q.type == "choice") {
        q.options.mapIndexed { i, option -> option to (labels.getOrNull(i) ?: option) }
            .filter { (value, _) -> value != "unclear" && value != "none_stated" }
    } else {
        listOf("yes" to copy.yes, "no" to copy.no)
    }

    Panel(Ink.accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tag(copy.oneMoreThing)
            Spacer(Modifier.width(8.dp))
            // The question gets read too. A question someone cannot read is a question
            // they cannot answer.
            Listen(copy, speaking = speakingId == ASK_ID) {
                onSpeak(
                    ASK_ID,
                    (listOf(q.ask.pick(lang.tag).ifBlank { q.prompt.pick(lang.tag) }) +
                        choices.map { it.second }).joinToString(". "),
                )
            }
        }
        Text(
            q.ask.pick(lang.tag).ifBlank { q.prompt.pick(lang.tag) },
            fontSize = 15.sp, lineHeight = 22.sp, color = Ink.fg,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 11.dp, bottom = 14.dp),
        )
        choices.forEach { (value, label) -> Choice(label) { onAnswer(q.id, value) } }
        Choice(copy.notSure, muted = true) { onSkip(q.id) }

        Text(
            if (ask.essential) copy.essentialWhy else copy.fullerWhy,
            fontSize = 11.5.sp, lineHeight = 18.sp, color = Ink.t4,
            modifier = Modifier.padding(top = 14.dp),
        )
        Text(
            copy.known(ask.known, ask.total),
            fontSize = 11.sp, color = Ink.t5, modifier = Modifier.padding(top = 4.dp),
        )

        // Bailing cannot skip an essential fact, so the way out is only offered when there
        // is one. Otherwise the button would sit there doing nothing.
        if (!ask.essential) {
            Text(
                copy.skipQuestions,
                fontSize = 11.5.sp, color = Ink.t3, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    .clickable(role = Role.Button) { onBail() },
            )
        }
    }
}

@Composable
private fun Choice(label: String, muted: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(innerShape)
            .border(1.dp, if (muted) Ink.line else Ink.lineHi, innerShape)
            .background(if (muted) Color.Transparent else Ink.raise)
            .clickable(role = Role.Button) { onClick() }
            .padding(vertical = 13.dp, horizontal = 14.dp)
    ) {
        Text(label, fontSize = 13.5.sp, color = if (muted) Ink.t3 else Ink.fg)
    }
}

/**
 * The honest end of the funnel. Nothing checked applies, so name someone who can actually
 * help rather than hand over the nearest-looking card.
 */
@Composable
fun AbstainCard(abstain: Abstain, footnote: String, lang: Lang, copy: Copy) {
    Panel(Ink.t5) {
        Tag(copy.noCheckedAnswer)
        Text(
            abstain.headline.pick(lang.tag),
            fontSize = 14.5.sp, lineHeight = 21.sp, color = Ink.fg,
            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 11.dp),
        )
        Text(
            abstain.body.pick(lang.tag),
            fontSize = 13.5.sp, lineHeight = 20.sp, color = Ink.t1,
            modifier = Modifier.padding(top = 9.dp, bottom = 4.dp),
        )
        abstain.channels.forEach { channel ->
            Box(
                Modifier.padding(top = 9.dp).fillMaxWidth()
                    .clip(innerShape).border(1.dp, Ink.line, innerShape)
                    .background(Ink.raise).padding(11.dp)
            ) {
                Column {
                    Text(channel.name, fontSize = 13.sp, color = Ink.fg, fontWeight = FontWeight.Medium)
                    Text(
                        channel.detail.pick(lang.tag), fontSize = 13.sp, lineHeight = 20.sp, color = Ink.t1,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        Text(footnote, fontSize = 10.5.sp, color = Ink.t4, modifier = Modifier.padding(top = 12.dp))
    }
}

/** What the engine read out of the words, and which cited rules that set of facts fires. */
@Composable
fun SituationPanel(
    questions: List<Question>,
    facts: Map<String, Fact>,
    fired: List<Rule>,
    lang: Lang,
    copy: Copy,
) {
    Panel(null) {
        Text(copy.whatThisDescribes, fontSize = 9.5.sp, letterSpacing = 1.sp, color = Ink.t3)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            questions.forEach { q -> facts[q.id]?.let { Chip(q, it, lang, copy) } }
        }
        fired.forEach { rule -> RuleCard(rule, lang, copy) }
    }
}

@Composable
private fun Chip(q: Question, fact: Fact, lang: Lang, copy: Copy) {
    val label = if (q.type == "choice") {
        q.labels.pick(lang.tag).getOrNull(q.options.indexOf(fact.value)) ?: fact.value
    } else {
        if (fact.value == "yes") copy.yes else copy.no
    }
    val lit = if (q.type == "choice") {
        fact.value != "none_stated" && fact.value != "unclear"
    } else {
        fact.value == "yes"
    }
    val edge = when {
        fact.fromUser -> Ink.okDim
        lit -> Ink.lineHi
        else -> Ink.line
    }

    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .border(1.dp, edge, RoundedCornerShape(7.dp))
            .background(if (lit || fact.fromUser) Ink.raise else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${q.prompt.pick(lang.tag)}: ", fontSize = 11.5.sp, color = if (lit) Ink.t1 else Ink.t4)
        Text(
            label, fontSize = 11.5.sp, color = Ink.fg, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (fact.fromUser) copy.youSaid else "${(fact.p * 100).roundToInt()}%",
            fontSize = 10.5.sp, color = Ink.t5,
        )
    }
}

@Composable
private fun RuleCard(rule: Rule, lang: Lang, copy: Copy) {
    Column(
        Modifier.padding(top = 13.dp).fillMaxWidth()
            .clip(innerShape)
            .border(1.dp, if (rule.severity == "high") Ink.warnDim else Ink.line, innerShape)
            .background(Ink.raise)
            .padding(13.dp)
    ) {
        Text(rule.text.pick(lang.tag), fontSize = 13.sp, lineHeight = 20.sp, color = Ink.t1)
        Cite(rule.citation, copy)
    }
}
