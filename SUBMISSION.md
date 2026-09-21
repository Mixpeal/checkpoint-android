# Submission

**Project title:** Checkpoint

**Track:** Transparency & Accountability

**Country:** Nigeria

**GitHub repository:** https://github.com/Mixpeal/checkpoint-android

---

## Summary

### The track, and why this one

Checkpoint sits in Transparency & Accountability. The brief describes holding institutions
to account "in real time, not after the fact", and a roadside stop is exactly that moment.
The published law already exists. It sits in PDFs nobody can read while an officer is
waiting, so folk law fills the gap and the person deciding whether to pay has no way to
check anything.

I was stopped travelling back from Ibadan and taken to a station over a learner's permit.
They asked ₦100,000, settled at ₦50,000, and told me to pay by POS so there would be no
paper trail. Further along the road FRSC stopped me, asked for their fine too, and told me
I should never have paid the police because a licence is their business. Three things were
wrong in that one stop and I could check none of them at the time. Checkpoint is the thing
I wanted in my hand.

### Information sources

Three primary instruments, all on disk in `sources/` with capture dates recorded in every
citation the app shows:

- **Police Act 2020**, from the gazette scan. Search and detention powers, the disclosure an
  officer owes before searching, recognisance and inventory.
- **National Road Traffic Regulations 2012**. Licence and vehicle particulars offences, and
  the amounts attached to them.
- **Federal Road Safety Commission (Establishment) Act 2007**. Who may demand and retain a
  driving licence, and what paperwork that produces.

Every claim in the app names its instrument and section and carries the date the source was
captured. Secondary reporting guided the research and is never the citation. One trap is
recorded in `sources/RESEARCH.md`: a widely linked PDF labelled "Cybercrime Act 2015" is
actually the 2013 Bill, with 43 sections rather than 59.

Two findings came out of reading the primary text rather than summaries. The phrase "liable
on conviction" appears 62 times in the Regulations, which is the whole answer to whether a
fine can be collected at a roadside. And the FRSC Act sets the same ten thousand naira
figure as the Regulations, independently, which means a number told to you at a checkpoint
can be checked against two instruments instead of one.

### Trust and accuracy

The design goal was that a fabricated legal answer should be structurally impossible rather
than merely unlikely.

**The model never writes anything the user reads.** It returns a probability across 27
claims a human wrote and checked against the primary text. Every word on screen comes from
a reviewed JSON file. There is no text generation step, so the characteristic failure of a
language model, a fluent and confident wrong answer, has nowhere to occur. The model can be
wrong about which checked claim applies. It cannot invent one.

Four decisions follow from that:

- **Claims are scored one against the rest, not as a softmax.** A softmax over 27 claims
  always sums to one and will confidently pick a winner for a question about rice prices.
  One-vs-rest can return nothing, and when it does the app says so and names a complaint
  line instead of showing the nearest card.
- **Six typed questions gate the screen.** Without knowing who stopped you and what they say
  you did, nothing is shown at all. "They asked me to bring 50k" is unanswerable until you
  know who asked.
- **Statute is quoted verbatim and never translated.** The Police Act text is OCR of a
  scanned gazette and contains character errors. Those stay. A French card shows French
  reasoning above the original English of the regulation, because a translated quotation is
  a paraphrase wearing quotation marks.
- **Uncertainty is labelled, not hidden.** Whether a learner's permit satisfies the licence
  requirement is genuinely arguable, and whether a police officer may act on a licence
  offence is not settled by either Act. Both ship marked contested, saying plainly that
  anyone who tells you it is obvious is overstating it.

Measured: out-of-fold accuracy 0.940 across 513 phrasings, expected calibration error 0.011.
Sixteen tests run on the JVM without a device, covering the gate, the fact-relevance path,
that bailing out cannot skip an essential question, that every rule and claim carries a
citation, that no condition names a question or option that does not exist, and the writing
rules for every language in the pack.

### Operating conditions

It runs with the network off. Nothing a person types or says at a checkpoint leaves the
device, which is why the speech recogniser is the on-device one and the voice used for
reading aloud is chosen for reporting that it needs no connection. The encoder is a one
time 123 MB download that can be copied by cable instead, and it is hash-checked on every
start because a truncated model and a broken app look identical from the inside. Voice
works in both directions, so a question can be asked and an answer heard without reading.
English, French and Portuguese throughout.

Scaling is a content problem, not an engineering one. A pack holds the claims, citations,
thresholds and the head trained on them. Nigeria is one directory. Another jurisdiction is
another, written by people who know that law, and the engine, the gate and the abstention
behaviour carry over untouched.

### How AI tools were used

Claude Code wrote effectively all of the Kotlin, the training scripts, the ONNX build and
this text. The interesting part is not that it wrote code, it is what the collaboration was
organised around.

`AGENTS.md` in the repository is the rule book, and it is worth reading as the main evidence
here. It is not a style guide. Nine numbered rules each record something that actually broke
and what the fix has to be, because the model that fixed it will not remember next session:

- A tokenizer once downloaded 14,284,760 bytes of an expected 17,082,730 and surfaced three
  debugging rounds later as an unrelated parse error, so every asset now carries a SHA-256
  and a byte count checked on every cold start.
- A single global calibration temperature measured worse than no calibration at all across
  mixed question types, so each question carries its own.
- Thresholds are editorial policy and must never be baked into a trained head. A test fails
  the build if one appears there, and it caught a real regression before it shipped.

Things the tooling got wrong, and how that was caught, are part of the record. It invented a
dependency version that did not exist, which a Maven query disproved. It guessed twice at a
voice bug before logging the actual error code, which turned out to be two separate faults
and neither was what the message claimed. It chased a recall warning across three different
claims before noticing every flagged one had exactly fourteen examples, where a single
example moves recall by 0.071. Raising every claim above that floor gained three points of
accuracy and the warning stopped moving.

The rule that mattered most was the one about content. Claim text, rule text and citations
are not code and are not generated from a summary. Every section number came from the
primary text on disk. The jurisdiction question was deliberately left unanswered for most of
the build, because the FRSC Act had not been retrieved, and it was only added once that Act
was downloaded and the section read.

The idea is mine. It came from my own stop. The tools built it.
