#!/usr/bin/env python3
"""
Score one sentence against the claim head, using the exact ONNX file the app loads.

This reports what the head thinks and nothing else. It deliberately does not reimplement
the gate, the rules or the fact-relevance path: those live in `engine/decide` and are
tested there, and a second copy of safety-critical logic is a second thing to keep right.

Use it to tune phrasings. If a sentence a person would really say scores low against the
claim that answers it, that claim needs more distinct phrasings.

    tools/probe.py "they said i must bring 50k"
    tools/probe.py --lang fr "ils exigent 50000"
"""
import json
import pathlib
import sys

import numpy as np
import onnxruntime as ort
from onnxruntime_extensions import get_library_path

ROOT = pathlib.Path(__file__).resolve().parent.parent
MODEL = ROOT / "build-assets" / "e5-small-tokenized.onnx"
PREFIX = "query: "


def main(text: str, lang: str = "en") -> int:
    if not MODEL.is_file():
        print(f"no model at {MODEL}. Run tools/build_model.py first.", file=sys.stderr)
        return 1

    head = json.loads((ROOT / "packs" / "claim_head.json").read_text())
    claims = {c["id"]: c for c in json.loads((ROOT / "packs" / "claims.json").read_text())["claims"]}

    opts = ort.SessionOptions()
    opts.register_custom_ops_library(get_library_path())
    sess = ort.InferenceSession(str(MODEL), opts, providers=["CPUExecutionProvider"])

    vec = sess.run(None, {sess.get_inputs()[0].name: np.array([PREFIX + text.strip()])})[0][0]
    vec = vec / max(float(np.linalg.norm(vec)), 1e-9)

    logits = np.array(head["Wo"]) @ vec + np.array(head["bo"])
    scores = 1.0 / (1.0 + np.exp(-logits))

    print(f'"{text}"\n')
    ranked = sorted(zip(head["claim_ids"], scores), key=lambda kv: -kv[1])
    for claim_id, p in ranked[:8]:
        claim = claims[claim_id]
        over = "MATCH " if p >= claim.get("threshold", 0.6) else "      "
        # canonical is a language map now. Fall back to English, as the app does.
        name = claim["canonical"].get(lang) or claim["canonical"]["en"]
        print(f"{over}{p:.3f}  [{claim['bucket']:9s}] {name}")

    fired = [cid for cid, p in ranked if p >= claims[cid].get("threshold", 0.6)]
    print(f"\n{len(fired)} over threshold, best {ranked[0][1]:.3f}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__.strip(), file=sys.stderr)
        raise SystemExit(2)
    args = sys.argv[1:]
    language = "en"
    if len(args) >= 2 and args[0] == "--lang":
        language, args = args[1], args[2:]
    raise SystemExit(main(" ".join(args), language))
