# Checkpoint (Android)

This file is the **agent rule book**. Read it before changing code.

Checkpoint tells a Nigerian motorist, at a roadside stop, whether what an officer is
telling them matches published law. It runs entirely on the device, with the network
off, and it is built so that a fabricated legal answer is **structurally impossible**
rather than merely unlikely.

That last sentence is the product. Most rules below exist to protect it.

---

## The invariant

**The model never writes anything the user reads.**

It produces a probability distribution over claims a human has already checked against
primary law. Every word on screen comes from `packs/*.json`. There is no text-generation
step in which a wrong law could be invented.

Consequences that are not negotiable:

- **Never** add a text-generating model, an LLM API call, or template interpolation that
  composes legal wording at runtime. Selecting between pre-authored variants is fine.
  Assembling a sentence from fragments is not.
- **Never** let a value the model produced flow into user-visible prose. It may select a
  claim, set a confidence, or fire a rule. It may not phrase anything.
- Claim text, rule text and citations are **content**, edited in JSON and reviewed by a
  human. They are not code and they are not generated.

If a feature seems to require the model to write, the feature is wrong. Say so.

---

## Who gets hurt when this is wrong

A person standing next to an armed officer, deciding in twenty seconds whether to pay.

- A **confidently wrong answer** is worse than no answer. Prefer abstention every time.
- Telling someone a real right is a myth could make them comply with something unlawful.
  That is the most dangerous direction of error in this app.
- Never emit anything that reads as a **script for arguing with an officer**. The useful
  thing at a roadside is the real number and the lawful payment channel, not a rights
  recitation. Knowing the fine is ₦10,000 ends a negotiation. Knowing your right to
  counsel escalates one.

---

## Architecture

```
speech / typing
      ↓
  Embedder            ONNX Runtime + tokenizer baked into the graph
      ↓  384-d vector
  SituationHeads      6 typed questions, judged independently
      ↓  facts
  Gate                essential facts missing? → ask, render nothing else
      ↓
  ClaimHeads          one-vs-rest per claim, + fact-relevance
      ↓
  Rules               human-written, each carrying a citation
      ↓
  UI                  renders only pre-authored text
```

### Layers and their one reason

| Package | Owns |
|---|---|
| `engine/assets` | Downloading and **verifying** model files. Nothing else. |
| `engine/embed` | Text → 384-d normalised vector. Knows nothing about claims. |
| `engine/pack` | Parsing and validating `packs/*.json`. Data shapes only. |
| `engine/decide` | Claims, situation heads, rules, gating. Pure functions over a vector. No Android imports. |
| `ui/` | Compose. Renders what `decide` returned. No decisions. |
| `voice/` | SpeechRecognizer → String. Nothing downstream. |
| `tools/` | Builds and verifies the one ONNX file the app loads. |
| `training/` | Phrasings, and the scripts that fit the heads from them. |

`engine/decide` must stay free of Android imports so it is testable on the JVM. Gated by
`EngineIsolationTest`.

---

## Rules that come from things that already went wrong

These are not hypothetical. Each cost real debugging time in the web prototype.

### 1. Verify every downloaded asset against a SHA-256

`tokenizer.json` once downloaded 14,284,760 bytes of an expected 17,082,730 and failed
silently. The app then failed three debugging rounds later with an unrelated-looking
`SyntaxError`. A partial model is indistinguishable from a broken app.

- Every entry in `assets/manifest.json` carries `sha256` and `bytes`.
- `AssetStore.ensure()` verifies both **after** download and **on every cold start**.
- A failed check deletes the file and re-downloads. It never proceeds with a bad asset.
- `AssetStore.adopt()` takes a model copied onto the device by hand, which matters when
  123 MB of mobile data is the obstacle. It passes the identical hash check, so a copied
  file earns no more trust than a fetched one.
- The graph itself is verified too. `tools/build_model.py` embeds five probes and compares
  against the sentence-transformers encoder the heads were fitted on. Below 0.99 cosine it
  refuses. The current build is 0.9963 at worst, the residual being int8 quantization.

### 2. Thresholds are policy, not weights

How sure the app must be before speaking about a claim is an editorial decision, not a
model parameter. Thresholds live in `packs/claims.json` and are read at runtime.

- **Never** bake a threshold into an exported head.
- Changing a threshold must not require retraining or a rebuild.

### 3. Calibrate per question, never globally

A single global temperature measured *worse than no calibration at all* across mixed
question types. Each situation head carries its own fitted temperature.

One exception, and it is the reason the claim head uses a single temperature: when each
class holds exactly one claim, a per-class temperature is degenerate. Every label in that
class's own subset is that class, so the fit collapses toward maximal confidence.

### 4. A softmax over N claims always sums to 1

It will confidently pick a winner for text about rice prices. A confidence threshold alone
cannot catch out-of-domain input.

- Claim heads are **one-vs-rest**, so "none of these apply" is expressible.
- Regularisation is tuned for the **worst false positive**, not mean recall. `C=1` caps it
  at 0.65 where `C=4` reached 0.78. A confident wrong answer costs more than a near-miss.

### 5. Essential facts gate rendering

Without knowing **who** stopped you and **what** they say you did, no claim and no rule can
be stated responsibly. Questions marked `essential` in `packs/situation.json` outrank any
answer the engine thinks it has.

While an essential fact is missing, render the question and **nothing else**. Showing a
partial answer beside it anchors the reader on the weaker result and they stop reading.

### 6. Below the floor, say nothing

A claim under `NEAR_FLOOR` (0.50) is noise. Rendering a 44 % card next to a question makes
it look like an answer. Abstain, name the best score, and point at a real complaint channel.

### 7. Quotes are verbatim, forever

`quote` fields are extracted from the source text programmatically, not retyped. They are
the trust claim made concrete.

- **Never** edit a `quote` for readability, grammar, tone or length.
- The Police Act text is OCR of a scanned gazette and contains character errors. Those
  errors stay. A proofreading pass happens against the PDF, by a human, not by tidying.
- The writing rules below apply to every other field. They do **not** apply to `quote`.

### 8. A fact condition is a boolean or an option name, and the parser must accept both

`relevant_when` and a rule's `when` are written the way each reads best: a yes/no fact as
the JSON boolean `true`, a choice fact as its option string `"cash_or_pos"`. The Kotlin
shapes first declared `Map<String, String>`, which parses neither pack and would have
thrown on launch. JavaScript never noticed; kotlinx.serialization did.

`FactValue` in `Pack.kt` normalises both to text at the boundary, which is the one form
the engine compares. `EngineTest` then checks that every condition names a question that
exists and an option that exists, because a typo there fails silently: the claim simply
never fires.

### 9. Language is a property of the text, not of the app

Every user-visible string in `packs/` is a map from language tag to text, and `en` is
always present because it is the fallback. A half-translated pack degrades to English
rather than rendering blank.

- The encoder is multilingual and the heads are fitted across **all** languages at once, so
  which claim applies does not depend on the reader's language. Switching language
  re-renders. It never re-decides.
- English-only training does not transfer. Measured on MASSIVE in-language: French 0.840
  and Portuguese 0.841, against Swahili 0.469 and Amharic 0.389 when trained on English
  alone. Every language needs its own phrasings in `training/phrasings.csv`, and
  `training/situation.csv` needs them too, because the situation heads are what gate the
  whole screen.
- **`quote` is never translated.** Nigerian law is enacted in English, so a translated
  quotation is a paraphrase wearing quotation marks. The card shows translated reasoning
  beside the original words, which is the more honest artifact anyway.
- Interface words live in `ui/Copy.kt` because they are code. Claim text, rule text and
  citations live in `packs/` because they are content. Nothing in `Copy.kt` ever says what
  the law is.

---

## Writing rules for claim, rule and question text

Everything a user reads, except `quote`.

- **No em dashes or en dashes as a pause.** Restructure into two sentences, or use a comma,
  or "and" / "but" / "because".
- Do not compensate with chains of short fragments. Vary sentence length deliberately.
- **Banned**: underscores, highlights, crucial, vital, pivotal, key role, plays a role,
  serves as, stands as, represents a, boasts, showcases, testament, landscape, robust,
  delve, enhance, foster, garner, meticulous, intricate, align with, commitment to,
  groundbreaking, renowned, vibrant, it's worth noting, it's important to note.
- If something **is** a thing, write "is". Do not reach for "serves as".
- No vague attribution. Name the instrument and the section, or say nothing.
- End on something concrete and checkable: a number, a section, an action. Never on
  significance.

`PackTextRulesTest` greps the pack JSON for dashes and the banned list, excluding `quote`.
The dash rule is typography and applies to every language. The banned list is about English
register and is checked against English only. Match placeholders as whole words: a
substring match for `TODO` flagged the ordinary Portuguese word `todos`.

---

## Content changes

`packs/*.json` is content. Treat it with more care than code, not less.

- **Every citation is read in the primary source by a human before it ships.** Not skimmed,
  not inferred from a search result. Read.
- Primary sources live in `sources/` with the URL and capture date recorded.
- A claim without a verified section number does not ship. There is no "TODO" state that
  reaches a user.
- Secondary reporting may guide research. It may never be the citation.
- Phrasings must be written by a native speaker of that language. Machine-plausible Pidgin
  is not Pidgin.
- **10 phrasings per claim is the measured floor.** Below 8, recall degrades sharply.

### Retraining

Heads are trained by the Python scripts in `training/` and exported to `packs/*_head.json`.

- Retrain after **any** change to phrasings or the claim set.
- Read the printed out-of-fold accuracy, ECE and per-claim recall before shipping. A claim
  under 0.80 recall needs more distinct phrasings or is crowding a neighbour.
- Work out which of those two it is before adding anything. At n=14 one example is worth
  0.071 of recall, so a claim sitting at the minimum lands on 0.786 and the warning moves
  to a different claim every time the data changes. Raising every claim to 16 or more took
  accuracy from 0.905 to 0.935 and stopped that.
- **The per-claim recall warning is computed on the multinomial diagnostic. The shipped
  head is one-vs-rest.** `stop_search_public` and `public_search_outer_only` stay flagged
  at 23 examples because they answer the same question from two angles, whether they may
  search you in public and how far. Multinomial recall forces one winner and punishes that.
  One-vs-rest fires both, at 0.765 and 0.695, which is the right answer. Check the probe
  before treating a flagged pair as a defect.
- The exported head must never contain thresholds (rule 2).

---

## Android specifics

- **Kotlin, Jetpack Compose.** No XML layouts.
- **ONNX Runtime Android** with the tokenizer compiled into the graph via
  `onnxruntime-extensions`. The model takes raw strings. This removes tokenizer parity as a
  category of bug rather than managing it.
- **`SpeechRecognizer`** for voice. It is a platform API. Do not add a speech dependency.
- Voice produces a String and hands it to the same path as typing. It gets no special
  treatment downstream.
- **Do not force a language tag on the recogniser.** `EXTRA_LANGUAGE` set to `en-NG` made
  every attempt fail on a Galaxy S24: the on-device Google recogniser has no offline pack
  for that tag and refuses rather than substituting. Omitting it uses the device language,
  which is the one most likely to be installed. Removing the tag was the whole fix.
- **Never fall back to an online recogniser.** Falling back would upload what someone says
  at a roadside, which is the opposite of what this app promises. When the offline pack is
  missing, say so and let them type.
- **`EXTRA_PREFER_OFFLINE` is a request, not a guarantee.** The service may ignore it, and
  on a Galaxy S24 it did, intermittently, returning `ERROR_NETWORK`. From API 33 use
  `SpeechRecognizer.createOnDeviceSpeechRecognizer`, which has no network path at all, so
  the property comes from which object was created rather than from a flag someone else
  honours. Keep the flag and the older recogniser only as the path below API 33.
- **`TextToSpeech` for reading aloud.** Platform API, no dependency, mirrors `Voice`. Voice
  in without voice out is the wrong way round for someone who reads with difficulty, so the
  question is read as well as the answer.
- **Choose the voice, do not let the engine choose.** English resolved to an embedded voice
  and French to one named `server`, which would have synthesised over the network. `Speaker`
  picks a `Voice` with `isNetworkConnectionRequired == false`, preferring the device's own
  region, and otherwise says nothing and explains why. Same move as the recogniser: the
  property comes from the object, not from a hope.
- **The `quote` is never spoken.** It is OCR of a scanned gazette with character errors, and
  in French or Portuguese a voice would be reading English legal text aloud. Spoken nonsense
  is worse than a block of text someone can point at.
- Every tappable carries `role = Role.Button` so a screen reader announces it as one.
- Error codes get named, not swallowed. `ERROR_LANGUAGE_NOT_SUPPORTED` and
  `ERROR_LANGUAGE_UNAVAILABLE` both landed in a generic "Speech recognition failed", which
  hid a one-line fix. Unmapped codes now carry their number, and `Voice` logs the code, so
  the next one of these is read rather than guessed at. Two rounds were lost to guessing.
- **Create one `SpeechRecognizer` and reuse it.** Destroying and recreating it per tap
  unbinds and rebinds the recognition service, and that race surfaced as an intermittent
  `ERROR_SERVER_DISCONNECTED`. Despite the name it is not a network failure, so it gets
  its own message, a rebuild and one silent retry.
- **`cancel()` reports `ERROR_CLIENT`.** A cancel the app asked for is not a failure.
  Cancelling before every session, and on the stop toggle, manufactured errors to show
  someone. Intentional cancels are flagged and swallowed.
- **`packs/` is bundled. The encoder is not.** The claims and the head fitted on them must
  version together, so `build.gradle.kts` points both the APK's assets and the JVM tests'
  resources at the single `packs/` directory. The 123 MB encoder is fetched or copied at
  runtime against `assets/manifest.json`.
- **`useLegacyPackaging = true` for jniLibs.** Native libraries are compressed in the APK
  and extracted at install, rather than stored uncompressed and mapped from inside it.
  That takes the APK from 52 MB to 28 MB, and it drops the requirement that libraries be
  page-aligned within the zip, which is a second way the problem below can surface.
- **onnxruntime-extensions 0.13.0 does not run on a 16 KB page device.** Its two libraries
  are linked with 4 KB LOAD alignment where core ONNX Runtime uses 16 KB:

  ```
  libonnxruntime.so                      LOAD align 0x4000
  libonnxruntime4j_jni.so                LOAD align 0x4000
  libonnxruntime_extensions4j_jni.so     LOAD align 0x1000
  libortextensions.so                    LOAD align 0x1000
  ```

  On a 16 KB device the linker fails with `empty/missing DT_HASH/DT_GNU_HASH ... (new hash
  type from the future?)`, which names the wrong cause. 1.22.0 and 0.13.0 are the newest
  published artifacts, so there is no version to move to and the fix has to come upstream.
  Check the alignment again after any version bump:

  ```sh
  llvm-readelf -l lib/arm64-v8a/libortextensions.so | grep LOAD
  ```

  Do not test on a `_ps16k` emulator image until this changes.
- **Two ABIs, not four.** ONNX Runtime ships arm64-v8a, armeabi-v7a, x86 and x86_64, and
  they dominate the APK: all four give 108 MB, the two ARM ones give 52 MB. x86 is only
  for emulators. armeabi-v7a stays, because cheap phones in this market still run it.
- `minSdk 26`, `targetSdk 35`, JDK 17.
- No analytics. No crash reporting that transmits query text. **What a person types at a
  checkpoint never leaves the device.**

---

## Code quality bar

- Clear logic over clever logic.
- Catch `Throwable`, not `Exception`, where startup must degrade into a message. A missing
  native library arrives as an `UnsatisfiedLinkError` and an `Exception` handler lets the
  app die instead. Rethrow `CancellationException` first.
- Names must be honest. `decide()` decides. `render()` renders.
- No `!!`. No `lateinit` outside Compose entry points. Handle the null.
- Boundaries validate with `kotlinx.serialization` and then trust. No `as` casts at a JSON
  boundary.
- No dead code, no commented-out code, no TODO left for later. Git remembers.
- No backward-compatibility shims. Nothing has shipped. Rename boldly, delete freely,
  update every call site in the same change.
- If the architecture would be cleaner with a multi-file refactor, do the refactor now.

---

## Verification

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug :app:testDebugUnitTest
tools/build_model.py      # only after touching the graph or the encoder
```

Run once at the end of a task, not after every edit.

A Homebrew `openjdk@17` on Apple silicon is often the x86_64 build and fails with "Bad CPU
type in executable". Android Studio's bundled JBR is the arm64 one.

The engine tests run on the JVM without a device. If a change to `engine/` cannot be
tested without an emulator, the change put Android in the wrong layer.

---

## TL;DR

1. The model selects. A human wrote every word on screen. Never blur that.
2. A confident wrong answer can get someone hurt. Abstain instead.
3. Verify downloaded assets against SHA-256, on every cold start.
4. Thresholds are content. Temperatures are per question. Claim heads are one-vs-rest.
5. Missing an essential fact means render the question and nothing else.
6. `quote` is verbatim and untouchable. Writing rules apply to everything else.
7. No citation ships unread in the primary source.
9. Text is language-keyed with an English fallback. `quote` stays in the language it was
   enacted in.
8. Fact conditions are booleans or option names. Parse both, then check every one names a
   question and an option that exist.
