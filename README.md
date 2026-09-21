# Checkpoint

Tells a Nigerian motorist, at a roadside stop, whether what an officer is telling them
matches published law.

It runs on the phone with the network off. It is built so that a fabricated legal answer
is structurally impossible rather than merely unlikely.

## The invariant

**The model never writes anything the user reads.**

It produces a probability distribution over claims a human has already checked against
primary law. Every word on screen comes from `packs/`. There is no text-generation step in
which a wrong law could be invented, so the failure mode of a language model at a
roadside, a confident and fluent wrong answer, cannot occur here. The app can be wrong
about *which* checked claim applies. It cannot invent a claim.

When nothing checked applies, it says so and names a complaint channel.

## How a question is answered

```
speech or typing
      |
  Embedder         one ONNX file: sentencepiece tokenizer, e5-small, mean pool
      |  384-d vector
  SituationHeads   6 typed questions, each judged independently, each with its own
      |  facts     fitted temperature
  Gate             an essential fact missing? ask it, render nothing else
      |
  ClaimHeads       one-vs-rest per claim, so "none of these" is expressible
      |
  Rules            human-written, each carrying a citation
      |
  UI               renders pre-authored text only
```

Three design choices carry most of the weight, and each came out of a measurement:

- **One-vs-rest, not softmax.** A softmax over 25 claims always sums to 1, so it will
  confidently pick a winner for text about rice prices. One-vs-rest can return nothing.
- **A temperature per question, not one global temperature.** Across mixed question types
  the optimal temperature spanned 1.02 to 50.0. A single global value measured worse than
  no calibration at all.
- **Essential facts gate the whole screen.** "They asked me to bring 50k" cannot be
  answered until we know who asked. While an essential fact is missing the app shows the
  question and nothing else, because a partial answer beside a question anchors the reader
  on the weaker result.

## Languages

English, French and Portuguese, switched from the header. The switch changes three things
at once: the interface, the claim text, and the language handed to the speech recogniser.

It does not change the answer. The encoder is multilingual and the heads are fitted across
all three languages together, so which claim applies is decided from meaning, not from
which language it arrived in. Switching re-renders; it never re-decides.

The quoted statute is not translated. Nigerian law is enacted in English, and a translated
quotation is a paraphrase wearing quotation marks. A card in French carries French
reasoning above the original English of the regulation, with the section and capture date
unchanged.

This is why the encoder is `multilingual-e5-small` rather than an English model. Measured
in-language accuracy on MASSIVE: English 0.862, French 0.840, Portuguese 0.841. Training on
English alone does not transfer, so each language carries its own phrasings in `training/`.

## Layout

| Path | Holds |
|---|---|
| `packs/` | The claims, the rules, the citations, the trained heads. Content, not code. |
| `app/src/main/kotlin/ng/checkpoint/engine/` | Assets, embedding, pack shapes, decisions. |
| `app/src/main/kotlin/ng/checkpoint/ui/` | Compose. Renders what the engine returned. |
| `training/` | Phrasings and the scripts that fit the heads. |
| `tools/build_model.py` | Builds and verifies the single ONNX file the app loads. |
| `sources/` | Primary law, with capture dates. |
| `AGENTS.md` | The rule book, including rules that exist because something broke. |

`packs/` is read by the APK as assets and by the JVM tests as resources, from the one
directory, so a claim, its trained head and the test that greps them cannot drift apart.

## Build

Needs JDK 17 or later on the same architecture as the machine. Android Studio's bundled
JBR works. A Homebrew `openjdk@17` on Apple silicon is often the x86_64 build and fails
with "Bad CPU type in executable".

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Build the release variant to run it on a phone:

```sh
./gradlew :app:assembleRelease
```

It is signed with the debug key, so it installs without a keystore, and R8 is off until
keep rules for ONNX Runtime's JNI entry points and for kotlinx.serialization are written
and checked on a device. Android 15 and later show a 16 KB compatibility warning on launch
for debuggable builds only, so the release variant is the one to demonstrate with.

## Getting the encoder onto the device

The encoder is 123 MB and is not in the APK. `AssetStore` verifies a SHA-256 and a byte
count after download and again on every cold start, because a truncated model is
indistinguishable from a broken app.

Build it:

```sh
python -m venv .venv && .venv/bin/pip install -r tools/requirements.txt
.venv/bin/python tools/build_model.py
```

That merges the sentencepiece tokenizer, the quantized e5-small encoder and a mean pool
into one graph, then checks the result against the sentence-transformers encoder the heads
were fitted on. It refuses to pass below 0.99 cosine. The current build measures 0.9963 at
worst across five probes, the residual being int8 quantization of the encoder.

Then either serve it at the `baseUrl` in `app/src/main/assets/manifest.json`, or copy it
across, which costs nothing in mobile data:

```sh
adb shell mkdir -p /sdcard/Android/data/ng.checkpoint/files/sideload
adb push build-assets/e5-small-tokenized.onnx /sdcard/Android/data/ng.checkpoint/files/sideload/
```

A copied file is adopted only after passing the same hash a downloaded one must pass.

## Checking content without a device

`tools/probe.py` scores one sentence against the claim head using the same ONNX file the
app loads. It reports what the head thinks and stops there, because the gate, the rules
and the fact-relevance path live in `engine/decide` and a second copy of safety-critical
logic is a second thing to keep right.

```sh
.venv/bin/python tools/probe.py "how much is the fine for driving without a licence"
```

```
MATCH 0.824  [settled  ] What is the actual fine for driving without a licence
MATCH 0.611  [settled  ] What is the fine for not having my vehicle particulars
      0.501  [settled  ] Can they make me pay a fine here at the roadside
```

Use it to tune phrasings. If a sentence someone would really say scores low against the
claim that answers it, that claim needs more distinct phrasings.

## Tests

```sh
./gradlew :app:testDebugUnitTest
```

They run on the JVM with no device, because `engine/decide` has no Android imports and
`EngineIsolationTest` fails the build if one appears. They cover the essential gate, the
fact-relevance path, that bailing out cannot skip an essential, that every rule and claim
carries a citation, that no condition names a question or option that does not exist, that
the trained head carries no thresholds, and the writing rules from `AGENTS.md`.

## Retraining

Retrain after any change to phrasings or the claim set.

```sh
.venv/bin/python training/train_head.py
.venv/bin/python training/train_situation.py
```

Read the printed out-of-fold accuracy, ECE and per-claim recall before shipping. A claim
under 0.80 recall needs more distinct phrasings, or it is crowding a neighbour.

## Accessibility

Voice goes both ways. The recogniser turns speech into the same String typing produces, and
`TextToSpeech` reads cards back. Both the question and the answer are readable aloud,
because a question someone cannot read is a question they cannot answer.

Reading aloud stays on the device. Engines will synthesise over the network given the
chance: English resolved to an embedded voice on the test phone and French to one named
`server`. The app selects a voice that reports `isNetworkConnectionRequired == false`,
preferring the phone's own region, and where no offline voice is installed it says so
rather than speaking over a connection.

The verbatim quote is not read aloud. It is OCR of a scanned gazette and carries character
errors, and in French or Portuguese a voice would be sounding out English legal text.

Every control carries a button role for screen readers.

## Known limit

`onnxruntime-extensions` 0.13.0, the newest published Android artifact, links its native
libraries with 4 KB page alignment where core ONNX Runtime uses 16 KB. On a device
configured for 16 KB memory pages the linker refuses to load them and reports a missing
`DT_GNU_HASH`, which names the wrong cause. Devices using 4 KB pages, which is almost
everything in use, are unaffected. The fix has to come from upstream.

## Status

Proof of concept. The claims are drawn from the Police Act 2020 and the National Road
Traffic Regulations 2012, quoted verbatim, with section numbers and capture dates recorded
in `packs/claims.json`. The Police Act text is OCR of a scanned gazette and carries
character errors, which stay: tidying a quotation turns it into a paraphrase.
